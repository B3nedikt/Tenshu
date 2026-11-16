#!/usr/bin/env python3
"""Tests for scripts/ci/ios-select.py: which iOS CI jobs a change runs.

    python3 scripts/test_ios_select.py
"""
import importlib.util
import json
import os
import sys
import unittest

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
# The Swift-folder mapping reads each app's real `project.yml`.
os.chdir(os.path.dirname(HERE))

spec = importlib.util.spec_from_file_location("ios_select", os.path.join(HERE, "ci", "ios-select.py"))
select = importlib.util.module_from_spec(spec)
spec.loader.exec_module(select)


def project(path, deps=(), ios_tests=True):
    tasks = ["jvmTest"] + (["linkDebugTestIosSimulatorArm64", "iosSimulatorArm64Test"] if ios_tests else [])
    return {"path": path, "dir": path.lstrip(":"), "srcDirs": [], "deps": list(deps), "tasks": tasks}


# core-common ← drive-shared-logic ← tenshu-sdk-ios-drive; control-desktop depends on core-common
# but has no iOS tests. Forty filler modules depend on core-common, so a core change needs 3 shards.
GRAPH = [
    project(":core-common"),
    project(":drive-shared-logic", [":core-common"]),
    project(":tenshu-sdk-ios-drive", [":drive-shared-logic"]),
    project(":tenshu-sdk-ios-maps", []),
    project(":control-desktop", [":core-common"], ios_tests=False),
    project(":ui-map", [":core-common"], ios_tests=False),
    project(":maps-shared-logic", [":ui-map"]),
] + [project(f":filler-{i:02d}", [":core-common"]) for i in range(40)]


def run(*files):
    return select.select(GRAPH, list(files))


class IosSelectTest(unittest.TestCase):
    def test_a_desktop_only_change_takes_no_macos_runner(self):
        out = run("control-desktop/src/desktopMain/kotlin/A.kt", "docs/x.md", ".github/workflows/windows.yml")
        self.assertEqual(out["shards"], "[]")
        self.assertEqual(out["app_halves"], "[]")

    def test_a_module_change_runs_it_and_its_dependents_and_their_apps(self):
        out = run("drive-shared-logic/src/commonMain/kotlin/A.kt")
        self.assertEqual(out["test_modules"].split(), [":drive-shared-logic", ":tenshu-sdk-ios-drive"])
        self.assertEqual(out["shards"], "[1]")
        self.assertEqual(json.loads(out["app_halves"]), ["drive"])

    def test_a_shared_change_spreads_over_three_shards(self):
        out = run("core-common/src/commonMain/kotlin/A.kt")
        self.assertEqual(out["shards"], "[1, 2, 3]")
        self.assertEqual(len(out["test_modules"].split()), 44)

    def test_an_apps_swift_compiles_that_app_alone(self):
        out = run("drive-ios/DriveIos/A.swift")
        self.assertEqual(out["shards"], "[]")
        self.assertEqual(json.loads(out["app_halves"]), ["drive"])

    def test_a_shared_swift_folder_compiles_every_app_that_includes_it(self):
        # ios-basemap/ is included by Fit, Maps and Timeline's project.yml.
        out = run("ios-basemap/Sources/A.swift")
        self.assertEqual(json.loads(out["app_halves"]), ["fit timeline maps"])

    def test_this_lanes_own_files_run_everything(self):
        for f in [".github/workflows/ios.yml", "scripts/ci/ios-test-shard.py", "scripts/ios-smoke.sh"]:
            out = run(f)
            self.assertTrue(out["reason"].startswith("full:"), f)
            self.assertEqual(out["shards"], "[1, 2, 3]", f)
            # Empty = every module the Mac shard's own graph lists.
            self.assertEqual(out["test_modules"], "", f)
            self.assertEqual(json.loads(out["app_halves"]), select.HALVES, f)

    def test_a_script_xcode_runs_is_still_walked_through_gradle(self):
        # Every project.yml runs ../scripts/ios-build-sdk.sh, so `scripts/` is an Xcode input —
        # but the native-library scripts behind core-common's cinterops are Gradle inputs too.
        for f in ["scripts/build-libsodium-apple.sh", "scripts/build-sqlite-apple.sh"]:
            out = run(f)
            self.assertNotEqual(out["shards"], "[]", f)

    def test_the_selectors_own_engine_runs_everything(self):
        for f in ["scripts/lib/affected_modules.py", "scripts/test_ios_select.py"]:
            self.assertTrue(run(f)["reason"].startswith("full:"), f)

    def test_an_asset_a_fragment_bundles_compiles_the_apps_that_include_it(self):
        # ios-basemap/basemap.yml bundles ../ui-map/…/tenshu_basemap.pmtiles into Fit, Timeline, Maps.
        out = run("ui-map/src/androidMain/assets/tenshu_basemap.pmtiles")
        self.assertEqual(json.loads(out["app_halves"]), ["fit timeline maps"])
        # …and, being in a Gradle module, it is walked through the graph as well, not instead.
        self.assertIn(":maps-shared-logic", out["test_modules"].split())

    def test_a_swift_folder_no_app_reads_runs_everything(self):
        self.assertTrue(run("ios-unheard-of/Sources/A.swift")["reason"].startswith("full:"))

    def test_a_build_file_runs_everything(self):
        self.assertTrue(run("gradle/libs.versions.toml")["reason"].startswith("full:"))

    def test_a_file_in_no_module_runs_everything(self):
        self.assertTrue(run("iosApp/iosApp.xcodeproj/project.pbxproj")["reason"].startswith("full:"))

    def test_a_graph_without_ios_tasks_is_refused_not_read_as_no_ios_code(self):
        bare = [project(":core-common", ios_tests=False)]
        with self.assertRaises(SystemExit):
            select.select(bare, ["core-common/src/commonMain/kotlin/A.kt"])

    def test_the_halves_are_every_app_once(self):
        apps = [a for h in select.HALVES for a in h.split()]
        self.assertEqual(len(apps), len(set(apps)))
        self.assertEqual(len(apps), 10)


if __name__ == "__main__":
    unittest.main()
