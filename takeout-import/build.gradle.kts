plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

/**
 * Reading a Google Takeout export — a **pure, dependency-free** KMP module
 * (`docs/proposals/takeout-import-and-recall.md` B1).
 *
 * Two halves, deliberately in one module:
 * - `zip/` is a **generic** ZIP reader (central directory, STORED + DEFLATE, zip64). Nothing about
 *   it is Takeout-specific, and it moves out the day a second consumer wants it.
 * - `TakeoutRouter` is the Takeout-specific half: which entry is a timeline export, which is a
 *   photo, which is a photo's metadata sidecar.
 *
 * ⚠ **The inflate is injected, never `expect`/`actual`** (TB-D1) — the `TileDecompressor`
 * precedent. `commonMain` has no deflate, a JS actual is awkward, and an injected lambda keeps
 * this module dependency-free and its tests platform-free. [app.tenshu.takeout.zip.JvmInflate]
 * is the JVM/Android half.
 */
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.takeout"
        compileSdk = 37
        minSdk = 24
    }

    jvm()

    js {
        browser()
    }

    // Declared so commonMain stays KMP-honest (catches accidental JVM-only deps); no framework.
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        // ⚠ `java.util.zip` is the inflate on **both** JVM targets, and the default hierarchy has
        // no jvm+android group — without this intermediate set, Android could not see the streaming
        // reader at all and the phone would have needed its own copy (which is how
        // `JvmTileDecompressor` ended up existing twice).
        val jvmSharedMain by creating { dependsOn(getByName("commonMain")) }
        getByName("jvmMain").dependsOn(jvmSharedMain)
        getByName("androidMain").dependsOn(jvmSharedMain)

        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
