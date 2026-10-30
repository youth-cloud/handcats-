"""Small staging check using one dedicated tester. Removes its newly created photos."""
import argparse
import asyncio
import getpass
import time
import uuid
from pathlib import Path

import httpx


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("base_url")
    parser.add_argument("photo", type=Path)
    parser.add_argument("--count", type=int, default=5)
    args = parser.parse_args()
    if not 1 <= args.count <= 8:
        parser.error("Use 1..8 concurrent uploads for this staging check")
    photo = args.photo.read_bytes()
    if len(photo) > 12 * 1024 * 1024:
        parser.error("Photo must be <= 12 MiB")
    username = input("Dedicated tester username: ").strip()
    password = getpass.getpass("Password: ")
    async with httpx.AsyncClient(base_url=args.base_url.rstrip("/"), timeout=120, follow_redirects=False) as client:
        login = await client.post("/api/v1/auth/login",json={"username":username,"password":password})
        login.raise_for_status()
        client.headers["Authorization"] = "Bearer " + login.json()["token"]
        created = set()
        async def upload():
            request_id = str(uuid.uuid4())
            start = time.perf_counter()
            response = await client.post("/api/v1/observations",headers={"X-Request-ID":request_id},
                files={"file":("test.jpg",photo,"image/jpeg")})
            elapsed = time.perf_counter()-start
            if response.status_code == 201:
                data=response.json(); created.add(data["id"])
                return request_id, data["id"], elapsed
            print("HTTP",response.status_code,"seconds",round(elapsed,2))
            if response.status_code not in (429,503):
                raise RuntimeError("Unexpected upload failure")
        try:
            before = time.perf_counter()
            results = await asyncio.gather(*(upload() for _ in range(args.count)), return_exceptions=True)
            for result in results:
                if isinstance(result,Exception):
                    raise RuntimeError("Upload failed; check API logs") from result
            successful = [r for r in results if r]
            print("Successful uploads:",len(successful),"total seconds:",round(time.perf_counter()-before,2))
            if not successful:
                raise RuntimeError("No upload succeeded")
            request_id, observation_id, _ = successful[0]
            retry = await client.post("/api/v1/observations",headers={"X-Request-ID":request_id},
                files={"file":("test.jpg",photo,"image/jpeg")})
            retry.raise_for_status()
            if retry.json()["id"] != observation_id:
                raise RuntimeError("Idempotency check failed")
            health = await client.get("/health/db")
            health.raise_for_status()
            print("Retry kept the same observation; DB health passed")
        finally:
            for observation_id in created:
                result = await client.delete(f"/api/v1/observations/{observation_id}")
                if result.status_code != 204:
                    print("Cleanup needed for observation:",observation_id)
            await client.post("/api/v1/auth/logout")


if __name__ == "__main__":
    asyncio.run(main())
