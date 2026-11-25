"""Deterministic input manifest creation; upload authorization is server-side work."""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
from itertools import islice
import json
import os
from pathlib import Path
import re
import stat
from typing import Iterable


_PATH = re.compile(r"[A-Za-z0-9._/-]{1,240}\Z")
_SHA256 = re.compile(r"[0-9a-f]{64}\Z")
_MAX_FILES = 256
_MAX_MANIFEST_BYTES = 65_536
_MAX_SIZE_BYTES = (1 << 63) - 1
_CHUNK_BYTES = 1024 * 1024


def _valid_path(value: str) -> bool:
    return (
        isinstance(value, str)
        and _PATH.fullmatch(value) is not None
        and all(part not in ("", ".", "..") for part in value.split("/"))
    )


@dataclass(frozen=True, slots=True)
class ManifestEntry:
    path: str
    size_bytes: int
    sha256: str

    def __post_init__(self) -> None:
        if not _valid_path(self.path):
            raise ValueError("manifest path must be normalized relative ASCII")
        if (isinstance(self.size_bytes, bool) or not isinstance(self.size_bytes, int)
                or not 0 <= self.size_bytes <= _MAX_SIZE_BYTES):
            raise ValueError("size_bytes must be a nonnegative signed 64-bit integer")
        if not isinstance(self.sha256, str) or _SHA256.fullmatch(self.sha256) is None:
            raise ValueError("sha256 must be lowercase SHA-256 hex")


@dataclass(frozen=True, slots=True)
class InputManifestV1:
    files: tuple[ManifestEntry, ...]

    def __post_init__(self) -> None:
        if not isinstance(self.files, tuple) or len(self.files) > _MAX_FILES:
            raise ValueError("manifest must contain at most 256 file entries")
        if any(not isinstance(entry, ManifestEntry) for entry in self.files):
            raise ValueError("manifest entries must be ManifestEntry values")
        paths = tuple(entry.path for entry in self.files)
        if paths != tuple(sorted(set(paths))):
            raise ValueError("manifest paths must be unique and sorted")
        if len(self.canonical_bytes()) > _MAX_MANIFEST_BYTES:
            raise ValueError("canonical manifest exceeds 64 KiB")

    @classmethod
    def from_paths(cls, root: os.PathLike[str] | str,
                   relative_paths: Iterable[str]) -> InputManifestV1:
        """Hash regular files under root; never follow a visible symlink component."""
        root_path = Path(root)
        if root_path.is_symlink() or not root_path.is_dir():
            raise ValueError("root must be a real directory")
        paths = list(islice(relative_paths, _MAX_FILES + 1))
        if len(paths) > _MAX_FILES:
            raise ValueError("manifest must contain at most 256 file entries")
        if any(not _valid_path(path) for path in paths) or len(paths) != len(set(paths)):
            raise ValueError("paths must be unique, normalized relative ASCII")

        entries = []
        for relative_path in sorted(paths):
            source = root_path
            for segment in relative_path.split("/"):
                source = source / segment
                if source.is_symlink():
                    raise ValueError("symlink inputs are not supported")
            if not source.is_file():
                raise ValueError("input must be a regular file")

            digest = hashlib.sha256()
            measured_size = 0
            with source.open("rb") as stream:
                initial = os.fstat(stream.fileno())
                if not stat.S_ISREG(initial.st_mode):
                    raise ValueError("input must be a regular file")
                for chunk in iter(lambda: stream.read(_CHUNK_BYTES), b""):
                    measured_size += len(chunk)
                    if measured_size > _MAX_SIZE_BYTES:
                        raise ValueError("input exceeds signed 64-bit size")
                    digest.update(chunk)
                final = os.fstat(stream.fileno())
            if measured_size != initial.st_size or final.st_size != initial.st_size:
                raise ValueError("input changed size while hashing")
            entries.append(ManifestEntry(relative_path, measured_size, digest.hexdigest()))
        return cls(tuple(entries))

    def canonical_bytes(self) -> bytes:
        payload = {
            "schemaVersion": 1,
            "files": [
                {"path": entry.path, "sha256": entry.sha256,
                 "sizeBytes": entry.size_bytes}
                for entry in self.files
            ],
        }
        return json.dumps(payload, sort_keys=True, separators=(",", ":"),
                          ensure_ascii=True).encode("ascii")

    def sha256(self) -> str:
        return hashlib.sha256(self.canonical_bytes()).hexdigest()
