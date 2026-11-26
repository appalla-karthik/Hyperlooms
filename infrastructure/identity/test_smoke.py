import base64
import json
import unittest

from smoke import claims, loopback_origin


class SmokeHelpersTest(unittest.TestCase):
    def test_only_loopback_origin_is_allowed(self):
        self.assertEqual("http://localhost:8180", loopback_origin("http://localhost:8180/"))
        for value in ("https://localhost:8180", "http://example.com",
                      "http://user:pass@localhost:8180", "http://localhost:8180/path"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                loopback_origin(value)

    def test_claims_decodes_payload_without_printing_token(self):
        payload = base64.urlsafe_b64encode(json.dumps({"sub": "user-1"}).encode()).rstrip(b"=")
        self.assertEqual("user-1", claims("header." + payload.decode() + ".signature")["sub"])


if __name__ == "__main__":
    unittest.main()
