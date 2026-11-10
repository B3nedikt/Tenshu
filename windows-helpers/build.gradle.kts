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

// `WindowsPeerProcess` reads a socket's SOCKET handle by reflection, exactly as `:macos-helpers`'
// `PeerAuditToken` reads its fd — so the test JVM needs the same opens the desktop apps pass.
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens", "java.base/java.io=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
    )
}

// ⚠⚠ SPIKE, not shipped: the X0 Cloud Files driver (`docs/proposals/desktop-windows-port.md` §7, X0
// findings). Its own source set so nothing in `main` — and so no app — can reach it. It registers a REAL
// sync root under %TEMP%\tenshu-cfapi-spike\ and unregisters it at the end of every run.
//   ./gradlew :windows-helpers:cfapiSpike [-PspikeArgs=--hold | -PspikeArgs=--cleanup]
val spike: SourceSet = sourceSets.create("spike") {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += output + compileClasspath + sourceSets.main.get().runtimeClasspath
}

tasks.register<JavaExec>("cfapiSpike") {
    description = "X0 spike: register a Cloud Files sync root, hydrate a placeholder, unregister (Windows only)."
    classpath = spike.runtimeClasspath
    mainClass.set("app.tenshu.windows.spike.CfapiSpikeKt")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(FFM_JDK)) })
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    args((findProperty("spikeArgs") as String?)?.split(' ')?.filter { it.isNotBlank() } ?: emptyList<String>())
    standardInput = System.`in`
}
