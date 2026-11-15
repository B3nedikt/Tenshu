#!/usr/bin/env python3
"""Pins `export-coreml-face-embedder.py`'s reproducibility rules and its refusals, with no model.

    python3 scripts/inference/test_export_coreml_face_embedder.py

Standard library only. ⚠ What this cannot show: that a real conversion is reproducible, or that
Core ML loads the canonical package. Those are the script's own run (it loads the archive it wrote)
and the two-run comparison recorded in `ios-face-grouping.md` §12. `canonical_spec` needs
coremltools and is exercised there, not here.
"""
import hashlib
import importlib.util
import json
import os
import sys
import tempfile
import unittest
import uuid

# Loading the script by path would otherwise drop an untracked `__pycache__/` beside it.
sys.dont_write_bytecode = True
_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("export_face", os.path.join(_HERE, "export-coreml-face-embedder.py"))
export = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(export)


def coremltools_manifest(model_id: str, weights_id: str) -> str:
    """`Manifest.json` as coremltools writes it, with the identifiers it would have minted."""
    return json.dumps(
        {
            "fileFormatVersion": "1.0.0",
            "itemInfoEntries": {
                model_id: {
                    "author": "com.apple.CoreML",
                    "description": "CoreML Model Specification",
                    "name": "model.mlmodel",
                    "path": "com.apple.CoreML/model.mlmodel",
                },
                weights_id: {
                    "author": "com.apple.CoreML",
                    "description": "CoreML Model Weights",
                    "name": "weights",
                    "path": "com.apple.CoreML/weights",
                },
            },
            "rootModelIdentifier": model_id,
        },
        indent=4,
    )


def random_manifest() -> str:
    return coremltools_manifest(str(uuid.uuid4()).upper(), str(uuid.uuid4()).upper())


def sha256(path: str) -> str:
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


class CanonicalManifestTest(unittest.TestCase):

    def test_two_saves_with_different_identifiers_give_the_same_bytes(self):
        self.assertEqual(export.canonical_manifest(random_manifest()), export.canonical_manifest(random_manifest()))

    def test_the_root_still_names_the_model_specification(self):
        canonical = json.loads(export.canonical_manifest(random_manifest()))
        root = canonical["itemInfoEntries"][canonical["rootModelIdentifier"]]
        self.assertEqual("com.apple.CoreML/model.mlmodel", root["path"])
        self.assertEqual(2, len(canonical["itemInfoEntries"]))
        # Still a UUID, in the case coremltools writes.
        for key in canonical["itemInfoEntries"]:
            self.assertEqual(key, str(uuid.UUID(key)).upper())

    def test_a_root_that_names_no_item_is_refused(self):
        broken = json.loads(random_manifest())
        broken["rootModelIdentifier"] = str(uuid.uuid4()).upper()
        with self.assertRaises(ValueError):
            export.canonical_manifest(json.dumps(broken))

    def test_two_items_at_one_path_are_refused(self):
        broken = json.loads(random_manifest())
        entries = list(broken["itemInfoEntries"].values())
        entries[1]["path"] = entries[0]["path"]
        with self.assertRaises(ValueError):
            export.canonical_manifest(json.dumps(broken))


class ArchiveTest(unittest.TestCase):
    """The same package content gives the same tar, whatever the times and identifiers on disk."""

    def package(self, root: str, mtime: int) -> str:
        package = os.path.join(root, "auraface_fp16.mlpackage")
        data = os.path.join(package, "Data", "com.apple.CoreML")
        os.makedirs(os.path.join(data, "weights"))
        with open(os.path.join(package, "Manifest.json"), "w", encoding="utf-8") as handle:
            handle.write(random_manifest())
        with open(os.path.join(data, "model.mlmodel"), "wb") as handle:
            handle.write(b"spec")
        with open(os.path.join(data, "weights", "weight.bin"), "wb") as handle:
            handle.write(bytes(range(256)) * 4)
        for directory, _, files in os.walk(package):
            for name in files + ["."]:
                os.utime(os.path.join(directory, name), (mtime, mtime))
        return package

    def archive(self, mtime: int, canonicalise: bool) -> str:
        root = tempfile.mkdtemp(dir=self.scratch)
        package = self.package(root, mtime)
        if canonicalise:
            path = os.path.join(package, "Manifest.json")
            with open(path, encoding="utf-8") as handle:
                text = handle.read()
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(export.canonical_manifest(text))
        tar = os.path.join(root, "out.tar")
        export.write_tar(package, tar)
        return tar

    def setUp(self):
        self._dir = tempfile.TemporaryDirectory()
        self.scratch = self._dir.name

    def tearDown(self):
        self._dir.cleanup()

    def test_two_exports_hours_apart_give_one_digest(self):
        self.assertEqual(sha256(self.archive(1_000, True)), sha256(self.archive(90_000, True)))

    def test_without_the_canonical_manifest_they_do_not(self):
        # ⚠ The negative half: proves the fixture's identifiers really differ per save, so the
        # positive case above is the canonicalisation and not a fixture that cannot vary.
        self.assertNotEqual(sha256(self.archive(1_000, False)), sha256(self.archive(1_000, False)))

    def test_the_archive_unpacks_to_one_mlpackage(self):
        into = tempfile.mkdtemp(dir=self.scratch)
        top = export.unpack(self.archive(1_000, True), into)
        self.assertEqual("auraface_fp16.mlpackage", os.path.basename(top))
        self.assertTrue(os.path.isfile(os.path.join(top, "Data", "com.apple.CoreML", "weights", "weight.bin")))


class RefusalTest(unittest.TestCase):

    def test_a_source_that_is_not_the_pinned_checkpoint_is_refused(self):
        with tempfile.NamedTemporaryFile() as handle:
            handle.write(b"not glintr100")
            handle.flush()
            with self.assertRaises(SystemExit) as raised:
                export.check_source(handle.name)
            self.assertIn(export.SOURCE_SHA256, str(raised.exception))

    def test_the_source_pin_is_a_full_commit_and_digest(self):
        # The signer refuses a revision that is not 40 hex characters (`ModelCatalogBuilder`).
        self.assertRegex(export.SOURCE_REVISION, r"^[0-9a-f]{40}$")
        self.assertRegex(export.SOURCE_SHA256, r"^[0-9a-f]{64}$")

    def test_every_recorded_package_has_a_pin_including_the_included_file(self):
        pins = export.pinned_versions()
        self.assertEqual(set(), set(export.TOOLCHAIN) - set(pins))
        # From `requirements-coreml-export.txt`, through `-r`: one coremltools for every Core ML artifact.
        self.assertEqual("9.0", pins["coremltools"])


if __name__ == "__main__":
    unittest.main()
