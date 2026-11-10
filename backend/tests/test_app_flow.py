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
from unittest.mock import patch, AsyncMock

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
from app.api.routes.collection import review_service
from app.schemas.review import ReviewResponse, ReviewSuggestion


class ApplicationFlow(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.key_patch = patch.object(settings, "deepseek_api_key", "mock-only")
        self.key_patch.start()
        # Never send test photos or incur real provider charges, including the
        # automatic fallback now performed during empty-detection uploads.
        self.provider_patch = patch.object(review_service,"review",new=AsyncMock(
            return_value=ReviewResponse(success=False,model="mock",message="unavailable")))
        self.provider_patch.start()
        from app.services.work_gate import review_throttle, review_budget, upload_throttle
        for throttle in (review_throttle, review_budget, upload_throttle):
            throttle.entries.clear()
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
        self.provider_patch.stop()
        self.key_patch.stop()
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

    async def test_review_can_collect_when_yolo_misses_and_is_private_and_idempotent(self):
        a = await self.account("review_a")
        b = await self.account("review_b")
        with patch.object(service, "detect_cat", return_value=DetectCatResponse(success=True, message="no cat")):
            uploaded = await self.client.post("/api/v1/observations", headers={**a, "X-Request-ID": str(uuid.uuid4())},
                files={"file": ("cat.jpg", self.picture, "image/jpeg")})
        self.assertEqual(uploaded.status_code, 201, uploaded.text)
        item = uploaded.json()
        self.assertEqual(item["detections"], [])
        endpoint = f"/api/v1/observations/{item['id']}"
        answer = ReviewResponse(success=True, model="deepseek-flash", message="ok", suggestions=[
            ReviewSuggestion(label="布偶猫", cat_code="ragdoll", confidence=.9),
            ReviewSuggestion(label="土耳其安哥拉猫", cat_code=None, confidence=.8)])
        with patch.object(review_service, "review", new=AsyncMock(return_value=answer)) as mocked:
            self.assertEqual((await self.client.post(endpoint+"/review", headers=b)).status_code,404)
            self.assertEqual(mocked.await_count,0)
            reviewed = (await self.client.post(endpoint+"/review", headers=a)).json()
        body = {"review_id":reviewed["review_id"], "selected_indices":[0]}
        self.assertEqual((await self.client.post(endpoint+"/review/apply",headers=b,json=body)).status_code,404)
        self.assertEqual((await self.client.post(endpoint+"/review/apply",headers=a,json={**body,"selected_indices":[1]})).status_code,400)
        accepted = await self.client.post(endpoint+"/review/apply",headers=a,json=body)
        self.assertEqual(accepted.status_code,200,accepted.text)
        d = next(d for d in accepted.json()["detections"] if d["cat_code"]=="ragdoll")
        self.assertEqual(d["cat_code"],"ragdoll")
        self.assertEqual(d["source"],"deepseek")
        self.assertEqual(d["bounding_box"],[])
        self.assertTrue(d["confirmed"])
        again = (await self.client.post(endpoint+"/review/apply",headers=a,json=body)).json()
        self.assertEqual(len(again["detections"]),2)
        cats=(await self.client.get("/api/v1/catalog",headers=a)).json()
        self.assertEqual({c["code"] for c in cats if c["collected"]},{"ragdoll"})
        history=(await self.client.get("/api/v1/observations?cat_code=ragdoll",headers=a)).json()
        self.assertEqual(history["items"][0]["id"],item["id"])
        with patch.object(review_service,"review",new=AsyncMock(return_value=answer.model_copy())):
            await self.client.post(endpoint+"/review",headers=a)
        self.assertEqual((await self.client.post(endpoint+"/review/apply",headers=a,json=body)).status_code,409)

    async def test_review_correction_replaces_wrong_collection_and_preserves_yolo(self):
        a = await self.account("correction_a")
        wrong = DetectCatResponse(success=True,message="ok",detections=[
            DetectionItem(label="中华田园猫(白)",confidence=.95,bounding_box=[.1,.1,.9,.9])])
        with patch.object(service,"detect_cat",return_value=wrong):
            item=(await self.client.post("/api/v1/observations",headers={**a,"X-Request-ID":str(uuid.uuid4())},
                files={"file":("cat.jpg",self.picture,"image/jpeg")})).json()
        endpoint=f"/api/v1/observations/{item['id']}"
        await self.client.patch(endpoint,headers=a,json={"confirmed":True,"note":"保留这段手记","favorite":True})
        answer=ReviewResponse(success=True,model="deepseek-flash",message="ok",suggestions=[
            ReviewSuggestion(label="波斯猫",cat_code="persian",confidence=.9),
            ReviewSuggestion(label="布偶猫",cat_code="ragdoll",confidence=.85)])
        with patch.object(review_service,"review",new=AsyncMock(return_value=answer)):
            result=(await self.client.post(endpoint+"/review",headers=a)).json()
        accepted=(await self.client.post(endpoint+"/review/apply",headers=a,json={
            "review_id":result["review_id"],"selected_indices":[0,1,1]})).json()
        self.assertEqual(len(accepted["detections"]),2)
        self.assertTrue(all(d["source"]=="deepseek" for d in accepted["detections"]))
        archived = (await self.conn.execute(text("SELECT bounding_box, active FROM detections WHERE observation_id=:id AND bounding_box->>'review_id' IS NULL"), {"id":item["id"]})).first()
        self.assertFalse(archived.active)
        self.assertEqual(archived.bounding_box["xyxy"],[.1,.1,.9,.9])
        self.assertEqual(accepted["note"],"保留这段手记")
        self.assertTrue(accepted["favorite"])
        cats=(await self.client.get("/api/v1/catalog",headers=a)).json()
        self.assertEqual({c["code"] for c in cats if c["collected"]},{"persian","ragdoll"})

    async def empty_observation(self, headers):
        with patch.object(service, "detect_cat", return_value=DetectCatResponse(success=True, message="none")):
            response = await self.client.post("/api/v1/observations", headers={**headers,"X-Request-ID":str(uuid.uuid4())},
                files={"file":("cat.jpg", self.picture, "image/jpeg")})
        self.assertEqual(response.status_code,201,response.text)
        return "/api/v1/observations/" + response.json()["id"]

    def review_answer(self):
        # Same breed, two distinct animals: never collapse by label.
        return ReviewResponse(success=True,model="mock",message="ok",suggestions=[
            ReviewSuggestion(label="布偶猫",cat_code="ragdoll",confidence=.9,bounding_box=[.05,.1,.45,.9]),
            ReviewSuggestion(label="布偶猫",cat_code="ragdoll",confidence=.8,bounding_box=[.55,.1,.95,.9])])

    async def test_three_reviews_replace_results_and_survive_new_requests(self):
        a = await self.account("quota_a")
        endpoint = await self.empty_observation(a)
        old_ids = []
        with patch.object(review_service,"review",new=AsyncMock(side_effect=lambda *args: self.review_answer())) as mocked:
            for attempt in range(1,4):
                r = await self.client.post(endpoint+"/review",headers=a)
                self.assertEqual(r.status_code,200,r.text)
                result = r.json()
                self.assertEqual(result["review_attempts"],attempt)
                body={"review_id":result["review_id"],"selected_indices":[0,1,1]}
                accepted=await self.client.post(endpoint+"/review/apply",headers=a,json=body)
                self.assertEqual(accepted.status_code,200,accepted.text)
                data=accepted.json()
                self.assertEqual(len(data["detections"]),2)
                self.assertTrue(all(d["confirmed"] for d in data["detections"]))
                self.assertEqual({tuple(d["bounding_box"]) for d in data["detections"]},
                                 {(.05,.1,.45,.9),(.55,.1,.95,.9)})
                again=(await self.client.post(endpoint+"/review/apply",headers=a,json=body)).json()
                self.assertEqual(data["detections"],again["detections"])
                if old_ids:
                    stale=await self.client.patch(endpoint,headers=a,json={"confirmed_detection_ids":old_ids})
                    self.assertEqual(stale.status_code,400)
                old_ids=[d["id"] for d in data["detections"]]
            capped=(await self.client.post(endpoint+"/review",headers=a)).json()
            self.assertFalse(capped["success"])
            self.assertEqual(capped["review_attempts"],3)
            self.assertEqual(mocked.await_count,3)
        persisted=(await self.client.get(endpoint,headers=a)).json()
        self.assertEqual(persisted["review_attempts"],3)
        self.assertEqual(persisted["review_result"]["review_id"],result["review_id"])
        # Even at quota, existing review remains usable, and an unselected cat
        # keeps its own box without counting toward the catalog.
        subset=(await self.client.post(endpoint+"/review/apply",headers=a,json={**body,"selected_indices":[1]})).json()
        self.assertEqual(len(subset["detections"]),2)
        self.assertEqual(sum(d["confirmed"] for d in subset["detections"]),1)
        cats=(await self.client.get("/api/v1/catalog",headers=a)).json()
        self.assertEqual(next(c["encounters"] for c in cats if c["code"]=="ragdoll"),1)

    async def test_failure_timeout_and_cancellation_release_photo_lease(self):
        a=await self.account("failure_a")
        endpoint=await self.empty_observation(a)
        failure=ReviewResponse(success=False,model="mock",message="failed")
        with patch.object(review_service,"review",new=AsyncMock(return_value=failure)):
            self.assertEqual((await self.client.post(endpoint+"/review",headers=a)).json()["review_attempts"],1)
        with patch.object(review_service,"review",new=AsyncMock(side_effect=TimeoutError)):
            timeout=(await self.client.post(endpoint+"/review",headers=a)).json()
            self.assertFalse(timeout["success"])
            self.assertEqual(timeout["review_attempts"],2)
        async def cancel(*args):
            raise asyncio.CancelledError
        with patch.object(review_service,"review",new=cancel):
            with self.assertRaises(asyncio.CancelledError):
                await self.client.post(endpoint+"/review",headers=a)
        state=(await self.conn.execute(text("SELECT review_attempts, review_request_id, review_busy_until FROM observations WHERE id=:id"),{"id":endpoint.rsplit('/',1)[1]})).first()
        self.assertEqual(state.review_attempts,3)
        self.assertIsNone(state.review_request_id)
        self.assertIsNone(state.review_busy_until)

    async def test_unconfigured_and_full_queue_do_not_consume_quota(self):
        from app.services.work_gate import review_gate
        a=await self.account("queue_a")
        endpoint=await self.empty_observation(a)
        with patch.object(settings,"deepseek_api_key",""):
            r=(await self.client.post(endpoint+"/review",headers=a)).json()
            self.assertEqual(r["review_attempts"],0)
        with patch.object(review_gate,"pending",review_gate.capacity):
            self.assertEqual((await self.client.post(endpoint+"/review",headers=a)).status_code,503)
        self.assertEqual((await self.client.get(endpoint,headers=a)).json()["review_attempts"],0)

    async def test_concurrent_review_apply_and_delete_use_independent_connections(self):
        # The ordinary fixture uses one connection. Real concurrent HTTP requests
        # must use independent transactions to exercise PostgreSQL row locks.
        from sqlalchemy.ext.asyncio import create_async_engine
        a=await self.account("parallel_a")
        endpoint=await self.empty_observation(a)
        concurrent_engine=create_async_engine(settings.database_url,
            connect_args={"server_settings":{"search_path":self.schema}})
        factory=async_sessionmaker(concurrent_engine,expire_on_commit=False)
        async def session():
            async with factory() as db:
                yield db
        self.app.dependency_overrides[get_session]=session
        started, finish=asyncio.Event(),asyncio.Event()
        async def delayed(*args):
            started.set()
            await finish.wait()
            return self.review_answer()
        try:
            with patch.object(review_service,"review",new=AsyncMock(side_effect=delayed)) as mocked:
                first=asyncio.create_task(self.client.post(endpoint+"/review",headers=a))
                await asyncio.wait_for(started.wait(),5)
                duplicate=await self.client.post(endpoint+"/review",headers=a)
                self.assertEqual(duplicate.status_code,409,duplicate.text)
                finish.set()
                result=(await first).json()
                self.assertEqual(mocked.await_count,1)
            body={"review_id":result["review_id"],"selected_indices":[0,1]}
            applied=await asyncio.gather(*(self.client.post(endpoint+"/review/apply",headers=a,json=body) for _ in range(4)))
            self.assertTrue(all(r.status_code==200 for r in applied),[r.text for r in applied])
            self.assertTrue(all(len(r.json()["detections"])==2 for r in applied))
            self.assertEqual(len({tuple(d["id"] for d in r.json()["detections"]) for r in applied}),1)
            # Competing callers for the final slot may produce one conflict or
            # a capped response, but must never start a fourth provider request.
            with patch.object(review_service,"review",new=AsyncMock(side_effect=lambda *args: self.review_answer())) as mocked:
                answers=await asyncio.gather(*(self.client.post(endpoint+"/review",headers=a) for _ in range(5)))
                self.assertLessEqual(mocked.await_count,2)
                self.assertTrue(all(r.status_code in (200,409,503) for r in answers))
            self.assertEqual((await self.client.get(endpoint,headers=a)).json()["review_attempts"],3)
            # Use another photo for the deletion race after exhausting this one.
            endpoint=await self.empty_observation(a)
            # Deletion while a paid request is in flight cannot resurrect the photo.
            started.clear(); finish.clear()
            with patch.object(review_service,"review",new=delayed):
                pending=asyncio.create_task(self.client.post(endpoint+"/review",headers=a))
                await asyncio.wait_for(started.wait(),5)
                self.assertEqual((await self.client.delete(endpoint,headers=a)).status_code,204)
                finish.set()
                self.assertEqual((await pending).status_code,404)
            self.assertEqual((await self.client.get(endpoint,headers=a)).status_code,404)
        finally:
            finish.set()
            await concurrent_engine.dispose()

    async def test_legacy_polluted_history_migration_is_repeatable(self):
        import json
        from contextlib import asynccontextmanager
        from types import SimpleNamespace
        from app.db.initialize import initialize_database
        a=await self.account("migration_a")
        endpoint=await self.empty_observation(a)
        oid=endpoint.rsplit('/',1)[1]
        category=await self.conn.scalar(text("SELECT id FROM cat_types WHERE code='ragdoll'"))
        ids=[]
        for batch in range(5):
            rid=str(uuid.uuid4())
            for index in range(2):
                did=await self.conn.scalar(text("""INSERT INTO detections (observation_id,cat_type_id,raw_label,confidence,bounding_box)
                    VALUES (:oid,:cid,'布偶猫',0.9,CAST(:box AS jsonb)) RETURNING id"""),
                    {"oid":oid,"cid":category,"box":json.dumps({"xyxy":[],"review_id":rid,"review_index":index})})
                ids.append(did)
        await self.conn.execute(text("UPDATE user_observations SET confirmed=true,confirmed_detection_ids=CAST(:ids AS jsonb) WHERE observation_id=:id"),{"ids":json.dumps(ids),"id":oid})
        await self.conn.commit()
        # Simulate the old schema. Its real ALTER path must work, not just fresh metadata.
        for column in ('review_attempts','review_request_id','review_busy_until','review_layout_version','auto_review_attempted'):
            await self.conn.execute(text(f'ALTER TABLE observations DROP COLUMN {column}'))
        await self.conn.execute(text('ALTER TABLE detections DROP COLUMN active'))
        await self.conn.commit()
        @asynccontextmanager
        async def begin():
            async with self.conn.begin():
                yield self.conn
        with patch('app.db.initialize.engine',SimpleNamespace(begin=begin)):
            await initialize_database()
        cleaned=(await self.client.get(endpoint,headers=a)).json()
        self.assertEqual(len(cleaned['detections']),2)
        self.assertEqual(cleaned['review_attempts'],3)
        self.assertEqual(set(cleaned['confirmed_detection_ids']),set(ids[-2:]))
        # Restart must never reactivate a cat intentionally unchecked afterwards.
        await self.client.patch(endpoint,headers=a,json={'confirmed_detection_ids':[ids[-1]]})
        await self.conn.commit()
        with patch('app.db.initialize.engine',SimpleNamespace(begin=begin)):
            await initialize_database()
        again=(await self.client.get(endpoint,headers=a)).json()
        self.assertEqual(again['confirmed_detection_ids'],[ids[-1]])
        self.assertEqual(again['review_attempts'],3)
        self.assertEqual(len(again['detections']),2)

    async def test_auto_fallback_catalog_is_collectable_and_manual_quota_stays_three(self):
        a=await self.account("auto_catalog")
        headers={**a,"X-Request-ID":str(uuid.uuid4())}
        with patch.object(service,"detect_cat",return_value=DetectCatResponse(success=True,message="none")), \
                patch.object(review_service,"review",new=AsyncMock(side_effect=lambda *args:self.review_answer())) as mocked:
            item=(await self.client.post("/api/v1/observations",headers=headers,
                files={"file":("cat.jpg",self.picture,"image/jpeg")})).json()
            repeat=(await self.client.post("/api/v1/observations",headers=headers,
                files={"file":("cat.jpg",self.picture,"image/jpeg")})).json()
            self.assertEqual(mocked.await_count,1)
            self.assertEqual(item['id'],repeat['id'])
        self.assertEqual(item['review_attempts'],0)
        self.assertEqual(len(item['detections']),2)
        self.assertTrue(all(d['source']=='automatic' and not d['confirmed'] for d in item['detections']))
        self.assertTrue(item['review_result']['automatic'])
        self.assertIsNone(item['applied_review_id'])
        self.assertFalse(item['confirmed'])
        endpoint='/api/v1/observations/'+item['id']
        saved=await self.client.patch(endpoint,headers=a,json={'confirmed_detection_ids':[d['id'] for d in item['detections']]})
        self.assertTrue(saved.json()['confirmed'])
        with patch.object(review_service,'review',new=AsyncMock(side_effect=lambda *args:self.review_answer())) as mocked:
            for count in range(1,4):
                result=(await self.client.post(endpoint+'/review',headers=a)).json()
                self.assertEqual(result['review_attempts'],count)
                self.assertFalse(result['automatic'])
            self.assertFalse((await self.client.post(endpoint+'/review',headers=a)).json()['success'])
            self.assertEqual(mocked.await_count,3)

    async def test_auto_outside_species_stays_outside_catalog(self):
        a=await self.account('auto_outside')
        answer=ReviewResponse(success=True,model='mock',message='ok',suggestions=[
            ReviewSuggestion(label='土耳其安哥拉猫',confidence=.9,bounding_box=[.1,.1,.9,.9])])
        with patch.object(service,'detect_cat',return_value=DetectCatResponse(success=True,message='none')), \
                patch.object(review_service,'review',new=AsyncMock(return_value=answer)):
            item=(await self.client.post('/api/v1/observations',headers={**a,'X-Request-ID':str(uuid.uuid4())},
                files={'file':('cat.jpg',self.picture,'image/jpeg')})).json()
        self.assertTrue(item['review_result']['automatic'])
        self.assertIsNone(item['detections'][0]['cat_code'])
        self.assertEqual(item['detections'][0]['label'],'土耳其安哥拉猫')
        self.assertEqual(item['review_attempts'],0)
        r=await self.client.patch('/api/v1/observations/'+item['id'],headers=a,json={'confirmed':True})
        self.assertEqual(r.status_code,400)
        self.assertFalse(any(c['collected'] for c in (await self.client.get('/api/v1/catalog',headers=a)).json()))

    async def test_auto_timeout_and_empty_answer_preserve_saved_photo_and_quota(self):
        a=await self.account('auto_empty')
        with patch.object(review_service,'review',new=AsyncMock(side_effect=TimeoutError)) as mocked:
            endpoint=await self.empty_observation(a)
            self.assertEqual(mocked.await_count,1)
        item=(await self.client.get(endpoint,headers=a)).json()
        self.assertEqual(item['detections'],[])
        self.assertEqual(item['review_attempts'],0)
        with patch.object(review_service,'review',new=AsyncMock(return_value=ReviewResponse(success=True,model='mock',message='none'))) as mocked:
            endpoint=await self.empty_observation(a)
            self.assertEqual(mocked.await_count,1)
        item=(await self.client.get(endpoint,headers=a)).json()
        self.assertEqual(item['detections'],[])
        self.assertEqual(item['review_attempts'],0)
        state=(await self.conn.execute(text('SELECT auto_review_attempted,review_busy_until FROM observations WHERE id=:id'),{'id':item['id']})).first()
        self.assertTrue(state.auto_review_attempted)
        self.assertIsNone(state.review_busy_until)

    async def test_auto_does_not_run_when_yolo_found_target(self):
        a=await self.account('auto_noop')
        with patch.object(service,'detect_cat',return_value=DetectCatResponse(success=True,message='ok',detections=[
                DetectionItem(label='cat',confidence=.8)])), patch.object(review_service,'review',new=AsyncMock()) as mocked:
            response=await self.client.post('/api/v1/observations',headers={**a,'X-Request-ID':str(uuid.uuid4())},
                files={'file':('cat.jpg',self.picture,'image/jpeg')})
        self.assertEqual(response.status_code,201,response.text)
        self.assertEqual(mocked.await_count,0)

    async def test_auto_retry_concurrency_does_not_duplicate_provider_call(self):
        from sqlalchemy.ext.asyncio import create_async_engine
        a=await self.account('auto_race')
        concurrent_engine=create_async_engine(settings.database_url,
            connect_args={'server_settings':{'search_path':self.schema}})
        factory=async_sessionmaker(concurrent_engine,expire_on_commit=False)
        async def session():
            async with factory() as db:
                yield db
        self.app.dependency_overrides[get_session]=session
        started,finish=asyncio.Event(),asyncio.Event()
        async def delayed(*args):
            started.set();await finish.wait();return self.review_answer()
        headers={**a,'X-Request-ID':str(uuid.uuid4())}
        async def upload():
            return await self.client.post('/api/v1/observations',headers=headers,
                files={'file':('cat.jpg',self.picture,'image/jpeg')})
        try:
            with patch.object(service,'detect_cat',return_value=DetectCatResponse(success=True,message='none')), \
                    patch.object(review_service,'review',new=AsyncMock(side_effect=delayed)) as mocked:
                first=asyncio.create_task(upload())
                await asyncio.wait_for(started.wait(),5)
                retry=await upload()
                self.assertEqual(retry.status_code,201,retry.text)
                finish.set()
                completed=await first
                self.assertEqual(completed.status_code,201,completed.text)
                self.assertEqual(completed.json()['id'],retry.json()['id'])
                self.assertEqual(mocked.await_count,1)
                self.assertEqual(completed.json()['review_attempts'],0)
                self.assertEqual(len(completed.json()['detections']),2)
        finally:
            finish.set()
            await concurrent_engine.dispose()

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
