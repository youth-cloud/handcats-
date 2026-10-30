"""Smoke-test a running development API with a temporary account and real model.

Run from backend: python -X utf8 scripts/check_deployed_model.py
Uses selected known-positive test images to verify integration, not measure accuracy.
Deletes only the account and records created by this run via the normal API.
"""
import argparse
import hashlib
import json
import secrets
import sys
import time
import uuid
from pathlib import Path

import httpx

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
from app.core.config import settings
from app.services.catalog import code_for_label


def require(response, status):
    if response.status_code != status:
        raise RuntimeError(f"{response.request.method} {response.request.url.path}: expected {status}, got {response.status_code}")
    return response.json() if status != 204 else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", default="http://127.0.0.1:8000")
    args = parser.parse_args()
    root = BACKEND.parent
    audit = json.loads((root / "runs/chinese/cat_breed_12classes_20261017_test/app_threshold_audit.json").read_text(encoding="utf-8"))
    labels = {row["id"]: row["name"] for row in audit["classes"]}
    model_path = Path(settings.model_weights_path)
    assert model_path.is_file(), "Configured model is missing"
    source = Path(audit["model"])
    digest = hashlib.sha256(model_path.read_bytes()).hexdigest()
    assert digest == hashlib.sha256(source.read_bytes()).hexdigest(), "Model checksum mismatch"
    report = {"server": args.server, "model": str(model_path), "sha256": digest,
              "purpose": "Known-positive integration smoke test, not an accuracy evaluation",
              "samples": [], "checks": [], "temporary_account_deleted": False}
    password = secrets.token_urlsafe(24)
    token = None
    with httpx.Client(base_url=args.server, timeout=150, trust_env=False) as client:
        try:
            assert require(client.get("/health/db"), 200)["status"] == "ok"
            require(client.get("/api/v1/catalog"), 401)
            account = require(client.post("/api/v1/auth/register", json={
                "username": "model_smoke_" + uuid.uuid4().hex[:16],
                "password": password, "nickname": "模型接入临时测试"}), 201)
            token = account["token"]
            client.headers["Authorization"] = "Bearer " + token
            assert len(require(client.get("/api/v1/catalog"), 200)) == 12
            first = None
            for class_id in range(12):
                candidates = [r for r in audit["images_detail"] if r["gt_classes"] == [class_id]
                              and r["class_set_exact"] and len(r["predictions"]) == 1]
                row = max(candidates, key=lambda r: r["predictions"][0]["confidence"])
                path = root / "训练集/one/test/images" / row["file"]
                data = path.read_bytes()
                request_id = str(uuid.uuid4())
                start = time.perf_counter()
                obs = require(client.post("/api/v1/observations", headers={"X-Request-ID": request_id},
                    files={"file": (path.name, data, "image/jpeg")}), 201)
                elapsed = (time.perf_counter() - start) * 1000
                assert obs["model_version"] == model_path.name, "Running server did not use the configured model version"
                assert obs["status"] == "processed" and not obs["confirmed"]
                for d in obs["detections"]:
                    assert d["cat_code"] == code_for_label(d["label"]) and d["cat_code"]
                    assert len(d["bounding_box"]) == 4 and all(0 <= x <= 1 for x in d["bounding_box"])
                top = max(obs["detections"], key=lambda d: d["confidence"], default=None)
                report["samples"].append({"file": path.name, "expected": labels[class_id],
                    "predictions": [{"label": d["label"], "cat_code": d["cat_code"], "confidence": d["confidence"]} for d in obs["detections"]],
                    "top1_matches": bool(top and top["cat_code"] == code_for_label(labels[class_id])),
                    "model_version": obs["model_version"], "upload_request_ms": round(elapsed, 1)})
                assert not any(c["collected"] for c in require(client.get("/api/v1/catalog"), 200))
                if first is None:
                    first = obs
                    again = require(client.post("/api/v1/observations", headers={"X-Request-ID": request_id},
                        files={"file": (path.name, data, "image/jpeg")}), 201)
                    assert again["id"] == obs["id"]
                    preview = client.get(obs["image_url"])
                    assert preview.status_code == 200 and preview.headers["content-type"].startswith("image/")
                    response = require(client.post("/api/v1/detection/cat", files={"file": (path.name, data, "image/jpeg")}), 200)
                    assert response["success"] and response["detections"]
                print(f"Checked class {class_id}: {labels[class_id]} -> {top['label'] if top else 'no detection'}", flush=True)
            assert require(client.get("/api/v1/observations"), 200)["total"] == 12
            for class_id in range(12):
                code = code_for_label(labels[class_id])
                history = require(client.get("/api/v1/observations", params={"cat_code": code, "limit": 1}), 200)
                assert history["total"] >= 1 and len(history["items"]) == 1
                assert any(d["cat_code"] == code for d in history["items"][0]["detections"])
            require(client.get("/api/v1/observations", params={"cat_code": "missing"}), 422)
            updated = require(client.patch(f"/api/v1/observations/{first['id']}", json={"confirmed": True}), 200)
            assert updated["confirmed"]
            expected_codes = {d["cat_code"] for d in first["detections"]}
            catalog = require(client.get("/api/v1/catalog"), 200)
            assert {c["code"] for c in catalog if c["collected"]} == expected_codes
            require(client.patch(f"/api/v1/observations/{first['id']}", json={"confirmed": False}), 200)
            assert not any(c["collected"] for c in require(client.get("/api/v1/catalog"), 200))
            require(client.post("/api/v1/observations", headers={"X-Request-ID": str(uuid.uuid4())},
                files={"file": ("invalid.jpg", b"invalid image", "image/jpeg")}), 400)
            report["checks"] = ["database_health", "authentication_required", "all_12_label_mappings",
                "real_upload_inference_and_persistence", "deployed_model_version", "idempotent_upload", "image_download",
                "legacy_detection_endpoint", "no_automatic_collection", "confirm_and_revoke_catalog", "reject_invalid_image", "category_history_filter"]
        finally:
            if token:
                require(client.post("/api/v1/auth/delete-account", json={"password": password}), 204)
                require(client.get("/api/v1/auth/me"), 401)
                report["temporary_account_deleted"] = True
            target = root / "docs/model_deployment_2026-10-28_results.json"
            target.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"checks": report["checks"], "samples": len(report["samples"]),
        "known_positive_top1_matches": sum(s["top1_matches"] for s in report["samples"]),
        "temporary_account_deleted": report["temporary_account_deleted"]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
