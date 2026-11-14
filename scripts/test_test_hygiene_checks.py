#!/usr/bin/env python3
"""Tests for scripts/check-test-wallclock.py and scripts/check-test-koin.py: what each flags.

    python3 scripts/test_test_hygiene_checks.py
"""
import importlib.util
import os
import sys
import unittest

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, file))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


wallclock = load("check_test_wallclock", "check-test-wallclock.py")
koin = load("check_test_koin", "check-test-koin.py")


def kinds(text):
    return [kind for _, kind, _ in wallclock.flagged_lines(text)]


class WallClockTest(unittest.TestCase):
    def test_an_elapsed_bound_is_flagged(self):
        self.assertEqual(["bound"], kinds('    assertTrue(tookMs < 1_500, "the drain")'))
        self.assertEqual(["bound"], kinds("    assertTrue(started.elapsedNow().inWholeMilliseconds < 4_000)"))

    def test_sleeps_and_duration_budgets_are_flagged(self):
        self.assertEqual(["sleep"], kinds("        Thread.sleep(100)"))
        self.assertEqual(["sleep"], kinds("        usleep(100_000u)"))
        self.assertEqual(["duration"], kinds('    val limit = Duration.parse("1s")'))
        self.assertEqual(["sleep"], kinds("        withContext(Dispatchers.Default) { delay(7_500) }"))

    def test_a_runTest_timeout_is_a_hang_guard_not_a_bound(self):
        self.assertEqual([], kinds('fun t() = runTest(timeout = Duration.parse("20s")) {'))

    def test_words_in_a_message_or_comment_are_not_code(self):
        self.assertEqual([], kinds('    assertEquals(3, n, "it took < 2 s before")'))
        self.assertEqual([], kinds("    // Thread.sleep(100) would race here"))

    def test_a_written_reason_exempts_the_line_or_the_next(self):
        self.assertEqual([], kinds("    assertTrue(tookMs < 3_000) // wall-clock: cut to 2 s, not 10 s"))
        self.assertEqual([], kinds("    // wall-clock: the watchdog is the subject\n    assertTrue(tookMs < 3_000)"))


class KoinTest(unittest.TestCase):
    def problems(self, text):
        return koin.problems("T.kt", text)

    def test_a_start_with_a_teardown_stop_is_clean(self):
        self.assertEqual([], self.problems(
            "class T {\n    @BeforeTest\n    fun up() {\n        startKoin { }\n    }\n\n"
            "    @AfterTest\n    fun down() = stopKoin()\n}\n"))

    def test_a_start_without_any_stop_leaks(self):
        found = self.problems("class T {\n    @Test\n    fun t() {\n        startKoin { }\n    }\n}\n")
        self.assertEqual(1, len(found))
        self.assertIn("never stops it", found[0])

    def test_the_composable_KoinApplication_is_a_start(self):
        self.assertEqual(1, len(self.problems("class T {\n    @Test\n    fun t() {\n        KoinApplication(application = {}) { }\n    }\n}\n")))

    def test_a_stop_before_a_start_in_setup_absorbs_a_leak(self):
        found = self.problems(
            "class T {\n    @Before\n    fun up() {\n        stopKoin()\n        val x = 1\n        startKoin { }\n    }\n\n"
            "    @After\n    fun down() {\n        stopKoin()\n    }\n}\n")
        self.assertEqual(1, len(found))
        self.assertIn("absorbs", found[0])

    def test_a_test_may_replace_the_koin_its_own_setup_started(self):
        self.assertEqual([], self.problems(
            "class T {\n    @Before\n    fun up() {\n        startKoin { }\n    }\n\n"
            "    @Test\n    fun swaps() {\n        val repo = object : R {\n            override fun f() = 1\n        }\n"
            "        stopKoin()\n        startKoin { }\n    }\n\n"
            "    @After\n    fun down() = stopKoin()\n}\n"))

    def test_an_isolated_koinApplication_is_not_a_start(self):
        self.assertEqual([], self.problems("class T {\n    val app = koinApplication { }\n}\n"))


if __name__ == "__main__":
    unittest.main()
