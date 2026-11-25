import hashlib
import os
from pathlib import Path
import tempfile
import unittest

from hyperlooms_sdk import InputManifestV1, ManifestEntry


class InputManifestV1Tests(unittest.TestCase):
    def test_same_files_produce_same_sorted_canonical_bytes_and_digest(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "nested").mkdir()
            (root / "nested" / "weights.bin").write_bytes(b"weights")
            (root / "request.json").write_bytes(b"{}")

            first = InputManifestV1.from_paths(
                root, ["request.json", "nested/weights.bin"])
            second = InputManifestV1.from_paths(
                root, ["nested/weights.bin", "request.json"])
            expected = (
                b'{"files":[{"path":"nested/weights.bin","sha256":"'
                + hashlib.sha256(b"weights").hexdigest().encode("ascii")
                + b'","sizeBytes":7},{"path":"request.json","sha256":"'
                + hashlib.sha256(b"{}").hexdigest().encode("ascii")
                + b'","sizeBytes":2}],"schemaVersion":1}'
            )

            self.assertEqual(expected, first.canonical_bytes())
            self.assertEqual(first.canonical_bytes(), second.canonical_bytes())
            self.assertEqual(hashlib.sha256(expected).hexdigest(), first.sha256())

    def test_empty_manifest_is_valid_and_versioned(self):
        manifest = InputManifestV1(())
        self.assertEqual(b'{"files":[],"schemaVersion":1}', manifest.canonical_bytes())

    def test_rejects_unsafe_paths_duplicates_and_nonregular_inputs(self):
        digest = "a" * 64
        for path in ("../escape", "./same", "/absolute", "a//b", "a\\b",
                     "C:/drive", "unicode-é", "a/"):
            with self.subTest(path=path):
                with self.assertRaises(ValueError):
                    ManifestEntry(path, 1, digest)
        with self.assertRaises(ValueError):
            InputManifestV1((ManifestEntry("x", 1, digest),
                             ManifestEntry("x", 1, digest)))
        with self.assertRaises(ValueError):
            InputManifestV1((ManifestEntry("z", 1, digest),
                             ManifestEntry("a", 1, digest)))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "directory").mkdir()
            with self.assertRaises(ValueError):
                InputManifestV1.from_paths(root, ["directory"])
            with self.assertRaises(ValueError):
                InputManifestV1.from_paths(root, ["missing"])

    def test_rejects_symlink_when_platform_allows_creating_one(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "actual").write_bytes(b"data")
            try:
                os.symlink(root / "actual", root / "linked")
            except (OSError, NotImplementedError):
                self.skipTest("symlink creation unavailable on this host")
            with self.assertRaises(ValueError):
                InputManifestV1.from_paths(root, ["linked"])

    def test_rejects_malformed_entry_and_oversized_manifest(self):
        for size in (-1, True, 1 << 63):
            with self.subTest(size=size):
                with self.assertRaises(ValueError):
                    ManifestEntry("input.bin", size, "a" * 64)
        with self.assertRaises(ValueError):
            ManifestEntry("input.bin", 1, "A" * 64)
        entries = tuple(ManifestEntry(f"{index:03d}-" + "a" * 230, 0,
                                      "a" * 64) for index in range(256))
        with self.assertRaises(ValueError):
            InputManifestV1(entries)

    def test_rejects_oversized_path_iterator_without_consuming_it_all(self):
        with tempfile.TemporaryDirectory() as temporary:
            yielded = 0

            def paths():
                nonlocal yielded
                while True:
                    yielded += 1
                    yield f"file-{yielded}"

            with self.assertRaises(ValueError):
                InputManifestV1.from_paths(temporary, paths())
            self.assertEqual(257, yielded)


if __name__ == "__main__":
    unittest.main()
