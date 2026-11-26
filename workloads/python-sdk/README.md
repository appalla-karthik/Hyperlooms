# Python Workload SDK

This SDK contains a dependency-free, versioned input-manifest builder and a synchronous requester intake client. It can submit a job, stage the manifest and files through the control plane, queue verified input, read status, and cancel an unleased job. It does **not** authorize storage access itself, run PyTorch, or execute a GPU job. The control-plane requester API must be enabled and configured with a real OIDC issuer; there is no deployed identity provider in this repository.

From this directory (Python 3.11+):

```python
from hyperlooms_sdk import InputManifestV1

manifest = InputManifestV1.from_paths(
    "./inputs", ["request.json", "nested/weights.bin"]
)
manifest_bytes = manifest.canonical_bytes()
manifest_digest = manifest.sha256()
```

The digest can populate the internal `JobRequestV1.inputManifestSha256` field. The control plane has a strict reader for these canonical bytes, but that value is only a declaration until the control plane verifies every stored object and binds an immutable verified snapshot to the job. Never queue a job based solely on the client-provided digest or parsed manifest.

For a configured control plane, install locally with `python -m pip install -e .` and supply a valid requester access token from the configured identity provider:

```python
from hyperlooms_sdk import RequesterClient

client = RequesterClient("https://control.example.com", bearer_token=access_token)
result = client.submit_inputs(
    recipe_id=approved_recipe_id,  # UUID supplied by the operator
    root="./inputs",
    relative_paths=["request.json"],
    idempotency_key="stable-user-chosen-key",
)
print(result["jobId"], result["state"])
print(client.get(result["jobId"]))
```

The recipe ID must already be approved by the control plane. The client requires HTTPS by default; explicit HTTP is allowed only for loopback development (`allow_insecure_localhost=True`). It never follows redirects with the bearer token. It verifies each local file against the manifest again immediately before upload, enforces the current 8 MiB/file and 32 MiB/job staging limits, and does not automatically retry non-idempotent operations. If an upload is interrupted, inspect the job and retry with the same idempotency key and unchanged inputs. A replay that is already past `CREATED` is returned without re-upload. Cancellation is currently only supported before a lease is claimed.

Canonical v1 bytes are UTF-8 JSON without whitespace, with object keys sorted lexicographically: `{"files":[{"path":"...","sha256":"...","sizeBytes":0}],"schemaVersion":1}`. File entries are sorted by path; SHA-256 values are lowercase hex. Paths are relative slash-separated ASCII segments without `.`/`..`, backslashes, or symlinks. The format caps at 256 entries and 64 KiB of canonical JSON. There is no global file-size quota in this helper; server policy must enforce quotas before staging or scheduling. A local file can still change after hashing, so this manifest is not a security attestation.

Run tests with `python -m unittest discover -s tests -v` (or `py -m unittest discover -s tests -v` on Windows). Linux CI also exercises symlink rejection. The HTTP client tests use a loopback fake control plane; they are not a live OIDC, S3, or GPU test. Later SDK increments will cover structured workload inputs, progress, checkpoints, output manifests, and bounded error categories; they will not grant arbitrary host or control-plane access.
