// Mocha's 2 s default is not enough for whichever test class happens to sort FIRST.
// The jsTest suite shares one singleton `JsRuntime`, so that class alone pays the cold
// boot — IndexedDB open, a possible store wipe, hydration — while every later class finds
// it warm. That made the budget a function of class NAMING: adding `ContentPlaneWiringTest`
// (W1) moved the cost onto it and failed it at 2 s, having changed nothing about the boot.
//
// Raising the ceiling removes the ordering dependency instead of encoding it in a name.
// A karma.config.d snippet rather than `testTask { useMocha { … } }`: this target is
// `browser()`, where Kotlin runs mocha *under karma*, and the Gradle DSL's `useMocha`
// selects the standalone node runner — which pulls a new npm dep and rewrites
// `kotlin-js-store/yarn.lock` for what is meant to be a timeout.
config.set({
    client: {
        mocha: {
            timeout: 30000,
        },
    },
})
