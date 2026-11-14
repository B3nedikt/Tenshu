// Mocha's 2 s default is not enough for whichever test pays a cold, one-off cost first — and on
// a slow runner that is a function of test ORDER, not of the code under test. Two cases so far:
//
// - `:tenshu-sdk-js` shares one singleton `JsRuntime`, so whichever class sorts first pays its
//   cold boot (IndexedDB open, a possible store wipe, hydration). Adding `ContentPlaneWiringTest`
//   moved that cost onto it and failed it at 2 s, having changed nothing about the boot.
// - `:app-notify`: the first `TimeZone.of("Europe/Berlin")` makes kotlinx-datetime parse the whole
//   js-joda tz database — 168 ms on a dev Mac (5 ms on the JVM), past 2 s on GitHub's 2-core
//   Linux runner. `AppleAlertScheduleTest`'s first case failed there on every public CI run with a
//   console line reading only `Error`; the HTML report has the Mocha timeout.
//
// Raising the ceiling removes the ordering dependency instead of encoding it in a name. ⚠ A
// synchronous test that overruns still fails: Mocha checks the elapsed time after it returns.
//
// ⚠ Applied to EVERY module's Karma run from the root build (`build.gradle.kts`, the
// `KotlinJsTest` block), not per module: Kotlin reads exactly one config directory per test
// task, so a per-module `karma.config.d/` would silently drop these snippets — the root build
// refuses one. A karma.config.d snippet rather than `testTask { useMocha { … } }`: the targets
// are `browser()`, where Kotlin runs mocha *under karma*, and the Gradle DSL's `useMocha` selects
// the standalone node runner instead.
config.set({
    client: {
        mocha: {
            timeout: 30000,
        },
    },
})
