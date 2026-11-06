import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

// IN-3m-iOS — the on-device measurement of the Core ML route
// (docs/proposals/ios-on-device-inference.md §4). ⚠⚠ Never shipped, and never a gate: it is linked
// only into `inference-harness-ios/`'s own app, and it refuses to report anything on a simulator.
//
// It is a module of its own because the harness must measure the SHIPPED Kotlin — `CoreMlSession`,
// `CoreMlModelCache`, `IosImageDecoder` in `:inference-service-proxy` — and Swift cannot reach
// those through `TenshuSdk`: the umbrella exports no inference module, and exporting one for a
// harness would put harness surface in every app (§4.2, D9).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    applyDefaultHierarchyTemplate()

    val xcframework = XCFramework("In3mHarnessKit")

    // No iosX64(), as the umbrella: Apple silicon is the only simulator arch here.
    // ⚠ RELEASE only. A debug Kotlin/Native binary runs the pixel loops several times slower, and
    // a measurement of unoptimised code describes nothing that ships.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework(listOf(NativeBuildType.RELEASE)) {
            baseName = "In3mHarnessKit"
            isStatic = true
            xcframework.add(this)
        }
    }

    sourceSets {
        iosMain {
            // ⚠ One file, a third compilation: `HarnessMath` is the percentile and cosine the
            // desktop harness and Android's IN-3m already use. `HarnessArguments` beside it is JVM.
            kotlin.srcDir("../inference-service-proxy/src/harnessShared/kotlin")
            kotlin.exclude("**/HarnessArguments.kt")
            dependencies {
                implementation(project(":inference-service-proxy"))
                implementation(libs.kotlinx.coroutines)
            }
        }
        iosTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

// The same fixture directory `:inference-service-proxy`'s `iosTest` reads, for the one case that
// runs the whole harness against a model Core ML really compiled.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    val fixtures = rootProject.layout.projectDirectory.dir("inference-service-proxy/src/iosTest/resources/coreml")
    inputs.dir(fixtures)
    environment("SIMCTL_CHILD_TENSHU_COREML_FIXTURES", fixtures.asFile.absolutePath)
}
