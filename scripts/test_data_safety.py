#!/usr/bin/env python3
"""Tests for scripts/lib/data_safety.py and `play_publish.py data-safety` (no network, no credentials).

  python3 scripts/test_data_safety.py
"""
import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest import mock

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "lib"))
import data_safety as ds  # noqa: E402
import play_publish as pp  # noqa: E402

TEMPLATE = ds.load_template()


def spec(**over):
    base = {"collects": True, "encrypted_in_transit": True, "account_creation": ["PSL_ACM_NONE"],
            "data_deletion": "DATA_DELETION_YES", "data_deletion_url": "https://example.invalid/delete",
            "outside_app_accounts": False,
            "data_types": {"PSL_EMAIL": {"collected": True, "shared": False, "ephemeral": False,
                                         "optional": True, "collection_purposes": ["PSL_APP_FUNCTIONALITY"],
                                         "sharing_purposes": []}}}
    base.update(over)
    return base


class PlayAccountRules(unittest.TestCase):
    """Play's API refuses answers the form would hide (measured on upload, 2026-10-24), so a spec
    that gives one is refused here instead of being trimmed silently."""

    def answered(self, text, question):
        return [(r, v) for q, r, v, _, _ in ds.read_rows(text) if q == question and v]

    def test_collects_false_renders_no_account_or_deletion_answer(self):
        bare = {"collects": False}
        text = ds.render(bare, TEMPLATE)
        for q in ("PSL_SUPPORTED_ACCOUNT_CREATION_METHODS", "PSL_HAS_OUTSIDE_APP_ACCOUNTS",
                  "PSL_ACCOUNT_DELETION_URL", "PSL_DATA_DELETION_URL", "PSL_SUPPORT_DATA_DELETION_BY_USER"):
            self.assertEqual(self.answered(text, q), [], q)

    def test_collects_false_refuses_each_hidden_answer(self):
        for key, value in (("account_creation", ["PSL_ACM_NONE"]), ("outside_app_accounts", False),
                           ("account_deletion_url", "https://example.invalid/a"),
                           ("data_deletion_url", "https://example.invalid/d"),
                           ("data_deletion", "DATA_DELETION_NO"), ("account_creation_specify", "x")):
            with self.subTest(key=key), self.assertRaises(ds.DataSafetyError):
                ds.render({"collects": False, key: value}, TEMPLATE)

    def test_outside_accounts_only_when_the_app_creates_none(self):
        with self.assertRaises(ds.DataSafetyError):
            ds.render(spec(account_creation=["PSL_ACM_USER_ID_OTHER_AUTH"]), TEMPLATE)
        own_accounts = spec(account_creation=["PSL_ACM_USER_ID_OTHER_AUTH"])
        del own_accounts["outside_app_accounts"]
        text = ds.render(own_accounts, TEMPLATE)
        self.assertEqual(self.answered(text, "PSL_HAS_OUTSIDE_APP_ACCOUNTS"), [])
        self.assertEqual(self.answered(ds.render(spec(), TEMPLATE), "PSL_HAS_OUTSIDE_APP_ACCOUNTS"), [("", "false")])

    def test_deletion_yes_needs_a_url(self):
        with self.assertRaises(ds.DataSafetyError):
            ds.render(spec(data_deletion_url=""), TEMPLATE)


def cell(text, question, response=""):
    for q, r, v, _, _ in ds.read_rows(text):
        if (q, r) == (question, response):
            return v
    raise KeyError((question, response))


class Template(unittest.TestCase):
    def test_the_form_as_exported_in_2026(self):
        self.assertEqual(len(TEMPLATE), 782)
        self.assertEqual(len(ds.data_type_ids(TEMPLATE)), 38)
        self.assertTrue(all(r[2] == "" for r in TEMPLATE), "the template must be blank")


class Render(unittest.TestCase):
    def test_fills_only_the_answered_cells_and_keeps_every_row(self):
        text = ds.render(spec(), TEMPLATE)
        rows = ds.read_rows(text)
        self.assertEqual([(r[0], r[1], r[3], r[4]) for r in rows], [(r[0], r[1], r[3], r[4]) for r in TEMPLATE])
        self.assertEqual(cell(text, "PSL_DATA_COLLECTION_COLLECTS_PERSONAL_DATA"), "true")
        self.assertEqual(cell(text, "PSL_DATA_TYPES_PERSONAL", "PSL_EMAIL"), "true")
        p = "PSL_DATA_USAGE_RESPONSES:PSL_EMAIL:"
        self.assertEqual(cell(text, p + "PSL_DATA_USAGE_COLLECTION_AND_SHARING", "PSL_DATA_USAGE_ONLY_COLLECTED"), "true")
        self.assertEqual(cell(text, p + "PSL_DATA_USAGE_COLLECTION_AND_SHARING", "PSL_DATA_USAGE_ONLY_SHARED"), "")
        self.assertEqual(cell(text, p + "PSL_DATA_USAGE_EPHEMERAL"), "false")
        self.assertEqual(cell(text, p + "DATA_USAGE_USER_CONTROL", "PSL_DATA_USAGE_USER_CONTROL_OPTIONAL"), "true")
        self.assertEqual(cell(text, p + "DATA_USAGE_COLLECTION_PURPOSE", "PSL_APP_FUNCTIONALITY"), "true")
        self.assertEqual(cell(text, p + "DATA_USAGE_COLLECTION_PURPOSE", "PSL_ANALYTICS"), "")
        # another type stays untouched
        self.assertEqual(cell(text, "PSL_DATA_TYPES_LOCATION", "PSL_PRECISE_LOCATION"), "")

    def test_no_collection_answers_no_data_type(self):
        text = ds.render({"collects": False}, TEMPLATE)
        self.assertEqual(cell(text, "PSL_DATA_COLLECTION_COLLECTS_PERSONAL_DATA"), "false")
        self.assertFalse([r for r in ds.read_rows(text) if r[0].startswith("PSL_DATA_TYPES_") and r[2]])

    def test_refuses_inconsistent_answers(self):
        bad = [
            spec(data_types={"PSL_NOT_A_TYPE": spec()["data_types"]["PSL_EMAIL"]}),
            spec(data_types={"PSL_EMAIL": dict(spec()["data_types"]["PSL_EMAIL"], collection_purposes=[])}),
            spec(data_types={"PSL_EMAIL": dict(spec()["data_types"]["PSL_EMAIL"], shared=True)}),
            spec(data_types={"PSL_EMAIL": dict(spec()["data_types"]["PSL_EMAIL"], collection_purposes=["ADS"])}),
            spec(collects=False),
            spec(data_types={}),
            spec(typo=1),
        ]
        for s in bad:
            with self.assertRaises(ds.DataSafetyError, msg=json.dumps(s)):
                ds.render(s, TEMPLATE)

    def test_two_answers_to_a_single_choice_fail_validation(self):
        text = ds.render(spec(), TEMPLATE).replace(
            "PSL_SUPPORT_DATA_DELETION_BY_USER,DATA_DELETION_NO,,", "PSL_SUPPORT_DATA_DELETION_BY_USER,DATA_DELETION_NO,true,")
        with self.assertRaises(ds.DataSafetyError):
            ds.validate(text, TEMPLATE)


class CommittedCsvs(unittest.TestCase):
    def test_every_app_has_a_current_csv(self):
        apps = list(ds.apps())
        self.assertEqual(len(apps), 10)
        for app, spec_path, csv_path in apps:
            with open(spec_path, encoding="utf-8") as f:
                expected = ds.render(json.load(f), TEMPLATE)
            with open(csv_path, encoding="utf-8", newline="") as f:
                self.assertEqual(f.read(), expected, f"{app}: run scripts/lib/data_safety.py render")


class Command(unittest.TestCase):
    def run_main(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        code = 0
        with redirect_stdout(out), redirect_stderr(err):
            try:
                pp.main(list(argv))
            except SystemExit as e:
                code = e.code
        return code, out.getvalue(), err.getvalue()

    def csv_in(self, folder, package, placeholder=False):
        os.makedirs(folder, exist_ok=True)
        with open(os.path.join(folder, "listing.json"), "w") as f:
            json.dump({"package": package}, f)
        s = spec(account_deletion_url="https://tenshu.app/PLACEHOLDER" if placeholder else "https://tenshu.app/x")
        path = os.path.join(folder, "data-safety.csv")
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write(ds.render(s, TEMPLATE))
        return path

    def test_dry_run_never_asks_for_credentials_or_the_network(self):
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(pp, "load_service_account", side_effect=AssertionError("credentials")), \
                mock.patch.object(pp, "request", side_effect=AssertionError("network")):
            path = self.csv_in(tmp, "app.tenshu.drive.android", placeholder=True)
            code, out, _ = self.run_main("data-safety", "app.tenshu.drive.android", path, "--dry-run")
        self.assertEqual(code, 0)
        self.assertIn("(dry run)", out)
        self.assertIn("PLACEHOLDER", out)

    def test_upload_posts_the_csv_as_safety_labels(self):
        calls = []
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(pp, "load_service_account", return_value={}), \
                mock.patch.object(pp, "access_token", return_value="tok"), \
                mock.patch.object(pp, "request", side_effect=lambda *a, **k: calls.append((a, k)) or {}):
            path = self.csv_in(tmp, "app.tenshu.drive.android")
            code, _, _ = self.run_main("data-safety", "app.tenshu.drive.android", path)
            with open(path, encoding="utf-8", newline="") as f:
                text = f.read()
        self.assertEqual(code, 0)
        (method, url, token), kwargs = calls[0]
        self.assertEqual((method, token), ("POST", "tok"))
        self.assertTrue(url.endswith("/applications/app.tenshu.drive.android/dataSafety"))
        self.assertEqual(kwargs["body"], {"safetyLabels": text})

    def test_upload_refuses_a_placeholder_and_the_wrong_package_before_credentials(self):
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(pp, "load_service_account", side_effect=AssertionError("credentials")):
            path = self.csv_in(os.path.join(tmp, "a"), "app.tenshu.control.android", placeholder=True)
            code, _, err = self.run_main("data-safety", "app.tenshu.control.android", path)
            self.assertEqual(code, 1)
            self.assertIn("PLACEHOLDER", err)
            path = self.csv_in(os.path.join(tmp, "b"), "app.tenshu.drive.android")
            code, _, err = self.run_main("data-safety", "app.tenshu.maps.android", path, "--dry-run")
            self.assertEqual(code, 1)
            self.assertIn("app.tenshu.drive.android", err)

    def gated_csv_in(self, folder):
        path = self.csv_in(folder, "app.tenshu.control.android")
        with open(os.path.join(folder, "data-safety.json"), "w") as f:
            json.dump(dict(spec(), upload_gate=ds.CLOSURE_FLAGS_GATE), f)
        return path

    def test_upload_refuses_deletion_answers_until_the_closure_flags_are_open(self):
        calls = []
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(pp, "load_service_account", side_effect=AssertionError("credentials")):
            path = self.gated_csv_in(tmp)
            code, _, err = self.run_main("data-safety", "app.tenshu.control.android", path)
            self.assertEqual(code, 1, "the gate must refuse before credentials are even read")
            self.assertIn("--closure-flags-open", err)
            code, out, _ = self.run_main("data-safety", "app.tenshu.control.android", path, "--dry-run")
            self.assertEqual(code, 0)
            self.assertIn("closure flags", out)
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(pp, "load_service_account", return_value={}), \
                mock.patch.object(pp, "access_token", return_value="tok"), \
                mock.patch.object(pp, "request", side_effect=lambda *a, **k: calls.append(a) or {}):
            path = self.gated_csv_in(tmp)
            code, _, _ = self.run_main("data-safety", "app.tenshu.control.android", path, "--closure-flags-open")
        self.assertEqual(code, 0)
        self.assertEqual(len(calls), 1, "with the explicit override the upload goes ahead")

    def test_controls_committed_answers_no_longer_carry_the_gate(self):
        # Both account-closure flags were opened in production on 2026-10-24, so the "Yes" to data
        # deletion is true and Control's answers upload without --closure-flags-open. The gate
        # mechanism stays for any future answer that depends on a switch.
        # ⚠ Putting the gate back (if a closure flag is ever closed again) means editing this test
        # too: it pins the gate's ABSENCE, so it goes red the moment the gate returns.
        with open(os.path.join(HERE, "..", "store", "control", "play", "data-safety.json")) as f:
            self.assertIsNone(json.load(f).get("upload_gate"))


if __name__ == "__main__":
    unittest.main()
