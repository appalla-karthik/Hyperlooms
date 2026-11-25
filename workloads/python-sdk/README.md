# Python Workload SDK

The first implemented SDK piece is a dependency-free, versioned input-manifest builder. It hashes selected regular files and produces deterministic JSON bytes for future private artifact staging. It does **not** upload files, authorize storage access, run PyTorch, or submit a job.

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

Canonical v1 bytes are UTF-8 JSON without whitespace, with object keys sorted lexicographically: `{"files":[{"path":"...","sha256":"...","sizeBytes":0}],"schemaVersion":1}`. File entries are sorted by path; SHA-256 values are lowercase hex. Paths are relative slash-separated ASCII segments without `.`/`..`, backslashes, or symlinks. The format caps at 256 entries and 64 KiB of canonical JSON. There is no global file-size quota in this helper; server policy must enforce quotas before staging or scheduling. A local file can still change after hashing, so this manifest is not a security attestation.

Run tests with `python -m unittest discover -s tests -v` (or `py -m unittest discover -s tests -v` on Windows). Linux CI also exercises symlink rejection. Later SDK increments will cover structured workload inputs, progress, cancellation, checkpoints, output manifests, and bounded error categories; they will not grant arbitrary host or control-plane access.
