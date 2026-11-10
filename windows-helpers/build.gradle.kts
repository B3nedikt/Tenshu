plugins {
    id("org.jetbrains.kotlin.jvm")
}

/**
 * The Windows twin of `:macos-helpers`: Win32 calls over FFM (`java.lang.foreign`) for the
 * capabilities a Windows Control needs and the JDK does not expose — DPAPI first, then the peer
 * pid, the notification-area icon, power/idle and the Cloud Files API
 * (`docs/proposals/desktop-windows-port.md`).
 *
 * ⚠⚠ Same toolchain split as `:macos-helpers` (read its build file for the full reasoning):
 * COMPILE against a final-FFM JDK, EMIT JVM-17 bytecode, so the ~100 shared modules on
 * `jvmToolchain(17)` can consume it. Keep [FFM_JDK] equal to the desktop apps' bundled runtime,
 * which is what FFM actually runs on.
 *
 * ⚠ A separate module rather than more code in `:macos-helpers`: that module's name is load-bearing
 * documentation for every reader of a dependency list, and the two never share a binding.
 */
val FFM_JDK = 25

kotlin {
    jvmToolchain(FFM_JDK)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.kotlin.test)
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
