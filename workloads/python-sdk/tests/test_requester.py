import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import tempfile
import threading
import unittest
from urllib.parse import parse_qs, urlsplit
from uuid import UUID

from hyperlooms_sdk import InputManifestV1, RequesterApiError, RequesterClient


JOB_ID = "11111111-1111-1111-1111-111111111111"
RECIPE_ID = "22222222-2222-2222-2222-222222222222"


class FakeControlPlane(BaseHTTPRequestHandler):
    calls = []
    state = "CREATED"
    reject_path = None
    redirect_path = None

    def log_message(self, *_args):
        pass

    def do_POST(self):
        self.handle_request()

    def do_PUT(self):
        self.handle_request()

    def do_GET(self):
        self.handle_request()

    def handle_request(self):
        size = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(size)
        self.calls.append((self.command, self.path, dict(self.headers), body))
        if self.path == self.reject_path:
            self.send_response(403)
            self.end_headers()
            self.wfile.write(b'{"detail":"never expose this"}')
            return
        if self.path == self.redirect_path:
            self.send_response(302)
            self.send_header("Location", "/unexpected-destination")
            self.end_headers()
            return
        path = urlsplit(self.path).path
        result = {}
        if self.command == "POST" and path == "/api/v1/jobs":
            result = {"jobId": JOB_ID, "state": type(self).state,
                      "replayed": type(self).state != "CREATED"}
        elif self.command == "POST" and path.endswith("/queue"):
            type(self).state = "QUEUED"
            result = {"jobId": JOB_ID, "state": type(self).state, "replayed": False}
        elif self.command == "POST" and path.endswith("/cancel"):
            result = {"jobId": JOB_ID, "state": "CANCELED", "replayed": False}
        elif self.command == "GET":
            result = {"jobId": JOB_ID, "state": type(self).state}
        response = json.dumps(result).encode("ascii")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(response)))
        self.end_headers()
        self.wfile.write(response)


class RequesterClientTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeControlPlane)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)

    def setUp(self):
        FakeControlPlane.calls = []
        FakeControlPlane.state = "CREATED"
        FakeControlPlane.reject_path = None
        FakeControlPlane.redirect_path = None
        self.client = RequesterClient(self.base, "test-token",
                                      allow_insecure_localhost=True)

    def test_create_stage_queue_and_read(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "nested").mkdir()
            (root / "nested" / "input.txt").write_bytes(b"input")
            result = self.client.submit_inputs(RECIPE_ID, root,
                                               ["nested/input.txt"], "one-key")
            self.assertEqual("QUEUED", result["state"])
            self.assertEqual("QUEUED", self.client.get(JOB_ID)["state"])
        calls = FakeControlPlane.calls
        self.assertEqual(["POST", "PUT", "PUT", "POST", "GET"],
                         [call[0] for call in calls])
        self.assertEqual("Bearer test-token", calls[0][2]["Authorization"])
        self.assertEqual("one-key", {key.lower(): value for key, value in
                                     calls[0][2].items()}["idempotency-key"])
        self.assertEqual(RECIPE_ID, json.loads(calls[0][3])["recipeId"])
        manifest_body = calls[1][3]
        self.assertEqual(hashlib.sha256(manifest_body).hexdigest(),
                         json.loads(calls[0][3])["inputManifestSha256"])
        self.assertEqual("input", calls[2][3].decode())
        self.assertEqual(["nested/input.txt"],
                         parse_qs(urlsplit(calls[2][1]).query)["path"])

    def test_replayed_noncreated_job_is_not_staged(self):
        FakeControlPlane.state = "QUEUED"
        with tempfile.TemporaryDirectory() as temporary:
            result = self.client.submit_inputs(RECIPE_ID, temporary, [], "retry")
        self.assertEqual("QUEUED", result["state"])
        self.assertEqual(1, len(FakeControlPlane.calls))

    def test_changed_file_is_not_uploaded_or_queued(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "data.bin").write_bytes(b"before")
            manifest = InputManifestV1.from_paths(root, ["data.bin"])
            (root / "data.bin").write_bytes(b"after")
            with self.assertRaisesRegex(ValueError, "changed"):
                self.client.upload_file(JOB_ID, root, "data.bin", manifest)
        self.assertEqual([], FakeControlPlane.calls)

    def test_rejection_does_not_expose_server_body_or_token(self):
        FakeControlPlane.reject_path = "/api/v1/jobs/" + JOB_ID + "/cancel"
        with self.assertRaises(RequesterApiError) as caught:
            self.client.cancel(JOB_ID)
        self.assertEqual(403, caught.exception.status)
        self.assertNotIn("never expose", str(caught.exception))
        self.assertNotIn("test-token", str(caught.exception))

    def test_does_not_follow_redirect_with_bearer_token(self):
        FakeControlPlane.redirect_path = "/api/v1/jobs/" + JOB_ID
        with self.assertRaises(RequesterApiError) as caught:
            self.client.get(JOB_ID)
        self.assertEqual(302, caught.exception.status)
        self.assertEqual(1, len(FakeControlPlane.calls))

    def test_requires_https_except_explicit_loopback(self):
        with self.assertRaises(ValueError):
            RequesterClient(self.base, "token")
        with self.assertRaises(ValueError):
            RequesterClient("http://example.com", "token",
                            allow_insecure_localhost=True)
        with self.assertRaises(ValueError):
            RequesterClient("https://user:password@example.com", "token")
        with self.assertRaises(ValueError):
            RequesterClient("https://example.com", "token\nmalicious")
        with self.assertRaises(ValueError):
            self.client.submit(RECIPE_ID, InputManifestV1(()), "bad key")
        self.assertEqual([], FakeControlPlane.calls)

    def test_read_and_cancel_contract(self):
        self.assertEqual(UUID(JOB_ID), UUID(self.client.get(JOB_ID)["jobId"]))
        self.assertEqual("CANCELED", self.client.cancel(JOB_ID)["state"])


if __name__ == "__main__":
    unittest.main()
