#!/usr/bin/env python3
"""Pins `label-eval.py`'s arithmetic, its port of the selection rule, its split and its refusals,
with no model and no network.

    python3 scripts/inference/test_label_eval.py

The metric cases are worked by hand in the comments beside them. The selection-rule cases are
`LabelSelectionTest.kt`'s, named where they are taken. ⚠ What this cannot show: that the three
artifacts embed correctly, or what they score. That is the harness's own run, against real weights.

Two classes need numpy and are skipped without it (`Bootstrap`, `EvaluateEndToEnd`); everything
else needs no package.
"""
import contextlib
import hashlib
import importlib.util
import io
import json
import math
import os
import re
import shutil
import struct
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("label_eval", os.path.join(_HERE, "label-eval.py"))
ev = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ev)
gate = ev.gate

try:
    import numpy
except ImportError:
    numpy = None


class Metrics(unittest.TestCase):
    def test_pr_points_walk_down_the_scores(self):
        # Fired 1, 2, 3, 4 images; hits 1, 1, 2, 2 of 2 positives.
        points = ev.pr_points([0.9, 0.8, 0.7, 0.6], [True, False, True, False])
        self.assertEqual(points, [(0.9, 1.0, 0.5, 1), (0.8, 0.5, 0.5, 2), (0.7, 2 / 3, 1.0, 3), (0.6, 0.5, 1.0, 4)])

    def test_tied_scores_are_one_point(self):
        # The positive cannot be ranked above the negative it ties with: precision 1/2, not 1.
        self.assertEqual(ev.pr_points([0.9, 0.9, 0.1], [True, False, False]), [(0.9, 0.5, 1.0, 2), (0.1, 1 / 3, 1.0, 3)])
        self.assertAlmostEqual(ev.average_precision([0.9, 0.9, 0.1], [True, False, False]), 0.5)

    def test_average_precision_by_hand(self):
        # Recall steps 0.5 at precision 1 and 0.5 at precision 2/3.
        self.assertAlmostEqual(ev.average_precision([0.9, 0.8, 0.7, 0.6], [True, False, True, False]), 0.5 + 0.5 * 2 / 3)

    def test_average_precision_is_one_for_a_perfect_ranking_and_ignores_input_order(self):
        self.assertEqual(ev.average_precision([0.1, 0.9, 0.2, 0.8], [False, True, False, True]), 1.0)

    def test_a_weight_counts_an_image_that_many_times(self):
        scores, truth = [0.9, 0.8, 0.7, 0.6], [True, False, True, False]
        weighted = ev.average_precision(scores, truth, weights=[1, 2, 3, 0])
        repeated = ev.average_precision([0.9, 0.8, 0.8, 0.7, 0.7, 0.7], [True, False, False, True, True, True])
        self.assertAlmostEqual(weighted, repeated)

    def test_pr_points_refuse_a_label_with_no_positive(self):
        with self.assertRaises(ValueError):
            ev.pr_points([0.1, 0.2], [False, False])

    def test_the_floor_is_the_lowest_threshold_holding_the_target(self):
        # Precision down the ranking: 1, 1, 2/3, 3/4, 3/5. The lowest at >= 0.75 is the fourth.
        scores, truth = [0.9, 0.8, 0.7, 0.6, 0.5], [True, True, False, True, False]
        self.assertEqual(ev.threshold_for_precision(scores, truth, 0.75, min_fired=1), 0.6)
        self.assertEqual(ev.threshold_for_precision(scores, truth, 0.8, min_fired=1), 0.8)

    def test_a_floor_is_not_placed_on_too_few_firing_images(self):
        # Precision 1.0 exists only with one or two images firing; with three required it is gone.
        scores, truth = [0.9, 0.8, 0.7, 0.6, 0.5], [True, True, False, True, False]
        self.assertIsNone(ev.threshold_for_precision(scores, truth, 1.0, min_fired=3))
        self.assertEqual(ev.threshold_for_precision(scores, truth, 1.0, min_fired=2), 0.8)

    def test_a_target_nothing_reaches_has_no_floor(self):
        self.assertIsNone(ev.threshold_for_precision([0.9, 0.8], [False, True], 0.9, min_fired=1))

    def test_a_score_equal_to_the_floor_fires_and_no_floor_never_does(self):
        # `>=`, as LabelSelection.kt:47 compares.
        self.assertEqual(ev.fires([0.5, 0.49999], 0.5), [True, False])
        self.assertEqual(ev.fires([9.0], None), [False])

    def test_confusion(self):
        got = ev.confusion([True, True, False, False], [True, False, True, False])
        self.assertEqual((got["fired"], got["hits"], got["positives"], got["precision"], got["recall"]), (2, 1, 2, 0.5, 0.5))
        self.assertIsNone(ev.confusion([False], [True])["precision"])

    def test_agreement_counts_the_four_cells(self):
        got = ev.agreement([True, True, False, False, False], [True, False, True, False, False])
        self.assertEqual((got["both"], got["onlyA"], got["onlyB"], got["neither"]), (1, 1, 1, 2))
        self.assertAlmostEqual(got["agreement"], 3 / 5)
        self.assertAlmostEqual(got["jaccard"], 1 / 3)

    def test_jaccard_is_undefined_when_neither_side_fires(self):
        got = ev.agreement([False, False], [False, False])
        self.assertEqual(got["agreement"], 1.0)
        self.assertIsNone(got["jaccard"])

    def test_near_threshold_looks_only_at_disagreements(self):
        # Image 0 agrees (both fire). Image 1: a fires by 0.002, b misses by 0.05: distance 0.002.
        # Image 2: a misses by 0.03, b fires by 0.04: distance 0.03.
        got = ev.near_threshold([0.20, 0.102, 0.07], 0.1, [0.30, 0.15, 0.24], 0.2, margins=(0.005, 0.01))
        self.assertEqual(got["disagreements"], 2)
        self.assertEqual(got["within"], {"0.005": 0.5, "0.01": 0.5})
        self.assertAlmostEqual(got["medianDistance"], 0.002)  # of two distances, the lower: nearest rank

    def test_near_threshold_with_a_side_that_has_no_floor(self):
        got = ev.near_threshold([0.2], 0.1, [0.9], None, margins=(0.005,))
        self.assertEqual(got["disagreements"], 1)
        self.assertAlmostEqual(got["medianDistance"], 0.1)

    def test_set_agreement(self):
        a = [["dog"], [], ["dog", "animals"], ["cat"]]
        b = [["dog"], [], ["dog"], ["dog"]]
        got = ev.set_agreement(a, b)
        self.assertEqual(got["identical"], 0.5)  # image 0, and the two empty sets of image 1
        self.assertEqual(got["eitherLabelled"], 3)
        self.assertAlmostEqual(got["identicalWhereEitherLabelled"], 1 / 3)
        self.assertAlmostEqual(got["meanJaccardWhereEitherLabelled"], (1 + 0.5 + 0) / 3)

    def test_quantiles_are_nearest_rank(self):
        # Nearest rank: the smallest observation with at least a share q of the row
        # at or below it. Where q·n is a whole number that is observation q·n, not the one after:
        # the median of [1, 2, 3, 4] is 2, where `int(q·n)` as an index would give 3.
        self.assertEqual(ev.quantiles([3, 1, 2, 4], at=(0.0, 0.25, 0.5, 0.75, 1.0)), {"0.0": 1, "0.25": 1, "0.5": 2, "0.75": 3, "1.0": 4})
        self.assertEqual(ev.quantiles([3, 1, 2], at=(0.0, 0.5, 1.0)), {"0.0": 1, "0.5": 2, "1.0": 3})   # q·n = 1.5 -> the 2nd
        self.assertEqual(ev.quantiles([7], at=(0.0, 0.05, 0.5, 1.0)), {"0.0": 7, "0.05": 7, "0.5": 7, "1.0": 7})
        row = list(range(1, 101))  # the value IS its rank
        self.assertEqual(ev.quantiles(row), {"0.0": 1, "0.05": 5, "0.25": 25, "0.5": 50, "0.75": 75, "0.95": 95, "1.0": 100})
        self.assertEqual(ev.quantiles(row[:99], at=(0.05, 0.5, 0.95)), {"0.05": 5, "0.5": 50, "0.95": 95})  # 4.95, 49.5, 94.05 -> up

    def test_a_quantile_rank_is_exact_where_floats_are_not(self):
        # 0.07 × 100 is 7.000000000000001 in floats, and its ceiling would be the 8th.
        self.assertGreater(0.07 * 100, 7)
        self.assertEqual(ev.nearest_rank(list(range(1, 101)), 0.07), 7)
        row = list(range(1, 2501))
        self.assertEqual(ev.nearest_rank(row, 0.05), 125)
        self.assertEqual([ev.nearest_rank(row, q) for q in (0.0, 0.25, 0.5, 0.75, 0.95, 1.0)], [1, 625, 1250, 1875, 2375, 2500])
        for count in range(1, 400):  # against the definition itself, for every size and point
            row = list(range(1, count + 1))
            for numerator in (0, 5, 25, 50, 75, 95, 100):
                want = max(1, -(-numerator * count // 100))  # ceil(q·n) in integers
                self.assertEqual(ev.nearest_rank(row, numerator / 100), want, (count, numerator))
        with self.assertRaises(ValueError):
            ev.nearest_rank([], 0.5)

    def test_the_median_of_an_even_count_is_the_lower_middle_observation(self):
        self.assertEqual(ev.median([4, 1, 3, 2]), 2)
        self.assertEqual(ev.median([0.25, 0.75]), 0.25)
        self.assertEqual(ev.median(value for value in (5, 1, 3)), 3)

    def test_the_probability_is_a_sigmoid_of_the_scaled_cosine(self):
        # The pinned checkpoint's constants put p = 0.5 at cos ≈ 0.116, and they are the default.
        scale, bias = ev.CHECKPOINT_CALIBRATION
        self.assertAlmostEqual(-bias / scale, 0.116, places=3)
        self.assertAlmostEqual(ev.probability(-bias / scale), 0.5)
        # The -224 checkpoint's constants put it at 0.110.
        self.assertAlmostEqual(12.932437 / 117.330765, 0.110, places=3)

    def kotlin(self, *path) -> str:
        root = os.path.dirname(os.path.dirname(_HERE))
        with open(os.path.join(root, "inference-service-proxy", "src", "commonMain", "kotlin", "org", "tenshu", "inference", "proxy", *path),
                  encoding="utf-8") as handle:
            return handle.read()

    def test_the_app_carries_the_checkpoints_constants(self):
        # ⚠⚠ The app must carry the pinned checkpoint's constants (models.md §3.10.8). This reads the
        # Kotlin, not a copy.
        found = re.findall(r"calibration = ScoreCalibration\(scale = (-?[0-9.]+)f, bias = (-?[0-9.]+)f\)", self.kotlin("onnx", "VisionModel.kt"))
        self.assertEqual(found, [("110.995995", "-12.885268")])
        self.assertEqual((float(found[0][0]), float(found[0][1])), ev.APP_CALIBRATION)
        self.assertEqual(ev.APP_CALIBRATION, ev.CHECKPOINT_CALIBRATION)

    def test_the_apps_search_floor_cuts_at_the_measured_cosine(self):
        # `SEMANTIC_RELEVANCE_FLOOR` is measured on the pinned checkpoint (inference-service.md
        # §35.1): cosine 0.06 is admitted and 0.0599 is not, under the app's own constants. The
        # iPhone's five cats scored 0.080-0.084 for "cat"; the carried-over cut was cosine 0.0915.
        found = re.findall(r"const val SEMANTIC_RELEVANCE_FLOOR = ([0-9.]+)f", self.kotlin("PhotosLikeImplementation.kt"))
        self.assertEqual(found, ["0.00197"])
        floor = float(found[0])
        scale, bias = ev.APP_CALIBRATION
        cut = (math.log(floor / (1 - floor)) - bias) / scale
        self.assertAlmostEqual(cut, 0.06, places=4)
        self.assertGreaterEqual(ev.probability(0.06), floor)
        self.assertLess(ev.probability(0.0599), floor)
        self.assertGreaterEqual(ev.probability(0.0802), floor, "the iPhone's weakest cat")
        self.assertLess(ev.probability(0.0213), floor, "the iPhone's best non-cat")

    def test_difference_separates_the_offset_from_the_noise(self):
        got = ev.difference([0.5, 0.25, 0.75, 0.5], [0.25, 0.25, 0.5, 0.25])  # deltas .25 0 .25 .25
        self.assertAlmostEqual(got["mean"], 0.1875)
        self.assertAlmostEqual(got["sd"], math.sqrt((3 * 0.0625 ** 2 + 0.1875 ** 2) / 4))
        self.assertEqual(ev.difference([1.0, 2.0], [0.5, 1.5]), {"mean": 0.5, "sd": 0.0})

    def test_pooled_jaccard_is_over_every_cell(self):
        got = ev.pooled_jaccard({"dog": [True, True, False], "cat": [False, True, False]}, {"dog": [True, False, False], "cat": [False, True, True]})
        self.assertEqual(got, {"both": 2, "disagreements": 2, "jaccard": 0.5})
        self.assertIsNone(ev.pooled_jaccard({"dog": [False]}, {"dog": [False]})["jaccard"])


class SelectionRule(unittest.TestCase):
    """`LabelSelectionTest.kt`, case for case: its vocabulary (lines 22-30) and its numbers."""

    VOCAB = ev.vocabulary_of([
        {"label": "indoors", "tier": "top", "parent": None, "floor": 0.04, "margin": 0.0},
        {"label": "dog", "tier": "top", "parent": None, "floor": 0.10, "margin": 0.0},
        {"label": "staircase", "tier": "leaf", "parent": "indoors", "floor": 0.08, "margin": 0.02},
        {"label": "gym", "tier": "leaf", "parent": "indoors", "floor": 0.08, "margin": 0.02},
    ])

    def select(self, *tags, vocabulary=None):
        return ev.select_labels(list(tags), self.VOCAB if vocabulary is None else vocabulary)

    def test_a_top_label_clearing_its_floor_is_written(self):  # aTopLabelClearingItsFloorIsWritten, :39
        self.assertEqual(set(self.select(("dog", 0.4))), {"dog"})

    def test_a_top_label_below_its_floor_is_dropped(self):  # aTopLabelBelowItsFloorIsDropped, :44
        self.assertEqual(self.select(("dog", 0.05)), {})

    def test_two_top_labels_can_both_fire(self):  # twoTopLabelsCanBothFire, :50
        self.assertEqual(set(self.select(("indoors", 0.3), ("dog", 0.4))), {"indoors", "dog"})

    def test_a_leaf_clearing_floor_and_margin_wins(self):  # aLeafClearingBothItsFloorAndItsMarginWins, :57
        self.assertEqual(set(self.select(("indoors", 0.3), ("staircase", 0.5), ("gym", 0.1))), {"indoors", "staircase"})

    def test_a_winning_leaf_below_its_floor_degrades_even_with_a_clear_margin(self):
        # aWinningLeafBelowItsFloorDegradesToTheParentEvenWithAClearMargin, :70 — the measured
        # `indoors 0.061` / `staircase −0.009` of models.md §3.7.5. ⚠ −0.009 is a cosine.
        got = self.select(("indoors", 0.061), ("staircase", -0.009), ("gym", -0.4))
        self.assertEqual(got, {"indoors": 0.061})

    def test_a_leaf_inside_its_margin_degrades_to_the_parent(self):
        # aLeafThatClearsItsFloorButNotItsMarginDegradesToTheParent, :79
        self.assertEqual(set(self.select(("indoors", 0.3), ("staircase", 0.20), ("gym", 0.19))), {"indoors"})

    def test_a_lone_sibling_is_judged_on_its_floor_alone(self):  # aLoneSiblingIsJudgedOnItsFloorAlone, :87
        self.assertEqual(set(self.select(("indoors", 0.3), ("staircase", 0.5))), {"indoors", "staircase"})

    def test_a_failing_leaf_does_not_promote_an_unearned_parent(self):
        # aFailingLeafDoesNotPromoteAParentThatDidNotEarnItsOwnPlace, :97
        self.assertEqual(self.select(("indoors", 0.01), ("staircase", -0.2), ("gym", -0.3)), {})

    def test_a_label_outside_the_vocabulary_is_never_written(self):  # aLabelOutsideTheVocabularyIsNeverWritten, :107
        self.assertEqual(self.select(("hamster", 0.99)), {})

    def test_an_empty_vocabulary_emits_nothing(self):  # anEmptyVocabularyEmitsNothingHoweverConfidentTheModelIs, :116
        self.assertEqual(self.select(("dog", 0.99), ("indoors", 0.99), vocabulary=[]), {})

    def test_a_leaf_with_no_parent_is_rejected(self):  # aLeafWithNoParentIsRejected, :125
        with self.assertRaises(ValueError):
            ev.vocabulary_of([{"label": "corgi", "tier": "leaf", "parent": None, "floor": 0.1, "margin": 0.02}])

    def test_a_leaf_naming_a_missing_parent_is_rejected(self):  # aLeafNamingAMissingParentIsRejected, :132
        with self.assertRaises(ValueError):
            ev.vocabulary_of([{"label": "corgi", "tier": "leaf", "parent": "dog", "floor": 0.1, "margin": 0.02}])

    def test_a_duplicated_label_is_rejected(self):  # aDuplicatedLabelIsRejected, :142
        with self.assertRaises(ValueError):
            ev.vocabulary_of([{"label": "dog", "tier": "top", "parent": None, "floor": 0.1, "margin": 0.0},
                              {"label": "dog", "tier": "top", "parent": None, "floor": 0.2, "margin": 0.0}])

    # Beyond the Kotlin suite: the lines of `selectLabels` it has no case for.

    def test_a_repeated_tag_keeps_its_highest_score(self):  # LabelSelection.kt:42
        self.assertEqual(self.select(("dog", 0.05), ("dog", 0.4)), {"dog": 0.4})

    def test_the_margin_is_inclusive(self):  # LabelSelection.kt:65, `>=`. Binary-exact numbers.
        vocabulary = ev.vocabulary_of([
            {"label": "indoors", "tier": "top", "parent": None, "floor": 0.0, "margin": 0.0},
            {"label": "staircase", "tier": "leaf", "parent": "indoors", "floor": 0.25, "margin": 0.25},
            {"label": "gym", "tier": "leaf", "parent": "indoors", "floor": 0.25, "margin": 0.25},
        ])
        self.assertIn("staircase", self.select(("staircase", 0.75), ("gym", 0.5), vocabulary=vocabulary))
        self.assertNotIn("staircase", self.select(("staircase", 0.75), ("gym", 0.625), vocabulary=vocabulary))

    def test_the_floor_and_margin_are_the_winners_own(self):  # LabelSelection.kt:64-65
        vocabulary = ev.vocabulary_of([
            {"label": "indoors", "tier": "top", "parent": None, "floor": 9.0, "margin": 0.0},
            {"label": "staircase", "tier": "leaf", "parent": "indoors", "floor": 0.5, "margin": 0.0},
            {"label": "gym", "tier": "leaf", "parent": "indoors", "floor": 0.1, "margin": 0.0},
        ])
        # staircase wins the argmax and is below ITS floor; gym, above its own, is not consulted.
        self.assertEqual(self.select(("staircase", 0.4), ("gym", 0.3), vocabulary=vocabulary), {})

    def test_only_one_leaf_of_a_sibling_set_is_ever_written(self):
        # What the rule costs on an image holding two siblings: the runner-up is never written.
        got = self.select(("indoors", 0.3), ("staircase", 0.9), ("gym", 0.8))
        self.assertEqual(set(got), {"indoors", "staircase"})

    def test_an_infinite_floor_never_fires(self):
        vocabulary = ev.vocabulary_of([{"label": "dog", "tier": "top", "parent": None, "floor": math.inf, "margin": 0.0}])
        self.assertEqual(self.select(("dog", 1.0), vocabulary=vocabulary), {})

    def test_leaf_outcome_names_what_the_sibling_set_did(self):
        self.assertEqual(ev.leaf_outcome([("staircase", 0.5), ("gym", 0.1)], self.VOCAB, "indoors"), "leaf")
        self.assertEqual(ev.leaf_outcome([("staircase", -0.009), ("gym", -0.4)], self.VOCAB, "indoors"), "belowFloor")
        self.assertEqual(ev.leaf_outcome([("staircase", 0.20), ("gym", 0.19)], self.VOCAB, "indoors"), "insideMargin")
        self.assertEqual(ev.leaf_outcome([("dog", 0.5)], self.VOCAB, "indoors"), "noScores")

    def test_tied_siblings_go_to_the_one_declared_first(self):
        # LabelSelection.kt:56-58: `siblingsOf` is declaration order and `sortedByDescending` is
        # stable. `staircase` is declared before `gym`; the order the tags arrive in is irrelevant.
        vocabulary = ev.vocabulary_of([
            {"label": "indoors", "tier": "top", "parent": None, "floor": 0.0, "margin": 0.0},
            {"label": "staircase", "tier": "leaf", "parent": "indoors", "floor": 0.25, "margin": 0.0},
            {"label": "gym", "tier": "leaf", "parent": "indoors", "floor": 0.25, "margin": 0.0},
        ])
        for tags in ([("staircase", 0.5), ("gym", 0.5)], [("gym", 0.5), ("staircase", 0.5)]):
            self.assertEqual(set(self.select(*tags, vocabulary=vocabulary)), {"staircase"})
            self.assertEqual(ev.leaf_outcome(tags, vocabulary, "indoors"), "leaf")

    def test_tied_siblings_inside_a_margin_degrade_and_do_not_crash(self):
        # A tie is a margin of 0: under the suite's vocabulary (margin 0.02) it degrades. The old
        # `leaf_outcome` compared the two entries here and raised TypeError.
        tags = [("indoors", 0.3), ("staircase", 0.5), ("gym", 0.5)]
        self.assertEqual(set(self.select(*tags)), {"indoors"})
        self.assertEqual(ev.leaf_outcome(tags, self.VOCAB, "indoors"), "insideMargin")
        self.assertEqual(ev.leaf_outcome([("staircase", 0.01), ("gym", 0.01)], self.VOCAB, "indoors"), "belowFloor")

    def test_leaf_outcome_reads_a_repeated_tag_as_the_rule_does(self):
        # The highest score of a repeated label, not the last one.
        tags = [("staircase", 0.5), ("staircase", 0.01), ("gym", 0.1)]
        self.assertIn("staircase", self.select(*tags))
        self.assertEqual(ev.leaf_outcome(tags, self.VOCAB, "indoors"), "leaf")

    def test_leaf_outcome_agrees_with_the_rule(self):
        for tags in ([("indoors", 0.3), ("staircase", 0.5), ("gym", 0.1)], [("indoors", 0.3), ("staircase", 0.2), ("gym", 0.19)],
                     [("indoors", 0.3), ("staircase", 0.01), ("gym", 0.0)]):
            wrote_leaf = bool({"staircase", "gym"} & set(ev.select_labels(tags, self.VOCAB)))
            self.assertEqual(ev.leaf_outcome(tags, self.VOCAB, "indoors") == "leaf", wrote_leaf)


class Split(unittest.TestCase):
    NAMES = [f"{index:012d}.jpg" for index in range(101)]

    def test_the_halves_partition_the_names(self):
        cal, held = ev.split(self.NAMES, 7)
        self.assertEqual(sorted(cal + held), self.NAMES)
        self.assertFalse(set(cal) & set(held))
        self.assertEqual((len(cal), len(held)), (50, 51))

    def test_the_split_depends_on_the_names_and_the_seed_only(self):
        self.assertEqual(ev.split(self.NAMES, 7), ev.split(list(reversed(self.NAMES)), 7))
        self.assertNotEqual(ev.split(self.NAMES, 7)[0], ev.split(self.NAMES, 8)[0])

    def test_the_split_is_pinned(self):
        # A golden value: another digest, another key or another ordering moves it, and then every
        # recorded held-out figure describes other images.
        cal, held = ev.split([f"{index}.jpg" for index in range(8)], 20261010)
        self.assertEqual(cal, ["0.jpg", "4.jpg", "5.jpg", "7.jpg"])
        self.assertEqual(held, ["1.jpg", "2.jpg", "3.jpg", "6.jpg"])
        self.assertEqual(ev.DEFAULT_SEED, 20261010)

    def test_a_label_with_one_class_in_a_half_is_named(self):
        truth = {"dog": [True, False, True, False], "cat": [False, False, True, True], "people": [True, True, True, True]}
        halves = {"calibration": [0, 1], "held-out": [2, 3]}
        self.assertEqual(ev.unscorable(truth, ["dog"], halves), [])
        self.assertEqual(ev.unscorable(truth, ["dog", "cat", "people"], halves),
                         ["cat: no positive in the calibration half", "cat: no negative in the held-out half",
                          "people: no negative in the calibration half", "people: no negative in the held-out half"])
        ev.refuse_unscorable(truth, ["dog"], halves)
        with self.assertRaises(ev.Refused) as refusal:
            ev.refuse_unscorable(truth, ["dog", "cat", "people"], halves)
        self.assertIn("cannot score 4 label halves", str(refusal.exception))
        with self.assertRaises(ev.Refused):
            ev.refuse_unscorable(truth, [], halves)

    def test_a_repeated_name_is_refused(self):
        with self.assertRaises(ValueError):
            ev.split(["a.jpg", "a.jpg"], 1)


def write(directory: str, name: str, text: str) -> str:
    path = os.path.join(directory, name)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)
    return path


LABELS = "# a comment\n\ntop\t-\tpeople\tThis is a photo of people.\ntop\t-\tanimals\tThis is a photo of an animal.\n" \
         "leaf\tanimals\tdog\tThis is a photo of a dog.\nleaf\tanimals\tcat\tThis is a photo of a cat.\n" \
         "top\t-\tindoors\tThis is a photo taken indoors.\n"
MAPPING = "label-eval-mapping 1\n# a comment\npeople\tone-to-one\texact\tperson\t-\nanimals\tone-to-many\texact\tdog;cat\tonly two\n" \
          "dog\tone-to-one\texact\tdog\t-\ncat\tone-to-one\tproxy\tcat\ta note\nindoors\tnone\t-\t-\ta scene\n"


class LabelSetAndMapping(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.labels = ev.read_label_set(write(self.scratch.name, "labels.tsv", LABELS))

    def mapping(self, text):
        return ev.load_mapping(write(self.scratch.name, "mapping.tsv", text), self.labels)

    def test_the_label_set_reads_in_file_order(self):
        self.assertEqual([row["label"] for row in self.labels], ["people", "animals", "dog", "cat", "indoors"])
        self.assertEqual(self.labels[2], {"tier": "leaf", "parent": "animals", "label": "dog", "prompt": "This is a photo of a dog."})
        self.assertIsNone(self.labels[0]["parent"])

    def test_a_malformed_label_row_is_refused_not_skipped(self):
        for bad in ("leaf\tanimals\tdog\n", "twig\t-\tdog\tA dog.\n", "top\tanimals\tdog\tA dog.\n", "leaf\tnowhere\tdog\tA dog.\n",
                    "top\t-\tdog\tA dog.\ntop\t-\tdog\tA dog.\n", "# only a comment\n"):
            with self.assertRaises(ev.Refused, msg=bad):
                ev.read_label_set(write(self.scratch.name, "bad.tsv", bad))

    def test_the_mapping_reads(self):
        mapping = self.mapping(MAPPING)
        self.assertEqual(mapping["animals"], {"kind": "one-to-many", "fidelity": "exact", "categories": ["dog", "cat"], "note": "only two"})
        self.assertEqual(mapping["indoors"]["kind"], "none")
        self.assertEqual(ev.mapped_labels(self.labels, mapping), ["people", "animals", "dog", "cat"])

    def test_a_mapping_that_does_not_cover_the_label_set_exactly_is_refused(self):
        with self.assertRaises(ev.Refused):
            self.mapping(MAPPING.replace("indoors\tnone\t-\t-\ta scene\n", ""))
        with self.assertRaises(ev.Refused):
            self.mapping(MAPPING + "hamster\tnone\t-\t-\t-\n")

    def test_a_mapping_row_that_contradicts_itself_is_refused(self):
        for old, new in (("label-eval-mapping 1", "label-eval-mapping 2"),
                         ("dog\tone-to-one\texact\tdog", "dog\tone-to-one\texact\twolf"),          # not a COCO category
                         ("dog\tone-to-one\texact\tdog", "dog\tone-to-many\texact\tdog"),         # kind against count
                         ("dog\tone-to-one\texact\tdog", "dog\tone-to-one\texact\tdog;dog"),      # a repeat
                         ("dog\tone-to-one\texact\tdog", "dog\tone-to-one\t-\tdog"),              # mapped with no fidelity
                         ("indoors\tnone\t-\t-", "indoors\tnone\texact\t-"),                      # unmapped with one
                         ("dog\tone-to-one\texact\tdog\t-\n", "dog\tone-to-one\texact\tdog\t-\ndog\tone-to-one\texact\tdog\t-\n"),
                         ("dog\tone-to-one\texact\tdog\t-", "dog\tone-to-one\texact\tdog")):       # four columns
            with self.assertRaises(ev.Refused, msg=new):
                self.mapping(MAPPING.replace(old, new))

    def test_the_evaluation_vocabulary_gives_what_has_no_floor_an_infinite_one(self):
        labels = ev.read_label_set(write(self.scratch.name, "l.tsv", LABELS + "top\t-\tobjects\tAn object.\nleaf\tobjects\tbook\tA book.\n"))
        mapping = ev.load_mapping(write(self.scratch.name, "m.tsv", MAPPING + "objects\tnone\t-\t-\t-\nbook\tone-to-one\texact\tbook\t-\n"), labels)
        vocabulary = ev.evaluation_vocabulary(labels, mapping, {"people": 0.1, "animals": 0.1, "dog": 0.12, "cat": None, "book": 0.2}, 0.01)
        by_name = {entry["label"]: entry for entry in vocabulary}
        self.assertEqual(list(by_name), ["people", "animals", "dog", "cat", "objects", "book"])  # `indoors` has no mapped leaf
        self.assertEqual(by_name["cat"]["floor"], math.inf)       # mapped, but its target was unreachable
        self.assertEqual(by_name["objects"]["floor"], math.inf)   # unmapped, present only as book's parent
        self.assertEqual((by_name["dog"]["floor"], by_name["dog"]["margin"], by_name["animals"]["margin"]), (0.12, 0.01, 0.0))
        # A book above its floor is written; its uncalibrated parent can never be.
        self.assertEqual(ev.select_labels([("objects", 0.9), ("book", 0.5)], vocabulary), {"book": 0.5})
        self.assertEqual(ev.select_labels([("objects", 0.9), ("book", 0.1)], vocabulary), {})


class Committed(unittest.TestCase):
    """The committed label set, mapping and corpus manifest agree with each other."""

    @classmethod
    def setUpClass(cls):
        cls.labels = ev.read_label_set(ev.DEFAULT_LABELS)
        cls.mapping = ev.load_mapping(ev.DEFAULT_MAPPING, cls.labels)

    def test_the_mapping_covers_the_candidate_set(self):
        self.assertEqual(len(self.labels), 207)
        kinds = [entry["kind"] for entry in self.mapping.values()]
        self.assertEqual((kinds.count("one-to-one"), kinds.count("one-to-many"), kinds.count("none")), (29, 8, 170))

    def test_what_is_scored_and_what_is_not(self):
        mapped = ev.mapped_labels(self.labels, self.mapping)
        self.assertEqual(len(mapped), 37)
        self.assertEqual([label for label in mapped if label in {row["label"] for row in self.labels if row["tier"] == "top"}],
                         ["people", "animals", "food and drink", "transport"])
        for scene in ("indoors", "landscape", "urban", "documents and screens", "screenshot", "receipt", "events", "wedding", "sky and weather"):
            self.assertEqual(self.mapping[scene]["kind"], "none", scene)
        self.assertEqual(sum(1 for label in mapped if self.mapping[label]["fidelity"] == "proxy"), 8)

    def test_the_open_images_mapping_covers_the_candidate_set_and_says_what_is_thin(self):
        mapping = ev.load_open_images_mapping(ev.DEFAULT_OPEN_IMAGES_MAPPING, self.labels)
        kinds = [entry["kind"] for entry in mapping.values()]
        self.assertEqual((kinds.count("one-to-one"), kinds.count("one-to-many"), kinds.count("thin"), kinds.count("none")), (121, 35, 39, 12))
        mapped = ev.mapped_labels(self.labels, mapping)
        self.assertEqual(len(mapped), 156)
        # What COCO could not score at all, and what Open Images still cannot.
        for scored in ("indoors", "urban", "documents and screens", "events", "sky and weather", "screenshot", "document", "wedding", "sunset", "painting", "kitchen"):
            self.assertIn(scored, mapped)
        for thin in ("receipt", "qr code", "business card", "landscape", "fireworks", "skateboarding"):
            self.assertEqual(mapping[thin]["kind"], "thin", thin)
        for none in ("objects", "activities", "form", "book page", "person"):
            self.assertEqual(mapping[none]["kind"], "none", none)
        fidelity = [mapping[label]["fidelity"] for label in mapped]
        self.assertEqual({name: fidelity.count(name) for name in ("exact", "broader", "narrower", "proxy")}, {"exact": 131, "broader": 14, "narrower": 3, "proxy": 8})
        # A scored label could be selected for: its pool holds twice the minimum of each kind.
        for label in mapped:
            self.assertGreaterEqual(min(mapping[label]["pool"].values()), 2 * ev.MIN_PER_HALF, label)
        self.assertEqual(mapping["animals"]["negatives"], ["Animal"])
        self.assertEqual(gate.sha256_of(ev.DEFAULT_OPEN_IMAGES_MAPPING), ev.CANONICAL_OPEN_IMAGES_MAPPING_SHA256)

    def test_the_open_images_manifest_pins_its_selection_and_names_no_coco_file(self):
        manifest = gate.load_corpus_manifest(ev.DEFAULT_OPEN_IMAGES_CORPUS_MANIFEST)
        self.assertEqual((manifest["count"], manifest["side"], manifest["source"]), (25803, 256, ev.OPEN_IMAGES_SOURCE_URL))
        self.assertEqual(ev.manifest_truth(ev.DEFAULT_OPEN_IMAGES_CORPUS_MANIFEST), "open-images")
        splits = [ev.open_images_id(entry["name"])[0] for entry in manifest["files"]]
        self.assertEqual((splits.count("validation"), splits.count("test")), (6138, 19665))
        self.assertEqual(ev.manifest_annotations(ev.DEFAULT_OPEN_IMAGES_CORPUS_MANIFEST)["name"], ev.SELECTION_LABELS)
        pins = ev.manifest_open_images(ev.DEFAULT_OPEN_IMAGES_CORPUS_MANIFEST)
        self.assertEqual((pins["classes"]["name"], pins["selection"]["seed"]), ("oidv7-class-descriptions.csv", ev.DEFAULT_SEED))
        self.assertEqual([entry["name"] for entry in pins["annotationsFrom"]], ["oidv7-val-annotations-human-imagelabels.csv", "oidv7-test-annotations-human-imagelabels.csv"])
        self.assertEqual(gate.sha256_of(ev.DEFAULT_OPEN_IMAGES_CORPUS_MANIFEST), ev.CANONICAL_OPEN_IMAGES_CORPUS_MANIFEST_SHA256)
        # Each ground truth has its own canonical mapping and manifest; the other's is an override.
        judged = lambda mapping, manifest, truth: ev.evaluation_overrides(self.canonical_inputs(), ev.CANONICAL_LABELS_SHA256, mapping, manifest, "p" * 64, True, truth)
        self.assertEqual(judged(ev.CANONICAL_OPEN_IMAGES_MAPPING_SHA256, ev.CANONICAL_OPEN_IMAGES_CORPUS_MANIFEST_SHA256, "open-images"), [])
        self.assertEqual(len(judged(ev.CANONICAL_MAPPING_SHA256, ev.CANONICAL_CORPUS_MANIFEST_SHA256, "open-images")), 2)
        self.assertEqual(len(judged(ev.CANONICAL_OPEN_IMAGES_MAPPING_SHA256, ev.CANONICAL_OPEN_IMAGES_CORPUS_MANIFEST_SHA256, "coco")), 2)
        self.assertEqual(ev.verdict_line(156, 207, [], [], "bare", "Open Images"),
                         "label eval: MEASURED — 156 of 207 labels scored against Open Images; 51 are outside what this can score; prompt variant: bare")

    def test_coco_has_eighty_categories(self):
        self.assertEqual(len(ev.COCO_CATEGORIES), 80)
        self.assertEqual(len(set(ev.COCO_CATEGORIES)), 80)

    def test_the_corpus_manifest_is_val2017_with_its_annotations_pinned(self):
        manifest = gate.load_corpus_manifest(ev.DEFAULT_CORPUS_MANIFEST)
        self.assertEqual((manifest["count"], manifest["side"]), (5000, 256))
        self.assertTrue(manifest["source"].endswith("/val2017/"))
        pinned = ev.manifest_annotations(ev.DEFAULT_CORPUS_MANIFEST)
        self.assertEqual(pinned["name"], ev.ANNOTATIONS_NAME)
        self.assertEqual(len(pinned["sha256"]), 64)
        # ⚠ Not the retrieval gate's images: that corpus is from test2017.
        other = gate.load_corpus_manifest(gate.DEFAULT_CORPUS_MANIFEST)
        self.assertNotEqual(manifest["source"], other["source"])

    def test_the_canonical_digests_are_the_committed_files(self):
        # A committed file that moves without its constant makes every later run read OVERRIDDEN
        # (or, for the token rows, refuses it); this is where that is noticed.
        self.assertEqual(gate.sha256_of(ev.DEFAULT_LABELS), ev.CANONICAL_LABELS_SHA256)
        self.assertEqual(gate.sha256_of(ev.DEFAULT_MAPPING), ev.CANONICAL_MAPPING_SHA256)
        self.assertEqual(gate.sha256_of(ev.DEFAULT_CORPUS_MANIFEST), ev.CANONICAL_CORPUS_MANIFEST_SHA256)

    def test_the_committed_label_sets_token_rows_are_pinned_and_another_sets_are_not(self):
        self.assertIs(ev.admit_token_rows(ev.CANONICAL_TOKEN_ROWS_SHA256, ev.CANONICAL_LABELS_SHA256), True)
        with self.assertRaises(ev.Refused):
            ev.admit_token_rows("0" * 64, ev.CANONICAL_LABELS_SHA256)
        self.assertIs(ev.admit_token_rows("0" * 64, "1" * 64), False)

    def canonical_inputs(self):
        return {"corpus": {"pixelsSha256": "p" * 64},
                "artifacts": {ev.vectors_name(artifact, ""): {"toolchain": {"pinned": True}, "shippedComputeUnits": True} for artifact in ev.ARTIFACTS}}

    def overrides(self, inputs=None, labels=None, mapping=None, manifest=None, pixels="p" * 64, pinned=True):
        return ev.evaluation_overrides(inputs or self.canonical_inputs(), labels or ev.CANONICAL_LABELS_SHA256, mapping or ev.CANONICAL_MAPPING_SHA256,
                                       manifest or ev.CANONICAL_CORPUS_MANIFEST_SHA256, pixels, pinned)

    def test_the_canonical_evaluation_has_no_override_and_each_departure_has_one(self):
        self.assertEqual(self.overrides(), [])
        self.assertEqual(ev.verdict_line(37, 207, []), "label eval: MEASURED — 37 of 207 labels scored against COCO; 170 are outside what this can score")
        for said, departure in (("label set", {"labels": "0" * 64}), ("mapping", {"mapping": "0" * 64}), ("corpus manifest", {"manifest": "0" * 64}),
                                ("embedded pixels", {"pixels": "0" * 64}), ("`evaluate` ran outside", {"pinned": False})):
            got = self.overrides(**departure)
            self.assertEqual(len(got), 1, departure)
            self.assertIn(said, got[0])
            self.assertIn("OVERRIDDEN MEASURED", ev.verdict_line(37, 207, got))
            self.assertNotIn("label eval: MEASURED", ev.verdict_line(37, 207, got))
        # A base artifact embedded with --allow-unpinned (another onnxruntime under the pinned
        # run's file name), and Core ML off the shipped compute units.
        inputs = self.canonical_inputs()
        inputs["artifacts"]["vectors-onnx.npz"]["toolchain"]["pinned"] = False
        inputs["artifacts"]["vectors-coreml.npz"]["shippedComputeUnits"] = False
        got = self.overrides(inputs=inputs)
        self.assertEqual(len(got), 2)
        self.assertIn("onnx vectors were embedded outside the pinned toolchain", got[0])
        self.assertIn("compute units", got[1])
        del inputs["artifacts"]["vectors-fp32.npz"]["toolchain"]  # an entry that does not say is not pinned
        self.assertEqual(len(self.overrides(inputs=inputs)), 3)

    def test_a_variant_departs_from_the_pins_by_what_its_record_holds(self):
        pins = gate.pinned_versions(os.path.join(_HERE, ev.PINNED))
        on_pin = {"artifact": "onnx", "toolchain": {"python": "3.13.7", "pinned": True, **pins}}
        self.assertEqual(ev.variant_departures(on_pin, pins), [])
        # The published comparison: another onnxruntime, in an environment that embeds only.
        other = {"artifact": "onnx", "toolchain": {**on_pin["toolchain"], "pinned": False, "onnxruntime": "1.29.0", "coremltools": None}}
        self.assertEqual(ev.variant_departures(other, pins), ["coremltools None (pinned 9.0)", "onnxruntime 1.29.0 (pinned 1.20.0)"])
        # ⚠ The flag is what that `embed` believed; the versions are the evidence, either way round.
        self.assertEqual(ev.variant_departures({"artifact": "onnx", "toolchain": {**other["toolchain"], "pinned": True}}, pins), ev.variant_departures(other, pins))
        self.assertEqual(ev.variant_departures({"artifact": "onnx", "toolchain": {**on_pin["toolchain"], "pinned": False}}, pins), ["its `embed` ran with --allow-unpinned"])
        self.assertEqual(ev.variant_departures({"artifact": "onnx", "toolchain": {**on_pin["toolchain"], "python": "3.12.1"}}, pins), ["python 3.12.1 (pinned 3.13)"])
        self.assertEqual(ev.variant_departures({"artifact": "onnx"}, pins), ["records no toolchain"])
        units = {"artifact": "coreml", "toolchain": on_pin["toolchain"], "computeUnits": "cpuOnly", "shippedComputeUnits": False}
        self.assertEqual(ev.variant_departures(units, pins), ["Core ML on cpuOnly, the app ships cpuAndNeuralEngine"])

    def test_the_last_line_names_off_pin_variants_apart_from_an_override(self):
        self.assertEqual(ev.variant_tag("vectors-onnx-ort1.29.0.npz"), "onnx-ort1.29.0")
        bare = ev.verdict_line(37, 207, [])
        one = ev.verdict_line(37, 207, [], ["onnx-ort1.29.0"])
        self.assertTrue(one.startswith("label eval: MEASURED; 1 off-pin variant: onnx-ort1.29.0 — 37 of 207"), one)
        self.assertNotIn("OVERRIDDEN", one)  # the base measurement did not depart
        self.assertNotEqual(one, bare)
        two = ev.verdict_line(37, 207, ["the mapping is not the committed mapping"], ["onnx-ort1.29.0", "coreml-all"])
        self.assertTrue(two.startswith("label eval: OVERRIDDEN MEASURED; 2 off-pin variants: onnx-ort1.29.0, coreml-all"), two)

    def test_targets_are_two_decimal_precisions_each_under_its_own_key(self):
        self.assertEqual(ev.checked_targets("0.95,0.9", 0.9), [0.9, 0.95])
        self.assertEqual([ev.target_key(target) for target in ev.checked_targets("0.5,0.75,0.99", 0.75)], ["0.50", "0.75", "0.99"])
        self.assertEqual(ev.checked_targets(",".join(str(target) for target in ev.DEFAULT_TARGETS), ev.DEFAULT_TARGETS[0]), list(ev.DEFAULT_TARGETS))
        # Both of these are "0.90": two targets under one key would overwrite each other.
        self.assertEqual(ev.target_key(0.901), ev.target_key(0.904))
        for text, primary, said in (("0.901,0.904", 0.901, "two decimals"), ("0.9,0.904", 0.9, "two decimals"), ("0.905", 0.905, "two decimals"),
                                    ("0.9,0.90", 0.9, "repeats"), ("0.9,0.95", 0.92, "--primary-target"), ("0.9,0.95", 0.901, "--primary-target"),
                                    ("0.4", 0.4, "[0.5, 1)"), ("1.0", 1.0, "[0.5, 1)"), ("0.9,x", 0.9, "--targets"), ("", 0.9, "--targets")):
            with self.assertRaises(ev.Refused, msg=text) as refusal:
                ev.checked_targets(text, primary)
            self.assertIn(said, str(refusal.exception), text)

    def test_every_two_decimal_target_is_its_own_key(self):
        # What the rule rests on: for every admitted precision, key -> float gives it back, and no
        # two share a key.
        targets = [index / 100 for index in range(50, 100)]
        self.assertEqual([float(ev.target_key(target)) for target in targets], targets)
        self.assertEqual(len({ev.target_key(target) for target in targets}), 50)
        self.assertEqual(ev.checked_targets(",".join(f"{target:.2f}" for target in targets), 0.9), targets)

    def test_margins_and_variants_are_not_named_twice(self):
        self.assertEqual(ev.checked_margins("0.01,0.0,0.02"), [0.01, 0.0, 0.02])
        self.assertEqual(len({str(margin) for margin in ev.checked_margins("0.01,0.010001,0.0")}), 3)  # str(float) is exact
        for text in ("0.01,0.01", "0.0,-0.0", "0.01,0.010", "-0.01", "wide"):
            with self.assertRaises(ev.Refused, msg=text):
                ev.checked_margins(text)
        self.assertEqual(str(ev.checked_margins("-0.0")[0]), "0.0")
        self.assertEqual(ev.checked_variants(None), [])
        self.assertEqual(ev.checked_variants(["vectors-onnx-a.npz", "vectors-onnx-b.npz"]), ["vectors-onnx-a.npz", "vectors-onnx-b.npz"])
        for names in (["vectors-onnx-a.npz", "vectors-onnx-a.npz"], ["vectors-onnx.npz"], ["vectors-onnx-a.npz", "vectors-fp32.npz"]):
            with self.assertRaises(ev.Refused, msg=names):
                ev.checked_variants(names)
        # The fixed keys: distinct by construction.
        self.assertEqual(len({str(margin) for margin in ev.NEAR}), len(ev.NEAR))
        self.assertEqual(len({ev.vectors_name(artifact, tag) for artifact in ev.ARTIFACTS for tag in ("", "a", "b")}), 9)

    def test_the_toolchain_is_the_retrieval_gates(self):
        self.assertEqual(gate.pinned_versions(os.path.join(_HERE, ev.PINNED)),
                         gate.pinned_versions(os.path.join(_HERE, "requirements-retrieval-gate.txt")))

    def test_the_prompt_variants(self):
        texts = ev.prompts_for(self.labels)
        self.assertEqual(len(texts), 3 * 207)
        by = {(variant, label): text for variant, label, text in texts}
        self.assertEqual(by[("tsv", "dog")], "This is a photo of a dog.")
        self.assertEqual(by[("bare", "dog")], "dog")
        self.assertEqual(by[("uniform", "dog")], "This is a photo of dog.")
        self.assertEqual([label for variant, label, _ in texts[:207]], [row["label"] for row in self.labels])


def annotations_document(images, instances) -> dict:
    """A COCO-shaped document: `images` is `{name: (w, h)}`, `instances` `[(name, category, crowd, area)]`."""
    ids = {name: index + 1 for index, name in enumerate(sorted(images))}
    categories = {name: index + 1 for index, name in enumerate(ev.COCO_CATEGORIES)}
    return {
        "categories": [{"id": identifier, "name": name} for name, identifier in categories.items()],
        "images": [{"id": ids[name], "file_name": name, "width": size[0], "height": size[1]} for name, size in images.items()],
        "annotations": [{"image_id": ids[name], "category_id": categories[category], "iscrowd": crowd, "area": area}
                        for name, category, crowd, area in instances],
    }


class GroundTruth(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        labels = ev.read_label_set(write(self.scratch.name, "labels.tsv", LABELS))
        self.mapping = ev.load_mapping(write(self.scratch.name, "mapping.tsv", MAPPING), labels)
        document = annotations_document(
            {"a.jpg": (100, 100), "b.jpg": (100, 100), "c.jpg": (100, 100), "d.jpg": (100, 100)},
            [("a.jpg", "dog", 0, 5000.0), ("a.jpg", "person", 0, 50.0),   # a: a dog, and a person of 0.5 % of the image
             ("b.jpg", "person", 1, 4000.0),                               # b: a crowd region only
             ("c.jpg", "cat", 0, 100.0), ("c.jpg", "cat", 0, 99.0)],       # c: one cat exactly at 1 %, one under
        )
        self.annotations = ev.read_annotations(write(self.scratch.name, "instances.json", json.dumps(document)))
        self.names = ["a.jpg", "b.jpg", "c.jpg", "d.jpg"]

    def truth(self, rule):
        return ev.ground_truth(self.annotations, self.mapping, self.names, rule)

    def test_presence_is_any_instance_of_any_mapped_category(self):
        truth = self.truth("any")
        self.assertEqual(sorted(truth), ["animals", "cat", "dog", "people"])  # `indoors` is not scored
        self.assertEqual(truth["people"], [True, True, False, False])
        self.assertEqual(truth["dog"], [True, False, False, False])
        self.assertEqual(truth["animals"], [True, False, True, False])

    def test_a_crowd_region_alone_is_not_a_positive_under_no_crowd(self):
        self.assertEqual(self.truth("noCrowd")["people"], [True, False, False, False])

    def test_a_tiny_instance_alone_is_not_a_positive_under_not_tiny(self):
        truth = self.truth("notTiny")
        self.assertEqual(truth["people"], [False, True, False, False])  # a's person is 0.5 %; b's crowd is 40 %
        self.assertEqual(truth["cat"], [False, False, True, False])     # exactly 1 % counts

    def test_annotations_without_cocos_categories_are_refused(self):
        document = annotations_document({"a.jpg": (1, 1)}, [])
        document["categories"] = document["categories"][:79]
        with self.assertRaises(ev.Refused):
            ev.read_annotations(write(self.scratch.name, "short.json", json.dumps(document)))

    def test_malformed_annotations_are_refused_not_a_traceback(self):
        with self.assertRaises(ev.Refused):
            ev.read_annotations(write(self.scratch.name, "list.json", "[]"))


def corpus_manifest_text(names, annotations_path=None, pixels="0" * 64, directory=None) -> str:
    """A corpus manifest for `names`. With `directory`, the lines describe the files really there."""
    if directory:
        files = [(name, gate.sha256_of(os.path.join(directory, name)), os.path.getsize(os.path.join(directory, name))) for name in sorted(names)]
    else:
        files = [(name, hashlib.sha256(name.encode()).hexdigest(), 10) for name in sorted(names)]
    header = [gate.CORPUS_MAGIC, f"id={gate.corpus_id((name, digest) for name, digest, _ in files)}", f"count={len(files)}",
              "source=http://example.invalid/", "resize=4x4 bicubic rgb stretch", "pixels=" + pixels]
    if annotations_path:
        header += [f"annotations={os.path.basename(annotations_path)}", f"annotationsBytes={os.path.getsize(annotations_path)}",
                   f"annotationsSha256={gate.sha256_of(annotations_path)}"]
    return "\n".join(header + [""] + [f"{digest}\t{size}\t{name}" for name, digest, size in files]) + "\n"


def safetensors_bytes(tensors: dict) -> bytes:
    """A safetensors file holding one float32 per name."""
    header, body = {}, b""
    for name, value in tensors.items():
        header[name] = {"dtype": "F32", "shape": [1], "data_offsets": [len(body), len(body) + 4]}
        body += struct.pack("<f", value)
    encoded = json.dumps(header).encode("utf-8")
    return struct.pack("<Q", len(encoded)) + encoded + body


class Calibration(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)

    def file(self, data: bytes) -> str:
        path = os.path.join(self.scratch.name, "model.safetensors")
        with open(path, "wb") as handle:
            handle.write(data)
        return path

    def test_the_constants_are_read_from_the_weights(self):
        # The pinned checkpoint stores logit_scale = 4.709494; the scale is its exponential.
        scale, bias = ev.checkpoint_calibration(self.file(safetensors_bytes({"other": 9.0, "logit_scale": 4.709494, "logit_bias": -12.885268})))
        self.assertAlmostEqual(scale, ev.CHECKPOINT_CALIBRATION[0], places=3)
        self.assertAlmostEqual(bias, ev.CHECKPOINT_CALIBRATION[1], places=5)

    def test_a_file_without_them_or_not_safetensors_is_refused(self):
        for data in (safetensors_bytes({"logit_scale": 4.7}), b"\0" * 8 + b"{}", b"short", struct.pack("<Q", 2) + b"[]"):
            with self.assertRaises(ev.Refused, msg=data[:20]):
                ev.checkpoint_calibration(self.file(data))

    def test_weights_other_than_the_pinned_checkpoints_are_refused(self):
        self.file(safetensors_bytes({"logit_scale": 4.709494, "logit_bias": -12.885268}))
        with self.assertRaises(ev.Refused) as refusal:
            ev.verify_calibration(self.scratch.name)
        self.assertIn("not the pinned checkpoint's weights", str(refusal.exception))


@contextlib.contextmanager
def without(*packages):
    """Makes `import <package>` fail as it does where the package is not installed."""
    saved = {name: sys.modules.get(name, KeyError) for name in packages}
    for name in packages:
        sys.modules[name] = None
    try:
        yield
    finally:
        for name, module in saved.items():
            if module is KeyError:
                del sys.modules[name]
            else:
                sys.modules[name] = module


HEAVY = ("numpy", "PIL", "torch", "transformers", "onnxruntime", "coremltools")


class Refusals(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.dir = self.scratch.name

    def test_nothing_is_written_into_the_export_directory(self):
        export = os.path.join(self.dir, "export")
        os.makedirs(os.path.join(export, "sub"))
        for inside in (export, os.path.join(export, "sub"), os.path.join(export, "new", "deeper")):
            with self.assertRaises(ev.Refused):
                ev.refuse_inside(inside, export, "--out")
        ev.refuse_inside(os.path.join(self.dir, "export-results"), export, "--out")  # a sibling sharing the prefix

    def test_a_symlink_into_the_export_directory_is_still_inside_it(self):
        export = os.path.join(self.dir, "export")
        os.makedirs(export)
        os.symlink(export, os.path.join(self.dir, "elsewhere"))
        with self.assertRaises(ev.Refused):
            ev.refuse_inside(os.path.join(self.dir, "elsewhere", "run"), export, "--out")

    def test_embed_refuses_an_out_inside_the_export_before_anything_else(self):
        export = os.path.join(self.dir, "export")
        os.makedirs(export)
        with contextlib.redirect_stderr(io.StringIO()) as stderr:
            status = ev.main(["embed", "--export", export, "--out", os.path.join(export, "run")])
        self.assertEqual(status, 2)
        self.assertIn("inside the export directory", stderr.getvalue())
        self.assertEqual(os.listdir(export), [])

    def test_the_refusals_need_no_package(self):
        # Whatever this interpreter has installed: the two commands refuse as documented with
        # every heavy import failing, and never reach one.
        export = os.path.join(self.dir, "export")
        os.makedirs(export)
        with without(*HEAVY), contextlib.redirect_stderr(io.StringIO()) as stderr:
            embed = ev.main(["embed", "--export", export, "--out", os.path.join(export, "run"), "--allow-unpinned"])
            evaluate = ev.main(["evaluate", "--run", self.dir, "--annotations", os.path.join(self.dir, "none.json"), "--allow-unpinned"])
            bad_artifacts = ev.main(["embed", "--out", os.path.join(self.dir, "run"), "--artifacts", "fp64", "--allow-unpinned"])
        self.assertEqual((embed, evaluate, bad_artifacts), (2, 2, 2))
        self.assertIn("inside the export directory", stderr.getvalue())
        self.assertIn(ev.INPUTS_NAME, stderr.getvalue())
        self.assertIn("--artifacts takes", stderr.getvalue())
        self.assertNotIn("not installed", stderr.getvalue())
        self.assertFalse(os.path.exists(os.path.join(self.dir, "run")))

    def test_a_missing_package_is_a_refusal_not_a_traceback(self):
        def needs_numpy():
            with without("numpy"):
                import numpy  # noqa: F401
            return 0

        with contextlib.redirect_stderr(io.StringIO()) as stderr:
            self.assertEqual(ev.exit_status(needs_numpy), 2)
        self.assertIn("is not installed", stderr.getvalue())
        self.assertIn(ev.PINNED, stderr.getvalue())

    def test_a_manifest_without_its_annotations_pin_is_refused(self):
        path = write(self.dir, "corpus.txt", corpus_manifest_text(["a.jpg"]))
        gate.load_corpus_manifest(path)  # a valid image manifest
        with self.assertRaises(ev.Refused):
            ev.manifest_annotations(path)

    def test_other_annotations_than_the_pinned_ones_are_refused(self):
        annotations = write(self.dir, "instances_val2017.json", "{}")
        pinned = ev.manifest_annotations(write(self.dir, "corpus.txt", corpus_manifest_text(["a.jpg"], annotations)))
        ev.verify_annotations(annotations, pinned)
        with self.assertRaises(ev.Refused):
            ev.verify_annotations(write(self.dir, "other.json", "[]"), pinned)       # same size, other bytes
        with self.assertRaises(ev.Refused):
            ev.verify_annotations(write(self.dir, "longer.json", "{   }"), pinned)
        with self.assertRaises(ev.Refused):
            ev.verify_annotations(os.path.join(self.dir, "absent.json"), pinned)

    def test_a_directory_that_is_not_an_embed_output_is_refused(self):
        with self.assertRaises(ev.Refused):
            ev.read_inputs(self.dir)
        write(self.dir, ev.INPUTS_NAME, json.dumps({"labels": {}}))
        with self.assertRaises(ev.Refused):
            ev.read_inputs(self.dir)

    def test_evaluate_exits_2_on_a_refusal_and_says_why(self):
        with contextlib.redirect_stderr(io.StringIO()) as stderr:
            status = ev.main(["evaluate", "--run", self.dir, "--annotations", os.path.join(self.dir, "none.json"), "--allow-unpinned"])
        self.assertEqual(status, 2)
        self.assertIn("label eval: REFUSED", stderr.getvalue())
        self.assertIn(ev.INPUTS_NAME, stderr.getvalue())

    def test_an_unreadable_input_is_a_refusal_not_a_failure(self):
        def missing():
            raise FileNotFoundError("gone")

        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(ev.exit_status(missing), 2)
            self.assertEqual(ev.exit_status(lambda: 0), 0)

    def test_the_toolchain_check_names_this_scripts_requirements_and_restores_the_gates(self):
        try:
            ev.check_toolchain(False)
        except ev.Refused as refusal:  # the usual outcome under a bare interpreter
            self.assertIn(ev.PINNED, str(refusal))
        self.assertEqual(gate.PINNED, "requirements-retrieval-gate.txt")


def tree(directory: str) -> dict:
    """`{relative path: sha256}` of every file under `directory`: what "byte-identical" compares."""
    found = {}
    for root, _, names in os.walk(directory):
        for name in names:
            path = os.path.join(root, name)
            found[os.path.relpath(path, directory)] = gate.sha256_of(path)
    return found


def run_main(*argv) -> tuple:
    """`(status, stderr)` of one command line. ⚠ An exception is NOT caught: a traceback where a
    refusal belongs fails the test that called this."""
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()) as stderr:
        status = ev.main(list(argv))
    return status, stderr.getvalue()


HUGE = str(2 ** 70)


class EveryArgumentsDomain(unittest.TestCase):
    """Every argparse argument of every command, with values outside what the code after it needs.

    Each row is one command line that is valid but for one argument. It must give status 2 with no
    traceback (`run_main` lets an exception through) and write nothing, with every heavy package
    blocked and with whatever this interpreter has, and the two must give the same refusal.
    `ARGUMENTS` below is the list the rows are checked against: a new argument without rows fails.
    """

    # command -> every option argparse defines for it.
    ARGUMENTS = {
        "embed": ["--labels", "--corpus-manifest", "--allow-unpinned", "--export", "--source", "--onnx-vision", "--onnx-text", "--corpus", "--out",
                  "--artifacts", "--compute-units", "--reuse-inputs", "--tag", "--threads"],
        "evaluate": ["--labels", "--corpus-manifest", "--allow-unpinned", "--run", "--annotations", "--mapping", "--export", "--source", "--out",
                     "--seed", "--targets", "--primary-target", "--margins", "--bootstrap", "--variant", "--prompt", "--truth", "--classes", "--per-label", "--selection"],
        "manifest": ["--corpus", "--out", "--side", "--source-url", "--allow-unpinned", "--truth", "--image-list", "--selection"],
        "select": ["--labels", "--mapping", "--classes", "--annotations", "--image-info", "--seed", "--per-label", "--out"],
        "survey": ["--labels", "--corpus-manifest", "--allow-unpinned", "--run", "--floors", "--export", "--out", "--smallest-count"],
    }
    # A flag has no value to be out of domain; it is exercised by every row (always passed).
    FLAGS = {"--allow-unpinned"}
    # `select` reads CSV files with the standard library and has no toolchain to be off.
    UNPINNABLE = {"select"}

    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.dir = self.scratch.name
        d = self.dir
        self.labels = write(d, "labels.tsv", LABELS)
        self.mapping = write(d, "mapping.tsv", MAPPING)
        names = [f"{index:03d}.jpg" for index in range(8)]
        instances = [(name, "person", 0, 5000.0) for index, name in enumerate(names) if index % 2 == 0]
        instances += [(name, category, 0, 5000.0) for index, name in enumerate(names) for category in (("dog",) if index % 4 == 0 else ("cat",) if index % 4 == 1 else ())]
        self.corpus = os.path.join(d, "corpus")
        os.makedirs(self.corpus)
        self.annotations = write(self.corpus, ev.ANNOTATIONS_NAME, json.dumps(annotations_document({name: (4, 4) for name in names}, instances)))
        self.manifest = write(d, "corpus.txt", corpus_manifest_text(names, self.annotations))
        # A run directory whose record is whole. Its vector files are not vectors: every row is
        # refused before one is opened, which is the point.
        self.run = os.path.join(d, "run")
        os.makedirs(self.run)
        artifacts = {}
        for name in [ev.vectors_name(artifact, "") for artifact in ev.ARTIFACTS] + ["vectors-onnx-x.npz"]:
            write(self.run, name, "not vectors")
            artifacts[name] = {"artifact": name.split("-")[1].split(".")[0], "sha256": gate.sha256_of(os.path.join(self.run, name))}
        write(self.run, ev.INPUTS_NAME, json.dumps({
            "labels": {"sha256": gate.sha256_of(self.labels)},
            "corpus": {"id": gate.load_corpus_manifest(self.manifest)["id"], "count": 8, "manifestSha256": gate.sha256_of(self.manifest), "pixelsSha256": "0" * 64},
            "texts": {"tokenRowsSha256": "0" * 64}, "artifacts": artifacts}))
        # The same run over a manifest that says it has no truth: what `survey` takes.
        self.library = write(d, "library.txt", corpus_manifest_text(names).replace("pixels=", "truth=none\npixels="))
        self.survey_run = os.path.join(d, "survey-run")
        os.makedirs(self.survey_run)
        for name in artifacts:
            write(self.survey_run, name, "not vectors")
        write(self.survey_run, ev.INPUTS_NAME, json.dumps({
            "labels": {"sha256": gate.sha256_of(self.labels)},
            "corpus": {"id": gate.load_corpus_manifest(self.library)["id"], "count": 8, "manifestSha256": gate.sha256_of(self.library), "pixelsSha256": "0" * 64},
            "texts": {"tokenRowsSha256": "0" * 64}, "artifacts": artifacts}))
        for name in ("export", "source", "reuse", "a-directory"):
            os.makedirs(os.path.join(d, name))
        self.onnx = [write(d, "vision.onnx", "v"), write(d, "text.onnx", "t")]
        self.absent = os.path.join(d, "absent")
        self.image_list = write(d, "images.txt", "".join(name + "\n" for name in names))
        self.missing_list = write(d, "missing-images.txt", "000.jpg\nnot-there.jpg\n")
        # The Open Images inputs of `select`, in a directory of their own; one file for both splits.
        self.oi = os.path.join(d, "open-images")
        os.makedirs(self.oi)
        official = oi_files(self.oi)
        for name, paths in (("all-labels.csv", official["annotations"]), ("all-info.csv", official["info"])):
            lines = []
            for index, path in enumerate(paths):
                with open(path, encoding="utf-8") as handle:
                    lines += handle.read().split("\n")[1 if index else 0:-1]
            write(self.oi, name, "\n".join(lines) + "\n")
        self.oi_mapping = write(self.oi, "mapping.tsv", oi_mapping())
        self.oi_classes = official["classes"]
        self.base = {
            "embed": {"--out": os.path.join(d, "out"), "--labels": self.labels, "--corpus-manifest": self.manifest, "--export": os.path.join(d, "export"),
                      "--source": os.path.join(d, "source"), "--corpus": self.corpus, "--onnx-vision": self.onnx[0], "--onnx-text": self.onnx[1]},
            "evaluate": {"--run": self.run, "--annotations": self.annotations, "--labels": self.labels, "--mapping": self.mapping,
                         "--corpus-manifest": self.manifest, "--targets": "0.99", "--primary-target": "0.99"},
            "manifest": {"--corpus": self.corpus, "--out": os.path.join(d, "new-manifest.txt")},
            # ⚠ The corpus has eight images: under the default --smallest-count every row would be
            # refused for that, whatever its own argument held.
            "survey": {"--run": self.survey_run, "--labels": self.labels, "--corpus-manifest": self.library, "--smallest-count": "2"},
            "select": {"--labels": self.labels, "--mapping": self.oi_mapping, "--classes": self.oi_classes, "--annotations": os.path.join(self.oi, "all-labels.csv"),
                       "--image-info": os.path.join(self.oi, "all-info.csv"), "--out": os.path.join(d, "selection")},
        }

    def rows(self):
        d, absent, directory = self.dir, self.absent, os.path.join(self.dir, "a-directory")
        long = "x" * 300
        return {
            "embed": [
                ("--labels", absent, "could not be read"), ("--labels", directory, "could not be read"), ("--labels", "", "could not be read"),
                ("--labels", self.mapping, "not a 'tier<TAB>parent<TAB>label<TAB>prompt' row"),
                ("--corpus-manifest", absent, "could not be read"), ("--corpus-manifest", self.labels, "does not open with"), ("--corpus-manifest", "", "could not be read"),
                ("--export", absent, "--export"), ("--export", self.labels, "--export"), ("--export", "", "needs --export"),
                ("--source", absent, "--source"), ("--source", "", "needs --source"),
                ("--onnx-vision", absent, "--onnx-vision"), ("--onnx-vision", directory, "--onnx-vision"), ("--onnx-vision", "", "needs --onnx-vision"),
                ("--onnx-text", absent, "--onnx-text"), ("--onnx-text", "", "needs --onnx-text"),
                ("--corpus", absent, "--corpus"), ("--corpus", self.labels, "--corpus"), ("--corpus", "", "needs --corpus"),
                ("--out", "", "--out is empty"), ("--out", "  ", "--out is empty"), ("--out", self.labels, "is not a directory"),
                ("--out", os.path.join(d, "export", "run"), "inside the export directory"),
                ("--artifacts", "", "--artifacts takes"), ("--artifacts", "fp64", "--artifacts takes"), ("--artifacts", "onnx,", "--artifacts takes"), ("--artifacts", ",", "--artifacts takes"),
                ("--compute-units", "gpu", "ARGPARSE"), ("--compute-units", "", "ARGPARSE"),
                ("--reuse-inputs", absent, "--reuse-inputs"), ("--reuse-inputs", os.path.join(d, "reuse"), "not an `embed` output"),
                ("--tag", "a/b", "--tag"), ("--tag", "..", "--tag"), ("--tag", "ort 1", "--tag"), ("--tag", long, "--tag"), ("--tag", "é", "--tag"),
                ("--threads", "0", "--threads is between"), ("--threads", "-4", "--threads is between"), ("--threads", HUGE, "--threads is between"),
                ("--threads", str(ev.MAX_THREADS + 1), "--threads is between"), ("--threads", "1.5", "ARGPARSE"), ("--threads", "nan", "ARGPARSE"), ("--threads", "", "ARGPARSE"),
            ],
            "evaluate": [
                ("--labels", absent, "could not be read"), ("--labels", directory, "could not be read"), ("--labels", self.mapping, "not a 'tier<TAB>parent<TAB>label<TAB>prompt' row"),
                ("--corpus-manifest", absent, "could not be read"), ("--corpus-manifest", self.labels, "does not open with"),
                ("--run", "", "--run is empty"), ("--run", absent, "not an `embed` output"), ("--run", self.labels, "is not a directory"),
                ("--annotations", "", "--annotations is empty"), ("--annotations", absent, "no annotations file"), ("--annotations", directory, "no annotations file"),
                ("--annotations", self.labels, "bytes, the corpus manifest says"),
                ("--mapping", absent, "could not be read"), ("--mapping", self.labels, "does not open with"), ("--mapping", "", "could not be read"),
                ("--export", self.dir, "inside the export directory"),
                ("--source", absent, "could not be read"), ("--source", directory, "could not be read"),
                ("--out", self.labels, "is not a directory"),
                ("--seed", "-1", "--seed is at least 0"), ("--seed", "-" + HUGE, "--seed is at least 0"), ("--seed", "1.5", "ARGPARSE"), ("--seed", "nan", "ARGPARSE"), ("--seed", "", "ARGPARSE"),
                ("--targets", "nan", "--targets"), ("--targets", "inf", "--targets"), ("--targets", "-inf", "--targets"), ("--targets", "", "--targets"),
                ("--targets", "0.99,", "--targets"), ("--targets", "abc", "--targets"), ("--targets", "0.991", "two decimals"), ("--targets", "0.4,0.99", "[0.5, 1)"),
                ("--targets", "1,0.99", "[0.5, 1)"), ("--targets", "1e400,0.99", "--targets"), ("--targets", "0.99,0.990", "repeats"), ("--targets", "0.98", "--primary-target"),
                ("--primary-target", "nan", "--primary-target"), ("--primary-target", "inf", "--primary-target"), ("--primary-target", "-inf", "--primary-target"),
                ("--primary-target", "0.9", "--primary-target"), ("--primary-target", "x", "ARGPARSE"), ("--primary-target", "", "ARGPARSE"),
                ("--margins", "nan", "--margins"), ("--margins", "inf", "--margins"), ("--margins", "-inf", "--margins"), ("--margins", "", "--margins"),
                ("--margins", "-0.01", "not negative"), ("--margins", "wide", "--margins"), ("--margins", "0.01,0.01", "repeats"), ("--margins", "0.0,-0.0", "repeats"),
                ("--margins", "1e400", "--margins"),
                ("--bootstrap", "0", "--bootstrap is between"), ("--bootstrap", "-5", "--bootstrap is between"), ("--bootstrap", HUGE, "--bootstrap is between"),
                ("--bootstrap", str(ev.MAX_BOOTSTRAP + 1), "--bootstrap is between"), ("--bootstrap", "1e3", "ARGPARSE"), ("--bootstrap", "nan", "ARGPARSE"),
                ("--variant", "", "not a vector file"), ("--variant", "vectors-onnx-absent.npz", "not a vector file"), ("--variant", "../labels.tsv", "not a vector file"),
                ("--variant", "vectors-onnx.npz", "tagged vector file"), (("--variant", "--variant"), ("vectors-onnx-x.npz", "vectors-onnx-x.npz"), "twice"),
                ("--prompt", "", "--prompt is one of"), ("--prompt", "TSV", "--prompt is one of"), ("--prompt", "bare,tsv", "--prompt is one of"), ("--prompt", long, "--prompt is one of"),
                ("--truth", "", "--truth is one of"), ("--truth", "none", "--truth is one of"), ("--truth", "Open Images", "--truth is one of"), ("--truth", long, "--truth is one of"),
                ("--truth", "open-images", "reads the class descriptions from --classes"),       # no class descriptions
                ("--classes", self.oi_classes, "and only it does"),                              # class descriptions, and COCO's truth
                (("--truth", "--classes"), ("open-images", absent), "from --selection"),         # class descriptions, and no selection record
                (("--truth", "--classes", "--selection"), ("open-images", absent, self.labels), "says truth=coco"),   # the manifest decides nothing, and may contradict
                (("--truth", "--classes"), ("open-images", ""), "reads the class descriptions from --classes"),
                ("--per-label", "0", "--per-label is between"), ("--per-label", "-3", "--per-label is between"), ("--per-label", HUGE, "--per-label is between"),
                ("--per-label", "1.5", "ARGPARSE"), ("--per-label", "nan", "ARGPARSE"), ("--per-label", "100", "the size of an Open Images label's sample"),
                ("--selection", self.labels, "from --selection"),                                # a selection record, and COCO's truth
                (("--truth", "--classes", "--selection"), ("open-images", self.oi_classes, ""), "from --selection"),
                (("--truth", "--classes", "--selection"), ("open-images", self.oi_classes, absent), "says truth=coco"),
            ],
            "manifest": [
                ("--corpus", "", "--corpus is empty"), ("--corpus", absent, "is not a directory"), ("--corpus", self.labels, "is not a directory"),
                ("--corpus", os.path.join(d, "a-directory"), "has no " + ev.ANNOTATIONS_NAME),
                ("--out", "", "--out is empty"), ("--out", d, "is a directory"), ("--out", os.path.join(absent, "m.txt"), "does not exist"),
                ("--side", "0", "--side is between"), ("--side", "-256", "--side is between"), ("--side", HUGE, "--side is between"),
                ("--side", str(ev.MAX_SIDE + 1), "--side is between"), ("--side", "256.0", "ARGPARSE"), ("--side", "nan", "ARGPARSE"),
                ("--source-url", "", "--source-url"), ("--source-url", "ftp://x/", "--source-url"), ("--source-url", "http://x/a b/", "--source-url"),
                ("--source-url", "http://x/\nid=0/", "--source-url"), ("--source-url", "http://x/val2017", "--source-url"), ("--source-url", "not-published", "--source-url"),
                ("--truth", "", "--truth is one of"), ("--truth", "open", "--truth is one of"), ("--truth", long, "--truth is one of"),
                ("--truth", "none", "takes its images from --image-list"),                      # no list
                ("--image-list", self.image_list, "takes its images from --image-list"),        # a list, and COCO's truth
                (("--truth", "--image-list"), ("none", absent), "could not be read"), (("--truth", "--image-list"), ("none", directory), "could not be read"),
                (("--truth", "--image-list"), ("none", ""), "takes its images from --image-list"),
                (("--truth", "--image-list"), ("none", self.missing_list), "is missing"),
                (("--truth", "--image-list"), ("none", self.mapping), "not a list of distinct file names"),   # tabs
                ("--truth", "open-images", "from --selection"),                                 # no selection
                ("--selection", directory, "from --selection"),                                 # a selection, and COCO's truth
                (("--truth", "--selection"), ("open-images", directory), "has no selection.json"), (("--truth", "--selection"), ("open-images", absent), "has no selection.json"),
                (("--truth", "--selection"), ("open-images", ""), "from --selection"),
                (("--truth", "--selection", "--image-list"), ("open-images", directory, self.image_list), "takes its images from --image-list"),
            ],
            "select": [
                ("--labels", absent, "could not be read"), ("--labels", directory, "could not be read"), ("--labels", self.mapping, "not a 'tier<TAB>parent<TAB>label<TAB>prompt' row"),
                ("--mapping", absent, "could not be read"), ("--mapping", "", "could not be read"), ("--mapping", self.mapping, "does not open with"),
                ("--classes", absent, "could not be read"), ("--classes", "", "--classes is empty"), ("--classes", self.labels, "does not open with the header"),
                ("--annotations", absent, "could not be read"), ("--annotations", "", "--annotations is empty"), ("--annotations", self.oi_classes, "does not open with the header"),
                (("--annotations", "--annotations"), (os.path.join(self.oi, "all-labels.csv"),) * 2, "twice"),
                ("--image-info", absent, "could not be read"), ("--image-info", "", "--image-info is empty"), ("--image-info", self.oi_classes, "image information header"),
                (("--image-info", "--image-info"), (os.path.join(self.oi, "all-info.csv"),) * 2, "twice"),
                ("--seed", "-1", "--seed is at least 0"), ("--seed", "-" + HUGE, "--seed is at least 0"), ("--seed", "1.5", "ARGPARSE"), ("--seed", "nan", "ARGPARSE"),
                ("--per-label", "0", "--per-label is between"), ("--per-label", "-3", "--per-label is between"), ("--per-label", HUGE, "--per-label is between"),
                ("--per-label", "2.5", "ARGPARSE"), ("--per-label", "nan", "ARGPARSE"), ("--per-label", "5", "fewer than 20 verified positives or negatives"),
                ("--out", "", "--out is empty"), ("--out", self.labels, "is not a directory"), ("--out", d, "holds something other than an earlier selection"),
            ],
            "survey": [
                ("--labels", absent, "could not be read"), ("--labels", directory, "could not be read"), ("--labels", self.mapping, "not a 'tier<TAB>parent<TAB>label<TAB>prompt' row"),
                ("--corpus-manifest", absent, "could not be read"), ("--corpus-manifest", self.labels, "does not open with"),
                ("--run", "", "--run is empty"), ("--run", absent, "not an `embed` output"), ("--run", self.labels, "is not a directory"),
                ("--floors", absent, "could not be read"), ("--floors", directory, "could not be read"), ("--floors", "", "--floors is empty"),
                ("--floors", self.labels, "a floors file is malformed"),                       # not JSON
                ("--floors", os.path.join(self.run, ev.INPUTS_NAME), "is not a 'label-eval"),  # JSON, and not a result
                (("--run", "--corpus-manifest"), (self.run, self.manifest), "says truth=coco"),   # an annotated corpus is `evaluate`'s
                (("--floors", "--floors"), (self.labels, self.labels), "twice"),
                ("--export", self.dir, "inside the export directory"),
                ("--out", self.labels, "is not a directory"),
                ("--smallest-count", "0", "--smallest-count is between"), ("--smallest-count", "-10", "--smallest-count is between"),
                ("--smallest-count", HUGE, "--smallest-count is between"), ("--smallest-count", "5", "fewer than twice --smallest-count"),
                ("--smallest-count", "2.5", "ARGPARSE"), ("--smallest-count", "nan", "ARGPARSE"), ("--smallest-count", "", "ARGPARSE"),
            ],
        }

    def attempt(self, command, flags, values):
        arguments = dict(self.base[command])
        argv = [command] + ([] if command in self.UNPINNABLE else ["--allow-unpinned"])
        for flag, value in zip(flags, values):
            arguments.pop(flag, None)
            # `--targets -inf` is an unknown option to argparse; `--targets=-inf` is a value.
            argv += [f"{flag}={value}"] if value.startswith("-") and not value.lstrip("-")[:1].isdigit() else [flag, value]
        for flag, value in arguments.items():
            argv += [flag, value]
        try:
            with without(*HEAVY):
                blocked = run_main(*argv)
            installed = run_main(*argv)
        except SystemExit as stopped:  # argparse's own refusal of a value: status 2, a usage line
            return stopped.code, "ARGPARSE"
        self.assertEqual(blocked, installed, argv)
        self.assertNotIn("not installed", blocked[1], argv)
        return blocked

    def test_an_out_of_domain_value_for_any_argument_is_refused_and_writes_nothing(self):
        before = tree(self.dir)
        tried = 0
        for command, rows in self.rows().items():
            for flag, value, said in rows:
                flags, values = (flag, value) if isinstance(flag, tuple) else ((flag,), (value,))
                with contextlib.redirect_stderr(io.StringIO()):  # argparse prints its usage itself
                    status, stderr = self.attempt(command, flags, values)
                self.assertEqual(status, 2, (command, flag, value, stderr))
                self.assertIn(said, stderr, (command, flag, value))
                self.assertEqual(tree(self.dir), before, (command, flag, value))
                tried += 1
        self.assertGreater(tried, 150)
        self.assertEqual(sorted(os.listdir(self.dir)), ["a-directory", "corpus", "corpus.txt", "export", "images.txt", "labels.tsv", "library.txt", "mapping.tsv", "missing-images.txt",
                                                        "open-images", "reuse", "run", "source", "survey-run", "text.onnx", "vision.onnx"])

    def test_every_argument_argparse_defines_has_rows(self):
        rows = self.rows()
        for command, arguments in self.ARGUMENTS.items():
            covered = {name for flag, _, _ in rows[command] for name in (flag if isinstance(flag, tuple) else (flag,))}
            self.assertEqual(covered | (set() if command in self.UNPINNABLE else self.FLAGS), set(arguments), command)
        # …and ARGUMENTS is what the parser really defines.
        for command, sub in ev.build_parser()[1].items():
            defined = {option for action in sub._actions for option in action.option_strings} - {"-h", "--help"}
            self.assertEqual(defined, set(self.ARGUMENTS[command]), command)

    def test_every_float_and_integer_argument_has_nan_inf_and_a_huge_value(self):
        rows = self.rows()
        values = lambda command, flag: {value for name, value, _ in rows[command] if name == flag}
        for flag in ("--targets", "--primary-target", "--margins"):
            self.assertTrue({"nan", "inf", "-inf"} <= values("evaluate", flag), flag)
        self.assertIn(HUGE, values("evaluate", "--bootstrap"))
        self.assertIn(HUGE, values("manifest", "--side"))
        self.assertIn(HUGE, values("embed", "--threads"))
        self.assertIn(HUGE, values("survey", "--smallest-count"))
        self.assertIn(HUGE, values("select", "--per-label"))
        self.assertIn(HUGE, values("evaluate", "--per-label"))
        self.assertIn("-" + HUGE, values("select", "--seed"))
        self.assertIn("-" + HUGE, values("evaluate", "--seed"))  # a huge POSITIVE seed is in the domain: see EvaluateEndToEnd


class EveryArgumentCombination(unittest.TestCase):
    """argparse makes most paths optional. Every combination it accepts either runs or is refused
    with status 2; none reaches a path call as `None`, and none writes.

    Nothing here can run to the end (there is no model), so every combination must refuse, and
    for a reason of its own: never because a package is missing. Each is tried twice, with no
    package importable (the mode this file supports) and with whatever this interpreter has, since
    a `None` that slips past the checks is only reached where the imports succeed.
    """

    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.dir = self.scratch.name
        self.labels = write(self.dir, "labels.tsv", LABELS)
        self.manifest = write(self.dir, "corpus.txt", corpus_manifest_text(["a.jpg", "b.jpg"]))
        self.inputs = os.path.join(self.dir, "inputs")
        os.makedirs(self.inputs)
        for name in ("export", "source", "corpus", "reuse"):
            os.makedirs(os.path.join(self.inputs, name))
        for name in ("vision.onnx", "text.onnx"):
            write(self.inputs, name, "not a model")
        self.present = {
            "--export": os.path.join(self.inputs, "export"), "--source": os.path.join(self.inputs, "source"),
            "--corpus": os.path.join(self.inputs, "corpus"), "--reuse-inputs": os.path.join(self.inputs, "reuse"),
            "--onnx-vision": os.path.join(self.inputs, "vision.onnx"), "--onnx-text": os.path.join(self.inputs, "text.onnx"),
        }
        self.out = os.path.join(self.dir, "out")
        self.before = tree(self.inputs)

    def embed(self, artifacts, flags: dict) -> tuple:
        argv = ["embed", "--out", self.out, "--artifacts", ",".join(artifacts), "--labels", self.labels, "--corpus-manifest", self.manifest, "--allow-unpinned"]
        for flag, value in flags.items():
            argv += [flag, value]
        with without(*HEAVY):
            blocked = run_main(*argv)
        installed = run_main(*argv)
        self.assertEqual(blocked, installed, argv)  # the same refusal, whatever is installed
        self.assertNotIn("not installed", blocked[1], argv)
        return blocked

    def subsets(self, items):
        items = list(items)
        return [[item for bit, item in enumerate(items) if mask >> bit & 1] for mask in range(1 << len(items))]

    def test_embed_refuses_every_combination_of_absent_and_present_paths(self):
        tried = 0
        for artifacts in self.subsets(ev.ARTIFACTS)[1:]:
            for flags in self.subsets(self.present):
                status, stderr = self.embed(artifacts, {flag: self.present[flag] for flag in flags})
                self.assertEqual(status, 2, (artifacts, flags, stderr))
                self.assertIn("label eval: REFUSED", stderr)
                self.assertFalse(os.path.exists(self.out), (artifacts, flags))
                tried += 1
        self.assertEqual(tried, 7 * 64)
        self.assertEqual(tree(self.inputs), self.before)
        self.assertEqual(sorted(os.listdir(self.inputs)), ["corpus", "export", "reuse", "source", "text.onnx", "vision.onnx"])

    def test_embed_names_the_flags_a_combination_lacks(self):
        # `--source` with no `--corpus` is refused by name, never reaching `verify_corpus(None, …)`.
        status, stderr = self.embed(["onnx"], {"--source": self.present["--source"], "--onnx-vision": self.present["--onnx-vision"],
                                               "--onnx-text": self.present["--onnx-text"]})
        self.assertEqual(status, 2)
        self.assertIn("needs --corpus", stderr)
        status, stderr = self.embed(ev.ARTIFACTS, {})
        self.assertIn("needs --corpus, --export, --onnx-text, --onnx-vision, --source", stderr)
        status, stderr = self.embed(["onnx"], {"--reuse-inputs": self.present["--reuse-inputs"]})
        self.assertIn("needs --onnx-text, --onnx-vision", stderr)

    def test_embed_refuses_a_path_that_is_not_there_and_does_not_create_it(self):
        # `gate.verify_corpus` makes its directory (for --fetch); a mistyped --corpus must not.
        for flag in self.present:
            absent = os.path.join(self.dir, "absent" + flag)
            for artifacts in self.subsets(ev.ARTIFACTS)[1:]:
                status, stderr = self.embed(artifacts, {**self.present, flag: absent})
                self.assertEqual(status, 2, (flag, artifacts, stderr))
                self.assertFalse(os.path.lexists(absent), flag)
        self.assertFalse(os.path.exists(self.out))
        self.assertEqual(tree(self.inputs), self.before)

    def test_embed_refuses_a_tag_that_is_not_a_file_name_part_and_an_out_that_is_a_file(self):
        for tag in ("../escape", "a/b", "_x", "ort 1.29", "."):
            status, stderr = self.embed(["onnx"], {**self.present, "--tag": tag})
            self.assertEqual(status, 2, tag)
            self.assertIn("--tag", stderr)
        self.out = self.labels  # an existing file
        status, stderr = self.embed(["onnx"], self.present)
        self.assertEqual(status, 2)
        self.assertIn("is not a directory", stderr)

    def test_manifest_refuses_every_wrong_argument_and_leaves_an_existing_manifest_alone(self):
        existing = write(self.dir, "existing.txt", "an earlier manifest\n")
        corpus = self.present["--corpus"]
        cases = [
            (["--corpus", os.path.join(self.dir, "absent"), "--out", existing], "is not a directory"),
            (["--corpus", corpus, "--out", existing], "has no " + ev.ANNOTATIONS_NAME),           # no annotations
            (["--corpus", corpus, "--out", existing, "--side", "0"], "--side"),
            (["--corpus", corpus, "--out", self.dir], "is a directory"),
            (["--corpus", corpus, "--out", os.path.join(self.dir, "no", "such", "m.txt")], "does not exist"),
        ]
        for argv, said in cases:
            with without(*HEAVY):
                status, stderr = run_main("manifest", "--allow-unpinned", *argv)
            self.assertEqual(status, 2, (argv, stderr))
            self.assertIn(said, stderr)
        # Annotations that name an image the directory lacks, and one outside it.
        for names in (["missing.jpg"], ["../labels.tsv"]):
            write(corpus, ev.ANNOTATIONS_NAME, json.dumps(annotations_document({name: (4, 4) for name in names}, [])))
            with without(*HEAVY):
                status, stderr = run_main("manifest", "--allow-unpinned", "--corpus", corpus, "--out", existing)
            self.assertEqual(status, 2, (names, stderr))
        with open(existing, encoding="utf-8") as handle:
            self.assertEqual(handle.read(), "an earlier manifest\n")
        self.assertEqual(sorted(os.listdir(self.dir)), ["corpus.txt", "existing.txt", "inputs", "labels.tsv"])


@unittest.skipIf(numpy is None, "needs numpy")
class EmbedWrites(unittest.TestCase):
    """`embed` validates everything, the output directory included, before it replaces a file; a
    refused run leaves an earlier run byte-identical. The model is a stand-in; the files are real."""

    SIDE, IMAGES = 4, ("a.png", "b.png")

    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.dir = self.scratch.name
        self.labels = write(self.dir, "labels.tsv", LABELS)
        self.texts = 3 * 5
        self.onnx = [write(self.dir, "vision.onnx", "vision"), write(self.dir, "text.onnx", "text")]
        published = dict(gate.PUBLISHED_ONNX)
        gate.PUBLISHED_ONNX.update(vision=gate.sha256_of(self.onnx[0]), text=gate.sha256_of(self.onnx[1]))
        self.addCleanup(gate.PUBLISHED_ONNX.update, published)
        self.real = (gate.embed_onnx, gate.tokenise, gate.YARDSTICK_SOURCE)
        self.addCleanup(lambda: [setattr(gate, name, value) for name, value in zip(("embed_onnx", "tokenise", "YARDSTICK_SOURCE"), self.real)])
        gate.embed_onnx = self.embed_onnx
        gate.tokenise = self.tokenise
        self.token_base, self.fail_with, self.seen, self.tokenised, self.threads = 7, None, [], [], []
        # A stand-in for the pinned checkpoint: the five files the pin names, a tokenizer vocabulary
        # beside them, and the pin moved onto them for the length of the test.
        self.source = os.path.join(self.dir, "checkpoint")
        os.makedirs(self.source)
        for name in self.real[2]["files"]:
            write(self.source, name, json.dumps({"size": {"height": self.SIDE}, "file": name}))
        write(self.source, "spiece.model", "a vocabulary")
        gate.YARDSTICK_SOURCE = {"revision": "r" * 40, "files": {name: gate.sha256_of(os.path.join(self.source, name)) for name in self.real[2]["files"]}}
        # An earlier run to reuse inputs from: two 4x4 images of resized pixels, and token rows.
        self.pixels = bytes(range(96))
        self.manifest = write(self.dir, "corpus.txt", corpus_manifest_text(self.IMAGES, pixels=hashlib.sha256(self.pixels).hexdigest()))
        self.run = self.make_run("run", self.labels)

    def rows(self):
        return [[self.token_base + index, 1, 1] for index in range(self.texts)]

    def tokenise(self, source, texts):
        self.tokenised.append(source)
        return numpy.array(self.rows(), dtype=numpy.int64), 64

    def embed_onnx(self, vision, text, pixels, ids, threads=None):
        self.threads.append(threads)
        self.seen.append(sum(1 for _ in pixels))
        if self.fail_with:
            raise self.fail_with
        return numpy.ones((self.seen[-1], 3)), numpy.ones((len(ids), 3))

    def record(self, labels_path, manifest_path=None, pixels=None) -> dict:
        corpus = gate.load_corpus_manifest(manifest_path or self.manifest)
        return {
            "labels": {"file": os.path.basename(labels_path), "sha256": gate.sha256_of(labels_path), "count": 5},
            "corpus": {"id": corpus["id"], "count": corpus["count"], "resize": corpus["resize"],
                       "manifestSha256": gate.sha256_of(manifest_path or self.manifest), "pixelsSha256": pixels or corpus["pixels"]},
            "texts": {"variants": list(ev.PROMPT_VARIANTS), "count": self.texts, "sequenceLength": 64, "padId": 1,
                      "tokenRowsSha256": gate.token_rows_digest(self.rows())},
            "artifacts": {},
        }

    def make_run(self, name, labels_path, artifacts=()) -> str:
        """A run directory as `embed` leaves one: pixels, token rows, a record, and vector files."""
        run = os.path.join(self.dir, name)
        os.makedirs(run)
        with open(os.path.join(run, ev.PIXELS_NAME), "wb") as handle:
            handle.write(self.pixels)
        write(run, ev.TOKENS_NAME, json.dumps(self.rows()))
        record = self.record(labels_path)
        for file in artifacts:
            with open(os.path.join(run, file), "wb") as handle:
                handle.write(b"an earlier run's vectors: " + file.encode())
            record["artifacts"][file] = {"artifact": "onnx", "sha256": gate.sha256_of(os.path.join(run, file))}
        write(run, ev.INPUTS_NAME, json.dumps(record))
        return run

    def embed(self, out, *extra, reuse=True, labels=None):
        argv = ["embed", "--out", out, "--artifacts", "onnx", "--labels", labels or self.labels, "--corpus-manifest", self.manifest,
                "--onnx-vision", self.onnx[0], "--onnx-text", self.onnx[1], "--allow-unpinned", *extra]
        return run_main(*argv, *(("--reuse-inputs", self.run) if reuse else ()))

    def test_a_run_writes_its_vectors_and_its_record_and_leaves_no_staging_behind(self):
        out = os.path.join(self.dir, "out")
        before = tree(self.run)
        status, stderr = self.embed(out)
        self.assertEqual((status, stderr), (0, ""))
        self.assertEqual(self.seen, [2])
        self.assertEqual(sorted(os.listdir(out)), sorted([ev.INPUTS_NAME, "vectors-onnx.npz"]))
        record = ev.read_inputs(out)
        self.assertEqual(record["artifacts"]["vectors-onnx.npz"]["sha256"], gate.sha256_of(os.path.join(out, "vectors-onnx.npz")))
        self.assertEqual(record["inputsReusedFrom"], self.run)
        self.assertEqual(tree(self.run), before)

    def test_a_thread_cap_reaches_the_embedder_and_the_record_and_none_is_none(self):
        out = os.path.join(self.dir, "capped")
        self.assertEqual(self.embed(out, "--threads", "4"), (0, ""))
        self.assertEqual(self.threads, [4])
        self.assertEqual(ev.read_inputs(out)["artifacts"]["vectors-onnx.npz"]["where"]["threads"], 4)
        self.assertEqual(self.embed(os.path.join(self.dir, "uncapped")), (0, ""))
        self.assertEqual(self.threads, [4, None])  # no flag: the session is left as onnxruntime makes it
        self.assertIsNone(ev.read_inputs(os.path.join(self.dir, "uncapped"))["artifacts"]["vectors-onnx.npz"]["where"]["threads"])

    def test_a_second_embed_into_a_run_adds_to_it(self):
        run = self.make_run("grown", self.labels, artifacts=["vectors-onnx.npz"])
        earlier = gate.sha256_of(os.path.join(run, "vectors-onnx.npz"))
        status, stderr = self.embed(run, "--tag", "ort9")
        self.assertEqual((status, stderr), (0, ""))
        record = ev.read_inputs(run)
        self.assertEqual(sorted(record["artifacts"]), ["vectors-onnx-ort9.npz", "vectors-onnx.npz"])
        self.assertEqual(gate.sha256_of(os.path.join(run, "vectors-onnx.npz")), earlier)
        self.assertEqual(sorted(os.listdir(run)), sorted([ev.INPUTS_NAME, ev.PIXELS_NAME, ev.TOKENS_NAME, "vectors-onnx-ort9.npz", "vectors-onnx.npz"]))

    def test_a_directory_holding_another_label_sets_run_is_refused_before_anything_is_replaced(self):
        # The check runs before `vectors-onnx.npz` is overwritten.
        other = write(self.dir, "other-labels.tsv", LABELS + "# another label set\n")
        out = self.make_run("other", other, artifacts=["vectors-onnx.npz"])
        before = tree(out)
        status, stderr = self.embed(out)
        self.assertEqual(status, 2)
        self.assertIn("already holds a run embedded from another labels", stderr)
        self.assertEqual(tree(out), before)
        self.assertEqual(self.seen, [])  # refused before the model ran, not after

    def test_a_directory_holding_another_corpus_run_is_refused_before_anything_is_replaced(self):
        out = self.make_run("other-corpus", self.labels, artifacts=["vectors-onnx.npz"])
        record = ev.read_inputs(out)
        record["corpus"]["id"] = "f" * 64
        write(out, ev.INPUTS_NAME, json.dumps(record))
        before = tree(out)
        status, stderr = self.embed(out)
        self.assertEqual(status, 2)
        self.assertIn("another corpus", stderr)
        self.assertEqual(tree(out), before)

    def test_a_directory_holding_other_token_rows_is_refused_before_anything_is_replaced(self):
        out = self.make_run("other-texts", self.labels, artifacts=["vectors-onnx.npz"])
        record = ev.read_inputs(out)
        record["texts"]["tokenRowsSha256"] = "e" * 64
        write(out, ev.INPUTS_NAME, json.dumps(record))
        before = tree(out)
        status, stderr = self.embed(out)
        self.assertEqual(status, 2)
        self.assertIn("another texts", stderr)
        self.assertEqual((tree(out), self.seen), (before, []))

    def test_a_run_refused_or_killed_while_embedding_leaves_the_earlier_run_as_it_was(self):
        out = self.make_run("earlier", self.labels, artifacts=["vectors-onnx.npz"])
        before = tree(out)
        self.fail_with = ev.Refused("a vector is not finite")
        self.assertEqual(self.embed(out)[0], 2)
        self.assertEqual(tree(out), before)
        self.fail_with = RuntimeError("killed")
        with self.assertRaises(RuntimeError):
            self.embed(out)
        self.assertEqual(tree(out), before)
        self.assertEqual(self.seen, [2, 2])

    def test_a_refused_run_leaves_no_directory_it_made(self):
        out = os.path.join(self.dir, "never")
        self.fail_with = ev.Refused("a vector is not finite")
        self.assertEqual(self.embed(out)[0], 2)
        self.assertFalse(os.path.exists(out))

    def test_reused_inputs_that_are_not_the_recorded_ones_are_refused(self):
        out = os.path.join(self.dir, "out")
        write(self.run, ev.TOKENS_NAME, json.dumps([[9, 9, 9]] * self.texts))
        status, stderr = self.embed(out)
        self.assertEqual(status, 2)
        self.assertIn("token rows", stderr)
        for broken in ("{}", "[[1], 2]", "[]"):  # not rows at all: still a refusal, not a traceback
            write(self.run, ev.TOKENS_NAME, broken)
            self.assertEqual(self.embed(out)[0], 2, broken)
        write(self.run, ev.TOKENS_NAME, json.dumps(self.rows()))
        with open(os.path.join(self.run, ev.PIXELS_NAME), "wb") as handle:
            handle.write(bytes(96))
        status, stderr = self.embed(out)
        self.assertIn("not the pixels", stderr)
        os.remove(os.path.join(self.run, ev.PIXELS_NAME))  # as after a disk clean-up
        status, stderr = self.embed(out)
        self.assertEqual(status, 2)
        self.assertIn("no longer holds", stderr)
        self.assertFalse(os.path.exists(out))

    def test_a_run_interrupted_in_its_final_move_is_refused_afterwards_not_read(self):
        # The run directory holds an earlier ONNX vector file and its record; a new
        # embed dies after moving its vector file in and before the record is written back.
        out = self.make_run("earlier", self.labels, artifacts=["vectors-onnx.npz"])
        write(out, ev.RESULT_NAME, "{}")  # a result computed from the vectors about to be replaced
        earlier = gate.sha256_of(os.path.join(out, "vectors-onnx.npz"))
        real, moves = os.replace, []

        def dies_on_the_second(source, target):
            moves.append(os.path.basename(target))
            if len(moves) == 2:
                raise RuntimeError("killed")
            real(source, target)

        ev.os.replace = dies_on_the_second
        try:
            with self.assertRaises(RuntimeError):
                self.embed(out)
        finally:
            ev.os.replace = real
        self.assertEqual(moves[0], "vectors-onnx.npz")
        self.assertNotEqual(gate.sha256_of(os.path.join(out, "vectors-onnx.npz")), earlier)  # the new file IS in place
        # …and nothing in the directory still claims to describe it.
        self.assertFalse(os.path.lexists(os.path.join(out, ev.INPUTS_NAME)))
        self.assertFalse(os.path.lexists(os.path.join(out, ev.RESULT_NAME)))
        self.assertEqual([name for name in os.listdir(out) if name.startswith(".")], [])
        with self.assertRaises(ev.Refused) as refusal:
            ev.read_inputs(out)
        self.assertIn("interrupted and is incomplete", str(refusal.exception))
        # Both readers refuse it: `evaluate`, and an `embed` that would reuse its inputs.
        status, stderr = run_main("evaluate", "--run", out, "--annotations", self.labels, "--labels", self.labels, "--corpus-manifest", self.manifest, "--allow-unpinned")
        self.assertEqual(status, 2)
        self.assertIn("incomplete", stderr)
        self.run = out
        status, stderr = self.embed(os.path.join(self.dir, "next"))
        self.assertEqual(status, 2)
        self.assertIn("incomplete", stderr)

    def evaluated(self, name):
        """A run directory holding every file `evaluate` writes, as after an evaluation."""
        out = self.make_run(name, self.labels, artifacts=["vectors-onnx.npz"])
        for output in ev.EVALUATION_OUTPUTS:
            write(out, output, "computed from the run as it was: " + output)
        return out

    def test_any_embed_that_changes_a_run_removes_every_evaluation_output_in_it(self):
        # Not only the result JSON, and not only when a vector file is replaced: the tables, the score
        # archives and the label sets would otherwise describe vectors that have moved.
        for extra, what in ((("--tag", "ort9"), "a tagged file added"), ((), "the base file replaced")):
            out = self.evaluated("evaluated-" + (extra[1] if extra else "base"))
            with contextlib.redirect_stdout(io.StringIO()) as stdout, contextlib.redirect_stderr(io.StringIO()):
                status = ev.main(["embed", "--out", out, "--artifacts", "onnx", "--labels", self.labels, "--corpus-manifest", self.manifest, "--onnx-vision", self.onnx[0],
                                  "--onnx-text", self.onnx[1], "--allow-unpinned", "--reuse-inputs", self.run, *extra])
            self.assertEqual(status, 0, what)
            self.assertEqual(ev.evaluation_outputs_in(out), [], what)
            # …and it says so: which files, and what to do.
            self.assertIn(f"removed {len(ev.EVALUATION_OUTPUTS)} evaluation outputs", stdout.getvalue(), what)
            self.assertIn("run `evaluate` again", stdout.getvalue())
            self.assertTrue(os.path.isfile(os.path.join(out, ev.INPUTS_NAME)))

    def test_a_refused_embed_leaves_the_evaluation_outputs_with_the_run_they_describe(self):
        out = self.evaluated("evaluated")
        before = tree(out)
        self.fail_with = ev.Refused("a vector is not finite")
        self.assertEqual(self.embed(out)[0], 2)
        self.assertEqual(tree(out), before)

    def test_an_evaluate_out_directory_is_not_a_place_to_embed(self):
        # Results of some other run's vectors, and no record: vectors written beside them would
        # make those results read as theirs. One derived file is enough.
        for present in (ev.EVALUATION_OUTPUTS, (ev.TABLES_NAME,), (ev.score_file("onnx"),)):
            out = os.path.join(self.dir, "results-" + str(len(present)) + present[0][:6])
            os.makedirs(out)
            for output in present:
                write(out, output, "another run's " + output)
            before = tree(out)
            status, stderr = self.embed(out)
            self.assertEqual(status, 2, present)
            self.assertIn("holds evaluation outputs", stderr)
            self.assertIn("no `embed` record", stderr)
            self.assertEqual((tree(out), self.seen), (before, []))

    def test_what_evaluate_writes_is_what_embed_removes(self):
        self.assertEqual(sorted(ev.EVALUATION_OUTPUTS), sorted([ev.RESULT_NAME, ev.TABLES_NAME, ev.SETS_NAME, *(ev.score_file(a) for a in ev.ARTIFACTS)]))
        self.assertEqual(ev.EVALUATION_OUTPUTS[-1], ev.RESULT_NAME)  # the result is written last
        out = self.evaluated("listed")
        self.assertEqual(ev.remove_evaluation_outputs(out), list(ev.EVALUATION_OUTPUTS))
        self.assertEqual(sorted(os.listdir(out)), sorted([ev.INPUTS_NAME, ev.PIXELS_NAME, ev.TOKENS_NAME, "vectors-onnx.npz"]))

    @unittest.skipIf(importlib.util.find_spec("PIL") is None, "needs Pillow")
    def test_fresh_token_rows_need_the_pinned_checkpoint_whatever_is_embedded(self):
        # `--artifacts onnx` must not read the tokenizer from any directory, unrecorded.
        corpus, _ = self.corpus_directory()
        out = os.path.join(self.dir, "fresh")
        for name in sorted(gate.YARDSTICK_SOURCE["files"]):
            other = os.path.join(self.dir, "other-" + name)
            shutil.copytree(self.source, other)
            write(other, name, "another checkpoint's " + name)
            status, stderr = self.embed(out, "--corpus", corpus, "--source", other, reuse=False)
            self.assertEqual(status, 2, name)
            self.assertIn(f"--source's {name} is not the pinned checkpoint's", stderr)
            os.remove(os.path.join(other, name))
            status, stderr = self.embed(out, "--corpus", corpus, "--source", other, reuse=False)
            self.assertIn(f"--source has no {name}", stderr)
        self.assertEqual(self.tokenised, [])  # no tokenizer was read from any of them
        self.assertFalse(os.path.exists(out))
        # The pinned one is admitted, and the record says which checkpoint the rows came from.
        self.assertEqual(self.embed(out, "--corpus", corpus, "--source", self.source, reuse=False)[0], 0)
        texts = ev.read_inputs(out)["texts"]
        self.assertEqual(texts["source"]["revision"], "r" * 40)
        self.assertEqual(texts["source"]["pinnedFiles"], gate.YARDSTICK_SOURCE["files"])
        self.assertEqual(texts["source"]["tokenizerFilesRecorded"], {"spiece.model": gate.sha256_of(os.path.join(self.source, "spiece.model"))})
        self.assertIs(texts["rowsPinned"], False)  # not the committed label set: recorded, not pinned
        self.assertEqual(self.tokenised, [self.source])

    @unittest.skipIf(importlib.util.find_spec("PIL") is None, "needs Pillow")
    def test_a_checkpoint_of_another_input_side_is_refused_for_onnx_alone_too(self):
        corpus, _ = self.corpus_directory()
        write(self.source, "preprocessor_config.json", json.dumps({"size": {"height": 8}}))
        gate.YARDSTICK_SOURCE["files"]["preprocessor_config.json"] = gate.sha256_of(os.path.join(self.source, "preprocessor_config.json"))
        status, stderr = self.embed(os.path.join(self.dir, "fresh"), "--corpus", corpus, "--source", self.source, reuse=False)
        self.assertEqual(status, 2)
        self.assertIn("does not take 4² images", stderr)

    def corpus_directory(self):
        """A corpus of two real images, and its manifest. Needs Pillow."""
        from PIL import Image

        corpus = os.path.join(self.dir, "corpus")
        os.makedirs(corpus)
        raw = b""
        for index, name in enumerate(self.IMAGES):
            image = Image.new("RGB", (self.SIDE, self.SIDE), (40 * index, 90, 200))
            image.save(os.path.join(corpus, name))
            raw += image.tobytes()
        self.pixels = raw
        self.manifest = write(self.dir, "real-corpus.txt", corpus_manifest_text(self.IMAGES, pixels=hashlib.sha256(raw).hexdigest(), directory=corpus))
        return corpus, self.source

    @unittest.skipIf(importlib.util.find_spec("PIL") is None, "needs Pillow")
    def test_embedding_from_the_corpus_writes_pixels_and_token_rows(self):
        corpus, source = self.corpus_directory()
        out = os.path.join(self.dir, "fresh")
        status, stderr = self.embed(out, "--corpus", corpus, "--source", source, reuse=False)
        self.assertEqual((status, stderr), (0, ""))
        self.assertEqual(sorted(os.listdir(out)), sorted([ev.INPUTS_NAME, ev.PIXELS_NAME, ev.TOKENS_NAME, "vectors-onnx.npz"]))
        self.assertEqual(gate.sha256_of(os.path.join(out, ev.PIXELS_NAME)), hashlib.sha256(self.pixels).hexdigest())
        self.assertEqual(ev.read_inputs(out)["texts"]["tokenRowsSha256"], gate.token_rows_digest(self.rows()))
        # A second run over the same inputs is admitted, and replaces like with like.
        before = {name: digest for name, digest in tree(out).items() if name in (ev.PIXELS_NAME, ev.TOKENS_NAME)}
        self.assertEqual(self.embed(out, "--corpus", corpus, "--source", source, reuse=False)[0], 0)
        self.assertEqual({name: digest for name, digest in tree(out).items() if name in before}, before)

    def test_a_corpus_directory_lacking_files_is_refused_in_this_scripts_own_words(self):
        corpus, source = os.path.join(self.dir, "empty-corpus"), self.source
        os.makedirs(corpus)
        out = os.path.join(self.dir, "fresh")
        status, stderr = self.embed(out, "--corpus", corpus, "--source", source, reuse=False)
        self.assertEqual(status, 2)
        self.assertIn("lacks 2 of the manifest's 2 files", stderr)
        self.assertNotIn("--fetch", stderr)  # the gate's flag, which this script does not have
        self.assertFalse(os.path.exists(out))

    @unittest.skipIf(importlib.util.find_spec("PIL") is None, "needs Pillow")
    def test_embedding_from_the_corpus_into_another_runs_directory_replaces_none_of_its_inputs(self):
        # `pixels.u8` and `token-ids.json` are not rewritten before the refusal either.
        corpus, source = self.corpus_directory()
        out = os.path.join(self.dir, "fresh")
        self.assertEqual(self.embed(out, "--corpus", corpus, "--source", source, reuse=False)[0], 0)
        before = tree(out)
        # The tokenizer now gives other rows: only known once computed, and still before a write.
        self.token_base = 500
        status, stderr = self.embed(out, "--corpus", corpus, "--source", source, reuse=False)
        self.assertEqual(status, 2)
        self.assertIn("another texts", stderr)
        self.assertEqual(tree(out), before)
        # And another label set, known at once.
        other = write(self.dir, "other-labels.tsv", LABELS + "# another label set\n")
        status, stderr = self.embed(out, "--corpus", corpus, "--source", source, reuse=False, labels=other)
        self.assertEqual(status, 2)
        self.assertEqual(tree(out), before)


@unittest.skipIf(numpy is None, "needs numpy")
class Bootstrap(unittest.TestCase):
    def test_resampled_ap_precision_and_recall_match_the_plain_python_metrics(self):
        scores = [0.31, 0.11, 0.27, 0.05, 0.19, 0.23, 0.02, 0.17]
        truth = [True, False, True, False, False, True, False, True]
        # Each row is one resample: how often each image was drawn. A percentile interval over a
        # single resample is that resample's value, so every figure can be checked exactly.
        for row in ([1, 1, 1, 1, 1, 1, 1, 1], [3, 0, 0, 2, 1, 0, 1, 1], [0, 2, 1, 0, 3, 1, 0, 1]):
            got = ev.bootstrap(scores, truth, 0.18, numpy.array([row]))
            self.assertAlmostEqual(got["ap"][0], ev.average_precision(scores, truth, weights=row))
            self.assertAlmostEqual(got["ap"][0], got["ap"][1])
            repeated_scores = [score for score, count in zip(scores, row) for _ in range(count)]
            repeated_truth = [value for value, count in zip(truth, row) for _ in range(count)]
            expected = ev.confusion(ev.fires(repeated_scores, 0.18), repeated_truth)
            self.assertAlmostEqual(got["precision"][0], expected["precision"])
            self.assertAlmostEqual(got["recall"][0], expected["recall"])

    def test_tied_scores_are_one_threshold_whatever_order_the_images_arrive_in(self):
        # Three images share 0.5: one positive, two negatives. The same data in two input orders,
        # the tied positive first and then last. Image by image, those rank it above and below the
        # negatives it ties with (AP 0.833 and 0.667 unweighted); as one threshold it is 0.75.
        first = ([0.9, 0.5, 0.5, 0.5, 0.1], [True, True, False, False, False])
        last = ([0.9, 0.5, 0.5, 0.5, 0.1], [True, False, False, True, False])
        self.assertAlmostEqual(ev.average_precision(*first), 0.5 * 1.0 + 0.5 * 2 / 4)
        for row in ([1, 1, 1, 1, 1], [2, 1, 1, 1, 0], [1, 3, 3, 3, 2]):  # symmetric in the tied group
            got_first = ev.bootstrap(*first, 0.5, numpy.array([row]))
            got_last = ev.bootstrap(*last, 0.5, numpy.array([row]))
            self.assertEqual(got_first, got_last)
            self.assertAlmostEqual(got_first["ap"][0], ev.average_precision(*first, weights=row))
        # And with weights that differ inside the tied group, each order against the plain metric.
        for scores, truth in (first, last):
            for row in ([1, 2, 0, 3, 1], [2, 1, 4, 1, 2], [3, 0, 2, 1, 0]):
                got = ev.bootstrap(scores, truth, 0.5, numpy.array([row]))
                self.assertAlmostEqual(got["ap"][0], ev.average_precision(scores, truth, weights=row))

    def test_a_resample_that_drew_none_of_a_tied_group_is_still_the_plain_metric(self):
        scores, truth = [0.9, 0.9, 0.5, 0.5, 0.1], [False, True, True, False, True]
        for row in ([0, 0, 2, 1, 2], [1, 1, 0, 0, 3], [0, 0, 0, 0, 5]):
            got = ev.bootstrap(scores, truth, None, numpy.array([row]))
            self.assertAlmostEqual(got["ap"][0], ev.average_precision(scores, truth, weights=row))

    def test_no_floor_gives_no_precision_interval(self):
        got = ev.bootstrap([0.3, 0.1], [True, False], None, numpy.array([[1, 1]]))
        self.assertEqual(sorted(got), ["ap", "undefined"])

    def test_a_resample_without_a_positive_or_without_a_firing_image_is_left_out_and_counted(self):
        scores, truth = [0.9, 0.8, 0.2, 0.1], [True, False, True, False]
        counts = numpy.array([
            [1, 1, 1, 1],  # everything defined
            [0, 2, 0, 2],  # no positive drawn: AP and recall are 0/0; precision is 0 of 2 firing
            [0, 0, 2, 2],  # nothing drawn fires at 0.5: precision is 0/0; recall is 0 of 2
        ])
        got = ev.bootstrap(scores, truth, 0.5, counts)
        self.assertEqual(got["undefined"], {"ap": 1, "precision": 1, "recall": 1})
        for name in ("ap", "precision", "recall"):
            self.assertTrue(all(math.isfinite(value) for value in got[name]), name)
        # Every resample undefined: no interval, and the count says why.
        got = ev.bootstrap(scores, truth, 0.5, numpy.array([[0, 3, 0, 1], [0, 1, 0, 3]]))
        self.assertIsNone(got["ap"])
        self.assertIsNone(got["recall"])
        self.assertEqual(got["undefined"]["ap"], 2)
        self.assertEqual(ev.fmt_interval(None), "[—]")
        self.assertEqual(ev.fmt_interval([0.25, 0.5]), "[0.250, 0.500]")


@unittest.skipIf(numpy is None, "needs numpy")
class EvaluateEndToEnd(unittest.TestCase):
    """`evaluate` over made-up vectors: the plumbing, the bindings and the result's shape."""

    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.dir = self.scratch.name
        self.labels = write(self.dir, "labels.tsv", LABELS)
        self.mapping = write(self.dir, "mapping.tsv", MAPPING)
        names = [f"{index:03d}.jpg" for index in range(80)]
        # Image i holds a person when i % 2 == 0, a dog when i % 4 == 0, a cat when i % 4 == 1.
        instances = [(name, "person", 0, 5000.0) for index, name in enumerate(names) if index % 2 == 0]
        instances += [(name, "dog", 0, 5000.0) for index, name in enumerate(names) if index % 4 == 0]
        instances += [(name, "cat", 0, 5000.0) for index, name in enumerate(names) if index % 4 == 1]
        self.annotations = write(self.dir, ev.ANNOTATIONS_NAME, json.dumps(annotations_document({name: (100, 100) for name in names}, instances)))
        self.manifest = write(self.dir, "corpus.txt", corpus_manifest_text(names, self.annotations))
        self.run = os.path.join(self.dir, "run")
        os.makedirs(self.run)
        rng = numpy.random.default_rng(1)
        # Five label directions (one-hot), every prompt variant the same. An image scores exactly 1
        # on what it holds and noise, differing per artifact, on what it does not.
        texts = numpy.tile(numpy.eye(5, 8, dtype=numpy.float32), (3, 1))
        truth = numpy.zeros((80, 8), dtype=numpy.float32)
        for index in range(80):
            truth[index, 0] = index % 2 == 0
            truth[index, 2] = index % 4 == 0
            truth[index, 3] = index % 4 == 1
            truth[index, 1] = truth[index, 2] or truth[index, 3]
        artifacts = {}
        for artifact in ev.ARTIFACTS:
            images = truth + (1 - truth) * rng.normal(0, 0.05, truth.shape).astype(numpy.float32)
            path = os.path.join(self.run, ev.vectors_name(artifact, ""))
            numpy.savez(path, images=images, texts=texts)
            artifacts[os.path.basename(path)] = {"artifact": artifact, "sha256": gate.sha256_of(path)}
        self.inputs = {
            "labels": {"sha256": gate.sha256_of(self.labels)},
            "corpus": {"id": gate.load_corpus_manifest(self.manifest)["id"], "count": 80, "manifestSha256": gate.sha256_of(self.manifest),
                       "pixelsSha256": "0" * 64},
            "texts": {"tokenRowsSha256": "0" * 64},
            "artifacts": artifacts,
        }
        write(self.run, ev.INPUTS_NAME, json.dumps(self.inputs))

    # ⚠ 0.99, not the default 0.90: a floor is the LOWEST threshold holding its target, so at 0.90
    # it reaches down into the negatives until one firing image in ten is wrong, by design.
    TARGET = ("--targets", "0.99", "--primary-target", "0.99")

    def evaluate(self, *extra):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()) as stderr:
            status = ev.main(["evaluate", "--run", self.run, "--annotations", self.annotations, "--labels", self.labels, "--mapping", self.mapping,
                              "--corpus-manifest", self.manifest, "--allow-unpinned", "--bootstrap", "20", *(extra or self.TARGET)])
        return status, stderr.getvalue()

    def test_a_run_is_measured_and_records_what_it_was_measured_on(self):
        status, stderr = self.evaluate()
        self.assertEqual((status, stderr), (0, ""))
        with open(os.path.join(self.run, ev.RESULT_NAME), encoding="utf-8") as handle:
            result = json.load(handle)
        self.assertEqual(result["format"], ev.RESULT_FORMAT)
        self.assertEqual(result["labels"]["mapped"], ["people", "animals", "dog", "cat"])
        self.assertEqual(result["labels"]["unmapped"], ["indoors"])
        self.assertEqual((result["split"]["seed"], result["split"]["calibration"], result["split"]["heldOut"]), (ev.DEFAULT_SEED, 40, 40))
        self.assertEqual(result["inputs"]["mapping"]["sha256"], gate.sha256_of(self.mapping))
        self.assertEqual(result["inputs"]["labels"]["sha256"], gate.sha256_of(self.labels))
        self.assertEqual(result["inputs"]["annotations"]["sha256"], gate.sha256_of(self.annotations))
        self.assertEqual(sorted(result["inputs"]["artifacts"]), sorted(ev.vectors_name(artifact, "") for artifact in ev.ARTIFACTS))
        # The made-up vectors separate every label cleanly, so each has a floor (1.0) and holds it.
        for artifact in ev.ARTIFACTS:
            for label in result["labels"]["mapped"]:
                entry = result["perLabel"][artifact][label]
                self.assertAlmostEqual(entry["ap"]["heldOut"], 1.0)
                self.assertEqual(entry["targets"]["0.99"]["threshold"], 1.0)
                self.assertEqual((entry["targets"]["0.99"]["heldOut"]["precision"], entry["targets"]["0.99"]["heldOut"]["recall"]), (1.0, 1.0))
        self.assertEqual(result["agreement"]["heldOutOwnFloors"]["onnxVsCoreml"]["pooled"]["jaccard"], 1.0)
        self.assertEqual(result["towersAgainstFp32"]["onnx"]["visionOnly"]["jaccard"], 1.0)
        self.assertIsNone(result["inputs"]["calibration"]["checkpointVerified"])  # no --source was given
        self.assertEqual(result["inputs"]["calibration"]["checkpoint"], list(ev.CHECKPOINT_CALIBRATION))
        # The full rule: a dog image (i % 4 == 0) is also a person image, so its set is animals,
        # dog, people; a cat image is animals, cat.
        with open(os.path.join(self.run, "label-sets-held-out.json"), encoding="utf-8") as handle:
            sets = json.load(handle)
        by_name = dict(zip(sets["images"], sets["sets"]["0.01"]["onnx"]))
        for name, labels in by_name.items():
            index = int(name[:3])
            expected = (["animals"] if index % 4 in (0, 1) else []) + (["cat"] if index % 4 == 1 else []) + \
                       (["dog"] if index % 4 == 0 else []) + (["people"] if index % 2 == 0 else [])
            self.assertEqual(labels, expected, name)
        for name, digest in result["files"].items():
            self.assertEqual(gate.sha256_of(os.path.join(self.run, name)), digest)
        self.assertTrue(os.path.isfile(os.path.join(self.run, ev.TABLES_NAME)))

    def test_vectors_that_are_not_the_recorded_ones_are_refused(self):
        path = os.path.join(self.run, ev.vectors_name("onnx", ""))
        with open(path, "ab") as handle:
            handle.write(b"\0")
        status, stderr = self.evaluate()
        self.assertEqual(status, 2)
        self.assertIn("is not the file `embed` recorded", stderr)

    def test_another_label_set_than_the_embedded_one_is_refused(self):
        with open(self.labels, "a", encoding="utf-8") as handle:
            handle.write("# moved\n")
        status, stderr = self.evaluate()
        self.assertEqual(status, 2)
        self.assertIn("another label set", stderr)

    def test_the_same_images_under_other_manifest_headers_are_refused(self):
        # The corpus id covers the image lines only. Here the resize line moves and the id does not;
        # the result would copy the recorded corpus block over it.
        with open(self.manifest, encoding="utf-8") as handle:
            text = handle.read()
        for old, new in (("pixels=" + "0" * 64, "pixels=" + "1" * 64), ("source=http://example.invalid/", "source=http://elsewhere.invalid/")):
            write(self.dir, "corpus.txt", text.replace(old, new))
            self.assertEqual(gate.load_corpus_manifest(self.manifest)["id"], self.inputs["corpus"]["id"])
            status, stderr = self.evaluate()
            self.assertEqual(status, 2, new)
            self.assertIn("the same images under other header lines", stderr)
        self.assertFalse(os.path.exists(os.path.join(self.run, ev.RESULT_NAME)))

    def test_a_record_without_the_manifest_digest_is_refused(self):
        del self.inputs["corpus"]["manifestSha256"]
        write(self.run, ev.INPUTS_NAME, json.dumps(self.inputs))
        self.assertEqual(self.evaluate()[0], 2)

    def test_a_run_over_made_up_inputs_reads_overridden(self):
        with contextlib.redirect_stdout(io.StringIO()) as stdout, contextlib.redirect_stderr(io.StringIO()):
            status = ev.main(["evaluate", "--run", self.run, "--annotations", self.annotations, "--labels", self.labels, "--mapping", self.mapping,
                              "--corpus-manifest", self.manifest, "--allow-unpinned", "--bootstrap", "20", *self.TARGET])
        self.assertEqual(status, 0)
        last = [line for line in stdout.getvalue().split("\n") if line.startswith("label eval:")][0]
        self.assertTrue(last.startswith("label eval: OVERRIDDEN MEASURED"), last)
        with open(os.path.join(self.run, ev.RESULT_NAME), encoding="utf-8") as handle:
            result = json.load(handle)
        self.assertIs(result["overridden"], True)
        for said in ("label set", "mapping", "corpus manifest", "fp32 vectors", "onnx vectors", "coreml vectors", "compute units"):
            self.assertTrue(any(said in line for line in result["overrides"]), said)

    def test_an_evaluate_interrupted_in_its_final_move_leaves_no_result(self):
        self.assertEqual(self.evaluate()[0], 0)
        real, moves = os.replace, []

        def dies_on_the_third(source, target):
            moves.append(os.path.basename(target))
            if os.path.dirname(target) == self.run and len([name for name in moves if name.startswith("scores-")]) == 2:
                raise RuntimeError("killed")
            real(source, target)

        ev.os.replace = dies_on_the_third
        try:
            with self.assertRaises(RuntimeError):
                self.evaluate(*self.TARGET, "--seed", "1")
        finally:
            ev.os.replace = real
        # One new score file is in place; the result that described the old ones is gone.
        self.assertFalse(os.path.lexists(os.path.join(self.run, ev.RESULT_NAME)))
        self.assertEqual([name for name in os.listdir(self.run) if name.startswith(".")], [])
        self.assertEqual(self.evaluate()[0], 0)  # and a rerun completes it
        self.assertTrue(os.path.isfile(os.path.join(self.run, ev.RESULT_NAME)))

    def other_corpus(self, names, instances, mapping=None):
        """Point the fixture at another annotated corpus, consistently: annotations, the manifest
        that pins them, and the record's manifest digest."""
        self.annotations = write(self.dir, ev.ANNOTATIONS_NAME, json.dumps(annotations_document({name: (100, 100) for name in names}, instances)))
        self.manifest = write(self.dir, "corpus.txt", corpus_manifest_text(names, self.annotations))
        self.inputs["corpus"].update(id=gate.load_corpus_manifest(self.manifest)["id"], manifestSha256=gate.sha256_of(self.manifest))
        write(self.run, ev.INPUTS_NAME, json.dumps(self.inputs))
        if mapping:
            self.mapping = write(self.dir, "mapping.tsv", mapping)

    def test_a_corpus_where_a_label_has_one_class_is_refused_before_anything_is_computed(self):
        # Refused by name, before `quantiles([])` raises IndexError and `average_precision` ValueError.
        names = [f"{index:03d}.jpg" for index in range(80)]
        # Every image holds a person (no negative), none holds a cat (no positive); dogs as before.
        instances = [(name, "person", 0, 5000.0) for name in names] + [(name, "dog", 0, 5000.0) for index, name in enumerate(names) if index % 4 == 0]
        self.other_corpus(names, instances)
        before = tree(self.run)
        status, stderr = self.evaluate()
        self.assertEqual(status, 2)
        for said in ("people: no negative in the calibration half", "people: no negative in the held-out half",
                     "cat: no positive in the calibration half", "cat: no positive in the held-out half", "cannot score 4 label halves"):
            self.assertIn(said, stderr)
        self.assertNotIn("dog:", stderr)
        self.assertNotIn("animals:", stderr)
        self.assertEqual(tree(self.run), before)
        with without("numpy"):  # with the input checks: before the first package is needed
            self.assertEqual(self.evaluate(), (status, stderr))

    def test_a_label_missing_from_one_half_only_is_refused_too(self):
        names = [f"{index:03d}.jpg" for index in range(80)]
        calibration, _ = ev.split(names, ev.DEFAULT_SEED)
        instances = [(name, "person", 0, 5000.0) for index, name in enumerate(names) if index % 2 == 0]
        instances += [(name, "dog", 0, 5000.0) for index, name in enumerate(names) if index % 4 == 0]
        instances += [(name, "cat", 0, 5000.0) for name in calibration[:5]]  # cats in the calibration half only
        self.other_corpus(names, instances)
        status, stderr = self.evaluate()
        self.assertEqual(status, 2)
        self.assertIn("cat: no positive in the held-out half", stderr)
        self.assertIn("cannot score 1 label halves", stderr)
        # Another seed puts cats in both halves: the refusal's own advice.
        self.assertEqual(self.evaluate(*self.TARGET, "--seed", "3")[0], 0, "pick a seed that splits the five cats")

    def test_a_mapping_that_maps_nothing_and_a_corpus_of_one_image_are_refused(self):
        nothing = "label-eval-mapping 1\n" + "".join(f"{label}\tnone\t-\t-\t-\n" for label in ("people", "animals", "dog", "cat", "indoors"))
        mapped = self.mapping
        self.mapping = write(self.dir, "nothing.tsv", nothing)
        status, stderr = self.evaluate()
        self.assertEqual(status, 2)
        self.assertIn("maps no label", stderr)
        self.mapping = mapped
        self.other_corpus(["000.jpg"], [("000.jpg", "dog", 0, 5000.0)])
        status, stderr = self.evaluate()
        self.assertEqual(status, 2)
        self.assertIn("names one image", stderr)

    def test_the_tables_print_a_missing_interval_as_a_dash(self):
        self.assertEqual(self.evaluate()[0], 0)
        with open(os.path.join(self.run, ev.RESULT_NAME), encoding="utf-8") as handle:
            result = json.load(handle)
        self.assertEqual(result["perLabel"]["onnx"]["dog"]["ap"]["heldOutCi95UndefinedResamples"], 0)
        self.assertEqual(result["perLabel"]["onnx"]["dog"]["targets"]["0.99"]["heldOut"]["ci95UndefinedResamples"], {"precision": 0, "recall": 0})
        result["perLabel"]["onnx"]["dog"]["ap"]["heldOutCi95"] = None  # every resample drew no dog
        self.assertIn("[—]", ev.tables(result))

    def add_variant(self, tag, toolchain):
        import shutil as copier

        name = ev.vectors_name("onnx", tag)
        copier.copy(os.path.join(self.run, ev.vectors_name("onnx", "")), os.path.join(self.run, name))
        self.inputs["artifacts"][name] = {"artifact": "onnx", "sha256": gate.sha256_of(os.path.join(self.run, name)), "onnxruntime": toolchain.get("onnxruntime"),
                                          "toolchain": toolchain}
        write(self.run, ev.INPUTS_NAME, json.dumps(self.inputs))
        return name

    def run_with(self, *variants):
        argv = ["evaluate", "--run", self.run, "--annotations", self.annotations, "--labels", self.labels, "--mapping", self.mapping,
                "--corpus-manifest", self.manifest, "--allow-unpinned", "--bootstrap", "20", *self.TARGET]
        for name in variants:
            argv += ["--variant", name]
        with contextlib.redirect_stdout(io.StringIO()) as stdout, contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(ev.main(argv), 0)
        with open(os.path.join(self.run, ev.RESULT_NAME), encoding="utf-8") as handle:
            result = json.load(handle)
        with open(os.path.join(self.run, ev.TABLES_NAME), encoding="utf-8") as handle:
            tables = handle.read()
        return result, tables, [line for line in stdout.getvalue().split("\n") if line.startswith("label eval:")][0]

    def test_a_requested_variant_from_outside_the_pins_is_said_so_everywhere(self):
        # The variant changes the tables, so the result and the last line must say so too, not read
        # as those of a run without it.
        pins = gate.pinned_versions(os.path.join(_HERE, ev.PINNED))
        off = self.add_variant("ort1.29.0", {"python": "3.13.7", "pinned": False, **pins, "onnxruntime": "1.29.0"})
        on = self.add_variant("again", {"python": "3.13.7", "pinned": True, **pins})
        without_variant, tables, last = self.run_with()
        self.assertEqual(without_variant["offPinVariants"], [])
        self.assertNotIn("off-pin variant", last)
        self.assertNotIn("OFF-PIN", tables)

        result, tables, last = self.run_with(off, on)
        self.assertEqual(result["offPinVariants"], ["onnx-ort1.29.0"])
        self.assertIn("MEASURED; 1 off-pin variant: onnx-ort1.29.0", last)
        self.assertEqual(result["variants"][off]["departsFrom"], ["onnxruntime 1.29.0 (pinned 1.20.0)"])
        self.assertIs(result["variants"][off]["offPin"], True)
        self.assertEqual((result["variants"][on]["offPin"], result["variants"][on]["departsFrom"]), (False, []))
        self.assertIn(f"### ⚠ OFF-PIN VARIANT — {off} against the pinned onnx run", tables)
        self.assertIn("departs from the pins in: onnxruntime 1.29.0 (pinned 1.20.0)", tables)
        self.assertIn(f"### {on} against the pinned onnx run", tables)
        # ⚠ And the base verdict is the one a run without the variant has: the variant adds to the
        # override list nothing, and takes from it nothing.
        self.assertEqual(result["overrides"], without_variant["overrides"])
        self.assertEqual(result["perLabel"], without_variant["perLabel"])

    def test_another_corpus_than_the_embedded_one_is_refused(self):
        self.inputs["corpus"]["id"] = "f" * 64
        write(self.run, ev.INPUTS_NAME, json.dumps(self.inputs))
        status, stderr = self.evaluate()
        self.assertEqual(status, 2)
        self.assertIn("was embedded over corpus", stderr)

    def test_without_numpy_a_valid_run_is_refused_after_its_inputs_were_checked(self):
        with without("numpy"):
            status, stderr = self.evaluate()
        self.assertEqual(status, 2)
        self.assertIn("is not installed", stderr)
        self.assertFalse(os.path.exists(os.path.join(self.run, ev.RESULT_NAME)))
        # And the dependency-free refusals still come first.
        with open(self.labels, "a", encoding="utf-8") as handle:
            handle.write("# moved\n")
        with without("numpy"):
            status, stderr = self.evaluate()
        self.assertIn("another label set", stderr)

    def test_every_wrong_argument_is_refused_and_an_earlier_result_stays_byte_identical(self):
        self.assertEqual(self.evaluate()[0], 0)
        before = tree(self.dir)
        elsewhere = os.path.join(self.dir, "elsewhere")
        other_run = os.path.join(self.dir, "other-run")
        os.makedirs(other_run)
        write(other_run, ev.INPUTS_NAME, json.dumps(self.inputs))
        not_weights = os.path.join(self.dir, "not-weights")
        os.makedirs(not_weights)
        with open(os.path.join(not_weights, "model.safetensors"), "wb") as handle:
            handle.write(safetensors_bytes({"logit_scale": 4.7, "logit_bias": -12.9}))
        before = tree(self.dir)
        cases = [
            (("--targets", "abc"), "--targets"), (("--targets", ""), "--targets"), (("--targets", "0.9,x", "--primary-target", "0.9"), "--targets"),
            (("--targets", "nan", "--primary-target", "0.9"), "--targets"), (("--targets", "1.0", "--primary-target", "1.0"), "--targets"),
            (("--targets", "0.8", "--primary-target", "0.9"), "--primary-target"),
            ((*self.TARGET, "--margins", "wide"), "--margins"), ((*self.TARGET, "--margins", "-0.01"), "--margins"),
            # Two targets under one key, and its relatives.
            (("--targets", "0.991,0.994", "--primary-target", "0.991"), "two decimals"),
            (("--targets", "0.99,0.990", "--primary-target", "0.99"), "repeats"),
            ((*self.TARGET, "--margins", "0.01,0.01"), "repeats"),
            ((*self.TARGET, "--variant", "vectors-onnx.npz"), "tagged vector file"),
            ((*self.TARGET, "--variant", "vectors-onnx-x.npz", "--variant", "vectors-onnx-x.npz"), "twice"),
            ((*self.TARGET, "--bootstrap", "0"), "--bootstrap"),
            # On a run that is otherwise complete: a negative seed would reach `default_rng`
            # (ValueError), and a resample count nothing can allocate.
            ((*self.TARGET, "--seed", "-1"), "--seed is at least 0"),
            ((*self.TARGET, "--bootstrap", HUGE), "--bootstrap is between"),
            ((*self.TARGET, "--out", self.labels), "is not a directory"),
            ((*self.TARGET, "--out", other_run), "another `embed` run's directory"),
            ((*self.TARGET, "--variant", "vectors-onnx-absent.npz"), "not a vector file"),
            ((*self.TARGET, "--variant", "../labels.tsv"), "not a vector file"),
            ((*self.TARGET, "--source", os.path.join(self.dir, "absent")), "could not be read"),
            # In `evaluate`, this one refuses BEFORE the score files and the label sets are replaced.
            # The other seed makes the label sets differ.
            ((*self.TARGET, "--seed", "1", "--source", not_weights), "not the pinned checkpoint's weights"),
            ((*self.TARGET, "--seed", "1", "--out", elsewhere, "--source", not_weights), "not the pinned checkpoint's weights"),
        ]
        for extra, said in cases:
            status, stderr = self.evaluate(*extra)
            self.assertEqual(status, 2, (extra, stderr))
            self.assertIn(said, stderr, extra)
            self.assertEqual(tree(self.dir), before, extra)
        self.assertFalse(os.path.exists(elsewhere))

    def test_the_ends_of_each_integer_domain_run(self):
        # In the domain, so they must RUN: seed 0, a seed beyond 2**63 (the hash split and
        # `default_rng` both take an integer of any size), and a single resample.
        for extra, field, value in ((("--seed", "0"), "seed", 0), (("--seed", HUGE), "seed", 2 ** 70), (("--bootstrap", "1"), "resamples", 1)):
            status, stderr = self.evaluate(*self.TARGET, *extra)
            self.assertEqual((status, stderr), (0, ""), extra)
            with open(os.path.join(self.run, ev.RESULT_NAME), encoding="utf-8") as handle:
                self.assertEqual(json.load(handle)["bootstrap"][field], value)

    def test_a_result_can_go_to_a_directory_of_its_own_and_a_rerun_replaces_it_whole(self):
        elsewhere = os.path.join(self.dir, "elsewhere")
        before = tree(self.run)
        self.assertEqual(self.evaluate(*self.TARGET, "--out", elsewhere)[0], 0)
        self.assertEqual(tree(self.run), before)
        wrote = sorted(os.listdir(elsewhere))
        self.assertEqual(wrote, sorted([ev.RESULT_NAME, ev.TABLES_NAME, "label-sets-held-out.json", *(f"scores-{a}.npz" for a in ev.ARTIFACTS)]))
        self.assertEqual(wrote, sorted(ev.EVALUATION_OUTPUTS))  # the one list `embed` removes by
        self.assertEqual(self.evaluate(*self.TARGET, "--seed", "1", "--out", elsewhere)[0], 0)
        self.assertEqual(sorted(os.listdir(elsewhere)), wrote)  # no staging directory left behind
        with open(os.path.join(elsewhere, ev.RESULT_NAME), encoding="utf-8") as handle:
            result = json.load(handle)
        self.assertEqual(result["split"]["seed"], 1)
        for name, digest in result["files"].items():
            self.assertEqual(gate.sha256_of(os.path.join(elsewhere, name)), digest)

    def test_a_recorded_vector_file_that_is_not_one_is_refused(self):
        path = os.path.join(self.run, ev.vectors_name("onnx", ""))
        with open(path, "wb") as handle:
            handle.write(b"not an npz")
        self.inputs["artifacts"][os.path.basename(path)]["sha256"] = gate.sha256_of(path)
        write(self.run, ev.INPUTS_NAME, json.dumps(self.inputs))
        status, stderr = self.evaluate()
        self.assertEqual(status, 2)
        self.assertIn("is not a vector file", stderr)

    def test_a_record_with_a_malformed_artifact_entry_is_refused(self):
        for entry in ({"artifact": "onnx"}, {"artifact": "fp64", "sha256": "0" * 64}, "vectors"):
            self.inputs["artifacts"]["vectors-onnx-x.npz"] = entry
            write(self.run, ev.INPUTS_NAME, json.dumps(self.inputs))
            self.assertEqual(self.evaluate()[0], 2, entry)

    def test_a_missing_artifact_is_refused(self):
        del self.inputs["artifacts"][ev.vectors_name("coreml", "")]
        write(self.run, ev.INPUTS_NAME, json.dumps(self.inputs))
        status, stderr = self.evaluate()
        self.assertEqual(status, 2)
        self.assertIn("run `embed` for coreml", stderr)

    def test_a_primary_target_outside_the_targets_is_refused(self):
        status, stderr = self.evaluate("--targets", "0.8", "--primary-target", "0.9")
        self.assertEqual(status, 2)

    def test_an_out_inside_the_export_is_refused(self):
        export = os.path.join(self.dir, "export")
        os.makedirs(export)
        status, stderr = self.evaluate(*self.TARGET, "--export", export, "--out", os.path.join(export, "results"))
        self.assertEqual(status, 2)
        self.assertEqual(os.listdir(export), [])


class SurveyArithmetic(unittest.TestCase):
    def test_a_small_count_of_images_is_not_written(self):
        self.assertEqual(ev.shown(0, 554, 10), {"count": 0, "rate": 0.0, "suppressed": False})       # never: describes the library
        self.assertEqual(ev.shown(10, 554, 10), {"count": 10, "rate": 10 / 554, "suppressed": False})
        for count in (1, 3, 9):
            self.assertEqual(ev.shown(count, 554, 10), {"count": None, "rate": None, "suppressed": True, "near": "none"}, count)
        # Withheld because nearly all is not "fewer than ten".
        self.assertEqual(ev.shown(551, 554, 10), {"count": None, "rate": None, "suppressed": True, "near": "all"})
        self.assertEqual(ev.shown(1, 554, 1)["count"], 1)  # a smallest count of 1 suppresses nothing
        # "On all but three" is about three photographs too; "on all" is about the library.
        self.assertTrue(ev.shown(551, 554, 10)["suppressed"])
        self.assertEqual(ev.shown(544, 554, 10)["count"], 540)
        # A written count is rounded to a multiple of the smallest count.
        # First on ten photographs and among the first three on thirteen is "second or third on
        # exactly three"; 20 + 13 − 30 is three photographs with both labels. No rule on each
        # figure alone stops a subtraction, so no written count is exact.
        self.assertEqual([ev.rounded(count, 10) for count in (10, 13, 14, 15, 24, 25, 127, 554)], [10, 10, 10, 20, 20, 30, 130, 550])
        self.assertEqual([ev.rounded(count, 1) for count in (0, 3, 13)], [0, 3, 13])
        self.assertEqual((ev.shown(10, 60, 10)["count"], ev.shown(13, 60, 10)["count"]), (10, 10))
        self.assertEqual((ev.shown(20, 60, 10)["count"], ev.shown(13, 60, 10)["count"], ev.shown(30, 60, 10)["count"]), (20, 10, 30))
        self.assertEqual(ev.shown(127, 554, 10), {"count": 130, "rate": 130 / 554, "suppressed": False})
        self.assertEqual((ev.shown(0, 554, 10)["count"], ev.shown(554, 554, 10)["count"]), (0, 554))   # none and all are said as they are
        self.assertEqual(ev.shown(554, 554, 10), {"count": 554, "rate": 1.0, "suppressed": False})

    def test_a_spread_has_no_minimum_and_no_maximum(self):
        got = ev.spread([0.0, 1.0, 2.0, 3.0, 4.0])
        self.assertEqual((got["mean"], got["sd"]), (2.0, math.sqrt(2.0)))
        self.assertEqual(got["quantiles"], {"0.05": 0.0, "0.25": 1.0, "0.5": 2.0, "0.75": 3.0, "0.95": 4.0})
        # Each end of a row is one image's score. Over 100 images neither is among the quantiles.
        wide = ev.spread([float(index) for index in range(100)])
        self.assertEqual(sorted(wide["quantiles"]), ["0.05", "0.25", "0.5", "0.75", "0.95"])
        self.assertNotIn(0.0, wide["quantiles"].values())
        self.assertNotIn(99.0, wide["quantiles"].values())

    def test_the_top_labels_are_the_highest_scores_and_a_tie_goes_to_the_first(self):
        self.assertEqual(ev.top_labels([0.1, 0.9, 0.5, 0.9], 3), [1, 3, 2])
        self.assertEqual(ev.top_labels([0.1, 0.9], 3), [1, 0])

    def test_the_last_line_never_says_scored(self):
        line = ev.survey_line(207, 554, [], [])
        self.assertEqual(line, "label survey: MEASURED — 207 labels over 554 images, no ground truth")
        with_floors = ev.survey_line(207, 554, [{"file": "r.json", "prompt": "bare"}], ["the label set is not the committed candidate set"])
        self.assertTrue(with_floors.startswith("label survey: OVERRIDDEN MEASURED"), with_floors)
        self.assertIn("r.json (prompt bare)", with_floors)
        self.assertIn("an instrument", with_floors)
        self.assertNotIn("scored", line + with_floors)

    def test_a_manifest_says_what_its_truth_is(self):
        with tempfile.TemporaryDirectory() as scratch:
            text = corpus_manifest_text(["a.jpg"])
            self.assertEqual(ev.manifest_truth(write(scratch, "plain.txt", text)), "coco")  # the committed manifest has no line
            self.assertEqual(ev.manifest_truth(write(scratch, "none.txt", text.replace("pixels=", "truth=none\npixels="))), "none")
            # Below the blank line it is an image line, not a header.
            self.assertEqual(ev.manifest_truth(write(scratch, "late.txt", text + "truth=none\n")), "coco")
            with self.assertRaises(ev.Refused):
                ev.manifest_truth(write(scratch, "odd.txt", text.replace("pixels=", "truth=imagenet\npixels=")))
        self.assertEqual(ev.manifest_truth(ev.DEFAULT_CORPUS_MANIFEST), "coco")

    @unittest.skipIf(importlib.util.find_spec("PIL") is None, "needs Pillow")
    def test_a_manifest_of_a_corpus_with_no_truth_names_its_listed_images_and_no_source(self):
        from PIL import Image

        with tempfile.TemporaryDirectory() as scratch:
            corpus = os.path.join(scratch, "photos")
            os.makedirs(corpus)
            for index, name in enumerate(("b.png", "a.png", "stray.png")):
                Image.new("RGB", (4, 4), (40 * index, 90, 200)).save(os.path.join(corpus, name))
            names = write(scratch, "names.txt", "b.png\na.png\n")
            target = os.path.join(scratch, "manifest.txt")
            self.assertEqual(run_main("manifest", "--allow-unpinned", "--truth", "none", "--image-list", names, "--corpus", corpus, "--out", target, "--side", "4"), (0, ""))
            manifest = gate.load_corpus_manifest(target)
            self.assertEqual([entry["name"] for entry in manifest["files"]], ["a.png", "b.png"])   # not the stray file
            self.assertEqual(ev.manifest_truth(target), "none")
            # The default source is not COCO's address, which would be wrong on somebody's photographs.
            self.assertEqual(manifest["source"], ev.NO_SOURCE)
            with open(target, encoding="utf-8") as handle:
                self.assertNotIn("cocodataset", handle.read())
            with self.assertRaises(ev.Refused):
                ev.manifest_annotations(target)   # it pins none
            self.assertEqual(run_main("manifest", "--allow-unpinned", "--truth", "none", "--image-list", names, "--corpus", corpus, "--out", target, "--side", "4",
                                      "--source-url", "https://example.invalid/set/")[0], 0)
            self.assertEqual(gate.load_corpus_manifest(target)["source"], "https://example.invalid/set/")
        self.assertEqual(gate.load_corpus_manifest(ev.DEFAULT_CORPUS_MANIFEST)["source"], ev.COCO_SOURCE_URL)

    def test_an_image_list_is_distinct_plain_names(self):
        with tempfile.TemporaryDirectory() as scratch:
            self.assertEqual(ev.read_image_list(write(scratch, "ok.txt", "b.jpg\n\na.jpg\n")), ["a.jpg", "b.jpg"])
            for bad in ("", "\n\n", "a.jpg\na.jpg\n", "sub/a.jpg\n", "a.jpg\tx\n", " a.jpg\n"):
                with self.assertRaises(ev.Refused, msg=repr(bad)):
                    ev.read_image_list(write(scratch, "bad.txt", bad))


@unittest.skipIf(numpy is None, "needs numpy")
class Survey(unittest.TestCase):
    """`survey` over made-up vectors: what it writes, what it never writes, and whose floors it
    will apply."""

    IMAGES = 60

    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.dir = self.scratch.name
        self.labels = write(self.dir, "labels.tsv", LABELS)
        self.mapping = write(self.dir, "mapping.tsv", MAPPING)
        self.names = [f"holiday-{index:03d}.jpg" for index in range(self.IMAGES)]
        self.manifest = write(self.dir, "library.txt", corpus_manifest_text(self.names).replace("pixels=", "truth=none\npixels="))
        self.run = self.make_run("library", self.manifest, self.IMAGES, seed=1)

    def make_run(self, name, manifest, count, seed, texts=None) -> str:
        """A run directory over `count` images. Image i scores 1 on `people` when i % 2 == 0, on
        `dog` when i % 4 == 0, on `cat` for the first three images only, and noise elsewhere."""
        run = os.path.join(self.dir, name)
        os.makedirs(run)
        rng = numpy.random.default_rng(seed)
        truth = numpy.zeros((count, 8), dtype=numpy.float32)
        for index in range(count):
            truth[index, 0] = index % 2 == 0
            truth[index, 2] = index % 4 == 0
            truth[index, 3] = index < 3 if name == "library" else index % 4 == 1
            truth[index, 1] = truth[index, 2] or truth[index, 3]
        texts = numpy.tile(numpy.eye(5, 8, dtype=numpy.float32), (3, 1)) if texts is None else texts
        artifacts = {}
        for artifact in ev.ARTIFACTS:
            images = truth + (1 - truth) * rng.normal(0, 0.05, truth.shape).astype(numpy.float32)
            path = os.path.join(run, ev.vectors_name(artifact, ""))
            numpy.savez(path, images=images, texts=texts)
            artifacts[os.path.basename(path)] = {"artifact": artifact, "sha256": gate.sha256_of(path)}
        inputs = {"labels": {"sha256": gate.sha256_of(self.labels)},
                  "corpus": {"id": gate.load_corpus_manifest(manifest)["id"], "count": count, "manifestSha256": gate.sha256_of(manifest), "pixelsSha256": "0" * 64},
                  "texts": {"tokenRowsSha256": "0" * 64}, "artifacts": artifacts}
        write(run, ev.INPUTS_NAME, json.dumps(inputs))
        return run

    def survey(self, *extra, run=None, manifest=None):
        with contextlib.redirect_stdout(io.StringIO()) as stdout, contextlib.redirect_stderr(io.StringIO()) as stderr:
            status = ev.main(["survey", "--run", run or self.run, "--labels", self.labels, "--corpus-manifest", manifest or self.manifest, "--allow-unpinned", *extra])
        return status, stderr.getvalue(), stdout.getvalue()

    def result(self, directory=None) -> dict:
        with open(os.path.join(directory or self.run, ev.SURVEY_RESULT_NAME), encoding="utf-8") as handle:
            return json.load(handle)

    def floors(self, prompt="tsv") -> str:
        """An `evaluate` result over an ANNOTATED corpus of the same artifacts, to borrow floors from."""
        names = [f"{index:03d}.jpg" for index in range(80)]
        instances = [(name, "person", 0, 5000.0) for index, name in enumerate(names) if index % 2 == 0]
        instances += [(name, "dog", 0, 5000.0) for index, name in enumerate(names) if index % 4 == 0]
        instances += [(name, "cat", 0, 5000.0) for index, name in enumerate(names) if index % 4 == 1]
        annotations = write(self.dir, ev.ANNOTATIONS_NAME, json.dumps(annotations_document({name: (100, 100) for name in names}, instances)))
        manifest = write(self.dir, f"annotated-{prompt}.txt", corpus_manifest_text(names, annotations))
        run = self.make_run(f"annotated-{prompt}", manifest, 80, seed=2)
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            status = ev.main(["evaluate", "--run", run, "--annotations", annotations, "--labels", self.labels, "--mapping", self.mapping, "--corpus-manifest", manifest,
                              "--allow-unpinned", "--bootstrap", "5", "--targets", "0.99", "--primary-target", "0.99", "--prompt", prompt])
        self.assertEqual(status, 0)
        return os.path.join(run, ev.RESULT_NAME)

    def test_a_survey_writes_aggregates_and_names_no_image(self):
        status, stderr, stdout = self.survey()
        self.assertEqual((status, stderr), (0, ""))
        self.assertEqual(sorted(name for name in os.listdir(self.run) if name.startswith("label-survey")), sorted(ev.SURVEY_OUTPUTS))
        self.assertEqual(ev.SURVEY_OUTPUTS[-1], ev.SURVEY_RESULT_NAME)  # the result is written last
        self.assertEqual([name for name in os.listdir(self.run) if name.startswith(".")], [])
        result = self.result()
        self.assertEqual((result["format"], result["labels"], result["smallestCount"]), (ev.SURVEY_FORMAT, ["people", "animals", "dog", "cat", "indoors"], 10))
        self.assertEqual(sorted(result["variants"]), sorted(ev.PROMPT_VARIANTS))
        self.assertEqual((result["inputs"]["truth"], result["floors"]), ("none", []))
        # ⚠⚠ The corpus may be someone's photographs: nothing either file holds is about one image.
        with open(os.path.join(self.run, ev.SURVEY_TABLES_NAME), encoding="utf-8") as handle:
            written = handle.read() + json.dumps(result) + stdout
        for name in self.names:
            self.assertNotIn(name, written)
        self.assertNotIn("holiday", written)
        people = result["variants"]["tsv"]["perLabel"]["people"]
        self.assertGreaterEqual(people["top1"]["onnx"]["count"], 30)   # every even image, and wherever noise put it first
        self.assertGreaterEqual(people["topK"]["onnx"]["count"], people["top1"]["onnx"]["count"])
        self.assertAlmostEqual(people["distribution"]["onnx"]["quantiles"]["0.75"], 1.0)
        self.assertEqual(sorted(people["difference"]), ["coremlVsFp32", "onnxVsCoreml", "onnxVsFp32"])
        # No count of fewer than ten images anywhere in the result, and some were withheld.
        counts = self.counts(result)
        self.assertEqual([entry for entry in counts if entry["count"] is not None and (0 < entry["count"] < 10 or 50 < entry["count"] < 60)], [])
        self.assertTrue(any(entry["suppressed"] for entry in counts))
        self.assertTrue(all((entry["count"] is None) == entry["suppressed"] == (entry["rate"] is None) == ("near" in entry) for entry in counts))
        self.assertIn("label survey: OVERRIDDEN MEASURED — 5 labels over 60 images, no ground truth", stdout)
        self.assertTrue(result["overridden"])

    def counts(self, node) -> list:
        """Every `{count, rate, suppressed}` entry of a result, wherever it sits."""
        if isinstance(node, dict):
            if {"count", "rate", "suppressed"} <= set(node) <= {"count", "rate", "suppressed", "near"}:
                return [node]
            return [entry for value in node.values() for entry in self.counts(value)]
        return [entry for value in node for entry in self.counts(value)] if isinstance(node, list) else []

    def test_a_share_that_would_give_a_small_count_away_is_not_written(self):
        # A Jaccard, a share of identical sets and a mean number of labels can each give back a count
        # the rule withheld.
        sets = [["dog"]] * 40 + [[]] * 20
        other = [["dog"]] * 37 + [["cat"]] * 3 + [[]] * 20                # three photographs differ
        got = ev.shown_set_agreement(sets, other, 10)
        withheld = {"count": None, "rate": None, "suppressed": True, "near": "cover"}
        self.assertEqual(got, {"images": 60, "identical": None, "eitherLabelled": withheld,
                               "identicalWhereEitherLabelled": None, "meanJaccardWhereEitherLabelled": None})   # 37 / 40 would say "three"
        # Each artifact labels ten images and three are the same ones. Ten and ten are
        # written beside this; a union of seventeen would be the three.
        first, second = [["x"]] * 10 + [[]] * 50, [[]] * 7 + [["y"]] * 10 + [[]] * 43
        self.assertEqual(ev.shown_set_agreement(first, second, 10)["eitherLabelled"], withheld)
        self.assertEqual(ev.shown_set_agreement([["x"]] * 20 + [[]] * 40, [[]] * 10 + [["y"]] * 20 + [[]] * 30, 10)["eitherLabelled"]["count"], 30)   # ten by both: written
        apart = ev.shown_set_agreement(sets, [["cat"]] * 15 + [["dog"]] * 25 + [[]] * 20, 10)
        self.assertEqual((apart["identical"], apart["identicalWhereEitherLabelled"], apart["meanJaccardWhereEitherLabelled"]), (50 / 60, 30 / 40, 0.62))   # of rounded counts: 25 alike is "about thirty"
        # Three labelled alike, 97 apart, 454 labelled by neither. The share of identical
        # sets is 457 / 554, and beside the labelled count of 100 it is "three".
        few = ev.shown_set_agreement([["dog"]] * 100 + [[]] * 454, [["dog"]] * 3 + [["cat"]] * 97 + [[]] * 454, 10)
        self.assertEqual(few, {"images": 554, "identical": None, "eitherLabelled": withheld, "identicalWhereEitherLabelled": None, "meanJaccardWhereEitherLabelled": None})
        nobody = ev.shown_set_agreement([[]] * 60, [[]] * 60, 10)
        self.assertEqual((nobody["identical"], nobody["identicalWhereEitherLabelled"]), (1.0, None))
        self.assertEqual(ev.shown_set_agreement(sets, sets, 10)["identical"], 1.0)
        # End to end: Core ML set to fp32 but for `people` on four photographs. The pooled cells
        # and the Jaccard are withheld; what is said is the bound "fewer than ten" gives.
        floors = self.floors("bare")
        path = os.path.join(self.run, "vectors-fp32.npz")
        with numpy.load(path) as archive:
            images, texts = archive["images"].copy(), archive["texts"]
        moved = images.copy()
        moved[[0, 2, 4, 6], 0] = 0.0
        numpy.savez(os.path.join(self.run, "vectors-coreml.npz"), images=moved, texts=texts)
        with open(os.path.join(self.run, ev.INPUTS_NAME), encoding="utf-8") as handle:
            inputs = json.load(handle)
        inputs["artifacts"]["vectors-coreml.npz"]["sha256"] = gate.sha256_of(os.path.join(self.run, "vectors-coreml.npz"))
        write(self.run, ev.INPUTS_NAME, json.dumps(inputs))
        status, stderr, stdout = self.survey("--floors", floors)
        self.assertEqual((status, stderr), (0, ""))
        pooled = self.result()["floors"][0]["pooled"]["coremlVsFp32"]
        # Nothing pooled, not even the bound: `cat`'s three are the one withheld firing count, and
        # anything pooled beside the three written labels is an equation in it.
        self.assertEqual(pooled, {"both": None, "onlyA": None, "onlyB": None, "either": None, "jaccard": None})
        self.assertIn("| coremlVsFp32 | withheld | withheld | withheld | withheld |", stdout)
        # With no label's count pinned (one count a label, all written), the bound is what is said.
        rows = [[1.0 if index < 60 else 0.0, 0.0, 0.0, 0.0, 0.0] for index in range(100)]
        other = [[1.0 if 4 <= index < 60 else 0.0, 0.0, 0.0, 0.0, 0.0] for index in range(100)]
        entry = {"file": "f", "sha256": "0" * 64, "prompt": "bare", "target": 0.9, "labels": ["people"], "calibratedOn": {"corpus": "c" * 64},
                 "floors": {artifact: {"people": 0.5} for artifact in ev.ARTIFACTS}}
        block = ev.survey_floors({"fp32": rows, "onnx": rows, "coreml": other}, ev.read_label_set(self.labels), entry, 10, 0.01)
        # And no bound in their place: a bound given only when the agreement is ten or more would,
        # beside the two written firing counts, be the whole answer.
        self.assertEqual(block["pooled"]["coremlVsFp32"], {"both": None, "onlyA": None, "onlyB": None, "either": None, "jaccard": None})
        result = {"smallestCount": 10, "inputs": {"corpus": {"id": "0" * 64, "count": 100}}, "labels": ["people"], "variants": {}, "floors": [block]}
        self.assertIn("| coremlVsFp32 | withheld | withheld | withheld | withheld |", ev.survey_tables(result))
        agreement = self.result()["floors"][0]["selection"]["agreement"]["coremlVsFp32"]
        self.assertEqual((agreement["identical"], agreement["identicalWhereEitherLabelled"]), (None, None))
        self.assertEqual(sorted(self.result()["floors"][0]["perLabel"]["people"]), ["firing", "floor"])   # no pair agreement a label
        self.assertEqual(self.counts(self.result()["floors"][0]["selection"]["agreement"]), [entry["eitherLabelled"] for entry in self.result()["floors"][0]["selection"]["agreement"].values()])

    def test_a_group_with_a_public_total_does_not_give_its_one_withheld_count_back(self):
        # Every image has one top label, so the top-1 counts sum to the
        # corpus: one withheld count of three is 60 less the others.
        self.assertTrue(ev.group_gives_one_back([30, 17, 15, 3], 10))
        self.assertTrue(ev.group_gives_one_back([30, 3, 4], 10))        # two, and their sum is still a handful
        self.assertFalse(ev.group_gives_one_back([30, 6, 7], 10))       # two that sum to thirteen: 4 + 9, 5 + 8, 6 + 7 …
        self.assertFalse(ev.group_gives_one_back([30, 17, 0], 10))
        # A sum at its ceiling or its floor pins every count. 18 over two is nine and nine.
        self.assertTrue(ev.group_gives_one_back([20, 9, 9], 10))
        self.assertTrue(ev.group_gives_one_back([20] + [1] * 12, 10))
        self.assertFalse(ev.group_gives_one_back([20, 9, 8], 10))
        # A count withheld as "nearly all" is given back by any total it is part of.
        self.assertTrue(ev.group_gives_one_back([57, 20], 10, each_of=60))
        self.assertFalse(ev.group_gives_one_back([50, 20], 10, each_of=60))
        self.assertFalse(ev.group_gives_one_back([60, 20], 10, each_of=60))   # all of them is about the library
        self.assertEqual([ev.hidden_counts_are_ambiguous(hidden, 10) for hidden in ([3], [3, 4], [9, 9], [1] * 12, [9, 8], [1] * 11 + [2])], [False, False, False, False, True, True])

        def covered(counts, total=60):
            entries = [ev.shown(count, total, 10) for count in counts]
            ev.cover_group(entries, counts, 10)
            return [entry.get("near") for entry in entries]

        # Pinned: the whole group goes, under one marker. Covering "the smallest neighbours until
        # there is room" would stop at a place that depends on the hidden count, and (1, 18, 21)
        # over 40 would read back as exactly 1.
        self.assertEqual(covered([40, 22, 14, 3, 0], 79), ["cover"] * 5)
        self.assertEqual(covered([1, 18, 21], 40), ["cover"] * 3)
        self.assertEqual(covered([2, 17, 21], 40), ["cover"] * 3)                                 # and so does its neighbour: nothing tells them apart
        self.assertEqual(covered([30, 12, 9, 9, 0]), ["cover"] * 5)                               # 9 and 9
        self.assertEqual(covered([1] * 10 + [0, 0], 10), ["cover"] * 12)                         # ten ones
        self.assertEqual(covered([30, 20, 9, 8, 0], 67), [None, None, "none", "none", None])     # 17 over two is not pinned: nothing more is withheld
        # Top-3 counts over 60 images sum to 180. With 57 withheld as "nearly all" and
        # the rest written, 180 less the rest is the 57.
        self.assertEqual(covered([57, 50, 40, 33], 60), ["cover"] * 4)
        self.assertEqual(covered([57, 50, 40, 9, 8, 16], 60), ["cover"] * 6)                     # though 9 and 8 alone would be ambiguous
        untouched = [ev.shown(count, 60, 10) for count in (30, 20, 10, 0)]
        ev.cover_group(untouched, [30, 20, 10, 0], 10)
        self.assertFalse(any(entry["suppressed"] for entry in untouched))
        # End to end: in every top-1 and top-3 group of a survey, the withheld counts are none,
        # or at least two that cannot be told apart.
        self.assertEqual(self.survey()[0], 0)
        for variant in self.result()["variants"].values():
            for kind in ("top1", "topK"):
                for artifact in ev.ARTIFACTS:
                    hidden = [entry[kind][artifact] for entry in variant["perLabel"].values() if entry[kind][artifact]["suppressed"]]
                    self.assertNotEqual(len(hidden), 1, (kind, artifact))
        self.assertEqual(ev.shown_rank({"suppressed": True, "near": "cover"}), 0.0)
        # …and where a survey does produce a lone one: 30, 27 and 3 images led by three labels.
        labels = ev.read_label_set(self.labels)
        lead = [0] * 30 + [1] * 27 + [3] * 3
        rows = [[1.0 if column == top else -0.1 * (column + 1) for column in range(5)] for top in lead]
        block = ev.survey_variant({artifact: rows for artifact in ev.ARTIFACTS}, labels, 10)
        top1 = {label: block["perLabel"][label]["top1"]["onnx"] for label in ("people", "animals", "dog", "cat", "indoors")}
        self.assertEqual({label: entry.get("near") for label, entry in top1.items()}, {label: "cover" for label in top1})
        self.assertTrue(all(entry["count"] is None for entry in top1.values()))

    def test_a_pairs_cells_are_written_together_or_not_at_all(self):
        # `both`, one-sided cells, `either` and each artifact's firing count are sums of each other;
        # one withheld cell would come back by subtraction.
        whole = ev.paired_cells({"both": 100, "onlyA": 12, "onlyB": 20}, 10)
        self.assertEqual(whole, {"both": 100, "onlyA": 10, "onlyB": 20, "either": 130, "jaccard": 100 / 130})   # written rounded: 12 is "about ten"
        nothing = {"both": None, "onlyA": None, "onlyB": None, "either": None, "jaccard": None}
        # One side differs on three: with `either` and the other side's 120 written, 123 − 120 is the three.
        self.assertEqual(ev.paired_cells({"both": 100, "onlyA": 3, "onlyB": 20}, 10), nothing)
        self.assertEqual(ev.paired_cells({"both": 4, "onlyA": 30, "onlyB": 20}, 10), nothing)
        # Rounds 3, 12 and 13: nothing stands in for withheld cells. A bound ("at least 0.94") and a
        # share to two places were each tried, and each said something by being there at all.
        self.assertEqual(ev.paired_cells({"both": 340, "onlyA": 1, "onlyB": 3}, 10), nothing)
        self.assertEqual(ev.paired_cells({"both": 10, "onlyA": 0, "onlyB": 1}, 10), nothing)      # marginals 10 and 11: a bound here gave `both` away
        self.assertEqual(ev.paired_cells({"both": 0, "onlyA": 0, "onlyB": 0}, 10), {"both": 0, "onlyA": 0, "onlyB": 0, "either": 0, "jaccard": None})
        self.assertEqual(ev.paired_cells({"both": 340, "onlyA": 1, "onlyB": 3}, 1)["onlyA"], 1)   # a smallest count of 1 withholds nothing

    def test_a_quantile_is_written_only_with_enough_images_on_each_side_of_it(self):
        # On 10 to 19 images the 5 % and 95 % points are the minimum and
        # the maximum, which are one photograph's score each.
        short = ev.spread([float(index) for index in range(12)], 10)
        self.assertEqual(short["quantiles"], {"0.05": None, "0.25": None, "0.5": None, "0.75": None, "0.95": None})
        self.assertEqual(short["mean"], 5.5)
        middling = ev.spread([float(index) for index in range(40)], 10)   # ranks 2, 10, 20, 30, 38
        self.assertEqual(middling["quantiles"], {"0.05": None, "0.25": 9.0, "0.5": 19.0, "0.75": 29.0, "0.95": None})
        long = ev.spread([float(index) for index in range(200)], 10)      # ranks 10 … 190
        self.assertEqual(long["quantiles"], {"0.05": 9.0, "0.25": 49.0, "0.5": 99.0, "0.75": 149.0, "0.95": 189.0})
        self.assertIsNone(ev.spread([float(index) for index in range(180)], 10)["quantiles"]["0.05"])   # rank 9 of 180: nine at or below
        # End to end, 60 images: the 5 % point (rank 3) is withheld, the quartiles are not.
        self.assertEqual(self.survey()[0], 0)
        quantiles = self.result()["variants"]["tsv"]["perLabel"]["dog"]["distribution"]["onnx"]["quantiles"]
        self.assertEqual([quantiles[key] is None for key in ("0.05", "0.25", "0.5", "0.75", "0.95")], [True, False, False, False, True])
        with open(os.path.join(self.run, ev.SURVEY_TABLES_NAME), encoding="utf-8") as handle:
            self.assertIn("[—, —]", handle.read())

    def test_nothing_pooled_is_written_beside_a_label_that_fires_on_nearly_everything(self):
        # 57 of 60 photographs and 20 of 60, alike on every artifact: a pooled 77 less the written 20 is the 57.
        labels = ev.read_label_set(self.labels)
        rows = [[1.0 if index >= 3 else 0.0, 0.0, 0.0, 1.0 if index < 20 else 0.0, 0.0] for index in range(60)]
        entry = {"file": "f", "sha256": "0" * 64, "prompt": "bare", "target": 0.9, "labels": ["people", "cat"], "calibratedOn": {"corpus": "c" * 64},
                 "floors": {artifact: {"people": 0.5, "cat": 0.5} for artifact in ev.ARTIFACTS}}
        block = ev.survey_floors({artifact: rows for artifact in ev.ARTIFACTS}, labels, entry, 10, 0.01)
        self.assertEqual((block["perLabel"]["people"]["firing"]["onnx"]["near"], block["perLabel"]["cat"]["firing"]["onnx"]["count"]), ("all", 20))
        self.assertEqual(block["pooled"]["onnxVsCoreml"], {"both": None, "onlyA": None, "onlyB": None, "either": None, "jaccard": None})

    def test_pooled_cells_are_written_only_when_every_labels_own_could_be(self):
        # `people` fires on all 60 on both artifacts; `cat` on 20 each,
        # agreeing on 3. Every firing count is written, and `people` must contribute 60 to the
        # pooled `both`: a pooled 63 would be `cat`'s three.
        labels = ev.read_label_set(self.labels)
        one = [[1.0, 0.0, 0.0, 1.0 if index < 20 else 0.0, 0.0] for index in range(60)]
        other = [[1.0, 0.0, 0.0, 1.0 if 17 <= index < 37 else 0.0, 0.0] for index in range(60)]
        entry = {"file": "f", "sha256": "0" * 64, "prompt": "bare", "target": 0.9, "labels": ["people", "cat"], "calibratedOn": {"corpus": "c" * 64},
                 "floors": {artifact: {"people": 0.5, "cat": 0.5} for artifact in ev.ARTIFACTS}}
        block = ev.survey_floors({"fp32": one, "onnx": one, "coreml": other}, labels, entry, 10, 0.01)
        self.assertEqual([block["perLabel"][label]["firing"][artifact]["count"] for label in ("people", "cat") for artifact in ("onnx", "coreml")], [60, 60, 20, 20])
        pooled = block["pooled"]["onnxVsCoreml"]
        self.assertEqual((pooled["both"], pooled["onlyA"], pooled["onlyB"], pooled["either"], pooled["jaccard"]), (None, None, None, None, None))
        many = [[1.0, 1.0 if index < 150 else 0.0, 0.0, 1.0 if index < 20 else 0.0, 0.0] for index in range(300)]
        more = [[1.0, 1.0 if 30 <= index < 180 else 0.0, 0.0, 1.0 if 17 <= index < 37 else 0.0, 0.0] for index in range(300)]
        entry["labels"], entry["floors"] = ["people", "animals", "cat"], {artifact: {"people": 0.5, "animals": 0.5, "cat": 0.5} for artifact in ev.ARTIFACTS}
        block = ev.survey_floors({"fp32": many, "onnx": many, "coreml": more}, labels, entry, 10, 0.01)
        self.assertEqual(block["pooled"]["onnxVsCoreml"], {"both": None, "onlyA": None, "onlyB": None, "either": None, "jaccard": None})   # however many pairs
        result = {"smallestCount": 10, "inputs": {"corpus": {"id": "0" * 64, "count": 300}}, "labels": [row["label"] for row in labels], "variants": {}, "floors": [block]}
        self.assertIn("| onnxVsCoreml | withheld | withheld | withheld | withheld |", ev.survey_tables(result))
        # And with every label's cells writable, the cells themselves.
        entry["labels"], entry["floors"] = ["people", "animals"], {artifact: {"people": 0.5, "animals": 0.5} for artifact in ev.ARTIFACTS}
        whole = ev.survey_floors({"fp32": many, "onnx": many, "coreml": more}, labels, entry, 10, 0.01)["pooled"]["onnxVsCoreml"]
        self.assertEqual(whole, {"both": 420, "onlyA": 30, "onlyB": 30, "either": 480, "jaccard": 420 / 480})

    def test_a_mean_number_of_labels_over_a_handful_of_labels_is_not_written(self):
        # Three photographs get a label, one each: 0.05 labels a photograph says "three" as plainly.
        labels = ev.read_label_set(self.labels)
        rows = [[0.0, 0.0, 0.0, 1.0 if index < 3 else 0.0, 0.0] for index in range(60)]
        floors = {artifact: {"people": None, "animals": None, "dog": None, "cat": 0.5} for artifact in ev.ARTIFACTS}
        entry = {"file": "f", "sha256": "0" * 64, "prompt": "bare", "target": 0.9, "labels": ["people", "animals", "dog", "cat"], "floors": floors, "calibratedOn": {"corpus": "c" * 64}}
        block = ev.survey_floors({artifact: rows for artifact in ev.ARTIFACTS}, labels, entry, 10, 0.01)
        # A mean number of labels is every label's count summed, so beside the written ones it would
        # return the withheld ones. It is not written at all.
        self.assertEqual(sorted(block["selection"]["artifacts"]["onnx"]), ["imagesWithALabel", "perLabel"])
        self.assertTrue(block["selection"]["artifacts"]["onnx"]["imagesWithALabel"]["suppressed"])
        self.assertEqual(block["pooled"]["onnxVsCoreml"], {"both": None, "onlyA": None, "onlyB": None, "either": None, "jaccard": None})
        self.assertEqual(ev.survey_floors({artifact: rows for artifact in ev.ARTIFACTS}, labels, entry, 1, 0.01)["selection"]["artifacts"]["onnx"]["imagesWithALabel"]["count"], 3)
        # One label on all but three photographs. 57 / 60 as a mean is the 57.
        most = [[0.0, 0.0, 0.0, 1.0 if index >= 3 else 0.0, 0.0] for index in range(60)]
        block = ev.survey_floors({artifact: most for artifact in ev.ARTIFACTS}, labels, entry, 10, 0.01)
        self.assertEqual(block["selection"]["artifacts"]["onnx"]["imagesWithALabel"]["near"], "all")
        self.assertEqual(block["perLabel"]["cat"]["firing"]["onnx"]["near"], "all")
        self.assertEqual(block["firingFlags"]["onnx"], ["cat"])
        # Nearly all sorts first and is flagged; withheld-as-few sorts last.
        self.assertEqual([ev.shown_rank(ev.shown(count, 60, 10)) for count in (57, 30, 3, 0)], [1.0, 0.5, 0.0, 0.0])
        result = {"smallestCount": 10, "inputs": {"corpus": {"id": "0" * 64, "count": 60}}, "labels": [row["label"] for row in labels], "variants": {}, "floors": [block]}
        table = ev.survey_tables(result)
        self.assertIn("all but < 10 images ⚠", table)     # and never "< 10 images" for 57 of 60
        self.assertNotIn("| < 10 images", table)

    def test_a_smallest_count_of_one_writes_every_count(self):
        self.assertEqual(self.survey("--smallest-count", "1")[0], 0)
        counts = self.counts(self.result())
        self.assertFalse(any(entry["suppressed"] for entry in counts))
        self.assertTrue(any(0 < entry["count"] < 10 for entry in counts))

    def test_the_offset_and_the_spread_of_a_pair_are_what_was_put_in(self):
        # Core ML is fp32 shifted by 0.25 on `people` exactly: an offset, and no spread.
        path = os.path.join(self.run, "vectors-fp32.npz")
        with numpy.load(path) as archive:
            images, texts = archive["images"].copy(), archive["texts"]
            shifted = images.copy()
            shifted[:, 0] += 0.25
            numpy.savez(os.path.join(self.run, "vectors-coreml.npz"), images=shifted, texts=texts)
        with open(os.path.join(self.run, ev.INPUTS_NAME), encoding="utf-8") as handle:
            inputs = json.load(handle)
        inputs["artifacts"]["vectors-coreml.npz"]["sha256"] = gate.sha256_of(os.path.join(self.run, "vectors-coreml.npz"))
        write(self.run, ev.INPUTS_NAME, json.dumps(inputs))
        self.assertEqual(self.survey()[0], 0)
        result = self.result()
        entry = result["variants"]["bare"]["perLabel"]
        self.assertAlmostEqual(entry["people"]["difference"]["coremlVsFp32"]["mean"], 0.25, places=6)
        self.assertAlmostEqual(entry["people"]["difference"]["coremlVsFp32"]["sd"], 0.0, places=6)
        self.assertAlmostEqual(entry["dog"]["difference"]["coremlVsFp32"]["mean"], 0.0, places=6)
        self.assertEqual(result["variants"]["bare"]["pairs"]["coremlVsFp32"]["labelsByAbsoluteOffset"][0], "people")
        self.assertAlmostEqual(result["variants"]["bare"]["pairs"]["coremlVsFp32"]["offsetRange"][1], 0.25, places=6)

    def test_another_corpus_floors_are_applied_under_the_prompt_they_were_placed_for(self):
        floors = self.floors("bare")
        status, stderr, stdout = self.survey("--floors", floors)
        self.assertEqual((status, stderr), (0, ""))
        block, = self.result()["floors"]
        self.assertEqual((block["prompt"], block["target"], block["sha256"]), ("bare", 0.99, gate.sha256_of(floors)))
        self.assertEqual(block["labelsWithAFloor"], {artifact: 4 for artifact in ev.ARTIFACTS})
        self.assertEqual(sorted(block["perLabel"]), ["animals", "cat", "dog", "people"])   # `indoors` has no floor: it was never scored
        self.assertEqual(block["perLabel"]["people"]["firing"]["onnx"], {"count": 30, "rate": 0.5, "suppressed": False})
        self.assertEqual(block["firingFlags"]["onnx"], ["animals", "dog", "people"])        # 17, 15 and 30 of 60
        # Three cats: neither the count nor a Jaccard over them is written.
        self.assertEqual(block["perLabel"]["cat"]["firing"]["onnx"], {"count": None, "rate": None, "suppressed": True, "near": "none"})
        self.assertEqual(block["selection"]["agreement"]["onnxVsCoreml"]["identical"], 1.0)
        # 30 + 17 + 15 are written and `cat`'s three are not, so a pooled 65 would be the three, and
        # so would a pooled Jaccard, beside the cells of
        # the three labels that are written. Nothing pooled is written for the pair.
        self.assertEqual(block["pooled"]["onnxVsCoreml"], {"both": None, "onlyA": None, "onlyB": None, "either": None, "jaccard": None})
        # A label's own pair cells, taken from the pool, leave another label's. None are written.
        self.assertEqual(sorted(block["perLabel"]["people"]), ["firing", "floor"])
        self.assertEqual(block["selection"]["artifacts"]["onnx"]["perLabel"]["people"]["count"], 30)
        self.assertIn("floors applied from annotated-bare/label-eval-result.json (prompt bare)", stdout)
        self.assertIn("an instrument", stdout)

    def test_floors_that_belong_to_other_vectors_or_say_no_prompt_are_refused(self):
        floors = self.floors()
        before = tree(self.run)
        with open(floors, encoding="utf-8") as handle:
            result = json.load(handle)

        def refused(change, said):
            altered = json.loads(json.dumps(result))
            change(altered)
            status, stderr, _ = self.survey("--floors", write(self.dir, "altered.json", json.dumps(altered)))
            self.assertEqual(status, 2, said)
            self.assertIn(said, stderr)
            self.assertEqual(tree(self.run), before, said)

        refused(lambda r: r.update(format="label-eval 2"), "is not a 'label-eval 3' result")          # an older result: which prompt?
        refused(lambda r: r.pop("promptVariant"), "a floors file is malformed")
        refused(lambda r: r.update(promptVariant="shouted"), "--prompt is one of")
        refused(lambda r: r["inputs"]["labels"].update(sha256="0" * 64), "another label set")
        refused(lambda r: r["inputs"]["texts"].update(tokenRowsSha256="1" * 64), "other token rows")
        refused(lambda r: r["inputs"]["artifacts"]["vectors-onnx.npz"].update(files={"vision": {"sha256": "another model"}}), "not from the artifact this run embedded")
        refused(lambda r: r["inputs"]["artifacts"]["vectors-coreml.npz"].update(computeUnits="cpuOnly"), "not from the artifact this run embedded")
        refused(lambda r: r["perLabel"]["onnx"]["dog"]["targets"]["0.99"].update(threshold="high"), "is not a number")
        # The same bytes under another runtime are another table of floors (`models.md` §3.10.6), so
        # the identity check covers the runtime too.
        refused(lambda r: r["inputs"]["artifacts"]["vectors-onnx.npz"].update(toolchain={"onnxruntime": "1.29.0"}),
                "its onnx vectors were embedded under another runtime than this run's (onnxruntime 1.29.0 there, None here)")
        refused(lambda r: r["inputs"]["artifacts"]["vectors-coreml.npz"].update(where={"macOS": "27.0"}), "macOS 27.0 there, None here")
        self.assertEqual(ev.artifact_runtime({"artifact": "onnx", "toolchain": {"pinned": True, "onnxruntime": "1.20.0"}, "where": {"machine": "arm64", "macOS": "26.6", "threads": 4}}),
                         {"onnxruntime": "1.20.0", "machine": "arm64"})   # neither the flag, nor the thread cap, nor an OS that is not its runtime
        self.assertEqual(ev.artifact_runtime({"artifact": "coreml", "where": {"machine": "arm64", "macOS": "26.6"}}), {"machine": "arm64", "macOS": "26.6"})
        # Two results for one prompt variant: which table?
        status, stderr, _ = self.survey("--floors", floors, "--floors", write(self.dir, "copy.json", json.dumps(result)))
        self.assertEqual(status, 2)
        self.assertIn("two results for one prompt variant", stderr)
        self.assertEqual(tree(self.run), before)

    def test_survey_refuses_a_corpus_that_has_ground_truth(self):
        # Over an annotated run it must not write "no ground truth".
        floors = self.floors()                      # an annotated run, its manifest and its record
        run = os.path.dirname(floors)
        before = tree(run)
        status, stderr, _ = self.survey(run=run, manifest=os.path.join(self.dir, "annotated-tsv.txt"))
        self.assertEqual(status, 2)
        self.assertIn("says truth=coco: `survey` is for a corpus with no ground truth", stderr)
        self.assertEqual(tree(run), before)

    def test_evaluate_still_refuses_a_corpus_with_no_ground_truth(self):
        # The survey is a command of its own so that this stays true.
        annotations = write(self.dir, ev.ANNOTATIONS_NAME, json.dumps(annotations_document({name: (1, 1) for name in self.names}, [])))
        before = tree(self.run)
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()) as stderr:
            status = ev.main(["evaluate", "--run", self.run, "--annotations", annotations, "--labels", self.labels, "--mapping", self.mapping,
                              "--corpus-manifest", self.manifest, "--allow-unpinned"])
        self.assertEqual(status, 2)
        self.assertIn("says truth=none", stderr.getvalue())
        self.assertEqual(tree(self.run), before)

    def test_a_refused_survey_and_one_without_numpy_write_nothing(self):
        self.assertEqual(self.survey()[0], 0)
        before = tree(self.dir)
        other = write(self.dir, "other.txt", corpus_manifest_text(self.names).replace("pixels=", "truth=none\nnote=moved\npixels="))
        for extra, kwargs, said in ((("--smallest-count", "31"), {}, "fewer than twice --smallest-count"), ((), {"manifest": other}, "the same images under other header lines"),
                                    (("--out", self.labels), {}, "is not a directory"), (("--floors", self.labels), {}, "malformed")):
            before = tree(self.dir)
            status, stderr, _ = self.survey(*extra, **kwargs)
            self.assertEqual(status, 2, said)
            self.assertIn(said, stderr)
            self.assertEqual(tree(self.dir), before, said)
        with without("numpy"):
            status, stderr, _ = self.survey("--smallest-count", "5")
        self.assertEqual(status, 2)
        self.assertIn("is not installed", stderr)
        self.assertEqual(tree(self.dir), before)
        with open(os.path.join(self.run, "vectors-onnx.npz"), "ab") as handle:
            handle.write(b"\0")
        self.assertIn("is not the file `embed` recorded", self.survey()[1])

    def test_a_result_can_go_to_a_directory_of_its_own_but_not_into_another_run(self):
        elsewhere = os.path.join(self.dir, "elsewhere")
        before = tree(self.run)
        self.assertEqual(self.survey("--out", elsewhere)[0], 0)
        self.assertEqual((tree(self.run), sorted(os.listdir(elsewhere))), (before, sorted(ev.SURVEY_OUTPUTS)))
        other_run = self.make_run("other", self.manifest, self.IMAGES, seed=3)
        status, stderr, _ = self.survey("--out", other_run)
        self.assertEqual(status, 2)
        self.assertIn("another `embed` run's directory", stderr)
        export = os.path.join(self.dir, "export")
        os.makedirs(export)
        self.assertEqual(self.survey("--export", export, "--out", os.path.join(export, "survey"))[0], 2)
        self.assertEqual(os.listdir(export), [])

    def test_the_headline_prompt_of_an_evaluation_is_the_one_asked_for(self):
        # Texts whose `bare` block is another direction for `dog`: under --prompt bare its scores,
        # and so its floor, are not the `tsv` ones, and the result says which it used.
        tsv, bare = self.floors("tsv"), self.floors("bare")
        with open(tsv, encoding="utf-8") as handle:
            first = json.load(handle)
        with open(bare, encoding="utf-8") as handle:
            second = json.load(handle)
        self.assertEqual((first["promptVariant"], second["promptVariant"]), ("tsv", "bare"))
        self.assertEqual(ev.verdict_line(4, 5, [], [], "tsv"), ev.verdict_line(4, 5, []))
        self.assertTrue(ev.verdict_line(4, 5, [], [], "bare").endswith("; prompt variant: bare"))
        run = os.path.dirname(bare)
        texts = numpy.tile(numpy.eye(5, 8, dtype=numpy.float32), (3, 1))
        texts[5 + 2] = numpy.eye(8, dtype=numpy.float32)[7]   # `dog` under `bare` points at nothing an image holds
        with open(os.path.join(run, ev.INPUTS_NAME), encoding="utf-8") as handle:
            inputs = json.load(handle)
        for artifact in ev.ARTIFACTS:
            path = os.path.join(run, ev.vectors_name(artifact, ""))
            with numpy.load(path) as archive:
                images = archive["images"]
            numpy.savez(path, images=images, texts=texts)
            inputs["artifacts"][os.path.basename(path)]["sha256"] = gate.sha256_of(path)
        write(run, ev.INPUTS_NAME, json.dumps(inputs))
        annotations, manifest = os.path.join(self.dir, ev.ANNOTATIONS_NAME), os.path.join(self.dir, "annotated-bare.txt")
        outcomes = {}
        for prompt in ("tsv", "bare"):
            with contextlib.redirect_stdout(io.StringIO()) as stdout, contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(ev.main(["evaluate", "--run", run, "--annotations", annotations, "--labels", self.labels, "--mapping", self.mapping, "--corpus-manifest", manifest,
                                          "--allow-unpinned", "--bootstrap", "5", "--targets", "0.99", "--primary-target", "0.99", "--prompt", prompt]), 0)
            with open(os.path.join(run, ev.RESULT_NAME), encoding="utf-8") as handle:
                outcomes[prompt] = (json.load(handle), stdout.getvalue())
        self.assertEqual(outcomes["tsv"][0]["perLabel"]["onnx"]["dog"]["targets"]["0.99"]["threshold"], 1.0)
        self.assertIsNone(outcomes["bare"][0]["perLabel"]["onnx"]["dog"]["targets"]["0.99"]["threshold"])   # noise has no floor
        self.assertEqual(outcomes["bare"][0]["perLabel"]["onnx"]["people"]["targets"]["0.99"]["threshold"], 1.0)
        self.assertIn("prompt variant: bare", outcomes["bare"][1])
        self.assertIn("Prompt variant: bare.", outcomes["bare"][1])
        self.assertNotIn("; prompt variant:", outcomes["tsv"][1])
        # The sensitivity table is the same either way: it is every variant, whichever leads.
        self.assertEqual(outcomes["tsv"][0]["sensitivity"]["prompts"], outcomes["bare"][0]["sensitivity"]["prompts"])


# ---- Open Images: a truth verified on some images only -------------------------------------------

OI_CLASSES = "LabelName,DisplayName\n/m/p,Person\n/m/a,Animal\n/m/d,Dog\n/m/c,Cat\n/m/r,Room\n"
OI_HEAD = "label-eval-open-images-mapping 1\n# a comment\n"


def oi_mapping(cat="thin", pools=None) -> str:
    """The fixture's mapping. `dog` is negative where Dog OR Animal is verified absent; `cat` has
    too few verified negatives to score, and is `thin` unless a test says otherwise."""
    pools = pools or OI_POOLS
    row = lambda label, kind, fidelity, positive, negative, note="-": "\t".join([label, kind, fidelity, positive, negative, *map(str, pools[label]), note]) + "\n"
    return (OI_HEAD + row("people", "one-to-one", "exact", "Person", "Person") + row("animals", "one-to-one", "exact", "Animal", "Animal")
            + row("dog", "one-to-one", "exact", "Dog", "Dog;Animal", "no animal, no dog") + row("cat", cat, "narrower", "Cat", "Cat")
            + "indoors\tnone\t-\t-\t-\t-\t-\tno class\n")


OI_IMAGES = 240
OI_POOL = 236  # the last four images are rotated, and are not in the pool


def oi_verified(index: int) -> dict:
    """What the fixture's annotators verified on image `index`: `{class id: present}`.

    Person on two images in three, Animal on three in four, Dog and Cat where present and on a
    few where absent, Room nowhere. So every label has images where nobody looked.
    """
    out = {}
    if index % 3 != 2:
        out["/m/p"] = index % 2 == 0
    if index % 4 != 3:
        out["/m/a"] = index % 4 in (0, 1)
    if index % 4 == 0:
        out["/m/d"] = True
    elif index % 8 == 2:
        out["/m/d"] = False
    if index % 4 == 1:
        out["/m/c"] = True
    elif index % 8 == 6:
        out["/m/c"] = False
    return out


# Verified (positives, negatives) per label over the pool, counted here by the rule and not by
# the code under test.
OI_POOLS = {
    "people": (sum(1 for i in range(OI_POOL) if i % 3 != 2 and i % 2 == 0), sum(1 for i in range(OI_POOL) if i % 3 != 2 and i % 2 == 1)),
    "animals": (sum(1 for i in range(OI_POOL) if i % 4 in (0, 1)), sum(1 for i in range(OI_POOL) if i % 4 == 2)),
    "dog": (sum(1 for i in range(OI_POOL) if i % 4 == 0), sum(1 for i in range(OI_POOL) if i % 4 == 2)),   # Animal absent on every i % 4 == 2
    "cat": (sum(1 for i in range(OI_POOL) if i % 4 == 1), sum(1 for i in range(OI_POOL) if i % 8 == 6)),
}


def oi_image(index: int) -> tuple:
    return ("validation" if index % 2 == 0 else "test"), f"{index:016x}"


def oi_files(directory: str, licence=None, machine=False) -> dict:
    """The official files of the fixture: class descriptions, one labels and one image
    information file a split."""
    licence = licence or ev.OPEN_IMAGES_LICENCE
    paths = {"classes": write(directory, "oidv7-class-descriptions.csv", OI_CLASSES), "annotations": [], "info": []}
    for split in ("validation", "test"):
        rows = ["ImageID,Source,LabelName,Confidence"]
        info = ["ImageID,Subset,OriginalURL,License,Author,Title,Rotation"]
        for index in range(OI_IMAGES):
            if oi_image(index)[0] != split:
                continue
            image = oi_image(index)[1]
            for identifier, present in oi_verified(index).items():
                # The official files write a verified label as 1, 1.0, 0 or 0.0, from two sources.
                rows.append(f"{image},{'verification' if index % 5 else 'crowdsource-verification'},{identifier},{('1' if index % 7 else '1.0') if present else '0.0'}")
            info.append(f'{image},{split},https://example.invalid/{image}.jpg,{licence},"Doe, Jane",Title {index},{"0.0" if index < OI_POOL else "90.0"}')
        if machine and split == "test":
            rows.append(f"{oi_image(1)[1]},machine,/m/c,0.7")
        paths["annotations"].append(write(directory, f"oidv7-{split}-annotations-human-imagelabels.csv", "\n".join(rows) + "\n"))
        paths["info"].append(write(directory, f"{split}-images-with-rotation.csv", "\n".join(info) + "\n"))
    return paths


class OpenImagesTruth(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.dir = self.scratch.name
        self.labels = ev.read_label_set(write(self.dir, "labels.tsv", LABELS))
        self.classes = ev.read_open_images_classes(write(self.dir, "classes.csv", OI_CLASSES))
        self.mapping = ev.load_open_images_mapping(write(self.dir, "mapping.tsv", oi_mapping()), self.labels, self.classes)

    def test_the_class_descriptions_read_and_a_shared_display_name_is_refused(self):
        self.assertEqual(self.classes["Dog"], "/m/d")
        for bad in ("", "DisplayName,LabelName\n/m/d,Dog\n", OI_CLASSES + "/m/x,Dog\n", "LabelName,DisplayName\n", OI_CLASSES + "/m/x\n", OI_CLASSES + ",Empty\n"):
            with self.assertRaises(ev.Refused, msg=repr(bad)):
                ev.read_open_images_classes(write(self.dir, "bad.csv", bad))

    def test_the_mapping_reads_and_what_it_scores_excludes_thin(self):
        self.assertEqual(self.mapping["dog"], {"kind": "one-to-one", "fidelity": "exact", "categories": ["Dog"], "negatives": ["Dog", "Animal"],
                                               "pool": {"positives": OI_POOLS["dog"][0], "negatives": OI_POOLS["dog"][1]}, "note": "no animal, no dog"})
        self.assertEqual((self.mapping["cat"]["kind"], self.mapping["indoors"]["pool"]), ("thin", None))
        self.assertEqual(ev.mapped_labels(self.labels, self.mapping), ["people", "animals", "dog"])

    def test_a_mapping_row_that_contradicts_itself_is_refused(self):
        text = oi_mapping()
        for old, new in ((OI_HEAD, "label-eval-mapping 1\n"),                                          # COCO's magic
                         ("\tDog\tDog;Animal\t", "\tWolf\tDog;Animal\t"), ("\tDog\tDog;Animal\t", "\tDog\tWolf\t"),     # not classes
                         ("\tDog\tDog;Animal\t", "\tDog;Dog\tDog;Animal\t"), ("\tDog\tDog;Animal\t", "\tDog\tDog;Dog\t"),  # repeats
                         ("dog\tone-to-one", "dog\tone-to-many"),                                        # kind against count
                         ("dog\tone-to-one\texact", "dog\tone-to-one\t-"), ("dog\tone-to-one\texact", "dog\tone-to-one\tloose"),
                         ("\tDog\tDog;Animal\t", "\tDog\t-\t"),                                          # mapped, and no way to be absent
                         ("indoors\tnone\t-\t-\t-\t-\t-", "indoors\tnone\t-\t-\tRoom\t-\t-"), ("indoors\tnone\t-\t-\t-\t-\t-", "indoors\tnone\t-\t-\t-\t3\t4"),
                         ("indoors\tnone\t-\t-\t-\t-\t-", "indoors\tthin\tproxy\t-\tRoom\t0\t0"),    # thin has classes
                         (f"\t{OI_POOLS['dog'][0]}\t{OI_POOLS['dog'][1]}\tno animal", "\tmany\tfew\tno animal"),
                         ("indoors\tnone", "people\tnone"), ("indoors\tnone\t-\t-\t-\t-\t-\tno class\n", ""), ("\tno class\n", "\n")):
            self.assertIn(old, text, old)
            with self.assertRaises(ev.Refused, msg=new):
                ev.load_open_images_mapping(write(self.dir, "bad.tsv", text.replace(old, new)), self.labels, self.classes)
        # Without the class descriptions the structure is still checked, the names are not.
        ev.load_open_images_mapping(write(self.dir, "unchecked.tsv", text.replace("\tDog\tDog;Animal\t", "\tWolf\tDog;Animal\t")), self.labels)

    def test_only_human_verified_rows_are_read(self):
        head = "ImageID,Source,LabelName,Confidence\n"
        got = ev.read_open_images_labels(write(self.dir, "ok.csv", head + "a,verification,/m/d,1\na,crowdsource-verification,/m/c,0.0\nb,verification,/m/d,1.0\na,verification,/m/d,1.0\n"))
        self.assertEqual(got, {"a": {"/m/d": True, "/m/c": False}, "b": {"/m/d": True}})
        self.assertEqual(ev.read_open_images_labels(write(self.dir, "more.csv", head + "c,verification,/m/p,0\nb,verification,/m/p,0\n"), into=got, keep={"c"})["c"], {"/m/p": False})
        self.assertNotIn("/m/p", got["b"])  # not kept
        for bad, said in ((head + "a,machine,/m/d,0.7\n", "machine-generated labels are not ground truth"), (head + "a,verification,/m/d,0.7\n", "neither 1"),
                          (head + "a,verification,/m/d,1\na,verification,/m/d,0\n", "both with and without"), ("ImageID,LabelName\n", "does not open with the header"),
                          (head + "a,verification,/m/d\n", "malformed"), (head + "a,verification,/m/d,yes\n", "malformed"), (head + ",verification,/m/d,1\n", "empty")):
            with self.assertRaises(ev.Refused, msg=bad) as refusal:
                ev.read_open_images_labels(write(self.dir, "bad.csv", bad))
            self.assertIn(said, str(refusal.exception))

    def test_a_label_is_present_absent_or_was_not_looked_at(self):
        verdicts = lambda verified: ev.open_images_verdicts(verified, self.mapping, self.classes)
        self.assertEqual(verdicts({"/m/d": True, "/m/a": True}), {"animals": True, "dog": True})
        self.assertEqual(verdicts({"/m/d": False}), {"dog": False})
        # No animal, so no dog: Animal is a negative class of `dog`. A cat is no evidence either way.
        self.assertEqual(verdicts({"/m/a": False}), {"animals": False, "dog": False})
        self.assertEqual(verdicts({"/m/c": True}), {"cat": True})
        # ⚠ A dog verified present says nothing of `animals` here: its row names Animal alone.
        self.assertEqual(verdicts({"/m/d": True}), {"dog": True})
        # Present wins over a negative class verified absent (an inconsistent pair of verifications).
        self.assertEqual(verdicts({"/m/d": True, "/m/a": False}), {"animals": False, "dog": True})
        self.assertEqual(verdicts({}), {})
        self.assertEqual(verdicts({"/m/r": True}), {})  # `indoors` is unmapped

    def test_truth_and_its_mask_cover_the_scored_labels_in_corpus_order(self):
        annotations = {"000000000000000a": {"/m/d": True}, "000000000000000b": {"/m/a": False, "/m/p": True}, "000000000000000c": {"/m/c": True}}
        names = ["test-000000000000000b.jpg", "validation-000000000000000a.jpg", "validation-000000000000000c.jpg"]
        truth, verified = ev.open_images_truth(annotations, self.mapping, self.classes, names)
        self.assertEqual(sorted(truth), ["animals", "dog", "people"])  # `cat` is thin, `indoors` unmapped
        self.assertEqual((truth["dog"], verified["dog"]), ([False, True, False], [True, True, False]))
        self.assertEqual((truth["people"], verified["people"]), ([True, False, False], [True, False, False]))
        self.assertEqual(ev.verified_rows([0, 1, 2], verified), {"animals": [0], "dog": [0, 1], "people": [0]})
        self.assertEqual(ev.verified_rows([2, 0], None), [2, 0])
        self.assertEqual((ev.rows_of([4, 5], "dog"), ev.rows_of({"dog": [1]}, "dog")), ([4, 5], [1]))
        for bad in (["validation-000000000000000a.jpg", "validation-000000000000000d.jpg"], ["000000000000000a.jpg"], ["train-000000000000000a.jpg"], ["validation-A.jpg"]):
            with self.assertRaises(ev.Refused, msg=bad):
                ev.open_images_truth(annotations, self.mapping, self.classes, bad)
        self.assertEqual(ev.open_images_id("test-0123456789abcdef.jpg"), ("test", "0123456789abcdef"))

    def test_a_label_short_of_verified_images_in_a_half_is_named_with_its_counts(self):
        truth = {"dog": [True] * 25 + [False] * 25, "cat": [True] * 25 + [False] * 25}
        halves = {"calibration": {"dog": list(range(0, 50)), "cat": list(range(0, 44))}, "held-out": {"dog": list(range(3, 47)), "cat": list(range(5, 45))}}
        with self.assertRaises(ev.Refused) as refusal:
            ev.refuse_thin(truth, ["dog", "cat"], halves)
        said = str(refusal.exception)
        self.assertIn("cat: 25 positives and 19 negatives verified in the calibration half", said)
        self.assertNotIn("dog", said)                 # 25 / 25 and 22 / 22
        self.assertNotIn("cat: 20 positives", said)   # its held-out half is exactly at the minimum
        self.assertIn("1 label halves", said)
        self.assertEqual(ev.MIN_PER_HALF, 20)
        ev.refuse_thin(truth, ["dog"], halves)
        ev.refuse_thin(truth, ["dog", "cat"], halves, minimum=19)

    def test_wilson_does_not_take_ten_of_ten_for_certainty(self):
        low, high = ev.wilson(10, 10)
        self.assertAlmostEqual(low, 10 / (10 + 1.959964 ** 2), places=6)   # n / (n + z²) when every one is a hit
        self.assertAlmostEqual(high, 1.0, places=12)
        self.assertLess(ev.wilson(34, 34)[0], 0.90)
        self.assertGreaterEqual(ev.wilson(35, 35)[0], 0.90)
        # By hand, 8 of 10: centre (0.8 + 0.192073) / 1.384146 = 0.716741, half-width 0.226...
        low, high = ev.wilson(8, 10)
        self.assertAlmostEqual(low, 0.4902, places=4)
        self.assertAlmostEqual(high, 0.9433, places=4)
        zero = ev.wilson(0, 10)
        self.assertEqual(zero[0], 0.0)
        self.assertAlmostEqual(zero[1], 1 - ev.wilson(10, 10)[0], places=12)  # symmetric
        self.assertIsNone(ev.wilson(0, 0))


@unittest.skipIf(numpy is None, "needs numpy")
class MaskedMetrics(unittest.TestCase):
    """A label verified on some images is scored exactly as it would be on a corpus holding only
    those images: AP, the floor, the held-out figures, the bootstrap and the agreement."""

    IMAGES = 400

    def setUp(self):
        rng = numpy.random.default_rng(7)
        self.labels = [{"label": name, "tier": "top", "parent": None, "prompt": name} for name in ("often", "seldom", "everywhere")]
        self.mapped = [row["label"] for row in self.labels]
        present = rng.random((self.IMAGES, 3)) < 0.4
        self.scores = numpy.where(present, 0.6, 0.4) + rng.normal(0, 0.08, present.shape)
        self.truth = {label: present[:, column].tolist() for column, label in enumerate(self.mapped)}
        # `often` verified on two images in three, `seldom` on one in five, `everywhere` on all.
        self.verified = {"often": [index % 3 != 0 for index in range(self.IMAGES)], "seldom": [index % 5 == 0 for index in range(self.IMAGES)], "everywhere": [True] * self.IMAGES}
        names = [f"{index:04d}.jpg" for index in range(self.IMAGES)]
        cal_names, held_names = ev.split(names, 11)
        self.cal, self.held = [int(name[:4]) for name in cal_names], [int(name[:4]) for name in held_names]
        self.counts = rng.multinomial(len(self.held), [1 / len(self.held)] * len(self.held), size=40)
        self.targets = [0.8, 0.9]

    def masked(self, scores=None, truth=None):
        rows = lambda indices: ev.verified_rows(indices, self.verified)
        return ev.evaluate_artifact(self.scores if scores is None else scores, self.labels, self.mapped, truth or self.truth, rows(self.cal), rows(self.held), self.targets, self.counts,
                                    rows(list(range(self.IMAGES))), self.held)

    def alone(self, label) -> dict:
        """`label` evaluated with no mask, over a corpus of its verified images only."""
        kept = [index for index in range(self.IMAGES) if self.verified[label][index]]
        place = {index: position for position, index in enumerate(kept)}
        held = [index for index in self.held if index in place]
        counts = self.counts[:, [self.held.index(index) for index in held]]
        truth = {label: [self.truth[label][index] for index in kept]}
        column = self.mapped.index(label)
        got = ev.evaluate_artifact(self.scores[kept][:, [column]], [self.labels[column]], [label], truth, [place[index] for index in self.cal if index in place],
                                   [place[index] for index in held], self.targets, counts)
        return got[label]

    def test_each_label_is_what_its_verified_images_alone_would_give(self):
        got = self.masked()
        for label in self.mapped:
            expected = self.alone(label)
            # The one figure that is over every image, by design: it needs no truth.
            for target in expected["targets"].values():
                target.pop("firingRateAll")
            mine = json.loads(json.dumps(got[label]))
            rates = [target.pop("firingRateAll") for target in mine["targets"].values()]
            self.assertEqual(mine, json.loads(json.dumps(expected)), label)
            self.assertTrue(all(0 <= rate <= 1 for rate in rates))
        # …and the labels do differ in what they were scored over.
        self.assertEqual([got[label]["targets"]["0.80"]["heldOut"]["positives"] + got[label]["targets"]["0.80"]["heldOut"]["negatives"] for label in self.mapped],
                         [sum(1 for index in self.held if self.verified[label][index]) for label in self.mapped])
        self.assertLess(got["seldom"]["targets"]["0.80"]["heldOut"]["positives"], got["everywhere"]["targets"]["0.80"]["heldOut"]["positives"])

    def test_an_image_where_nobody_looked_moves_no_figure_against_truth(self):
        # Every unverified image scores far above everything and is "absent" in the truth row, as
        # `open_images_truth` leaves it: a reader that forgot the mask would take them all for
        # false positives above any floor.
        before = self.masked()
        scores, truth = self.scores.copy(), {label: list(row) for label, row in self.truth.items()}
        for column, label in enumerate(self.mapped):
            for index in range(self.IMAGES):
                if not self.verified[label][index]:
                    scores[index, column], truth[label][index] = 9.0, False
        after = self.masked(scores, truth)
        for label in ("often", "seldom"):
            for key in ("ap", "distribution", "curveHeldOut"):
                self.assertEqual(after[label][key], before[label][key], (label, key))
            for target in ("0.80", "0.90"):
                a, b = dict(after[label]["targets"][target]), dict(before[label]["targets"][target])
                self.assertGreater(a.pop("firingRateAll"), b.pop("firingRateAll"), label)  # they do fire: that figure counts them
                self.assertEqual(a, b, (label, target))
        self.assertIsNotNone(before["often"]["targets"]["0.80"]["threshold"])

    def test_the_held_out_figures_carry_the_negatives_and_a_wilson_bound(self):
        held = self.masked()["often"]["targets"]["0.80"]["heldOut"]
        rows = [index for index in self.held if self.verified["often"][index]]
        self.assertEqual(held["negatives"], sum(1 for index in rows if not self.truth["often"][index]))
        self.assertAlmostEqual(held["falsePositiveRate"], (held["fired"] - held["hits"]) / held["negatives"])
        self.assertEqual(held["precisionWilson95"], ev.wilson(held["hits"], held["fired"]))
        self.assertLess(held["precisionWilson95"][0], held["precision"])

    def test_agreement_over_a_labels_own_rows_is_agreement_over_those_rows(self):
        other = self.scores + numpy.random.default_rng(3).normal(0, 0.05, self.scores.shape)
        scores = {"onnx": self.scores, "coreml": other, "fp32": other}
        rows = ev.verified_rows(self.held, self.verified)
        per = {artifact: ev.evaluate_artifact(scores[artifact], self.labels, self.mapped, self.truth, ev.verified_rows(self.cal, self.verified), rows, [0.8], self.counts,
                                              ev.verified_rows(list(range(self.IMAGES)), self.verified), self.held) for artifact in scores}
        masked = ev.firing_agreement(scores, per, self.labels, self.mapped, rows, 0.8)["onnxVsCoreml"]
        everywhere = ev.firing_agreement(scores, per, self.labels, self.mapped, self.held, 0.8)["onnxVsCoreml"]
        for column, label in enumerate(self.mapped):
            floors = [per[artifact][label]["targets"]["0.80"]["threshold"] for artifact in ("onnx", "coreml")]
            expected = ev.agreement(ev.fires([self.scores[index, column] for index in rows[label]], floors[0]), ev.fires([other[index, column] for index in rows[label]], floors[1]))
            self.assertEqual({key: masked["perLabel"][label][key] for key in expected}, expected, label)
            cells = lambda entry: entry["both"] + entry["onlyA"] + entry["onlyB"] + entry["neither"]
            self.assertEqual((cells(masked["perLabel"][label]), cells(everywhere["perLabel"][label])), (len(rows[label]), len(self.held)), label)
        self.assertEqual(masked["pooled"]["both"], sum(masked["perLabel"][label]["both"] for label in self.mapped))
        self.assertEqual(masked["perLabel"]["everywhere"], everywhere["perLabel"]["everywhere"])  # verified on every image: the same rows
        # A floor is placed on the label's own calibration rows, whatever rows it is then fired over.
        fired = ev.own_floor_firing(self.scores, self.labels, self.mapped, self.truth, ev.verified_rows(self.cal, self.verified), self.held, 0.8)
        for column, label in enumerate(self.mapped):
            self.assertEqual(fired[label], ev.fires([self.scores[index, column] for index in self.held], per["onnx"][label]["targets"]["0.80"]["threshold"]), label)


class Select(unittest.TestCase):
    """`select` chooses a corpus from the official files, deterministically, or writes nothing."""

    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.dir = self.scratch.name
        self.labels = write(self.dir, "labels.tsv", LABELS)
        self.official = os.path.join(self.dir, "official")
        os.makedirs(self.official)
        self.files = oi_files(self.official)
        self.mapping = write(self.dir, "mapping.tsv", oi_mapping())

    def select(self, out, *extra, mapping=None, files=None):
        files = files or self.files
        # 150 a label unless a test says otherwise: above every count of the fixture, so the cap does not bite.
        argv = ["select", "--labels", self.labels, "--mapping", mapping or self.mapping, "--classes", files["classes"], "--out", out,
                *(() if "--per-label" in extra else ("--per-label", "150")), *extra]
        for flag, paths in (("--annotations", files["annotations"]), ("--image-info", files["info"])):
            for path in paths:
                argv += [flag, path]
        with without(*HEAVY):  # it needs no package at all
            return run_main(*argv)

    def chosen(self, out) -> list:
        return ev.read_image_list(os.path.join(out, ev.SELECTION_LIST))

    def test_a_selection_is_the_verified_images_of_the_pool_and_says_where_it_came_from(self):
        out = os.path.join(self.dir, "selection")
        self.assertEqual(self.select(out), (0, ""))
        self.assertEqual(sorted(os.listdir(out)), sorted(ev.SELECTION_OUTPUTS))
        names = self.chosen(out)
        indices = sorted(int(ev.open_images_id(name)[1], 16) for name in names)
        self.assertTrue(all(index < OI_POOL for index in indices))                        # no rotated image
        self.assertTrue(all(ev.open_images_id(name)[0] == oi_image(int(ev.open_images_id(name)[1], 16))[0] for name in names))  # the split is in the name
        # Every image of the pool is verified for a scored label here (Person or Animal), and the
        # cap is above every count: the corpus is the pool but the images verified for `cat` alone.
        scored = lambda index: any(key in oi_verified(index) for key in ("/m/p", "/m/a", "/m/d"))
        self.assertEqual(indices, [index for index in range(OI_POOL) if scored(index)])
        with open(os.path.join(out, ev.SELECTION_RECORD), encoding="utf-8") as handle:
            record = json.load(handle)
        self.assertEqual((record["format"], record["seed"], record["perLabel"], record["minPerHalf"]), (ev.SELECTION_FORMAT, ev.DEFAULT_SEED, 150, 20))
        self.assertEqual((record["pool"]["images"], record["images"]["count"], record["images"]["licence"]), (OI_POOL, len(names), ev.OPEN_IMAGES_LICENCE))
        self.assertEqual(record["images"]["validation"] + record["images"]["test"], len(names))
        self.assertEqual(sorted(record["labels"]), ["animals", "dog", "people"])          # `cat` is thin: counted in the mapping, not selected for
        self.assertEqual(record["labels"]["dog"]["pool"], {"positives": OI_POOLS["dog"][0], "negatives": OI_POOLS["dog"][1]})
        halves = record["labels"]["dog"]
        self.assertEqual(halves["calibration"]["positives"] + halves["held-out"]["positives"], OI_POOLS["dog"][0])
        self.assertEqual([entry["sha256"] for entry in record["inputs"]["annotations"]], [gate.sha256_of(path) for path in self.files["annotations"]])
        self.assertEqual(record["inputs"]["classes"]["sha256"], gate.sha256_of(self.files["classes"]))
        for name, digest in record["outputs"].items():
            self.assertEqual(gate.sha256_of(os.path.join(out, name)), digest)
        # The extract: every row a row of an official file, whole, and every row of a chosen image.
        official = set()
        for path in self.files["annotations"]:
            with open(path, encoding="utf-8") as handle:
                official.update(handle.read().split("\n")[1:-1])
        with open(os.path.join(out, ev.SELECTION_LABELS), encoding="utf-8") as handle:
            extract = handle.read().split("\n")
        self.assertEqual(extract[0], "ImageID,Source,LabelName,Confidence")
        self.assertTrue(set(extract[1:-1]) <= official)
        ids = {ev.open_images_id(name)[1] for name in names}
        self.assertEqual(set(extract[1:-1]), {row for row in official if row.split(",")[0] in ids})
        self.assertEqual(set(ev.read_open_images_labels(os.path.join(out, ev.SELECTION_LABELS))), ids)
        # The information rows travel with the corpus: licence and author, a comma in a name included.
        header, info = ev.read_image_info([os.path.join(out, ev.SELECTION_INFO)])
        self.assertEqual(set(info), ids)
        self.assertEqual({row[header.index("Author")] for row in info.values()}, {"Doe, Jane"})

    def test_a_selection_depends_on_its_inputs_and_its_seed_alone(self):
        first, second, other = (os.path.join(self.dir, name) for name in ("first", "second", "other"))
        capped = oi_mapping()
        for out, seed in ((first, "4"), (second, "4"), (other, "5")):
            self.assertEqual(self.select(out, "--seed", seed, "--per-label", "60")[0], 0, out)
        self.assertEqual(tree(first), tree(second))
        self.assertNotEqual(self.chosen(first), self.chosen(other))
        uncapped = os.path.join(self.dir, "uncapped")
        self.assertEqual(self.select(uncapped, "--seed", "4")[0], 0)
        self.assertLess(len(self.chosen(first)), len(self.chosen(uncapped)))   # the cap bit: 60 of Person's 79, 60 of Animal's 118
        self.assertTrue(set(self.chosen(first)) < set(self.chosen(uncapped)))
        self.assertEqual(ev.selection_order(4, "dog", ["b", "a", "c"]), ev.selection_order(4, "dog", ["c", "b", "a"]))
        self.assertNotEqual(ev.selection_order(4, "dog", [f"{index:x}" for index in range(40)]), ev.selection_order(4, "cat", [f"{index:x}" for index in range(40)]))
        # A rerun into the same directory replaces the selection whole.
        self.assertEqual(self.select(first, "--seed", "5", "--per-label", "60")[0], 0)
        self.assertEqual(tree(first), tree(other))

    def test_a_refused_selection_writes_nothing(self):
        out = os.path.join(self.dir, "selection")
        rotten, guessed = os.path.join(self.dir, "rotten"), os.path.join(self.dir, "guessed")
        os.makedirs(rotten)
        os.makedirs(guessed)
        counts = dict(OI_POOLS, dog=(OI_POOLS["dog"][0] + 1, OI_POOLS["dog"][1]))
        cases = [
            ((), {"mapping": write(self.dir, "scored-cat.tsv", oi_mapping(cat="one-to-one"))}, "cat: "),            # 59 positives, 29 negatives: under 20 a half
            ((), {"mapping": write(self.dir, "miscounted.tsv", oi_mapping(pools=counts))}, "the pool counts of 1 labels are not the annotations'"),
            (("--per-label", "30"), {}, "fewer than 20 verified positives or negatives"),
            # 40 a label: every label is still VERIFIED on 20 a half of that corpus, on images taken
            # for other labels; its own sample of 40 is not, and the sample is what is scored.
            (("--per-label", "40"), {}, "fewer than 20 verified positives or negatives"),
            ((), {"files": oi_files(rotten, licence="https://creativecommons.org/licenses/by-nc/2.0/")}, "the licence this corpus was cleared on does not cover it"),
            ((), {"files": oi_files(guessed, machine=True)}, "machine-generated labels are not ground truth"),
        ]
        for extra, kwargs, said in cases:
            status, stderr = self.select(out, *extra, **kwargs)
            self.assertEqual(status, 2, said)
            self.assertIn(said, stderr)
            self.assertFalse(os.path.exists(out), said)
        # The refusal carries the counts: 59 cats, 29 images verified to hold none, split in two.
        self.assertRegex(self.select(out, mapping=os.path.join(self.dir, "scored-cat.tsv"))[1], r"cat: \d+ positives and 1\d negatives verified in the calibration half")
        # An earlier selection stays as it was.
        self.assertEqual(self.select(out)[0], 0)
        before = tree(out)
        self.assertEqual(self.select(out, "--per-label", "30")[0], 2)
        self.assertEqual(tree(out), before)

    def test_a_selection_that_is_not_what_its_record_says_is_refused(self):
        out = os.path.join(self.dir, "selection")
        self.assertEqual(self.select(out)[0], 0)
        got = ev.read_selection(out)
        self.assertEqual(got["names"], self.chosen(out))
        self.assertIn("truth=open-images", got["pins"])
        self.assertIn(f"annotationsSha256={gate.sha256_of(os.path.join(out, ev.SELECTION_LABELS))}", got["pins"])
        self.assertIn(f"classesSha256={gate.sha256_of(self.files['classes'])}", got["pins"])
        self.assertIn(f"selectionSeed={ev.DEFAULT_SEED}", got["pins"])
        self.assertTrue(any(line.startswith("annotationsFrom=oidv7-validation-annotations-human-imagelabels.csv:") for line in got["pins"]))
        self.assertTrue(all("\n" not in line for line in got["pins"]))
        with open(os.path.join(out, ev.SELECTION_LIST), "a", encoding="utf-8") as handle:
            handle.write("validation-00000000000000ff.jpg\n")
        with self.assertRaises(ev.Refused) as refusal:
            ev.read_selection(out)
        self.assertIn("is not the file the selection recorded", str(refusal.exception))
        os.remove(os.path.join(out, ev.SELECTION_RECORD))
        with self.assertRaises(ev.Refused):
            ev.read_selection(out)


@unittest.skipIf(numpy is None, "needs numpy")
class OpenImagesEndToEnd(unittest.TestCase):
    """`select`, a manifest that pins the selection, made-up vectors, `evaluate --truth open-images`."""

    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.dir = self.scratch.name
        self.labels = write(self.dir, "labels.tsv", LABELS)
        official = os.path.join(self.dir, "official")
        os.makedirs(official)
        self.files = oi_files(official)
        self.mapping = write(self.dir, "mapping.tsv", oi_mapping())
        self.selection = os.path.join(self.dir, "selection")
        argv = ["select", "--labels", self.labels, "--mapping", self.mapping, "--classes", self.files["classes"], "--out", self.selection, "--per-label", "150"]
        for flag, paths in (("--annotations", self.files["annotations"]), ("--image-info", self.files["info"])):
            for path in paths:
                argv += [flag, path]
        self.assertEqual(run_main(*argv), (0, ""))
        selection = ev.read_selection(self.selection)
        self.names = selection["names"]
        self.manifest = write(self.dir, "corpus.txt", corpus_manifest_text(self.names).replace("\n\n", "\n" + "\n".join(selection["pins"]) + "\n\n", 1))
        self.annotations = os.path.join(self.selection, ev.SELECTION_LABELS)
        self.run = os.path.join(self.dir, "run")
        os.makedirs(self.run)
        rng = numpy.random.default_rng(5)
        # What is IN each image, verified or not: a person on every even image, an animal on
        # i % 4 in (0, 1), a dog on i % 4 == 0. ⚠ So a third of the people images were never
        # verified for Person, and score 1 on `people` all the same.
        indices = [int(ev.open_images_id(name)[1], 16) for name in self.names]
        content = numpy.zeros((len(indices), 8), dtype=numpy.float32)
        for row, index in enumerate(indices):
            content[row, 0], content[row, 1], content[row, 2], content[row, 3] = index % 2 == 0, index % 4 in (0, 1), index % 4 == 0, index % 4 == 1
        texts = numpy.tile(numpy.eye(5, 8, dtype=numpy.float32), (3, 1))
        artifacts = {}
        for artifact in ev.ARTIFACTS:
            images = content + (1 - content) * rng.normal(0, 0.05, content.shape).astype(numpy.float32)
            path = os.path.join(self.run, ev.vectors_name(artifact, ""))
            numpy.savez(path, images=images, texts=texts)
            artifacts[os.path.basename(path)] = {"artifact": artifact, "sha256": gate.sha256_of(path)}
        self.indices = indices
        self.inputs = {"labels": {"sha256": gate.sha256_of(self.labels)},
                       "corpus": {"id": gate.load_corpus_manifest(self.manifest)["id"], "count": len(self.names), "manifestSha256": gate.sha256_of(self.manifest), "pixelsSha256": "0" * 64},
                       "texts": {"tokenRowsSha256": "0" * 64}, "artifacts": artifacts}
        write(self.run, ev.INPUTS_NAME, json.dumps(self.inputs))

    def evaluate(self, *extra, truth="open-images", classes=True, mapping=None, annotations=None, manifest=None, record=None):
        argv = ["evaluate", "--run", self.run, "--annotations", annotations or self.annotations, "--labels", self.labels, "--mapping", mapping or self.mapping,
                "--corpus-manifest", manifest or self.manifest, "--allow-unpinned", "--bootstrap", "20", "--targets", "0.99", "--primary-target", "0.99", "--truth", truth, *extra]
        argv += ["--classes", self.files["classes"]] if classes is True else ["--classes", classes] if classes else []
        argv += ["--selection", record or os.path.join(self.selection, ev.SELECTION_RECORD)] if record is not False and truth == "open-images" else []
        with contextlib.redirect_stdout(io.StringIO()) as stdout, contextlib.redirect_stderr(io.StringIO()) as stderr:
            status = ev.main(argv)
        return status, stderr.getvalue(), stdout.getvalue()

    def result(self) -> dict:
        with open(os.path.join(self.run, ev.RESULT_NAME), encoding="utf-8") as handle:
            return json.load(handle)

    def test_each_label_is_scored_over_the_images_it_was_verified_on(self):
        status, stderr, stdout = self.evaluate()
        self.assertEqual((status, stderr), (0, ""))
        result = self.result()
        self.assertEqual((result["truth"]["kind"], result["truth"]["name"], result["truth"]["minPerHalf"]), ("open-images", "Open Images", 20))
        self.assertEqual(result["labels"]["mapped"], ["people", "animals", "dog"])
        self.assertEqual(result["labels"]["unmapped"], ["cat", "indoors"])   # thin, and none
        held_names = ev.split(self.names, ev.DEFAULT_SEED)[1]
        held = [int(ev.open_images_id(name)[1], 16) for name in held_names]
        people = result["positives"]["people"]
        self.assertEqual(people["scored"]["all"], sum(1 for index in self.indices if index % 3 != 2))
        self.assertEqual(people["all"], sum(1 for index in self.indices if index % 3 != 2 and index % 2 == 0))
        self.assertEqual(people["scored"]["heldOut"], sum(1 for index in held if index % 3 != 2))
        self.assertLess(people["scored"]["all"], len(self.names))
        self.assertEqual((result["truth"]["perLabel"], result["truth"]["selectedPerLabel"], result["truth"]["sampleSeed"]), (150, 150, ev.DEFAULT_SEED))   # the selection's own, by default
        for artifact in ev.ARTIFACTS:
            for label in result["labels"]["mapped"]:
                entry = result["perLabel"][artifact][label]["targets"]["0.99"]
                self.assertEqual(entry["threshold"], 1.0, label)
                # ⚠⚠ The people images nobody verified fire too. Counted as negatives they would
                # make this 2 / 3; left out, as they are, every firing image is a verified hit.
                self.assertEqual((entry["heldOut"]["precision"], entry["heldOut"]["recall"], entry["heldOut"]["falsePositiveRate"]), (1.0, 1.0, 0.0), (artifact, label))
                self.assertEqual(entry["heldOut"]["precisionWilson95"], ev.wilson(entry["heldOut"]["hits"], entry["heldOut"]["fired"]))
            held_people = result["perLabel"][artifact]["people"]["targets"]["0.99"]["heldOut"]
            self.assertEqual(held_people["fired"], sum(1 for index in held if index % 3 != 2 and index % 2 == 0))
            self.assertEqual(held_people["negatives"], sum(1 for index in held if index % 3 != 2 and index % 2 == 1))
            # The firing rate needs no truth and is over every image: every even one.
            self.assertAlmostEqual(result["perLabel"][artifact]["people"]["targets"]["0.99"]["firingRateAll"], sum(1 for index in self.indices if index % 2 == 0) / len(self.indices))
        # Agreement is over every held-out image; the verified-only table over each label's own.
        everywhere = result["agreement"]["heldOutOwnFloors"]["onnxVsCoreml"]["perLabel"]["people"]
        verified = result["agreement"]["heldOutOwnFloorsVerifiedOnly"]["onnxVsCoreml"]["perLabel"]["people"]
        self.assertEqual(everywhere["both"], sum(1 for index in held if index % 2 == 0))
        self.assertEqual(verified["both"], sum(1 for index in held if index % 3 != 2 and index % 2 == 0))
        self.assertEqual(everywhere["both"] + everywhere["neither"], len(held))
        # The rule: its sets over every image, its precision over the verified ones.
        self.assertEqual(result["selection"][0]["artifacts"]["onnx"]["micro"]["rule"]["precision"], 1.0)
        self.assertEqual(result["selection"][0]["artifacts"]["onnx"]["perLabel"]["people"]["rule"]["fired"], held_people["fired"])
        # Every other table that is read against truth reads the mask too: each would be 2 / 3, or
        # an AP under 1, with the unverified people images counted as negatives.
        self.assertEqual(result["selection"][0]["artifacts"]["onnx"]["perLabel"]["people"]["independent"]["precision"], 1.0)
        self.assertEqual(result["selection"][0]["artifacts"]["onnx"]["micro"]["independent"], {"precision": 1.0, "recall": 1.0})
        for table in result["crossThresholds"].values():
            self.assertEqual((table["people"]["0.99"]["ownFloors"]["precision"], table["people"]["0.99"]["otherFloors"]["precision"]), (1.0, 1.0))
        self.assertEqual(result["sensitivity"]["prompts"]["onnx"]["people"], {"tsv": 1.0, "bare": 1.0, "uniform": 1.0})
        self.assertEqual(result["sensitivity"]["groundTruth"]["onnx"]["people"]["any"], {"positives": people["heldOut"], "ap": 1.0})
        self.assertEqual(result["perLabel"]["onnx"]["people"]["ap"], {**result["perLabel"]["onnx"]["people"]["ap"], "all": 1.0, "calibration": 1.0, "heldOut": 1.0})
        self.assertEqual(result["towersAgainstFp32"]["onnx"]["bothTowers"]["jaccard"], 1.0)
        self.assertEqual(result["summary"]["floors"]["0.99"]["heldOut"]["onnx"]["microPrecision"], 1.0)
        bars = result["summary"]["heldOutBars"]["0.99"]
        self.assertEqual(bars["point"]["bothShipped"], ["people", "animals", "dog"])
        self.assertEqual(bars["bootstrapLower"]["onnx"], ["people", "animals", "dog"])       # every resample is 1.0: see `wilson`
        self.assertEqual(bars["wilsonLower"]["bothShipped"], [])                             # nothing here fires on the 380 images 0.99 takes
        self.assertEqual(sorted(result["sensitivity"]["groundTruth"]["onnx"]["dog"]), ["any"])
        self.assertEqual(result["inputs"]["classes"]["sha256"], gate.sha256_of(self.files["classes"]))
        self.assertEqual(result["inputs"]["annotations"]["sha256"], gate.sha256_of(self.annotations))
        self.assertEqual(result["inputs"]["selection"]["seed"], ev.DEFAULT_SEED)
        self.assertEqual(len(result["inputs"]["annotationsFrom"]), 2)
        with open(os.path.join(self.run, ev.TABLES_NAME), encoding="utf-8") as handle:
            tables = handle.read()
        for said in ("Ground truth: Open Images.", "Each label is scored over ITS SAMPLE", "### Each label's sample", "| Label | Open Images |", "Held-out precision ≥ 0.99"):
            self.assertIn(said, tables)
        self.assertNotIn("noCrowd", tables)
        last = [line for line in stdout.split("\n") if line.startswith("label eval:")][0]
        self.assertIn("3 of 5 labels scored against Open Images; 2 are outside", last)
        self.assertIn("the mapping is not the committed mapping", last)

    def test_a_label_is_scored_over_its_sample_not_over_every_image_it_is_verified_on(self):
        # 79 images are verified to hold a person and 78 to hold none. With 60 of each, `people`
        # is scored over 120 images, the ones a selection of 60 would have taken; `dog`, with 59
        # positives and 59 negatives, over all of its own.
        self.assertEqual(self.evaluate("--per-label", "60")[0], 0)
        result = self.result()
        self.assertEqual((result["truth"]["perLabel"], result["truth"]["selectedPerLabel"]), (60, 150))   # a subsample of what was selected
        self.assertEqual((result["positives"]["people"]["scored"]["all"], result["positives"]["people"]["all"]), (120, 60))
        self.assertEqual((result["positives"]["dog"]["scored"]["all"], result["positives"]["dog"]["all"]), (OI_POOLS["dog"][0] + OI_POOLS["dog"][1], OI_POOLS["dog"][0]))
        held = result["perLabel"]["onnx"]["people"]["targets"]["0.99"]["heldOut"]
        self.assertEqual(held["positives"] + held["negatives"], result["positives"]["people"]["scored"]["heldOut"])
        # The sample is the selection's own choice: the first 60 of the pool in its order.
        present = [f"{index:016x}" for index in range(OI_POOL) if index % 3 != 2 and index % 2 == 0]
        taken = set(ev.selection_order(ev.DEFAULT_SEED, "people", present)[:60])
        annotations = ev.read_open_images_labels(self.annotations)
        classes = ev.read_open_images_classes(self.files["classes"])
        mapping = ev.load_open_images_mapping(self.mapping, ev.read_label_set(self.labels), classes)
        truth, verified = ev.open_images_truth(annotations, mapping, classes, self.names)
        sample = ev.label_samples(truth, verified, self.names, ev.DEFAULT_SEED, 60)
        self.assertEqual({ev.open_images_id(name)[1] for name, kept, positive in zip(self.names, sample["people"], truth["people"]) if kept and positive}, taken)
        self.assertEqual(sum(sample["people"]), 120)
        self.assertTrue(all(looked or not kept for looked, kept in zip(verified["people"], sample["people"])))   # never an image nobody looked at
        self.assertEqual(ev.label_samples(truth, verified, self.names, ev.DEFAULT_SEED, 1000), verified)          # no cap, no difference
        self.assertNotEqual(ev.label_samples(truth, verified, self.names, 5, 60)["people"], sample["people"])     # the order is the seed's
        # A sample too small to place a floor on is refused as a thin label is.
        status, stderr, _ = self.evaluate("--per-label", "30")
        self.assertEqual(status, 2)
        self.assertIn("fewer than 20 verified positives or negatives", stderr)

    def test_a_variant_is_read_through_the_mask_too(self):
        import shutil as copier

        name = ev.vectors_name("onnx", "again")
        copier.copy(os.path.join(self.run, ev.vectors_name("onnx", "")), os.path.join(self.run, name))
        self.inputs["artifacts"][name] = {"artifact": "onnx", "sha256": gate.sha256_of(os.path.join(self.run, name))}
        write(self.run, ev.INPUTS_NAME, json.dumps(self.inputs))
        self.assertEqual(self.evaluate("--variant", name)[0], 0)
        entry = self.result()["variants"][name]
        for which in ("variantUnderPinnedFloors", "variantUnderOwnFloors"):
            self.assertEqual((entry["perLabel"]["people"][which]["precision"], entry["perLabel"]["people"][which]["recall"]), (1.0, 1.0), which)
        self.assertEqual(entry["perLabel"]["people"]["floor"], {"pinned": 1.0, "variant": 1.0})
        self.assertEqual((entry["pooledOwnFloors"]["jaccard"], entry["againstFp32OwnFloors"]["jaccard"]), (1.0, 1.0))

    def test_the_reader_is_chosen_on_the_command_line_and_the_manifest_must_agree(self):
        before = tree(self.dir)
        coco = write(self.dir, "coco.txt", corpus_manifest_text(self.names, self.annotations))
        self.inputs["corpus"].update(manifestSha256=gate.sha256_of(coco))
        cases = [
            ({"truth": "coco", "classes": False}, "does not open with 'label-eval-mapping 1'"),             # the Open Images mapping is not COCO's
            ({"truth": "coco"}, "and only it does"),
            ({"classes": False}, "reads the class descriptions from --classes"),
            ({"manifest": coco}, "says truth=coco, and this run was asked for --truth open-images"),
            ({"classes": write(self.dir, "longer-classes.csv", OI_CLASSES + "/m/x,Extra\n")}, "bytes, the corpus manifest says"),
            ({"classes": write(self.dir, "other-classes.csv", OI_CLASSES.replace("Room", "Roof"))}, "is not the class descriptions file the corpus manifest names"),
            ({"annotations": self.files["annotations"][0]}, "bytes, the corpus manifest says"),
            # The corpus holds a label's own images and other labels'. Another
            # mapping, or a larger sample than was selected, would score a label over the second kind.
            ({"mapping": write(self.dir, "scored-cat.tsv", oi_mapping(cat="one-to-one"))}, "is not the mapping this corpus was selected with"),
            ({"mapping": write(self.dir, "renoted.tsv", oi_mapping().replace("no animal, no dog", "another note"))}, "is not the mapping this corpus was selected with"),
            ({"record": False}, "from --selection"),
            ({"record": write(self.dir, "other-selection.json", json.dumps({"format": ev.SELECTION_FORMAT, "perLabel": 150, "seed": 1, "inputs": {"mapping": "0" * 64}}))},
             "is not the selection record the corpus manifest names"),
            ({"record": os.path.join(self.dir, "absent.json")}, "no selection record"),
        ]
        before = tree(self.dir)
        for kwargs, said in cases:
            status, stderr, _ = self.evaluate(**kwargs)
            self.assertEqual(status, 2, said)
            self.assertIn(said, stderr, kwargs)
            self.assertEqual(tree(self.dir), before, said)
        # A label verified on few images, with its counts, and with every package blocked: with the input checks.
        with without("numpy"):
            status, stderr, _ = self.evaluate("--per-label", "30")
        self.assertEqual(status, 2)
        self.assertRegex(stderr, r"people: 1\d positives and \d+ negatives verified in the (calibration|held-out) half|: \d+ positives and 1\d negatives verified in the")
        self.assertIn("fewer than 20 verified positives or negatives", stderr)
        status, stderr, _ = self.evaluate("--per-label", "151")
        self.assertEqual(status, 2)
        self.assertIn("--per-label 151 is more than the 150 a label this corpus was selected with", stderr)
        self.assertEqual(tree(self.dir), before)
        # And the COCO reader refuses a manifest that says Open Images, whatever else is given.
        status, stderr, _ = self.evaluate(truth="coco", classes=False, mapping=write(self.dir, "coco-mapping.tsv", MAPPING))
        self.assertEqual(status, 2)
        self.assertIn("says truth=open-images, and this run was asked for --truth coco", stderr)

    def test_the_seed_of_a_sample_is_the_selection_records_not_a_manifest_line(self):
        # A manifest whose `selectionSeed=` was edited, embedded as it is, with a digest that still
        # matches the record, is refused: the sample order would otherwise follow the edited line.
        with open(self.manifest, encoding="utf-8") as handle:
            text = handle.read()
        self.assertIn(f"selectionSeed={ev.DEFAULT_SEED}\n", text)
        reseeded = write(self.dir, "reseeded.txt", text.replace(f"selectionSeed={ev.DEFAULT_SEED}\n", "selectionSeed=5\n"))
        self.inputs["corpus"].update(manifestSha256=gate.sha256_of(reseeded))
        write(self.run, ev.INPUTS_NAME, json.dumps(self.inputs))
        before = tree(self.dir)
        status, stderr, _ = self.evaluate(manifest=reseeded)
        self.assertEqual(status, 2)
        self.assertIn(f"says selectionSeed=5, the selection record it pins says {ev.DEFAULT_SEED}", stderr)
        self.assertEqual(tree(self.dir), before)
        self.assertEqual(ev.selection_parameters(os.path.join(self.selection, ev.SELECTION_RECORD), gate.sha256_of(os.path.join(self.selection, ev.SELECTION_RECORD)))["seed"], ev.DEFAULT_SEED)

    def test_another_seed_than_the_selections_is_refused_when_it_leaves_a_label_short(self):
        # The corpus was chosen so that every label has 20 a half under ITS seed. `dog` has 59
        # positives: most seeds still split them 20 and over, and one that does not is refused.
        outcomes = {self.evaluate("--seed", str(seed))[0] for seed in range(1, 6)}
        self.assertTrue(outcomes <= {0, 2})
        self.assertEqual(self.evaluate()[0], 0)

    @unittest.skipIf(importlib.util.find_spec("PIL") is None, "needs Pillow")
    def test_a_manifest_pins_the_selection_it_was_written_from(self):
        from PIL import Image

        corpus = os.path.join(self.dir, "images")
        os.makedirs(corpus)
        for position, name in enumerate(self.names):
            Image.new("RGB", (4, 4), (position % 250, 90, 200)).save(os.path.join(corpus, name))
        Image.new("RGB", (4, 4)).save(os.path.join(corpus, "validation-00000000000000ff.jpg"))  # a stray file: not in the selection
        target = os.path.join(self.dir, "written.txt")
        status, stderr = run_main("manifest", "--allow-unpinned", "--truth", "open-images", "--selection", self.selection, "--corpus", corpus, "--out", target, "--side", "4")
        self.assertEqual((status, stderr), (0, ""))
        manifest = gate.load_corpus_manifest(target)
        self.assertEqual(manifest["source"], ev.OPEN_IMAGES_SOURCE_URL)   # the bucket, by default: never COCO's address
        # `source + name` is not where the bucket keeps a file. The manifest says
        # how its names become addresses, and the gate's fetcher reads it.
        self.assertEqual(manifest["names"], gate.SPLIT_NAMES)
        self.assertEqual(gate.fetch_url(manifest, "validation-0001eeaf4aed83f9.jpg"), "https://open-images-dataset.s3.amazonaws.com/validation/0001eeaf4aed83f9.jpg")
        self.assertEqual(gate.fetch_url({"source": "http://x/", "names": None}, "a.jpg"), "http://x/a.jpg")
        with self.assertRaises(ev.Refused):
            gate.fetch_url({"source": "http://x/", "names": "something else"}, "test-a.jpg")
        self.assertEqual(gate.load_corpus_manifest(ev.DEFAULT_OPEN_IMAGES_CORPUS_MANIFEST)["names"], gate.SPLIT_NAMES)
        self.assertIsNone(gate.load_corpus_manifest(ev.DEFAULT_CORPUS_MANIFEST)["names"])
        self.assertEqual([entry["name"] for entry in manifest["files"]], self.names)
        self.assertEqual(ev.manifest_truth(target), "open-images")
        self.assertEqual(ev.manifest_annotations(target), {"name": ev.SELECTION_LABELS, "bytes": os.path.getsize(self.annotations), "sha256": gate.sha256_of(self.annotations)})
        pins = ev.manifest_open_images(target)
        self.assertEqual(pins["classes"]["sha256"], gate.sha256_of(self.files["classes"]))
        self.assertEqual(pins["selection"], {"sha256": gate.sha256_of(os.path.join(self.selection, ev.SELECTION_RECORD)), "seed": ev.DEFAULT_SEED})
        self.assertEqual([entry["sha256"] for entry in pins["annotationsFrom"]], [gate.sha256_of(path) for path in self.files["annotations"]])
        # A manifest without those lines is not an Open Images manifest.
        with self.assertRaises(ev.Refused):
            ev.manifest_open_images(write(self.dir, "plain.txt", corpus_manifest_text(self.names, self.annotations)))
        # And it is the manifest `evaluate` accepts for this run's images.
        self.inputs["corpus"].update(id=manifest["id"], manifestSha256=gate.sha256_of(target))
        write(self.run, ev.INPUTS_NAME, json.dumps(self.inputs))
        self.assertEqual(self.evaluate(manifest=target)[0], 0)


if __name__ == "__main__":
    unittest.main()
