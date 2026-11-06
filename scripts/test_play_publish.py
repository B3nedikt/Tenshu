#!/usr/bin/env python3
"""Tests for scripts/lib/play_publish.py's promotion choice (no network, no credentials).

  python3 scripts/test_play_publish.py
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

import play_publish  # noqa: E402

NOTES = [{"language": "en-US", "text": "Fixes"}]


def track(*releases):
    return {"track": "internal", "releases": list(releases)}


class PromotedReleaseTest(unittest.TestCase):
    def test_the_completed_release_moves_with_its_name_and_notes(self):
        release = play_publish.promoted_release(
            track({"versionCodes": ["9"], "status": "completed", "name": "1.0.2", "releaseNotes": NOTES}), 9)
        self.assertEqual({"versionCodes": ["9"], "status": "completed", "name": "1.0.2", "releaseNotes": NOTES},
                         release)

    def test_another_version_code_is_refused(self):
        """⚠ The guard that stops promoting whatever happens to be on the track since it was tested."""
        with self.assertRaisesRegex(ValueError, r"not \[9\]"):
            play_publish.promoted_release(track({"versionCodes": ["10"], "status": "completed"}), 9)

    def test_a_draft_is_not_promoted(self):
        with self.assertRaisesRegex(ValueError, "0 completed"):
            play_publish.promoted_release(track({"versionCodes": ["9"], "status": "draft"}), 9)

    def test_an_empty_track_is_refused(self):
        with self.assertRaisesRegex(ValueError, "0 completed"):
            play_publish.promoted_release({"track": "internal"}, 9)

    def test_a_staged_rollout_beside_it_is_ambiguous(self):
        with self.assertRaisesRegex(ValueError, "2 completed"):
            play_publish.promoted_release(track({"versionCodes": ["8"], "status": "completed"},
                                                {"versionCodes": ["9"], "status": "completed"}), 9)


if __name__ == "__main__":
    unittest.main()
