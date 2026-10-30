"""Integration tests against an isolated schema in the configured PostgreSQL database.

Run: python -m unittest discover -s tests -v
No real user rows are touched. Model output is controlled to test application logic.
"""
import asyncio
import io
import unittest
import uuid
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

import httpx
from PIL import Image
from sqlalchemy import text
from sqlalchemy.ext.asyncio import async_sessionmaker

from app.api.routes.accounts import attempts
from app.api.routes.detection import service
from app.core.config import settings
from app.db.base import Base
from app.db.models import CatType
from app.db.session import engine, get_session
from app.main import create_application
from app.schemas.detection import DetectCatResponse, DetectionItem
from app.services.catalog import CATALOG, code_for_label
from app.services.detection_service import DetectionService
from app.api.routes.collection import normalize_image


class ApplicationFlow(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.schema = "test_handcats_" + uuid.uuid4().hex
        self.conn = await engine.connect()
        await self.conn.execute(text(f'CREATE SCHEMA "{self.schema}"'))
        await self.conn.execute(text(f'SET search_path TO "{self.schema}"'))
        await self.conn.run_sync(Base.metadata.create_all)
        await self.conn.commit()
        factory = async_sessionmaker(self.conn, expire_on_commit=False)
        async with factory() as db:
            db.add_all(CatType(code=c[0], display_name=c[1]) for c in CATALOG)
            await db.commit()
        self.app = create_application()
        async def session():
            async with factory() as db:
                yield db
        self.app.dependency_overrides[get_session] = session
        self.temp = TemporaryDirectory()
        self.original_dir = settings.upload_dir
        settings.upload_dir = self.temp.name
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app), base_url="http://test")
        attempts.clear()
        picture = io.BytesIO()
        Image.new("RGB", (64, 64), "orange").save(picture, "JPEG")
        self.picture = picture.getvalue()

    async def asyncTearDown(self):
        await self.client.aclose()
        settings.upload_dir = self.original_dir
        self.temp.cleanup()
        await self.conn.rollback()
        await self.conn.execute(text('SET search_path TO public'))
        await self.conn.execute(text(f'DROP SCHEMA IF EXISTS "{self.schema}" CASCADE'))
        await self.conn.commit()
        await self.conn.close()
        await engine.dispose()

    async def account(self, username="test_cat"):
        response = await self.client.post("/api/v1/auth/register", json=dict(username=username, nickname="拾猫测试员", password="test-Password-2026"))
        self.assertEqual(response.status_code, 201, response.text)
        return {"Authorization": "Bearer " + response.json()["token"]}

    async def test_account_lifecycle(self):
        headers = await self.account()
        r = await self.client.get("/api/v1/auth/me", headers=headers)
        self.assertEqual(r.json()["nickname"], "拾猫测试员")
        self.assertNotIn("password_hash", r.json())
        r = await self.client.post("/api/v1/auth/register", json=dict(username="TEST_CAT", nickname="重复", password="test-Password-2026"))
        self.assertEqual(r.status_code, 409)
        r = await self.client.post("/api/v1/auth/login", json=dict(username="test_cat", password="wrong-password"))
        self.assertEqual(r.status_code, 401)
        r = await self.client.patch("/api/v1/auth/me", json={"nickname":"新的名字"}, headers=headers)
        self.assertEqual(r.json()["nickname"], "新的名字")
        r = await self.client.post("/api/v1/auth/password", json={"old_password":"test-Password-2026", "new_password":"new-Password-2026"}, headers=headers)
        self.assertEqual(r.status_code, 204)
        self.assertEqual((await self.client.get("/api/v1/auth/me", headers=headers)).status_code, 401)
        r = await self.client.post("/api/v1/auth/login", json={"username":"test_cat", "password":"new-Password-2026"})
        self.assertEqual(r.status_code, 200)
        headers={"Authorization":"Bearer "+r.json()["token"]}
        self.assertEqual((await self.client.post("/api/v1/auth/logout", headers=headers)).status_code,204)
        self.assertEqual((await self.client.get("/api/v1/auth/me", headers=headers)).status_code,401)

    async def test_collection_ownership_idempotency_and_delete(self):
        a=await self.account("cat_a")
        b=await self.account("cat_b")
        result=DetectCatResponse(success=True,message="ok",detections=[DetectionItem(label="中华田园猫(狸花)",confidence=.91,bounding_box=[.1,.1,.9,.9])])
        headers={**a,"X-Request-ID":str(uuid.uuid4())}
        with patch.object(service,"detect_cat",return_value=result) as detect:
            r=await self.client.post("/api/v1/observations",headers=headers,files={"file":("cat.jpg",self.picture,"image/jpeg")})
            self.assertEqual(r.status_code,201,r.text)
            item=r.json(); oid=item["id"]
            self.assertEqual(item["detections"][0]["cat_code"],"domestic_tabby")
            r2=await self.client.post("/api/v1/observations",headers=headers,files={"file":("cat.jpg",self.picture,"image/jpeg")})
            self.assertEqual(r2.json()["id"],oid)
            self.assertEqual(detect.call_count,1)
        self.assertEqual((await self.client.get("/api/v1/observations",headers=b)).json()["total"],0)
        for path in (f"/api/v1/observations/{oid}",item["image_url"]):
            self.assertEqual((await self.client.get(path,headers=b)).status_code,404)
        self.assertEqual((await self.client.patch(f"/api/v1/observations/{oid}",headers=b,json={"confirmed":True})).status_code,404)
        self.assertEqual((await self.client.delete(f"/api/v1/observations/{oid}",headers=b)).status_code,404)
        self.assertEqual((await self.client.get(item["image_url"],headers=a)).status_code,200)
        self.assertFalse(any(c["collected"] for c in (await self.client.get("/api/v1/catalog",headers=a)).json()))
        r=await self.client.patch(f"/api/v1/observations/{oid}",headers=a,json={"confirmed":True,"favorite":True,"note":"在公园相遇"})
        self.assertTrue(r.json()["confirmed"])
        self.assertEqual(r.json()["note"],"在公园相遇")
        cats=(await self.client.get("/api/v1/catalog",headers=a)).json()
        self.assertEqual(next(c for c in cats if c["code"]=="domestic_tabby")["encounters"],1)
        self.assertFalse(any(c["collected"] for c in (await self.client.get("/api/v1/catalog",headers=b)).json()))
        self.assertEqual((await self.client.delete(f"/api/v1/observations/{oid}",headers=a)).status_code,204)
        self.assertEqual(list(Path(self.temp.name).glob("*.jpg")),[])
        self.assertFalse(any(c["collected"] for c in (await self.client.get("/api/v1/catalog",headers=a)).json()))

    async def test_unknown_model_label_and_invalid_upload(self):
        headers={**await self.account(),"X-Request-ID":str(uuid.uuid4())}
        self.assertEqual((await self.client.get("/api/v1/catalog")).status_code,401)
        r=await self.client.post("/api/v1/observations",headers=headers,files={"file":("cat.jpg",b"not an image","image/jpeg")})
        self.assertEqual(r.status_code,400)
        result=DetectCatResponse(success=True,message="cat",detections=[DetectionItem(label="cat",confidence=.9)])
        with patch.object(service,"detect_cat",return_value=result):
            r=await self.client.post("/api/v1/observations",headers=headers,files={"file":("cat.jpg",self.picture,"image/jpeg")})
        oid=r.json()["id"]
        self.assertIsNone(r.json()["detections"][0]["cat_code"])
        self.assertEqual((await self.client.patch(f"/api/v1/observations/{oid}",headers=headers,json={"confirmed":True})).status_code,400)
        self.assertIsNone(code_for_label("阿比西尼亚猫"))
        self.assertEqual(code_for_label("中华田园猫（狸花）"),"domestic_tabby")
        with patch.object(service,"detect_cat",return_value=DetectCatResponse(success=False,message="model unavailable")):
            r=await self.client.post("/api/v1/observations",headers={**headers,"X-Request-ID":str(uuid.uuid4())},files={"file":("cat.jpg",self.picture,"image/jpeg")})
        self.assertEqual(r.status_code,503)
        self.assertEqual((await self.client.get("/api/v1/observations",headers=headers)).json()["total"],1)

    async def test_account_deletion_removes_owned_data(self):
        headers = await self.account("to_remove")
        other = await self.account("to_keep")
        result = DetectCatResponse(success=True, message="ok", detections=[DetectionItem(label="Birman", confidence=.9)])
        with patch.object(service, "detect_cat", return_value=result):
            response = await self.client.post("/api/v1/observations", headers={**headers, "X-Request-ID": str(uuid.uuid4())},
                                              files={"file": ("cat.jpg", self.picture, "image/jpeg")})
        self.assertEqual(response.status_code, 201, response.text)
        item = response.json()
        self.assertEqual(item["detections"][0]["cat_code"], "birman")
        saved = list(Path(self.temp.name).glob("*.jpg"))
        self.assertEqual(len(saved), 1)
        self.assertEqual((await self.client.post("/api/v1/auth/delete-account", headers=headers,
                           json={"password": "wrong-password"})).status_code, 400)
        self.assertTrue(saved[0].exists())
        self.assertEqual((await self.client.post("/api/v1/auth/delete-account", headers=headers,
                           json={"password": "test-Password-2026"})).status_code, 204)
        self.assertFalse(saved[0].exists())
        self.assertEqual((await self.client.get("/api/v1/auth/me", headers=headers)).status_code, 401)
        self.assertEqual((await self.client.post("/api/v1/auth/login", json={"username": "to_remove",
                           "password": "test-Password-2026"})).status_code, 401)
        self.assertEqual((await self.client.get("/api/v1/auth/me", headers=other)).status_code, 200)

    async def test_category_history_filters_all_owned_records_without_duplicates(self):
        a = await self.account("category_a")
        b = await self.account("category_b")

        async def upload_for(headers, labels):
            result = DetectCatResponse(success=True, message="ok", detections=[
                DetectionItem(label=label, confidence=.9, bounding_box=[.1, .1, .9, .9]) for label in labels])
            with patch.object(service, "detect_cat", return_value=result):
                response = await self.client.post("/api/v1/observations", headers={**headers, "X-Request-ID": str(uuid.uuid4())},
                    files={"file": ("cat.jpg", self.picture, "image/jpeg")})
            self.assertEqual(response.status_code, 201, response.text)
            return response.json()

        tabby = await upload_for(a, ["中华田园猫(狸花)", "中华田园猫(狸花)", "中华田园猫(橘)"])
        await upload_for(a, ["中华田园猫(白)"])
        second = await upload_for(a, ["中华田园猫(狸花)"])
        private = await upload_for(b, ["中华田园猫(狸花)"])
        # A category history includes both pending and confirmed saved photos.
        await self.client.patch(f"/api/v1/observations/{tabby['id']}", headers=a, json={"confirmed": True})
        path = "/api/v1/observations?cat_code=domestic_tabby"
        require_auth = await self.client.get(path)
        self.assertEqual(require_auth.status_code, 401)
        all_rows = (await self.client.get(path, headers=a)).json()
        self.assertEqual(all_rows["total"], 2)
        self.assertEqual({r["id"] for r in all_rows["items"]}, {tabby["id"], second["id"]})
        self.assertEqual({r["confirmed"] for r in all_rows["items"]}, {True, False})
        self.assertNotIn(private["id"], {r["id"] for r in all_rows["items"]})
        first = (await self.client.get(path + "&limit=1", headers=a)).json()
        next_page = (await self.client.get(path + "&offset=1&limit=1", headers=a)).json()
        self.assertEqual(first["total"], 2)
        self.assertNotEqual(first["items"][0]["id"], next_page["items"][0]["id"])
        self.assertEqual((await self.client.get(path + "&offset=2", headers=a)).json()["items"], [])
        orange = (await self.client.get("/api/v1/observations?cat_code=domestic_orange", headers=a)).json()
        self.assertEqual(orange["total"], 1)
        self.assertEqual(orange["items"][0]["id"], tabby["id"])
        self.assertEqual((await self.client.get("/api/v1/observations?cat_code=siamese", headers=a)).json()["total"], 0)
        self.assertEqual((await self.client.get("/api/v1/observations?cat_code=missing", headers=a)).status_code, 422)
        self.assertEqual((await self.client.get("/api/v1/observations", headers=a)).json()["total"], 3)
        self.assertEqual((await self.client.get(path, headers=b)).json()["total"], 1)
        cats = (await self.client.get("/api/v1/catalog", headers=a)).json()
        self.assertEqual(next(c["name"] for c in cats if c["code"] == "domestic_tabby"), "中华田园猫(狸花)")
        await self.client.delete(f"/api/v1/observations/{second['id']}", headers=a)
        self.assertEqual((await self.client.get(path, headers=a)).json()["total"], 1)

    async def test_multicat_selection_is_per_detection_and_preserves_legacy_confirmation(self):
        a = await self.account("multi_a")
        b = await self.account("multi_b")
        result = DetectCatResponse(success=True, message="ok", detections=[
            DetectionItem(label="中华田园猫(白)", confidence=.96, bounding_box=[.55,.1,.95,.9]),
            DetectionItem(label="中华田园猫(狸花)", confidence=.93, bounding_box=[.05,.1,.45,.9]),
            DetectionItem(label="cat", confidence=.5, bounding_box=[.1,.1,.2,.2])])
        async def upload(headers):
            with patch.object(service, "detect_cat", return_value=result):
                response = await self.client.post("/api/v1/observations", headers={**headers,"X-Request-ID":str(uuid.uuid4())},
                    files={"file":("two.jpg",self.picture,"image/jpeg")})
            self.assertEqual(response.status_code,201,response.text)
            return response.json()
        item = await upload(a)
        other = await upload(b)
        white, tabby, unknown = [d["id"] for d in item["detections"]]
        self.assertEqual(len({white,tabby,unknown}),3)
        self.assertFalse(any(d["confirmed"] for d in item["detections"]))
        endpoint=f"/api/v1/observations/{item['id']}"
        selected=await self.client.patch(endpoint,headers=a,json={"confirmed_detection_ids":[tabby,tabby]})
        self.assertEqual(selected.status_code,200,selected.text)
        self.assertEqual(selected.json()["confirmed_detection_ids"],[tabby])
        self.assertEqual([d["id"] for d in selected.json()["detections"] if d["confirmed"]],[tabby])
        cats=(await self.client.get("/api/v1/catalog",headers=a)).json()
        self.assertEqual({c["code"] for c in cats if c["collected"]},{"domestic_tabby"})
        # Editing a note does not reset the per-cat selection.
        note=await self.client.patch(endpoint,headers=a,json={"note":"两只猫的合影"})
        self.assertEqual(note.json()["confirmed_detection_ids"],[tabby])
        for invalid in ([unknown],[other["detections"][0]["id"]],[-1]):
            self.assertEqual((await self.client.patch(endpoint,headers=a,json={"confirmed_detection_ids":invalid})).status_code,400)
        self.assertEqual((await self.client.patch(endpoint,headers=b,json={"confirmed_detection_ids":[white]})).status_code,404)
        self.assertEqual((await self.client.patch(endpoint,headers=a,json={"confirmed":False,"confirmed_detection_ids":[white]})).status_code,400)
        both=(await self.client.patch(endpoint,headers=a,json={"confirmed_detection_ids":[white,tabby]})).json()
        self.assertTrue(both["confirmed"])
        cats=(await self.client.get("/api/v1/catalog",headers=a)).json()
        self.assertEqual({c["code"] for c in cats if c["collected"]},{"domestic_white","domestic_tabby"})
        cleared=(await self.client.patch(endpoint,headers=a,json={"confirmed_detection_ids":[]})).json()
        self.assertFalse(cleared["confirmed"])
        self.assertFalse(any(c["collected"] for c in (await self.client.get("/api/v1/catalog",headers=a)).json()))
        # Old clients can still confirm/unconfirm a whole image.
        self.assertEqual((await self.client.patch(endpoint,headers=a,json={"confirmed":True})).json()["confirmed_detection_ids"],sorted([white,tabby]))
        # Existing records have SQL NULL in the additive column: their old collection is preserved.
        await self.conn.execute(text("UPDATE user_observations SET confirmed = true, confirmed_detection_ids = NULL WHERE observation_id = :id"),{"id":item["id"]})
        await self.conn.commit()
        legacy=(await self.client.get(endpoint,headers=a)).json()
        self.assertEqual({d["id"] for d in legacy["detections"] if d["confirmed"]},{white,tabby})
        cats=(await self.client.get("/api/v1/catalog",headers=a)).json()
        self.assertEqual({c["code"] for c in cats if c["collected"]},{"domestic_white","domestic_tabby"})

    def test_english_breed_label_is_not_filtered(self):
        matches = DetectionService()._filter_cat_labels([DetectionItem(label="Birman", confidence=.9),
                                                           DetectionItem(label="dog", confidence=.8)])
        self.assertEqual([item.label for item in matches], ["Birman"])

    def test_upload_reader_is_independent_of_yolo_pillow_patch(self):
        from fastapi import HTTPException
        with patch("PIL.Image.open", side_effect=RuntimeError("YOLO plugin fallback must not run")):
            self.assertTrue(normalize_image(self.picture).startswith(b"\xff\xd8"))
            with self.assertRaises(HTTPException) as invalid:
                normalize_image(b"invalid image")
            self.assertEqual(invalid.exception.status_code, 400)


if __name__ == "__main__":
    unittest.main()
