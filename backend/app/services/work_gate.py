"""Bounded single-process work queues. Deploy with exactly one API worker."""
import asyncio
from collections import OrderedDict, deque
from contextlib import asynccontextmanager
from functools import partial
from time import monotonic

from fastapi import HTTPException

from app.core.config import settings


class WorkGate:
    def __init__(self, workers: int, capacity: int, wait_seconds: float):
        self.semaphore = asyncio.Semaphore(workers)
        self.capacity = capacity
        self.wait_seconds = wait_seconds
        self.pending = 0
        self.tasks = set()

    @asynccontextmanager
    async def slot(self):
        if self.pending >= self.capacity:
            raise HTTPException(503, "识别任务较多，请稍后重试", headers={"Retry-After": "10"})
        self.pending += 1
        acquired = False
        try:
            try:
                await asyncio.wait_for(self.semaphore.acquire(), self.wait_seconds)
            except TimeoutError:
                raise HTTPException(503, "识别排队超时，请稍后重试", headers={"Retry-After": "10"}) from None
            acquired = True
            yield
        finally:
            if acquired:
                self.semaphore.release()
            self.pending -= 1

    async def run_sync(self, function, *args):
        async def work():
            async with self.slot():
                return await asyncio.to_thread(partial(function, *args))
        task = asyncio.create_task(work())
        self.tasks.add(task)
        def completed(done):
            self.tasks.discard(done)
            # Consume errors even if the HTTP caller disconnected.
            if not done.cancelled():
                done.exception()
        task.add_done_callback(completed)
        # Cancelling an HTTP request cannot stop a Python worker thread. Retain
        # its slot until it really finishes, preventing simultaneous YOLO access.
        return await asyncio.shield(task)


class UserThrottle:
    def __init__(self, count: int, seconds: float):
        self.count, self.seconds = count, seconds
        self.entries = OrderedDict()

    def check(self, key):
        now = monotonic()
        while self.entries and now - self.entries[next(iter(self.entries))][-1] >= self.seconds:
            self.entries.popitem(last=False)
        queue = self.entries.setdefault(str(key), deque())
        while queue and now - queue[0] >= self.seconds:
            queue.popleft()
        if len(queue) >= self.count:
            raise HTTPException(429, "操作较频繁，请稍后重试", headers={"Retry-After": str(int(self.seconds))})
        # Bound account-key memory; never evict a live limit to accept new keys.
        if len(self.entries) > 10000:
            del self.entries[str(key)]
            raise HTTPException(503, "服务繁忙，请稍后重试")
        queue.append(now)
        self.entries.move_to_end(str(key))


inference_gate = WorkGate(1, settings.inference_capacity, settings.inference_queue_seconds)
review_gate = WorkGate(2, settings.review_capacity, settings.inference_queue_seconds)
upload_throttle = UserThrottle(12, 60)
review_throttle = UserThrottle(20, 3600)
review_budget = UserThrottle(100, 86400)
