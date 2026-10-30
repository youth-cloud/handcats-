import json
import unittest
from unittest.mock import patch

import httpx

from app.core.config import settings
from app.services.deepseek_review import DeepSeekReviewService


class DeepSeekReviewTests(unittest.IsolatedAsyncioTestCase):
    async def test_catalog_and_outside_labels_and_image_request(self):
        async def respond(request):
            data = json.loads(request.content)
            self.assertEqual(data["response_format"], {"type": "json_object"})
            self.assertEqual(data["messages"][0]["content"][1]["image_url"]["url"], "data:image/jpeg;base64,cGhvdG8=")
            return httpx.Response(200, json={"choices":[{"message":{"content":json.dumps({
                "cats":[{"label":"Ragdoll","confidence":.9},{"label":"土耳其安哥拉猫","confidence":.8}],
                "explanation":"外观参考"})}}]})
        client = httpx.AsyncClient(transport=httpx.MockTransport(respond))
        with patch.object(settings,"deepseek_api_key","test-key"), patch("app.services.deepseek_review.httpx.AsyncClient",return_value=client):
            result = await DeepSeekReviewService().review(b"photo",[])
        self.assertTrue(result.success)
        self.assertEqual(result.suggestions[0].label,"布偶猫")
        self.assertEqual(result.suggestions[0].cat_code,"ragdoll")
        self.assertEqual(result.suggestions[1].label,"土耳其安哥拉猫")
        self.assertIsNone(result.suggestions[1].cat_code)

    async def test_invalid_structure_returns_failure(self):
        client = httpx.AsyncClient(transport=httpx.MockTransport(lambda request: httpx.Response(200,json={
            "choices":[{"message":{"content":'{"cats":null}'}}]})))
        with patch.object(settings,"deepseek_api_key","test-key"), patch("app.services.deepseek_review.httpx.AsyncClient",return_value=client):
            result = await DeepSeekReviewService().review(b"photo",[])
        self.assertFalse(result.success)
