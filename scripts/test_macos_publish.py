#!/usr/bin/env python3
"""Tests for the macOS direct release's checks, manifest and casks: scripts/lib/macos_publish.py.

  python3 scripts/test_macos_publish.py      (no network, no Gradle, no notarized build)

The Gatekeeper / mount step (`inspect_dmg`) is replaced by a fake that returns what the test says
the app inside declares; everything else — SHA256SUMS, the catalogue, the refusals, the manifest and
the casks — runs for real against files in a temporary `macos-release` directory.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "lib"))
import macos_publish as mp  # noqa: E402

BASE = "https://releases.tenshu.app/sha256/"
CATALOGUE = mp.catalogue()


class Release:
    """A fake build/macos-release: ten .dmg of distinct bytes and their SHA256SUMS."""

    def __init__(self, root, version="1.0.1", skip=(), extra=()):
        self.dir = os.path.join(root, "macos-release")
        os.makedirs(self.dir)
        self.version = version
        self.info = {}
        lines = []
        for app, bundle, bundle_id in CATALOGUE:
            if app in skip:
                continue
            name = f"{bundle}-{version}.dmg"
            with open(os.path.join(self.dir, name), "wb") as f:
                f.write(f"{app} {version}".encode())
            lines.append(f"{mp.sha256(os.path.join(self.dir, name))}  {name}")
            self.info[bundle] = {"bundleId": bundle_id, "version": version, "build": "202611061517",
                                 "minimumMacOS": "14.0"}
        lines += list(extra)
        self.write_sums(lines)

    def write_sums(self, lines):
        with open(os.path.join(self.dir, "SHA256SUMS"), "w") as f:
            f.write("\n".join(lines) + "\n")

    def fake_inspect(self, path, bundle):
        return dict(self.info[bundle])


class PlanTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="macos-publish-test.")
        self.stage = os.path.join(self.tmp, "stage")
        self.published = os.path.join(self.tmp, "published.json")
        self._saved = (mp.inspect_dmg, mp.MANIFEST)
        mp.MANIFEST = self.published  # absolute: os.path.join(ROOT, abs) is abs

    def tearDown(self):
        mp.inspect_dmg, mp.MANIFEST = self._saved
        shutil.rmtree(self.tmp)

    def plan(self, rel, published="2026-11-06"):
        mp.inspect_dmg = rel.fake_inspect
        mp.plan(rel.dir, BASE, self.stage, published)
        with open(os.path.join(self.stage, "macos-release.json")) as f:
            return json.load(f)

    def refused(self, rel, needle):
        with self.assertRaises(mp.Refusal) as e:
            self.plan(rel)
        self.assertIn(needle, str(e.exception))
        self.assertFalse(os.path.exists(os.path.join(self.stage, "macos-release.json")),
                         "a refusal must write no manifest")

    def test_a_complete_release_plans_every_app_in_catalogue_order(self):
        m = self.plan(Release(self.tmp))
        self.assertEqual(list(m["apps"]), [a for a, _, _ in CATALOGUE])
        self.assertEqual((m["version"], m["build"], m["minimumMacOS"]), ("1.0.1", "202611061517", "14.0"))
        for e in m["apps"].values():
            self.assertEqual(e["url"], BASE + e["sha256"] + ".dmg")
        with open(os.path.join(self.stage, "objects.tsv")) as f:
            rows = [line.rstrip("\n").split("\t") for line in f]
        self.assertEqual(len(rows), 10)
        for sha, path, key, name, size in rows:
            self.assertEqual(key, f"sha256/{sha}.dmg")
            self.assertEqual(mp.sha256(path), sha)
            self.assertEqual(int(size), os.path.getsize(path))
        mp.check_manifest(m)

    def test_a_pipeline_test_directory_is_refused(self):
        rel = Release(self.tmp)
        test_dir = os.path.join(self.tmp, "macos-release-test")
        os.rename(rel.dir, test_dir)
        rel.dir = test_dir
        self.refused(rel, "not a build/macos-release directory")

    def test_a_missing_app_is_refused(self):
        self.refused(Release(self.tmp, skip=("photos",)), "Missing: ['TenshuPhotos-1.0.1.dmg']")

    def test_two_versions_are_refused(self):
        rel = Release(self.tmp, extra=[f"{'0' * 64}  TenshuPhotos-1.0.2.dmg"])
        self.refused(rel, "more than one version")

    def test_bytes_that_differ_from_the_sums_are_refused(self):
        rel = Release(self.tmp)
        with open(os.path.join(rel.dir, "TenshuMaps-1.0.1.dmg"), "ab") as f:
            f.write(b"!")
        self.refused(rel, "TenshuMaps-1.0.1.dmg hashes to")

    def test_a_wrong_bundle_id_is_refused(self):
        rel = Release(self.tmp)
        rel.info["TenshuFit"]["bundleId"] = "app.tenshu.fit"
        self.refused(rel, "the catalogue says app.tenshu.fit.ios")

    def test_an_app_whose_version_differs_from_its_file_name_is_refused(self):
        rel = Release(self.tmp)
        rel.info["TenshuMia"]["version"] = "1.0.0"
        self.refused(rel, "TenshuMia.app is version 1.0.0")

    def test_the_jpackage_default_minimum_is_caught(self):
        # The first notarized 1.0.1: eight apps at 10.13, Control and Drive at 14.0.
        rel = Release(self.tmp)
        for bundle in rel.info:
            if bundle not in ("TenshuControl", "TenshuDrive"):
                rel.info[bundle]["minimumMacOS"] = "10.13"
        self.refused(rel, "LSMinimumSystemVersion")

    def test_ten_apps_agreeing_on_the_wrong_minimum_are_refused(self):
        # Agreement is not enough: the floor is gradle.properties' (D10).
        rel = Release(self.tmp)
        for bundle in rel.info:
            rel.info[bundle]["minimumMacOS"] = "10.13"
        self.refused(rel, "gradle.properties says 14.0")

    def test_apps_from_two_builds_are_refused(self):
        rel = Release(self.tmp)
        rel.info["TenshuHome"]["build"] = "202611061041"
        self.refused(rel, "CFBundleVersion")

    def publish(self, rel, published="2026-11-06"):
        m = self.plan(rel, published)
        shutil.copy(os.path.join(self.stage, "macos-release.json"), self.published)
        shutil.rmtree(self.stage)
        return m

    def test_a_lower_version_than_the_published_one_is_refused(self):
        self.publish(Release(os.path.join(self.tmp, "a"), version="1.0.2"))
        self.refused(Release(os.path.join(self.tmp, "b"), version="1.0.1"), "lower than the published 1.0.2")

    def test_a_published_version_with_other_bytes_is_refused(self):
        self.publish(Release(os.path.join(self.tmp, "a")))
        rel = Release(os.path.join(self.tmp, "b"))
        name = "TenshuCalendar-1.0.1.dmg"
        with open(os.path.join(rel.dir, name), "wb") as f:
            f.write(b"rebuilt")
        rel.write_sums([f"{mp.sha256(os.path.join(rel.dir, f))}  {f}"
                        for f in sorted(os.listdir(rel.dir)) if f.endswith(".dmg")])
        self.refused(rel, "already published with other bytes for calendar")

    def test_a_changed_base_is_refused(self):
        self.publish(Release(os.path.join(self.tmp, "a")))
        mp.inspect_dmg = Release(os.path.join(self.tmp, "b"), version="1.0.2").fake_inspect
        with self.assertRaises(mp.Refusal) as e:
            mp.plan(os.path.join(self.tmp, "b", "macos-release"), "https://elsewhere.example/sha256/",
                    self.stage, "2026-12-01")
        self.assertIn("move the manifest deliberately", str(e.exception))

    def test_a_published_version_spelled_differently_is_refused(self):
        self.publish(Release(os.path.join(self.tmp, "a"), version="1.1"))
        self.refused(Release(os.path.join(self.tmp, "b"), version="1.1.0"), "spelled differently")

    def test_republishing_the_same_bytes_keeps_the_published_date(self):
        first = self.publish(Release(os.path.join(self.tmp, "a")), published="2026-11-06")
        again = self.plan(Release(os.path.join(self.tmp, "b")), published="2026-12-01")
        self.assertEqual(again["published"], "2026-11-06")
        self.assertEqual(again["apps"], first["apps"])

    def test_a_newer_version_is_planned(self):
        self.publish(Release(os.path.join(self.tmp, "a")))
        m = self.plan(Release(os.path.join(self.tmp, "b"), version="1.0.10"), published="2026-12-01")
        self.assertEqual((m["version"], m["published"]), ("1.0.10", "2026-12-01"))


class ManifestAndCaskTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="macos-publish-test.")
        saved = (mp.inspect_dmg, mp.MANIFEST)
        rel = Release(self.tmp)
        mp.inspect_dmg, mp.MANIFEST = rel.fake_inspect, os.path.join(self.tmp, "none.json")
        try:
            mp.plan(rel.dir, BASE, os.path.join(self.tmp, "stage"), "2026-11-06")
        finally:
            mp.inspect_dmg, mp.MANIFEST = saved
        self.path = os.path.join(self.tmp, "stage", "macos-release.json")
        with open(self.path) as f:
            self.m = json.load(f)

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def test_check_rejects_a_url_that_is_not_base_plus_hash(self):
        self.m["apps"]["drive"]["url"] = BASE + "0" * 64 + ".dmg"
        with self.assertRaises(mp.Refusal):
            mp.check_manifest(self.m)

    def test_check_rejects_a_missing_app(self):
        del self.m["apps"]["contacts"]
        with self.assertRaises(mp.Refusal):
            mp.check_manifest(self.m)

    def test_an_unknown_minimum_has_no_cask(self):
        self.m["minimumMacOS"] = "27.0"
        with self.assertRaises(mp.Refusal):
            mp.cask("photos", self.m)

    def test_casks(self):
        out = os.path.join(self.tmp, "tap")
        mp.write_casks(self.path, out)
        casks = sorted(os.listdir(os.path.join(out, "Casks")))
        self.assertEqual(casks, sorted(f"tenshu-{a}.rb" for a, _, _ in CATALOGUE))
        for app, bundle, bundle_id in CATALOGUE:
            with open(os.path.join(out, "Casks", f"tenshu-{app}.rb")) as f:
                text = f.read()
            e = self.m["apps"][app]
            self.assertIn(f'sha256 "{e["sha256"]}"', text)
            self.assertIn(f'url "{e["url"]}"', text)
            self.assertIn(f'app "{bundle}.app"', text)
            self.assertIn("depends_on arch: :arm64", text)
            self.assertIn("depends_on macos: :sonoma", text)  # from minimumMacOS 14.0
            self.assertIn(f'url "https://tenshu.app/apps/{app}/download/macos.json"', text)
            self.assertIn('json["version"]', text)
            # §2.12.3 T3: no cask may pull in a second Control over a store copy.
            code = "\n".join(line for line in text.splitlines() if not line.lstrip().startswith("#"))
            self.assertNotIn("depends_on cask:", code)
            # T4: `brew upgrade` is the update path until the M3 updater exists.
            self.assertNotIn("auto_updates", code)
            if app == "control":
                self.assertIn('"app.tenshu.control.tray"', text)  # C1: the LaunchAgents go with it
                self.assertIn("app.tenshu.control.desktop.master-key-store", text)
            else:
                self.assertIn(f'uninstall quit: "{bundle_id}"', text)
                self.assertIn("needs Tenshu Control", text)
                self.assertIn(f"app.tenshu.{app}.desktop", text)
            if shutil.which("ruby"):
                r = subprocess.run(["ruby", "-c", os.path.join(out, "Casks", f"tenshu-{app}.rb")],
                                   capture_output=True, text=True)
                self.assertEqual(r.returncode, 0, r.stderr)


if __name__ == "__main__":
    unittest.main()
