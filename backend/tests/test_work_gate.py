import asyncio
import threading
import unittest

from fastapi import HTTPException

from app.services.work_gate import WorkGate


class WorkGateTests(unittest.IsolatedAsyncioTestCase):
    async def test_capacity_rejects_and_queue_times_out_without_leaking_slots(self):
        gate = WorkGate(1, 2, .02)
        async with gate.slot():
            waiting = asyncio.create_task(gate.run_sync(lambda: "unexpected"))
            await asyncio.sleep(.001)
            with self.assertRaises(HTTPException) as full:
                async with gate.slot():
                    pass
            self.assertEqual(full.exception.status_code, 503)
            with self.assertRaises(HTTPException):
                await waiting
            self.assertEqual(gate.pending, 1)
        self.assertEqual(gate.pending, 0)
        self.assertEqual(await gate.run_sync(lambda: "ok"), "ok")

    async def test_disconnect_keeps_cpu_slot_until_thread_really_exits(self):
        gate = WorkGate(1, 1, .1)
        started, finish = threading.Event(), threading.Event()
        def work():
            started.set()
            finish.wait(3)
        task = asyncio.create_task(gate.run_sync(work))
        try:
            while not started.is_set():
                await asyncio.sleep(.001)
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
            self.assertEqual(gate.pending, 1)
            with self.assertRaises(HTTPException):
                await gate.run_sync(lambda: None)
        finally:
            finish.set()
            while gate.tasks:
                await asyncio.sleep(.001)
        self.assertEqual(gate.pending, 0)
