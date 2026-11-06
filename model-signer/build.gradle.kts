plugins {
    id("org.jetbrains.kotlin.jvm")
    application
    alias(libs.plugins.kotlinx.serialization)
}

dependencies {
    // ⚠⚠ The SAME Ed25519 the client verifies with (`BouncyCastleEd25519Backend`), so the model
    // catalog has ONE signature stack, exactly as the tiles catalog does (IN-D10 inherits
    // `maps-region-delivery.md` D3). A second primitive here would be a second thing to get wrong.
    implementation(project(":core-common"))
    // ⚠⚠ For `InferenceCapability` alone, so a typo'd capability is a BUILD failure. The client
    // deliberately drops names it does not know (forward compatibility — a newer catalog must
    // still deliver the capabilities an older build does understand), which means a misspelling
    // publishes an entry every device silently ignores. At build time from this same tree there is
    // no forward-compatibility case to protect: an unknown name is a mistake.
    implementation(project(":inference-service"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
}

// ⚠ 17, matching `:inference-service-proxy` — that module's `jvmTest` depends on this one for the
// round-trip test, and Gradle refuses to put a 21-target library on a 17 runtime classpath. The
// tool has no need of a newer JVM, so the toolchain follows the consumer rather than the other way
// round.
kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("org.tenshu.models.MainKt")
}

// ⚠ Run from the REPO ROOT. Every documented invocation passes repo-relative paths, because that is
// where the operator's shell is; Gradle's JavaExec would otherwise resolve them against
// model-signer/ and every one would fail with FileNotFoundException. Same fix, same reason, as
// `:tiles-signer` — see its build file.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
