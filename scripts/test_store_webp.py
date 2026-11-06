#!/usr/bin/env python3
"""Tests for scripts/lib/store_webp.py — which WebP derivatives a publish makes, and the header parse.

  python3 scripts/test_store_webp.py      (no network; the encode test runs only where cwebp is installed)
"""
import hashlib
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))
import store_webp  # noqa: E402

A, B, C = "a" * 64, "b" * 64, "c" * 64


def manifest(*entries):
    return {"screens": {f"android/phone/en/0{i}-x": {"sha256": sha, "width": w, "height": h}
                        for i, (sha, w, h) in enumerate(entries)}}


def variant(width, sha="d" * 64):
    return {"width": width, "height": width * 2, "sha256": sha, "bytes": 100}


class PlanTest(unittest.TestCase):
    def test_every_png_gets_every_width_below_its_own(self):
        kept, jobs = store_webp.plan_app(manifest((A, 1080, 2400), (B, 1320, 2868)), None)
        self.assertEqual(kept, {})
        self.assertEqual(jobs, [(A, 1080, 2400, 540), (A, 1080, 2400, 960), (B, 1320, 2868, 540), (B, 1320, 2868, 960)])

    def test_never_an_upscale_nor_a_same_size_copy(self):
        _, jobs = store_webp.plan_app(manifest((A, 960, 2134), (B, 500, 1000)), None)
        self.assertEqual(jobs, [(A, 960, 2134, 540)])

    def test_keeps_what_exists_and_makes_only_the_missing_width(self):
        kept, jobs = store_webp.plan_app(manifest((A, 1080, 2400)), {"derivatives": {A: [variant(540)]}})
        self.assertEqual(kept, {A: [variant(540)]})
        self.assertEqual(jobs, [(A, 1080, 2400, 960)])

    def test_prunes_pngs_the_manifest_no_longer_names_and_widths_no_longer_wanted(self):
        sidecar = {"derivatives": {A: [variant(480), variant(540)], C: [variant(540)]}}
        kept, jobs = store_webp.plan_app(manifest((A, 1080, 2400)), sidecar)
        self.assertEqual(kept, {A: [variant(540)]})
        self.assertEqual(jobs, [(A, 1080, 2400, 960)])

    def test_a_png_listed_twice_is_one_set_of_jobs(self):
        _, jobs = store_webp.plan_app(manifest((A, 1080, 2400), (A, 1080, 2400)), None)
        self.assertEqual(len(jobs), 2)

    def test_sidecar_text_is_stable_and_round_trips(self):
        text = store_webp.sidecar_text("https://x/sha256/", {B: [variant(540)], A: [variant(540), variant(960)]})
        doc = json.loads(text)
        self.assertEqual(list(doc["derivatives"]), [A, B])
        self.assertEqual(doc["version"], 1)
        self.assertEqual(text, store_webp.sidecar_text("https://x/sha256/", doc["derivatives"]))


class WebpSizeTest(unittest.TestCase):
    def riff(self, chunk, payload):
        body = chunk + struct.pack("<I", len(payload)) + payload
        return b"RIFF" + struct.pack("<I", 4 + len(body)) + b"WEBP" + body

    def test_lossy(self):
        payload = b"\x00\x00\x00" + b"\x9d\x01\x2a" + struct.pack("<HH", 540, 1200) + b"\x00" * 8
        self.assertEqual(store_webp.webp_size(self.riff(b"VP8 ", payload)), (540, 1200))

    def test_lossless(self):
        bits = (540 - 1) | ((1200 - 1) << 14)
        payload = b"\x2f" + bits.to_bytes(4, "little") + b"\x00" * 8
        self.assertEqual(store_webp.webp_size(self.riff(b"VP8L", payload)), (540, 1200))

    def test_extended(self):
        payload = b"\x10\x00\x00\x00" + (540 - 1).to_bytes(3, "little") + (1200 - 1).to_bytes(3, "little")
        self.assertEqual(store_webp.webp_size(self.riff(b"VP8X", payload)), (540, 1200))

    def test_not_webp(self):
        with self.assertRaises(ValueError):
            store_webp.webp_size(b"\x89PNG\r\n\x1a\n" + b"\x00" * 40)


def png(width, height):
    raw = b"".join(b"\x00" + b"".join(bytes((x % 256, y % 256, 128)) for x in range(width)) for y in range(height))
    chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


@unittest.skipIf(shutil.which("cwebp") is None, "cwebp is not installed")
class BuildTest(unittest.TestCase):
    def test_builds_from_a_local_capture_and_stages_objects_and_sidecar(self):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as stage:
            data = png(1080, 2400)
            sha = hashlib.sha256(data).hexdigest()
            capture = os.path.join(root, "01-x.png")
            open(capture, "wb").write(data)
            os.makedirs(os.path.join(stage, "drive"))
            json.dump({"base": "https://x/sha256/", "screens": {"android/phone/en/01-x": {"sha256": sha, "width": 1080, "height": 2400}}},
                      open(os.path.join(stage, "drive", "screenshots.manifest.json"), "w"))
            open(os.path.join(stage, "objects.tsv"), "w").write(f"{sha}\t{capture}\n")
            subprocess.run([sys.executable, store_webp.__file__, "build", root, stage, "https://x/sha256/", "drive"],
                           check=True, capture_output=True)
            doc = json.load(open(os.path.join(stage, "drive", store_webp.SIDECAR)))
            variants = doc["derivatives"][sha]
            self.assertEqual([(v["width"], v["height"]) for v in variants], [(540, 1200), (960, 2134)])  # cwebp rounds 2133.3 up
            objects = dict(line.rstrip("\n").split("\t") for line in open(os.path.join(stage, "webp-objects.tsv")))
            for v in variants:
                body = open(objects[v["sha256"]], "rb").read()
                self.assertEqual(hashlib.sha256(body).hexdigest(), v["sha256"])
                self.assertEqual(len(body), v["bytes"])


if __name__ == "__main__":
    unittest.main()
