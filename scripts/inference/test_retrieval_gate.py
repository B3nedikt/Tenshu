#!/usr/bin/env python3
"""Pins `retrieval-gate.py`'s arithmetic and its refusals, with no model, no network, no package.

    python3 scripts/inference/test_retrieval_gate.py

The metric cases are worked by hand in the comments beside them. ⚠ What this cannot show: that the
three pairs embed correctly. That is the gate's own run, against real weights.
"""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
import struct
import sys
import tarfile
import tempfile
import unittest

# Loading the script by path would otherwise drop an untracked `__pycache__/` beside it.
sys.dont_write_bytecode = True
_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("retrieval_gate", os.path.join(_HERE, "retrieval-gate.py"))
gate = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gate)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class Metrics(unittest.TestCase):
    def test_ranks_put_the_highest_score_first(self):
        self.assertEqual(gate.ranks([3.0, 1.0, 2.0]), [0.0, 2.0, 1.0])

    def test_tied_scores_share_the_mean_rank(self):
        # 5 and 5 hold ranks 0 and 1, so each gets 0.5.
        self.assertEqual(gate.ranks([5.0, 5.0, 1.0]), [0.5, 0.5, 2.0])

    def test_spearman_of_a_row_with_itself_and_its_reverse(self):
        row = [0.1, 0.4, 0.3, 0.9]
        self.assertAlmostEqual(gate.spearman(row, row), 1.0)
        self.assertAlmostEqual(gate.spearman(row, [-value for value in row]), -1.0)

    def test_spearman_of_one_adjacent_swap(self):
        # Ranks differ by 1 in two places: 1 - 6·2 / (4·15) = 0.8.
        self.assertAlmostEqual(gate.spearman([1, 2, 3, 4], [1, 2, 4, 3]), 0.8)

    def test_spearman_with_a_tie(self):
        # Ranks [1.5, 1.5, 0] and [2, 1, 0]; deviations [.5, .5, -1] and [1, 0, -1].
        # Covariance 1.5, spreads 1.5 and 2: 1.5 / sqrt(3).
        self.assertAlmostEqual(gate.spearman([1, 1, 2], [1, 2, 3]), 1.5 / 3 ** 0.5)

    def test_spearman_refuses_a_flat_row(self):
        with self.assertRaises(ValueError):
            gate.spearman([1, 1, 1], [1, 2, 3])

    def test_top_k_breaks_a_tie_by_index(self):
        self.assertEqual(gate.top_k([1.0, 1.0, 0.0], 1), [0])
        self.assertEqual(gate.top_k([0.2, 0.9, 0.5], 2), [1, 2])

    def test_overlap_ignores_order_inside_the_top_k(self):
        a = [0.9, 0.8, 0.7, 0.1, 0.0]
        b = [0.9, 0.1, 0.7, 0.8, 0.0]
        self.assertEqual(gate.overlap(a, b, 2), 0.5)  # {0, 1} against {0, 3}
        self.assertAlmostEqual(gate.overlap(a, b, 3), 2 / 3)  # {0, 1, 2} against {0, 3, 2}
        self.assertEqual(gate.overlap([3, 2, 1], [2, 3, 1], 2), 1.0)

    def test_summarise_counts_and_takes_the_worst_query(self):
        reference = [list(range(12, 0, -1)), list(range(12, 0, -1))]
        swapped = list(reference[1])
        swapped[0], swapped[11] = swapped[11], swapped[0]  # the best image becomes the worst
        figures = gate.summarise([reference[0], swapped], reference)
        self.assertEqual(figures["queries"], 2)
        self.assertEqual(figures["top1"], 1)
        self.assertEqual(figures["top5Worst"], 0.8)  # {11, 1, 2, 3, 4} against {0, 1, 2, 3, 4}
        self.assertEqual(figures["top5Mean"], 0.9)
        self.assertEqual(figures["top10Worst"], 0.9)
        self.assertEqual(figures["top10Mean"], 0.95)
        # Two ranks move by 11: 1 - 6·242 / (12·143).
        self.assertAlmostEqual(figures["spearmanWorst"], 1 - 6 * 242 / (12 * 143))
        self.assertAlmostEqual(figures["spearmanMean"], (1 + figures["spearmanWorst"]) / 2)
        self.assertEqual([entry["top1"] for entry in figures["perQuery"]], [True, False])


class Rule(unittest.TestCase):
    YARDSTICK = {
        "top1": 23, "top5Mean": 0.75, "top5Worst": 0.2, "top10Mean": 0.77, "top10Worst": 0.5,
        "spearmanMean": 0.93, "spearmanWorst": 0.88,
    }

    def test_equal_passes(self):
        self.assertEqual(gate.verdict(dict(self.YARDSTICK), self.YARDSTICK), (True, []))

    def test_better_on_every_figure_passes(self):
        better = {figure: value + 0.01 for figure, value in self.YARDSTICK.items()}
        self.assertEqual(gate.verdict(better, self.YARDSTICK), (True, []))

    def test_worse_on_any_one_figure_fails_and_names_it(self):
        self.assertEqual(set(self.YARDSTICK), set(gate.FIGURES))
        for figure in gate.FIGURES:
            candidate = {name: value + 1 for name, value in self.YARDSTICK.items()}
            candidate[figure] = self.YARDSTICK[figure] - 1e-9
            self.assertEqual(gate.verdict(candidate, self.YARDSTICK), (False, [figure]), figure)

    def test_a_better_mean_does_not_buy_a_worse_worst_query(self):
        candidate = dict(self.YARDSTICK, spearmanMean=0.9999, spearmanWorst=0.5)
        self.assertEqual(gate.verdict(candidate, self.YARDSTICK), (False, ["spearmanWorst"]))


class Export(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.dir = self.scratch.name
        self.bytes = {name: f"{role}-a".encode() for role, name in gate.EXPORT_FILES.items()}
        for name, data in self.bytes.items():
            self.put(name, data)
        self.write_manifest()

    def tearDown(self):
        self.scratch.cleanup()

    def put(self, name: str, data: bytes):
        with open(os.path.join(self.dir, name), "wb") as handle:
            handle.write(data)

    def write_manifest(self):
        artifacts = {
            role: {"file": name, "sha256": sha(self.bytes[name]), "bytes": len(self.bytes[name])}
            for role, name in gate.EXPORT_FILES.items()
        }
        source = {"revision": "abc", "files": {"config.json": sha(b"config")}}
        self.put(gate.EXPORT_MANIFEST, json.dumps({"artifacts": artifacts, "source": source}).encode())

    def test_a_complete_export_is_accepted(self):
        self.assertEqual(gate.verify_export(self.dir)["source"]["revision"], "abc")

    def test_no_manifest_is_refused(self):
        os.remove(os.path.join(self.dir, gate.EXPORT_MANIFEST))
        with self.assertRaisesRegex(gate.Refused, "not a complete export"):
            gate.verify_export(self.dir)

    def test_an_archive_the_manifest_did_not_record_is_refused(self):
        for name in gate.EXPORT_FILES.values():
            original = self.bytes[name]
            self.put(name, original[:-1] + b"b")  # the same length, other bytes
            with self.assertRaisesRegex(gate.Refused, "not the file the manifest recorded"):
                gate.verify_export(self.dir)
            self.put(name, original)
        gate.verify_export(self.dir)

    def test_a_missing_archive_is_refused(self):
        os.remove(os.path.join(self.dir, gate.EXPORT_FILES["text"]))
        with self.assertRaisesRegex(gate.Refused, "no .*text_fp16"):
            gate.verify_export(self.dir)

    def test_a_manifest_naming_another_file_is_refused(self):
        self.put(gate.EXPORT_MANIFEST, json.dumps({"artifacts": {}, "source": {"revision": "abc", "files": {}}}).encode())
        with self.assertRaisesRegex(gate.Refused, "does not name"):
            gate.verify_export(self.dir)

    def test_the_source_must_be_the_checkpoint_the_export_read(self):
        manifest = gate.verify_export(self.dir)
        with tempfile.TemporaryDirectory() as source:
            with self.assertRaisesRegex(gate.Refused, "has no config.json"):
                gate.verify_source(source, manifest)
            with open(os.path.join(source, "config.json"), "wb") as handle:
                handle.write(b"other")
            with self.assertRaisesRegex(gate.Refused, "not the one the export read"):
                gate.verify_source(source, manifest)
            with open(os.path.join(source, "config.json"), "wb") as handle:
                handle.write(b"config")
            gate.verify_source(source, manifest)


class Admission(unittest.TestCase):
    def manifest(self, pinned=True):
        source = {"revision": gate.YARDSTICK_SOURCE["revision"], "files": dict(gate.YARDSTICK_SOURCE["files"])}
        manifest = {"source": source}
        if pinned is not None:
            manifest["toolchain"] = {"pinned": pinned, "python": gate.PINNED_PYTHON + ".7", **gate.export_pins()}
        return manifest

    def test_the_pinned_checkpoint_and_toolchain_are_admitted_with_no_override(self):
        self.assertEqual(
            gate.admit_export(self.manifest(), True, False),
            {"checkpointBinding": "pinned", "exportToolchainPinned": True, "overrides": []},
        )

    def test_the_pin_names_the_weights_and_every_file_the_export_records(self):
        self.assertEqual(len(gate.YARDSTICK_SOURCE["revision"]), 40)
        self.assertEqual(
            set(gate.YARDSTICK_SOURCE["files"]),
            {"model.safetensors", "config.json", "preprocessor_config.json", "tokenizer_config.json", "tokenizer.json"},
        )

    def test_another_revision_is_refused(self):
        manifest = self.manifest()
        manifest["source"]["revision"] = "0" * 40
        with self.assertRaisesRegex(gate.Refused, "made from revision"):
            gate.admit_export(manifest, True, False)

    def test_any_one_other_source_file_is_refused(self):
        for name in gate.YARDSTICK_SOURCE["files"]:
            manifest = self.manifest()
            manifest["source"]["files"][name] = "0" * 64
            with self.assertRaisesRegex(gate.Refused, f"read a {name} "):
                gate.admit_export(manifest, True, False)
            del manifest["source"]["files"][name]  # not recorded at all
            with self.assertRaisesRegex(gate.Refused, f"read a {name} "):
                gate.admit_export(manifest, True, False)

    def test_the_binding_is_waived_only_when_the_yardstick_is_not_the_published_pair(self):
        manifest = self.manifest()
        manifest["source"]["revision"] = "0" * 40
        manifest["source"]["files"]["model.safetensors"] = "0" * 64
        admitted = gate.admit_export(manifest, False, False)
        self.assertTrue(admitted["checkpointBinding"].startswith("waived"))
        self.assertEqual(len(admitted["overrides"]), 1)

    def test_a_gate_run_outside_its_pins_is_an_override_and_never_a_bare_pass(self):
        admitted = gate.admit_export(self.manifest(), True, False)
        pinned = gate.with_gate_toolchain(admitted, True)
        self.assertEqual(pinned["overrides"], [])
        self.assertEqual(pinned["gateToolchainPinned"], True)
        unpinned = gate.with_gate_toolchain(admitted, False)
        self.assertEqual(unpinned["gateToolchainPinned"], False)
        self.assertEqual(unpinned["overrides"], ["the gate itself ran outside its pinned toolchain"])
        self.assertIn("OVERRIDDEN PASS", gate.verdict_line(True, [], unpinned["overrides"]))
        # The export's own overrides are kept, not replaced.
        both = gate.with_gate_toolchain(gate.admit_export(self.manifest(pinned=False), True, True), False)
        self.assertEqual(len(both["overrides"]), 2)

    def test_only_the_committed_evaluation_on_the_shipped_units_is_a_bare_verdict(self):
        admitted = gate.admit_export(self.manifest(), True, False)
        q, c, u = gate.CANONICAL_QUERIES_SHA256, gate.CANONICAL_CORPUS_MANIFEST_SHA256, gate.SHIPPED_COMPUTE_UNITS
        canonical = gate.with_evaluation_inputs(admitted, q, c, u)
        self.assertEqual(canonical["overrides"], [])
        self.assertTrue(canonical["canonicalQueries"] and canonical["canonicalCorpus"] and canonical["shippedComputeUnits"])
        for args, word in (((("0" * 64), c, u), "queries"), ((q, "0" * 64, u), "corpus"), ((q, c, "all"), "all"), ((q, c, "cpuOnly"), "cpuOnly")):
            other = gate.with_evaluation_inputs(admitted, *args)
            self.assertEqual(len(other["overrides"]), 1, args)
            self.assertIn(word, other["overrides"][0])
            self.assertIn("OVERRIDDEN PASS", gate.verdict_line(True, [], other["overrides"]))

    def test_the_committed_evaluation_files_are_the_pinned_ones(self):
        # Editing the queries or the corpus manifest changes what the gate measures: the constant
        # moves in the same commit, deliberately.
        here = os.path.dirname(os.path.abspath(__file__))
        self.assertEqual(gate.sha256_of(os.path.join(here, "retrieval-gate-queries.json")), gate.CANONICAL_QUERIES_SHA256)
        self.assertEqual(gate.sha256_of(os.path.join(here, "retrieval-gate-corpus.txt")), gate.CANONICAL_CORPUS_MANIFEST_SHA256)

    def test_phone_vectors_from_another_preprocessing_revision_are_refused(self):
        good = {"tower": "vision", "archiveSha256": "a" * 64, "corpusId": "c" * 64, "preprocessingRevision": "3"}
        for stale in ({**good, "preprocessingRevision": "2"}, {k: v for k, v in good.items() if k != "preprocessingRevision"}):
            with self.assertRaisesRegex(gate.Refused, "preprocessing revision"):
                gate.admit_phone_vectors(stale, "a" * 64, "c" * 64, 3, "v.txt")

    def test_the_revision_is_read_from_the_decoder_source_and_never_assumed(self):
        self.assertEqual(gate.repo_preprocessing_revision("x\nconst val IOS_PREPROCESSING_REVISION: Int = 7\n"), 7)
        with self.assertRaisesRegex(gate.Refused, "not declared"):
            gate.repo_preprocessing_revision("const val SOMETHING_ELSE: Int = 7")
        # The real file still declares it where the gate looks.
        with open(gate.IOS_DECODER_SOURCE, encoding="utf-8") as handle:
            self.assertGreaterEqual(gate.repo_preprocessing_revision(handle.read()), 1)

    def test_the_committed_queries_must_become_the_committed_token_rows(self):
        rows = [[1, 2, 3], [4, 5, 1]]
        digest = gate.token_rows_digest(rows)
        self.assertEqual(digest, hashlib.sha256(struct.pack(">6i", 1, 2, 3, 4, 5, 1)).hexdigest())
        self.assertNotEqual(digest, gate.token_rows_digest([[1, 2, 3], [4, 5, 0]]))
        # Canonical queries with other rows: refused, whatever the installed versions say.
        with self.assertRaisesRegex(gate.Refused, "tokenised to"):
            gate.admit_token_rows(rows, True)
        # Other queries are already an override; their rows are recorded, not judged.
        self.assertEqual(gate.admit_token_rows(rows, False), digest)

    def test_an_unreadable_input_is_a_refusal_never_a_measured_failure(self):
        def missing():
            return gate.load_queries(os.path.join(tempfile.gettempdir(), "no-such-queries-file.json"))

        def malformed():
            with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as handle:
                handle.write("{not json")
            try:
                return gate.load_queries(handle.name)
            finally:
                os.remove(handle.name)

        def refused():
            raise gate.Refused("no")

        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(gate.exit_status(missing), 2)
            self.assertEqual(gate.exit_status(malformed), 2)
            self.assertEqual(gate.exit_status(refused), 2)
        self.assertEqual(gate.exit_status(lambda: 0), 0)
        self.assertEqual(gate.exit_status(lambda: 1), 1)
        # A bug in the gate itself is not an input error and must stay loud.
        with self.assertRaises(ZeroDivisionError):
            gate.exit_status(lambda: 1 // 0)

    def test_an_export_promotion_takes_the_old_gate_result_with_the_old_manifest(self):
        spec = importlib.util.spec_from_file_location("export_towers", os.path.join(_HERE, "export-coreml-towers.py"))
        export = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(export)
        self.assertEqual(export.GATE_RESULT, gate.RESULT_NAME)
        with tempfile.TemporaryDirectory() as out, tempfile.TemporaryDirectory() as stage:
            for name in ("export-manifest.json", gate.RESULT_NAME, "vision_fp16.mlpackage.tar"):
                with open(os.path.join(out, name), "w", encoding="utf-8") as handle:
                    handle.write("old")
            for name in ("export-manifest.json", "vision_fp16.mlpackage.tar"):
                with open(os.path.join(stage, name), "w", encoding="utf-8") as handle:
                    handle.write("new")
            export.promote(stage, out)
            self.assertEqual(sorted(os.listdir(out)), ["export-manifest.json", "vision_fp16.mlpackage.tar"])
            with open(os.path.join(out, "export-manifest.json"), encoding="utf-8") as handle:
                self.assertEqual(handle.read(), "new")

    def test_a_structurally_malformed_input_is_refused_with_status_2(self):
        def queries(document):
            with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as handle:
                json.dump(document, handle)
            try:
                return gate.load_queries(handle.name)
            finally:
                os.remove(handle.name)

        def corpus(text):
            with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as handle:
                handle.write(text)
            try:
                return gate.load_corpus_manifest(handle.name)
            finally:
                os.remove(handle.name)

        malformed = (
            lambda: queries({"queries": [{"language": "en", "text": ["dog"]}]}),
            lambda: queries({"queries": [{"language": "en", "text": 7}]}),
            lambda: queries({"queries": [{"language": 1, "text": "a dog"}]}),
            lambda: queries({"queries": [{"language": "en", "text": "   "}]}),
            lambda: queries({"queries": {"language": "en", "text": "a dog"}}),
            lambda: queries({"queries": [{"language": "en", "text": "a"}, 7]}),
            lambda: queries([1, 2]),
            lambda: corpus("resize=\n"),
            lambda: corpus("side\n"),
            lambda: gate.admit_export({"source": "not a dict"}, True, False),
            lambda: gate.admit_export([], True, False),
        )
        for case in malformed:
            with self.assertRaises(gate.Refused):
                case()
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(gate.exit_status(case), 2)

    def test_an_earlier_result_is_cleared_before_anything_is_validated(self):
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, gate.RESULT_NAME)
            with open(path, "w", encoding="utf-8") as handle:
                handle.write('{"verdict": "PASS"}')
            gate.clear_stale_result(path)
            self.assertFalse(os.path.exists(path))
            gate.clear_stale_result(path)  # nothing there: not an error

    def test_the_versions_an_export_recorded_are_judged_not_its_pinned_flag(self):
        pins = gate.export_pins()
        self.assertTrue({"coremltools", "torch", "numpy", "transformers"} <= set(pins))
        good = {"pinned": True, "python": gate.PINNED_PYTHON + ".7", **pins}
        self.assertEqual(gate.recorded_toolchain_misses(good, pins), {})
        # An exporter from before the interpreter check wrote `pinned: true` under any Python.
        for stale in ({**good, "python": "3.12.9"}, {**good, "torch": "2.13.0"}, {k: v for k, v in good.items() if k != "numpy"},
                      {k: v for k, v in good.items() if k != "python"}):
            self.assertTrue(gate.recorded_toolchain_misses(stale, pins), stale)
            manifest = self.manifest()
            manifest["toolchain"] = stale
            with self.assertRaisesRegex(gate.Refused, "--allow-unpinned-export"):
                gate.admit_export(manifest, True, False)
            admitted = gate.admit_export(manifest, True, True)
            self.assertEqual(admitted["exportToolchainPinned"], False)
            self.assertEqual(len(admitted["overrides"]), 1)

    def test_another_python_is_outside_the_pinned_toolchain(self):
        self.assertEqual(gate.python_pin_miss(gate.PINNED_PYTHON + ".7"), {})
        self.assertEqual(gate.python_pin_miss(gate.PINNED_PYTHON), {})
        for other in ("3.12.9", "3.14.0", "3.1.3", "2.13.0"):
            self.assertEqual(gate.python_pin_miss(other), {"python": (other, gate.PINNED_PYTHON)})

    def test_phone_vectors_must_be_this_archive_over_the_pushed_pixels(self):
        good = {"tower": "vision", "archiveSha256": "a" * 64, "corpusId": "c" * 64, "preprocessingRevision": "3"}
        gate.admit_phone_vectors(good, "a" * 64, "c" * 64, 3, "v.txt")
        with self.assertRaisesRegex(gate.Refused, "measured over corpus"):
            gate.admit_phone_vectors({**good, "corpusId": "d" * 64}, "a" * 64, "c" * 64, 3, "v.txt")
        with self.assertRaisesRegex(gate.Refused, "measured over corpus"):
            gate.admit_phone_vectors({k: v for k, v in good.items() if k != "corpusId"}, "a" * 64, "c" * 64, 3, "v.txt")
        with self.assertRaisesRegex(gate.Refused, "vision archive"):
            gate.admit_phone_vectors({**good, "archiveSha256": "b" * 64}, "a" * 64, "c" * 64, 3, "v.txt")
        with self.assertRaisesRegex(gate.Refused, "vision archive"):
            gate.admit_phone_vectors({**good, "tower": "text"}, "a" * 64, "c" * 64, 3, "v.txt")

    def test_an_export_from_an_unpinned_toolchain_is_refused(self):
        # `false`, no `pinned` key, no toolchain block: none of them says the toolchain was pinned.
        for manifest in (self.manifest(pinned=False), self.manifest(pinned=None), {**self.manifest(), "toolchain": {}},
                         self.manifest(pinned="true")):
            with self.assertRaisesRegex(gate.Refused, "--allow-unpinned-export"):
                gate.admit_export(manifest, True, False)

    def test_the_override_admits_it_and_is_recorded(self):
        admitted = gate.admit_export(self.manifest(pinned=False), True, True)
        self.assertEqual(admitted["exportToolchainPinned"], False)
        self.assertEqual(admitted["overrides"], ["the export was made outside the pinned toolchain"])
        # The flag changes nothing for a pinned export, and never waives the checkpoint.
        self.assertEqual(gate.admit_export(self.manifest(), True, True)["overrides"], [])
        manifest = self.manifest(pinned=False)
        manifest["source"]["revision"] = "0" * 40
        with self.assertRaises(gate.Refused):
            gate.admit_export(manifest, True, True)

    def test_an_overridden_verdict_never_reads_as_a_bare_pass(self):
        self.assertTrue(gate.verdict_line(True, [], []).startswith("retrieval gate: PASS — "))
        self.assertTrue(gate.verdict_line(False, ["top1"], []).startswith("retrieval gate: FAIL — "))
        overridden = gate.verdict_line(True, [], ["the export was made outside the pinned toolchain"])
        self.assertTrue(overridden.startswith("retrieval gate: OVERRIDDEN PASS"))
        self.assertIn("the export was made outside the pinned toolchain", overridden)
        self.assertTrue(gate.verdict_line(False, ["top1"], ["x"]).startswith("retrieval gate: OVERRIDDEN FAIL"))


class Unpack(unittest.TestCase):
    def tar(self, directory: str, entries) -> str:
        path = os.path.join(directory, "tower.tar")
        with tarfile.open(path, "w", format=tarfile.USTAR_FORMAT) as archive:
            for name, kind in entries:
                info = tarfile.TarInfo(name)
                info.type = kind
                if kind == tarfile.SYMTYPE:
                    info.linkname = "/etc/passwd"
                if kind == tarfile.REGTYPE:
                    info.size = 1
                    archive.addfile(info, io.BytesIO(b"x"))
                else:
                    archive.addfile(info)
        return path

    def test_one_package_directory_is_unpacked(self):
        with tempfile.TemporaryDirectory() as scratch:
            path = self.tar(scratch, [("m.mlpackage", tarfile.DIRTYPE), ("m.mlpackage/Manifest.json", tarfile.REGTYPE)])
            package = gate.unpack(path, os.path.join(scratch, "out"))
            self.assertTrue(os.path.isfile(os.path.join(package, "Manifest.json")))

    def test_anything_else_is_refused(self):
        cases = {
            "a parent segment": [("m.mlpackage/../x", tarfile.REGTYPE)],
            "a symbolic link": [("m.mlpackage", tarfile.DIRTYPE), ("m.mlpackage/link", tarfile.SYMTYPE)],
            "two top-level directories": [("m.mlpackage/a", tarfile.REGTYPE), ("n.mlpackage/a", tarfile.REGTYPE)],
            "no package directory": [("weights.bin", tarfile.REGTYPE)],
        }
        for what, entries in cases.items():
            with tempfile.TemporaryDirectory() as scratch:
                with self.assertRaises(gate.Refused, msg=what):
                    gate.unpack(self.tar(scratch, entries), os.path.join(scratch, "out"))
                self.assertFalse(os.path.exists(os.path.join(scratch, "out")), what)


class Corpus(unittest.TestCase):
    FILES = {"a.jpg": b"alpha", "b.jpg": b"beta"}
    # The id `inference-harness-ios/run-in3m.sh self-test` and `CorpusManifestTest` hold for these
    # two files: the gate's corpus id is the harness's construction, pinned from both sides.
    HARNESS_GOLDEN = "15e951bef9a4ab29c1bfc0514ffbc7caf68be0035c62386d7b5f8e88403b8109"

    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.dir = os.path.join(self.scratch.name, "corpus")
        os.makedirs(self.dir)
        for name, data in self.FILES.items():
            with open(os.path.join(self.dir, name), "wb") as handle:
                handle.write(data)
        self.manifest_path = os.path.join(self.scratch.name, "manifest.txt")

    def tearDown(self):
        self.scratch.cleanup()

    def write(self, files=None, **header):
        files = self.FILES if files is None else files
        fields = {
            "id": gate.corpus_id((name, sha(data)) for name, data in files.items()),
            "count": str(len(files)),
            "source": "http://127.0.0.1:9/",
            "resize": "256x256 bicubic rgb stretch",
            "pixels": "0" * 64,
        }
        fields.update(header)
        lines = [gate.CORPUS_MAGIC] + [f"{key}={value}" for key, value in fields.items() if value is not None] + [""]
        lines += [f"{sha(data)}\t{len(data)}\t{name}" for name, data in files.items()]
        with open(self.manifest_path, "w", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")

    def test_the_id_is_the_harness_construction(self):
        self.assertEqual(gate.corpus_id((name, sha(data)) for name, data in self.FILES.items()), self.HARNESS_GOLDEN)
        # Name order, whatever order the entries arrive in.
        self.assertEqual(gate.corpus_id([("b.jpg", sha(b"beta")), ("a.jpg", sha(b"alpha"))]), self.HARNESS_GOLDEN)

    def test_the_id_follows_the_bytes_and_the_names(self):
        self.assertNotEqual(gate.corpus_id([("a.jpg", sha(b"alpha")), ("b.jpg", sha(b"betb"))]), self.HARNESS_GOLDEN)
        self.assertNotEqual(gate.corpus_id([("a.jpg", sha(b"alpha")), ("c.jpg", sha(b"beta"))]), self.HARNESS_GOLDEN)

    def test_a_matching_directory_is_accepted_and_extra_files_are_ignored(self):
        self.write()
        manifest = gate.load_corpus_manifest(self.manifest_path)
        self.assertEqual((manifest["id"], manifest["count"], manifest["side"]), (self.HARNESS_GOLDEN, 2, 256))
        with open(os.path.join(self.dir, "stray.jpg"), "wb") as handle:
            handle.write(b"stray")
        gate.verify_corpus(self.dir, manifest, fetch=False)

    def test_a_changed_file_is_refused(self):
        self.write()
        manifest = gate.load_corpus_manifest(self.manifest_path)
        with open(os.path.join(self.dir, "b.jpg"), "wb") as handle:
            handle.write(b"betb")  # the same size
        with self.assertRaisesRegex(gate.Refused, "not the file the manifest names"):
            gate.verify_corpus(self.dir, manifest, fetch=False)
        with open(os.path.join(self.dir, "b.jpg"), "wb") as handle:
            handle.write(b"longer")
        with self.assertRaisesRegex(gate.Refused, "bytes, the manifest says"):
            gate.verify_corpus(self.dir, manifest, fetch=False)

    def test_a_missing_file_is_refused_without_fetch_and_a_failed_fetch_leaves_nothing(self):
        self.write()
        manifest = gate.load_corpus_manifest(self.manifest_path)
        os.remove(os.path.join(self.dir, "a.jpg"))
        with self.assertRaisesRegex(gate.Refused, "pass --fetch"):
            gate.verify_corpus(self.dir, manifest, fetch=False)
        with self.assertRaisesRegex(gate.Refused, "could not fetch"):  # nothing listens on port 9
            gate.verify_corpus(self.dir, manifest, fetch=True)
        self.assertEqual(sorted(os.listdir(self.dir)), ["b.jpg"])

    def test_a_manifest_that_does_not_describe_itself_is_refused(self):
        bad = {
            "another id": dict(id="0" * 64),
            "another count": dict(count="3"),
            "a transform this version does not apply": dict(resize="256x256 bilinear rgb stretch"),
            "a non-square side": dict(resize="256x224 bicubic rgb stretch"),
            "no pixels line": dict(pixels=None),
        }
        for what, header in bad.items():
            self.write(**header)
            with self.assertRaises(gate.Refused, msg=what):
                gate.load_corpus_manifest(self.manifest_path)
        self.write(files={"b.jpg": b"beta", "a.jpg": b"alpha"})  # out of name order
        with self.assertRaisesRegex(gate.Refused, "name order"):
            gate.load_corpus_manifest(self.manifest_path)

    def test_the_committed_manifest_describes_itself(self):
        manifest = gate.load_corpus_manifest(gate.DEFAULT_CORPUS_MANIFEST)
        self.assertEqual((manifest["count"], manifest["side"]), (300, 256))
        self.assertTrue(all(entry["name"].endswith(".jpg") for entry in manifest["files"]))


class Queries(unittest.TestCase):
    def test_the_committed_queries_are_the_four_language_set(self):
        queries = gate.load_queries(gate.DEFAULT_QUERIES)
        counts = {}
        for query in queries:
            counts[query["language"]] = counts.get(query["language"], 0) + 1
        self.assertEqual(counts, {"en": 16, "de": 12, "ja": 4, "ru": 4})

    def test_a_repeated_or_malformed_query_is_refused(self):
        for document in ({"queries": []}, {"queries": [{"text": "a dog"}]}, ["a dog"],
                         {"queries": [{"language": "en", "text": "a dog"}] * 2}):
            with tempfile.NamedTemporaryFile("w", suffix=".json", encoding="utf-8") as handle:
                json.dump(document, handle)
                handle.flush()
                with self.assertRaises(gate.Refused, msg=str(document)):
                    gate.load_queries(handle.name)


class Toolchain(unittest.TestCase):
    def test_the_pins_include_the_export_set_and_what_the_gate_adds(self):
        pins = gate.pinned_versions(os.path.join(_HERE, gate.PINNED))
        export = gate.pinned_versions(os.path.join(_HERE, "requirements-coreml-export.txt"))
        self.assertTrue(export and all(pins.get(name) == version for name, version in export.items()))
        self.assertTrue({"onnxruntime", "pillow", "sentencepiece"} <= set(pins))


class PhoneVectors(unittest.TestCase):
    def test_a_vector_of_another_width_is_refused_before_measurement(self):
        good = {"a.png": [0.0] * 4, "b.png": [1.0] * 4}
        gate.admit_phone_widths(good, 4, "v.txt")
        for bad in ({**good, "b.png": [1.0] * 3}, {**good, "c.png": [1.0] * 8}):
            with self.assertRaisesRegex(gate.Refused, "floats wide"):
                gate.admit_phone_widths(bad, 4, "v.txt")

    def test_a_malformed_vector_is_refused_not_a_traceback(self):
        for payload in ("zz", "0011223", "001122", ""):
            text = f"{gate.PHONE_MAGIC}\ntower=vision\n\n000000000001.png\t{payload}\n"
            with tempfile.NamedTemporaryFile("w", suffix=".txt", encoding="utf-8") as handle:
                handle.write(text)
                handle.flush()
                with self.assertRaises(gate.Refused, msg=payload):
                    gate.read_phone_vectors(handle.name)

    def test_a_harness_vector_file_is_read_back(self):
        # `VectorFile.write`: each float's raw bits as 8 hex digits.
        floats = [1.0, -0.5, 0.25]
        line = "".join(struct.pack(">f", value).hex() for value in floats)
        text = f"{gate.PHONE_MAGIC}\ntower=vision\nrow=coreml-ane\narchiveSha256=ab\n\n000000000001.png\t{line}\n"
        with tempfile.NamedTemporaryFile("w", suffix=".txt", encoding="utf-8") as handle:
            handle.write(text)
            handle.flush()
            fingerprint, vectors = gate.read_phone_vectors(handle.name)
        self.assertEqual(fingerprint, {"tower": "vision", "row": "coreml-ane", "archiveSha256": "ab"})
        self.assertEqual(vectors, {"000000000001.png": floats})

    def test_a_file_of_another_format_is_refused(self):
        with tempfile.NamedTemporaryFile("w", suffix=".txt", encoding="utf-8") as handle:
            handle.write("in3m-vectors 2\ntower=vision\n\n")
            handle.flush()
            with self.assertRaises(gate.Refused):
                gate.read_phone_vectors(handle.name)


if __name__ == "__main__":
    unittest.main()
