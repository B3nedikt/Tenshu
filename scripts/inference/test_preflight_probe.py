#!/usr/bin/env python3
"""Pins `preflight-probe.py`'s formula, its record and its refusals, with no model and no package.

    python3 scripts/inference/test_preflight_probe.py

⚠ What this cannot show: that the two cosines are right. That is the script's own run, against
the real towers. What it does show is that the committed record, the committed image and the
Kotlin constants the app compiles are one thing.
"""
import argparse
import contextlib
import hashlib
import importlib.util
import io
import json
import os
import struct
import sys
import tempfile
import unittest
import zlib

# Loading the script by path would otherwise drop an untracked `__pycache__/` beside it.
sys.dont_write_bytecode = True
_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("preflight_probe", os.path.join(_HERE, "preflight-probe.py"))
probe = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(probe)

import shutil

RECORDS = probe.DEFAULT_RECORDS
KOTLIN = probe.DEFAULT_KOTLIN


def committed_records() -> dict:
    return probe.load_records(RECORDS)


def committed_record() -> dict:
    """One committed record, for the cases about a record's own content."""
    records = committed_records()
    return json.loads(json.dumps(records[sorted(records)[0]]))


def quiet(action) -> int:
    with contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
        return probe.exit_status(action)


class TheFormula(unittest.TestCase):
    def pixel(self, x: int, y: int) -> tuple:
        rgb = probe.probe_pixels()
        offset = 3 * (y * probe.SIDE + x)
        return tuple(rgb[offset : offset + 3])

    def test_the_pixels_are_the_pinned_ones(self):
        rgb = probe.probe_pixels()
        self.assertEqual(len(rgb), 3 * probe.SIDE * probe.SIDE)
        self.assertEqual(hashlib.sha256(rgb).hexdigest(), probe.PROBE_RGB_SHA256)

    def test_a_disc_in_the_middle_of_a_plain_background(self):
        self.assertEqual(self.pixel(128, 128), probe.DISC)
        self.assertEqual(self.pixel(0, 0), probe.BACKGROUND)
        self.assertEqual(self.pixel(255, 255), probe.BACKGROUND)

    def test_the_rim_is_where_the_radius_says(self):
        # Along the row just below the centre line, dy = 1. Pixel x has dx = 2x + 1 - 256, and is
        # inside while dx² + 1 <= 154²: |dx| <= 153, so x from 51 to 204.
        self.assertEqual(self.pixel(51, 128), probe.DISC)
        self.assertEqual(self.pixel(204, 128), probe.DISC)
        self.assertEqual(self.pixel(50, 128), probe.BACKGROUND)
        self.assertEqual(self.pixel(205, 128), probe.BACKGROUND)

    def test_the_disc_is_symmetric(self):
        rgb = probe.probe_pixels()
        side = probe.SIDE
        rows = [rgb[3 * side * y : 3 * side * (y + 1)] for y in range(side)]
        self.assertEqual(rows, rows[::-1])

    def test_the_tensor_is_the_preprocessing_arithmetic_in_single_precision(self):
        # One pixel (0, 128, 255): -1, then (128/255 - 0.5) / 0.5 in float32, then 1.
        tensor = struct.unpack("<3f", probe.tensor_bytes(bytes([0, 128, 255]), 1))
        self.assertEqual(tensor[0], -1.0)
        self.assertEqual(tensor[2], 1.0)
        # 128/255 rounds to 0x3F008081 in float32; minus a half and doubled is exact from there.
        (third,) = struct.unpack("<f", struct.pack("<I", 0x3F008081))
        self.assertEqual(tensor[1], (third - 0.5) / 0.5)

    def test_the_tensor_is_channel_planes_not_interleaved(self):
        # Four pixels, two red then two blue: R plane (1, 1, -1, -1), G plane all -1, B plane (-1, -1, 1, 1).
        values = struct.unpack("<12f", probe.tensor_bytes(bytes([255, 0, 0] * 2 + [0, 0, 255] * 2), 2))
        self.assertEqual(values, (1.0, 1.0, -1.0, -1.0) + (-1.0,) * 4 + (-1.0, -1.0, 1.0, 1.0))


class TheImageFile(unittest.TestCase):
    def test_a_png_reads_back_as_what_was_written(self):
        rgb = bytes(range(48))
        self.assertEqual(probe.decode_png(probe.encode_png(rgb, 4)), (rgb, 4))

    def test_the_committed_image_is_the_formula(self):
        identity = probe.read_probe_image(probe.DEFAULT_IMAGE)
        self.assertEqual(identity["rgbSha256"], probe.PROBE_RGB_SHA256)
        self.assertEqual(identity["side"], probe.SIDE)

    def write(self, data: bytes) -> str:
        handle = tempfile.NamedTemporaryFile(suffix=".png", delete=False)
        self.addCleanup(os.remove, handle.name)
        handle.write(data)
        handle.close()
        return handle.name

    def test_an_image_with_other_pixels_is_refused(self):
        rgb = bytearray(probe.probe_pixels())
        rgb[0] ^= 1
        with self.assertRaises(probe.Refused):
            probe.read_probe_image(self.write(probe.encode_png(bytes(rgb), probe.SIDE)))

    def test_an_image_of_another_size_is_refused(self):
        with self.assertRaises(probe.Refused):
            probe.read_probe_image(self.write(probe.encode_png(bytes(48), 4)))

    def test_a_file_that_is_not_a_png_is_refused(self):
        with self.assertRaises(probe.Refused):
            probe.read_probe_image(self.write(b"not a png at all"))

    def test_a_png_with_a_filtered_row_is_refused_not_misread(self):
        raw = b"\x01" + bytes(3) + b"\x00" + bytes(3)
        header = struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)
        data = b"\x89PNG\r\n\x1a\n" + probe._chunk(b"IHDR", header) + probe._chunk(b"IDAT", zlib.compress(raw[:4])) + probe._chunk(b"IEND", b"")
        with self.assertRaises(probe.Refused):
            probe.decode_png(data)

    def test_a_truncated_png_is_a_refusal_not_a_traceback(self):
        data = probe.encode_png(probe.probe_pixels(), probe.SIDE)
        self.assertEqual(quiet(lambda: probe.read_probe_image(self.write(data[:40])) and 0), 2)


class TheArithmetic(unittest.TestCase):
    def test_cosines_margin_and_norms_by_hand(self):
        # vision (3, 4): norm 5. describes (6, 8): cosine 1, norm 10. does not (4, -3): cosine 0.
        reading = probe.judge([3.0, 4.0], [6.0, 8.0], [4.0, -3.0])
        self.assertAlmostEqual(reading["cosineDescribes"], 1.0)
        self.assertAlmostEqual(reading["cosineDoesNot"], 0.0)
        self.assertAlmostEqual(reading["margin"], 1.0)
        self.assertEqual(reading["norms"], {"vision": 5.0, "describes": 10.0, "doesNot": 5.0})

    def test_a_non_finite_or_zero_vector_measures_nothing(self):
        with self.assertRaises(ValueError):
            probe.judge([float("nan"), 1.0], [1.0, 0.0], [0.0, 1.0])
        with self.assertRaises(ValueError):
            probe.judge([1.0, 1.0], [0.0, 0.0], [0.0, 1.0])

    def test_the_threshold_is_a_share_of_the_narrowest_path(self):
        limits = probe.threshold({"a": {"margin": 0.30}, "b": {"margin": 0.20}})
        self.assertEqual(limits["minMargin"], 0.1)
        self.assertIs(limits["phoneMeasured"], False)

    def test_a_narrow_or_mis_ranked_probe_is_not_recorded(self):
        with self.assertRaises(ValueError):
            probe.threshold({"a": {"margin": 0.30}, "b": {"margin": probe.MIN_HOST_MARGIN - 0.001}})
        with self.assertRaises(ValueError):
            probe.threshold({"a": {"margin": -0.2}})


class TheExport(unittest.TestCase):
    DIGESTS = {"vision": "a" * 64, "text": "b" * 64, "vocabulary": "c" * 64}

    def export(self, result) -> tuple:
        directory = tempfile.mkdtemp()
        self.addCleanup(lambda: [os.remove(os.path.join(directory, name)) for name in os.listdir(directory)] and None)
        if result is not None:
            with open(os.path.join(directory, probe.gate.RESULT_NAME), "w", encoding="utf-8") as handle:
                json.dump(result, handle)
        manifest = {"artifacts": {role: {"sha256": digest} for role, digest in self.DIGESTS.items()}}
        return directory, manifest

    def result(self, **changes) -> dict:
        base = {
            "verdict": "PASS",
            "overridden": False,
            "export": {"artifacts": {role: {"sha256": digest} for role, digest in self.DIGESTS.items()}},
        }
        base.update(changes)
        return base

    def test_a_clean_pass_for_these_bytes_is_admitted(self):
        directory, manifest = self.export(self.result())
        admitted = probe.admit_gate_result(directory, manifest, allow_ungated=False)
        self.assertEqual(admitted["verdict"], "PASS")
        self.assertIsNone(admitted["overridden"])

    def test_an_export_with_no_gate_result_is_refused(self):
        directory, manifest = self.export(None)
        with self.assertRaises(probe.Refused):
            probe.admit_gate_result(directory, manifest, allow_ungated=False)

    def test_a_fail_an_overridden_pass_and_a_result_about_other_bytes_are_refused(self):
        other = {"artifacts": {role: {"sha256": "d" * 64} for role in self.DIGESTS}}
        for result in (self.result(verdict="FAIL"), self.result(overridden=True), self.result(export=other)):
            directory, manifest = self.export(result)
            with self.assertRaises(probe.Refused):
                probe.admit_gate_result(directory, manifest, allow_ungated=False)

    def test_a_malformed_gate_result_is_refused_not_a_traceback(self):
        directory, manifest = self.export({"verdict": "PASS"})
        with self.assertRaises(probe.Refused):
            probe.admit_gate_result(directory, manifest, allow_ungated=False)

    def test_the_override_is_recorded(self):
        directory, manifest = self.export(self.result(verdict="FAIL"))
        admitted = probe.admit_gate_result(directory, manifest, allow_ungated=True)
        self.assertIn("not a clean PASS", admitted["overridden"])

    def test_an_output_inside_the_export_is_refused_before_anything_is_written(self):
        directory, _ = self.export(self.result())
        before = probe.listing(directory)
        for flag in ("out", "records", "kotlin"):
            args = argparse.Namespace(
                export=directory, source=directory, image=probe.DEFAULT_IMAGE, out=None, records=RECORDS, kotlin=KOTLIN,
                allow_unpinned=True, allow_ungated=False, replace=False,
            )
            setattr(args, flag, os.path.join(directory, "sub", "record.json"))
            self.assertEqual(quiet(lambda: probe.run(args)), 2)
        self.assertEqual(probe.listing(directory), before)

    def test_the_listing_sees_a_changed_file(self):
        directory, _ = self.export(self.result())
        before = probe.listing(directory)
        with open(os.path.join(directory, "new"), "w", encoding="utf-8") as handle:
            handle.write("x")
        self.assertNotEqual(probe.listing(directory), before)


class TheCommittedRecords(unittest.TestCase):
    def each(self):
        records = committed_records()
        self.assertTrue(records, "no probe record is committed: no phone could ever be called ready")
        return records.items()

    def test_each_is_about_the_committed_probe(self):
        image = probe.read_probe_image(probe.DEFAULT_IMAGE)["sha256"]
        tensor = hashlib.sha256(probe.tensor_bytes(probe.probe_pixels(), probe.SIDE)).hexdigest()
        for name, record in self.each():
            with self.subTest(record=name):
                self.assertEqual(record["format"], probe.RECORD_FORMAT)
                self.assertEqual(record["image"]["rgbSha256"], probe.PROBE_RGB_SHA256)
                self.assertEqual(record["image"]["sha256"], image)
                self.assertEqual(record["image"]["tensorSha256"], tensor)
                self.assertEqual(record["captions"], {"describes": probe.CAPTION_DESCRIBES, "doesNot": probe.CAPTION_DOES_NOT})

    def test_each_was_made_inside_the_pins_for_a_gated_export_with_no_override(self):
        for name, record in self.each():
            with self.subTest(record=name):
                self.assertEqual(record["overrides"], [])
                self.assertIs(record["toolchain"]["pinned"], True)
                self.assertEqual(record["export"]["retrievalGate"]["verdict"], "PASS")
                self.assertIsNone(record["export"]["retrievalGate"]["overridden"])

    def test_each_threshold_follows_from_its_own_readings(self):
        for name, record in self.each():
            with self.subTest(record=name):
                self.assertEqual(probe.threshold(record["host"]), record["threshold"])
                self.assertIs(record["threshold"]["phoneMeasured"], False)

    def test_each_has_full_padded_token_rows(self):
        for name, record in self.each():
            with self.subTest(record=name):
                rows = record["tokenRows"]
                for which in ("describes", "doesNot"):
                    self.assertEqual(len(rows[which]), rows["length"])
                    self.assertEqual(rows[which][-1], rows["padId"])
                self.assertNotEqual(rows["describes"], rows["doesNot"])

    def test_a_record_made_with_an_override_is_not_rendered(self):
        record = committed_record()
        record["overrides"] = ["--allow-ungated"]
        with self.assertRaises(probe.Refused):
            probe.render_kotlin(record)

    def test_a_caption_cannot_break_out_of_its_kotlin_string(self):
        record = committed_record()
        record["captions"]["describes"] = 'a "quoted" $name \\ caption'
        self.assertIn('captionDescribes = "a \\"quoted\\" \\$name \\\\ caption",', probe.render_kotlin(record))


class TheGeneratedSet(unittest.TestCase):
    """The Kotlin the app compiles is exactly the committed records: every one, and nothing else."""

    def test_the_committed_kotlin_is_the_committed_records(self):
        self.assertEqual(probe.check_generated(RECORDS, KOTLIN), [])

    def test_every_committed_record_has_its_own_file_and_its_line_in_the_registry(self):
        records = committed_records()
        with open(os.path.join(KOTLIN, probe.REGISTRY_NAME), encoding="utf-8") as handle:
            registry = handle.read()
        listed = sorted(line.strip().rstrip(",") for line in registry.splitlines() if line.strip().startswith(probe.RECORD_PREFIX))
        self.assertEqual(listed, [f"{probe.RECORD_PREFIX}{name}.probe" for name in sorted(records)])
        for name in records:
            self.assertTrue(os.path.isfile(os.path.join(KOTLIN, f"{probe.RECORD_PREFIX}{name}.kt")), name)

    def scratch(self) -> tuple:
        """A copy of the committed records and of their Kotlin, to break."""
        root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, root)
        records, kotlin = os.path.join(root, "records"), os.path.join(root, "kotlin")
        shutil.copytree(RECORDS, records)
        os.mkdir(kotlin)
        for name in probe.generated_on_disk(KOTLIN):
            shutil.copy(os.path.join(KOTLIN, name), kotlin)
        self.assertEqual(probe.check_generated(records, kotlin), [])
        return records, kotlin

    def second(self, records: str) -> str:
        """A second export's record in `records`: the first, under other digests. Returns its id."""
        record = committed_record()
        for role, fill in (("vision", "1"), ("text", "2"), ("vocabulary", "3")):
            record["export"][role] = fill * 64
        record["export"]["manifestSha256"] = "9" * 64
        name = probe.record_id(record)
        with open(os.path.join(records, f"{name}.json"), "w", encoding="utf-8") as handle:
            json.dump(record, handle)
        return name

    def test_a_record_with_no_kotlin_and_a_record_not_in_the_registry_fail(self):
        records, kotlin = self.scratch()
        name = self.second(records)
        problems = probe.check_generated(records, kotlin)
        self.assertTrue(any(f"{probe.RECORD_PREFIX}{name}.kt is missing" in problem for problem in problems), problems)
        self.assertTrue(any(probe.REGISTRY_NAME in problem for problem in problems), problems)
        # Its file alone is not enough: the registry must list it.
        with open(os.path.join(kotlin, f"{probe.RECORD_PREFIX}{name}.kt"), "w", encoding="utf-8") as handle:
            handle.write(probe.generated(probe.load_records(records))[f"{probe.RECORD_PREFIX}{name}.kt"])
        self.assertEqual(probe.check_generated(records, kotlin), [f"{probe.REGISTRY_NAME} is not the rendering of the committed records"])

    def test_a_kotlin_record_with_no_committed_record_fails(self):
        records, kotlin = self.scratch()
        with open(os.path.join(kotlin, f"{probe.RECORD_PREFIX}aaaaaaaaaaaa.kt"), "w", encoding="utf-8") as handle:
            handle.write("// a probe nothing recorded")
        problems = probe.check_generated(records, kotlin)
        self.assertEqual(len(problems), 1, problems)
        self.assertIn("has no record", problems[0])

    def test_a_record_registered_and_then_deleted_fails(self):
        records, kotlin = self.scratch()
        name = self.second(records)
        probe.sync_generated(records, kotlin)
        self.assertEqual(probe.check_generated(records, kotlin), [])
        os.remove(os.path.join(records, f"{name}.json"))
        problems = probe.check_generated(records, kotlin)
        self.assertTrue(any("has no record" in problem for problem in problems), problems)
        self.assertTrue(any(probe.REGISTRY_NAME in problem for problem in problems), problems)
        # Regenerating after the removal takes its file and its line away, and nothing else.
        self.assertEqual(sorted(probe.sync_generated(records, kotlin)), [f"removed {probe.RECORD_PREFIX}{name}.kt", f"wrote {probe.REGISTRY_NAME}"])
        self.assertFalse(os.path.exists(os.path.join(kotlin, f"{probe.RECORD_PREFIX}{name}.kt")))
        self.assertEqual(probe.check_generated(records, kotlin), [])

    def test_adding_an_export_changes_no_other_exports_file(self):
        records, kotlin = self.scratch()
        def text(name: str) -> str:
            with open(os.path.join(kotlin, name), encoding="utf-8") as handle:
                return handle.read()

        before = {name: text(name) for name in probe.generated_on_disk(kotlin)}
        name = self.second(records)

        changed = probe.sync_generated(records, kotlin)

        self.assertEqual(sorted(changed), [f"wrote {probe.RECORD_PREFIX}{name}.kt", f"wrote {probe.REGISTRY_NAME}"])
        for other, content in before.items():
            if other != probe.REGISTRY_NAME:
                with open(os.path.join(kotlin, other), encoding="utf-8") as handle:
                    self.assertEqual(handle.read(), content, f"{other} was rewritten for another export")
        with open(os.path.join(kotlin, probe.REGISTRY_NAME), encoding="utf-8") as handle:
            registry = handle.read()
        for listed in list(committed_records()) + [name]:
            self.assertIn(f"{probe.RECORD_PREFIX}{listed}.probe,", registry)

    def test_an_exports_own_record_is_not_overwritten_unasked(self):
        records, _ = self.scratch()
        record = committed_record()
        with self.assertRaises(probe.Refused):
            probe.place_record(record, records, replace=False)
        self.assertEqual(probe.place_record(record, records, replace=True), os.path.join(records, f"{probe.record_id(record)}.json"))

    def test_a_second_record_for_the_same_three_artifacts_is_refused(self):
        records, _ = self.scratch()
        record = committed_record()
        record["export"]["manifestSha256"] = "8" * 64
        with self.assertRaises(probe.Refused):
            probe.place_record(record, records, replace=False)
        with open(os.path.join(records, "888888888888.json"), "w", encoding="utf-8") as handle:
            json.dump(record, handle)
        with self.assertRaises(probe.Refused):
            probe.load_records(records)

    def test_a_record_filed_under_another_id_is_refused(self):
        records, _ = self.scratch()
        os.rename(os.path.join(records, sorted(os.listdir(records))[0]), os.path.join(records, "000000000000.json"))
        with self.assertRaises(probe.Refused):
            probe.load_records(records)

    def test_a_new_exports_record_is_placed_under_its_own_id(self):
        records, _ = self.scratch()
        record = committed_record()
        for role, fill in (("vision", "1"), ("text", "2"), ("vocabulary", "3")):
            record["export"][role] = fill * 64
        record["export"]["manifestSha256"] = "7" * 64
        self.assertEqual(probe.place_record(record, records, replace=False), os.path.join(records, "777777777777.json"))


if __name__ == "__main__":
    unittest.main()
