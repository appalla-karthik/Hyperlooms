"""Small authenticated client for the opt-in requester intake API.

This is an intake client, not a remote-execution or GPU availability claim.
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import stat
from typing import Any, Iterable
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import UUID

from .input_manifest import InputManifestV1


_KEY = re.compile(r"[A-Za-z0-9._:-]{1,200}\Z")
_MAX_FILE_BYTES = 8 * 1024 * 1024
_MAX_TOTAL_BYTES = 32 * 1024 * 1024
_MAX_RESPONSE_BYTES = 64 * 1024


class RequesterApiError(Exception):
    """A server rejection or transport failure, without leaking token/body data."""

    def __init__(self, message: str, status: int | None = None):
        super().__init__(message)
        self.status = status


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, new_url):
        return None


class RequesterClient:
    """Synchronous v1 client. The caller supplies a valid OIDC access token."""

    def __init__(self, base_url: str, bearer_token: str, *, timeout: float = 30,
                 allow_insecure_localhost: bool = False):
        parsed = urlsplit(base_url)
        local = parsed.hostname in ("localhost", "127.0.0.1", "::1")
        if (parsed.scheme != "https" and not
                (allow_insecure_localhost and parsed.scheme == "http" and local)):
            raise ValueError("HTTPS is required (except explicitly allowed localhost)")
        if not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("base_url must be an origin without credentials or query")
        if parsed.path not in ("", "/"):
            raise ValueError("base_url must not include a path")
        if not bearer_token or any(c.isspace() or ord(c) < 33 for c in bearer_token):
            raise ValueError("bearer_token must be a nonempty single-line token")
        if not isinstance(timeout, (int, float)) or not 0 < timeout <= 300:
            raise ValueError("timeout must be between 0 and 300 seconds")
        self._base_url = base_url.rstrip("/")
        self._token = bearer_token
        self._timeout = timeout
        self._opener = build_opener(_NoRedirect())

    def submit(self, recipe_id: str | UUID, manifest: InputManifestV1,
               idempotency_key: str) -> dict[str, Any]:
        if not isinstance(manifest, InputManifestV1):
            raise TypeError("manifest must be InputManifestV1")
        if not isinstance(idempotency_key, str) or not _KEY.fullmatch(idempotency_key):
            raise ValueError("invalid idempotency key")
        body = json.dumps({"recipeId": str(UUID(str(recipe_id))),
                           "inputManifestSha256": manifest.sha256()},
                          separators=(",", ":")).encode("ascii")
        return self._job_response(self._request(
            "POST", "/api/v1/jobs", body, "application/json",
            {"Idempotency-Key": idempotency_key}))

    def upload_manifest(self, job_id: str | UUID, manifest: InputManifestV1) -> None:
        self._request("PUT", self._job_path(job_id) + "/inputs/manifest",
                      manifest.canonical_bytes(), "application/json")

    def upload_file(self, job_id: str | UUID, root: os.PathLike[str] | str,
                    entry_path: str, manifest: InputManifestV1) -> None:
        entry = next((item for item in manifest.files if item.path == entry_path), None)
        if entry is None:
            raise ValueError("file is not in manifest")
        data = self._read_matching_file(root, entry_path, entry.size_bytes, entry.sha256)
        self._request("PUT", self._job_path(job_id) + "/inputs/files?path="
                      + quote(entry_path, safe=""), data, "application/octet-stream")

    def queue(self, job_id: str | UUID) -> dict[str, Any]:
        return self._job_response(self._request(
            "POST", self._job_path(job_id) + "/queue"), job_id)

    def get(self, job_id: str | UUID) -> dict[str, Any]:
        return self._job_response(self._request("GET", self._job_path(job_id)), job_id)

    def cancel(self, job_id: str | UUID) -> dict[str, Any]:
        return self._job_response(self._request(
            "POST", self._job_path(job_id) + "/cancel"), job_id)

    def submit_inputs(self, recipe_id: str | UUID, root: os.PathLike[str] | str,
                      relative_paths: Iterable[str], idempotency_key: str) -> dict[str, Any]:
        """Create, stage, and queue. Reuse the same key on a manual retry.

        No automatic retry: an interrupted upload may require inspecting job state.
        """
        manifest = InputManifestV1.from_paths(root, relative_paths)
        self._validate_sizes(manifest)
        submitted = self.submit(recipe_id, manifest, idempotency_key)
        job_id = UUID(submitted["jobId"])
        if submitted["state"] != "CREATED":
            # A replay can refer to a queued/claimed job. Never upload into it.
            return submitted
        self.upload_manifest(job_id, manifest)
        for entry in manifest.files:
            self.upload_file(job_id, root, entry.path, manifest)
        return self.queue(job_id)

    @staticmethod
    def _validate_sizes(manifest: InputManifestV1) -> None:
        if any(entry.size_bytes > _MAX_FILE_BYTES for entry in manifest.files):
            raise ValueError("file exceeds the server's 8 MiB staging limit")
        if sum(entry.size_bytes for entry in manifest.files) > _MAX_TOTAL_BYTES:
            raise ValueError("inputs exceed the server's 32 MiB staging limit")

    @staticmethod
    def _read_matching_file(root: os.PathLike[str] | str, relative: str,
                            expected_size: int, expected_digest: str) -> bytes:
        if expected_size > _MAX_FILE_BYTES:
            raise ValueError("file exceeds the server's 8 MiB staging limit")
        source = Path(root)
        if source.is_symlink() or not source.is_dir():
            raise ValueError("root must be a real directory")
        for segment in relative.split("/"):
            source = source / segment
            if source.is_symlink():
                raise ValueError("symlink inputs are not supported")
        flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(source, flags)
        with os.fdopen(descriptor, "rb") as stream:
            if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
                raise ValueError("input must be a regular file")
            data = stream.read(_MAX_FILE_BYTES + 1)
            if len(data) > _MAX_FILE_BYTES or stream.read(1):
                raise ValueError("file exceeds the server's 8 MiB staging limit")
        if len(data) != expected_size or hashlib.sha256(data).hexdigest() != expected_digest:
            raise ValueError("input changed since manifest creation")
        return data

    @staticmethod
    def _job_path(job_id: str | UUID) -> str:
        return "/api/v1/jobs/" + str(UUID(str(job_id)))

    @staticmethod
    def _job_response(payload: dict[str, Any],
                      expected_id: str | UUID | None = None) -> dict[str, Any]:
        try:
            job_id = UUID(payload["jobId"])
        except (KeyError, ValueError, TypeError, AttributeError) as error:
            raise RequesterApiError("invalid job response") from error
        if (expected_id is not None and job_id != UUID(str(expected_id))) or not isinstance(
                payload.get("state"), str) or not payload["state"]:
            raise RequesterApiError("invalid job response")
        return payload

    def _request(self, method: str, path: str, body: bytes | None = None,
                 content_type: str | None = None,
                 extra_headers: dict[str, str] | None = None) -> dict[str, Any]:
        headers = {"Authorization": "Bearer " + self._token,
                   "Accept": "application/json"}
        if content_type is not None:
            headers["Content-Type"] = content_type
        if extra_headers:
            headers.update(extra_headers)
        request = Request(self._base_url + path, data=body, method=method, headers=headers)
        try:
            with self._opener.open(request, timeout=self._timeout) as response:
                payload = response.read(_MAX_RESPONSE_BYTES + 1)
                if len(payload) > _MAX_RESPONSE_BYTES:
                    raise RequesterApiError("response exceeds 64 KiB")
                if not payload:
                    return {}
                decoded = json.loads(payload)
                if not isinstance(decoded, dict):
                    raise RequesterApiError("expected a JSON object response")
                return decoded
        except HTTPError as error:
            error.close()
            raise RequesterApiError("request rejected by control plane", error.code) from None
        except URLError as error:
            raise RequesterApiError("control plane connection failed") from error
        except (json.JSONDecodeError, UnicodeDecodeError) as error:
            raise RequesterApiError("invalid control plane JSON response") from error
