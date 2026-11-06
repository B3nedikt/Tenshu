#!/usr/bin/env python3
"""Tests for scripts/lib/check_developer_verification.py (no network).

  python3 scripts/test_developer_verification.py
"""
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "lib"))

import check_developer_verification as cdv  # noqa: E402
import direct_release  # noqa: E402

DIGEST = ":".join(f"{0xA0 + i:02X}" for i in range(32))


class FingerprintTest(unittest.TestCase):
    def test_the_keytool_form_becomes_bare_lower_hex(self):
        self.assertEqual(DIGEST.replace(":", "").lower(), cdv.query_fingerprint(DIGEST))

    def test_a_malformed_digest_is_refused(self):
        with self.assertRaises(ValueError):
            cdv.query_fingerprint("AB:CD")


class AppListTest(unittest.TestCase):
    def test_the_ten_apps_come_from_the_release_script(self):
        apps = cdv.all_apps(direct_release.ROOT)
        self.assertEqual(10, len(apps))
        self.assertIn("control", apps)
        self.assertEqual(len(apps), len(set(apps)))


if __name__ == "__main__":
    unittest.main()
