plugins {
    id("org.jetbrains.kotlin.jvm")
    application
    alias(libs.plugins.kotlinx.serialization)
}

dependencies {
    // Reuse the in-tree Ed25519 (BouncyCastleEd25519Backend) + sha256 so the manifest is signed
    // with the exact primitive the client verifies with — no second signature stack (D3/§9).
    implementation(project(":core-common"))
    // §33 L3: the overlay's FORMAT_VERSION comes from the reader itself rather than being restated
    // here — a second copy of a format constant is exactly the drift `TransitLineIndex.encode` is
    // kept single for.
    implementation(project(":vector-tile"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
}

application {
    // deploy: `./gradlew :tiles-signer:run --args="build-and-sign --regions … --extracts … --out … --key …"`
    mainClass.set("org.tenshu.tiles.MainKt")
}

// Run from the REPO ROOT, not tiles-signer/. Every documented invocation passes repo-relative paths
// (`--regions deploy/tiles/regions.json`), because that is where the operator's shell is and what
// the how-to shows. Gradle's JavaExec defaults workingDir to the subproject directory, so those
// paths resolved against tiles-signer/ and every one of them failed with FileNotFoundException —
// the how-to's sign step could not be copy-pasted. `tiles-build.sh` only worked around this by
// passing absolute paths it had already computed.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
