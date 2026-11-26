"""Loopback-only Keycloak token/claim smoke; never prints tokens or passwords."""

from __future__ import annotations

import argparse
import base64
import json
import os
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import uuid4


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, new_url):
        return None


OPENER = build_opener(NoRedirect())


def loopback_origin(value: str) -> str:
    parsed = urlsplit(value)
    if (parsed.scheme != "http" or parsed.hostname not in ("localhost", "127.0.0.1")
            or parsed.username or parsed.password or parsed.path not in ("", "/")
            or parsed.query or parsed.fragment):
        raise ValueError("smoke endpoints must be loopback HTTP origins")
    return value.rstrip("/")


def call(method: str, url: str, *, form=None, payload=None, token=None):
    headers = {}
    body = None
    if form is not None:
        body = urlencode(form).encode("ascii")
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    if payload is not None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    with OPENER.open(Request(url, data=body, headers=headers, method=method),
                     timeout=60) as response:
        raw = response.read(65_537)
        if len(raw) > 65_536:
            raise ValueError("identity response too large")
        return json.loads(raw) if raw else None


def claims(access_token: str) -> dict:
    payload = access_token.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--identity-base", default="http://localhost:8180")
    parser.add_argument("--api-base", help="also test real Spring JWT verification")
    args = parser.parse_args()
    base = loopback_origin(args.identity_base)
    api_base = loopback_origin(args.api_base) if args.api_base else None
    password = os.environ.get("HYPERLOOMS_IDENTITY_ADMIN_PASSWORD")
    if not password:
        raise SystemExit("set HYPERLOOMS_IDENTITY_ADMIN_PASSWORD locally")
    realm = base + "/realms/hyperlooms-dev"
    discovery = call("GET", realm + "/.well-known/openid-configuration")
    if discovery["issuer"] != realm:
        raise ValueError("unexpected issuer")
    keys = call("GET", discovery["jwks_uri"])
    if not keys.get("keys"):
        raise ValueError("empty JWKS")
    admin = call("POST", base + "/realms/master/protocol/openid-connect/token",
                 form={"grant_type": "password", "client_id": "admin-cli",
                       "username": os.environ.get("HYPERLOOMS_IDENTITY_ADMIN_USER", "admin"),
                       "password": password})["access_token"]
    username = "hyperlooms-smoke-" + uuid4().hex[:12]
    user_password = uuid4().hex
    user = {"username": username, "firstName": "Smoke", "lastName": "Tester",
            "email": username + "@example.invalid", "emailVerified": True,
            "enabled": True, "requiredActions": [],
            "credentials": [{"type": "password", "value": user_password,
                             "temporary": False}]}
    users_url = base + "/admin/realms/hyperlooms-dev/users"
    call("POST", users_url, payload=user, token=admin)
    try:
        access_token = call("POST", discovery["token_endpoint"], form={
            "grant_type": "password", "client_id": "hyperlooms-local-client",
            "username": username, "password": user_password})["access_token"]
        issued = claims(access_token)
        audience = issued.get("aud", [])
        if isinstance(audience, str):
            audience = [audience]
        if (issued.get("iss") != realm or "hyperlooms-api" not in audience
                or not issued.get("sub") or not {"jobs:read", "jobs:write"}.issubset(
                    set(issued.get("scope", "").split()))):
            raise ValueError("token is missing required issuer, audience, subject or scopes")
        print("PASS: real Keycloak token has issuer, audience, subject and job scopes")
        if api_base:
            path = api_base + "/api/v1/jobs/00000000-0000-0000-0000-000000000001"
            for supplied, expected in ((None, 401), (access_token, 404)):
                try:
                    call("GET", path, token=supplied)
                except HTTPError as error:
                    if error.code != expected:
                        raise ValueError(f"expected HTTP {expected}, got {error.code}") from error
                    error.close()
                else:
                    raise ValueError(f"expected HTTP {expected}")
            print("PASS: Spring rejects missing token and accepts Keycloak JWT")
    finally:
        matches = call("GET", users_url + "?" + urlencode({"username": username,
                                                            "exact": "true"}), token=admin)
        for match in matches:
            call("DELETE", users_url + "/" + match["id"], token=admin)


if __name__ == "__main__":
    main()
