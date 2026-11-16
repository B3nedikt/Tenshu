import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

// Shared macOS code-signing (desktop-ipc-client-identity.md P0): registers
// `signDesktopApp`, which signs this module's .app with the configured Apple
// identity so the desktop IPC peer-credential gate can attest it. Self-skips when
// unconfigured; see the script for why hardened runtime is opt-in.
apply(from = rootProject.file("gradle/desktop-app-signing.gradle.kts"))

// Calendar desktop run target (docs/proposals/calendar-app.md CA-2b). `./gradlew
// :calendar-desktop:run` opens the app. Mirrors :maps-desktop — same desktop
// ViewModelStore / SavedState shims. No macOS icon yet (icon track, later milestone).
// ── Toolchain: a final-FFM runtime (calendar-external-sources.md CX-2c) ───────
//
// *Copy from this Mac* reads EventKit in-process over `java.lang.foreign` (final in JDK 22), so the
// runtime jpackage bundles has to be 22+ or the first FFM call fails in the shipped app. Same rule
// and same JDK as `:drive-desktop` / `:control-desktop`; the shared modules stay on 17.
val FFM_JDK = 25

kotlin {
    jvmToolchain(FFM_JDK)
    jvm()
    // The window's own icon on Windows — title bar, Alt+Tab (TenshuWindowIcon; ADR 0026 §6b).
    sourceSets.named("jvmMain") { resources.srcDir("icons/windows/window") }

    sourceSets {
        getByName("jvmMain") {
            dependencies {
                implementation(project(":calendar-shared-ui"))
                implementation(project(":ui-core"))
                // DesktopViewModelStoreOwner / DesktopSavedStateRegistryOwner.
                implementation(project(":core-common"))
                implementation(compose.desktop.currentOs)
                implementation(libs.koinCore)
                implementation(libs.koinMultiplatform)
                implementation(libs.koinComposeViewmodel)
                implementation(libs.lifecycle.viewmodel.compose)
                implementation(libs.lifecycle.runtime.compose)
                implementation(libs.savedstate)
                implementation(libs.savedstate.compose)
                implementation(libs.kotlinx.coroutines.swing)
                implementation("org.slf4j:slf4j-simple:2.0.19")
            }
        }
    }
}

tasks.withType<JavaExec> {
    if (name == "run" || name == "jvmRun") {
        mainClass.set("app.tenshu.calendar.MainKt")
        // ⚠⚠ The dev-run flags go in `doFirst`, not here. Compose's `configureRunTask` calls
        // `setJvmArgs(app.jvmArgs)` AFTER this block and REPLACES the list, so anything set at
        // configuration time never reaches the JVM. `PEER_GATE_JVM_ARGS` reaches the dev run through
        // `compose.desktop.application.jvmArgs`, below. ⚠ Never move these flags onto that list: it
        // also feeds the packaged app, which must never carry `tenshu.release=false`.
        val devArgs = buildList {
            // Dev run is not a release build: A16 developer mode lets it reach a dev-run Control, an
            // unsigned `java` process (`ControlServerCheck`).
            add("-Dtenshu.release=false")
            // A Skiko UnsatisfiedLinkError fix on macOS only: METAL is not a Windows/Linux renderer.
            if (System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)) {
                add("-Dskiko.renderApi=METAL")
            }
        }
        doFirst { (this as JavaExec).jvmArgs(devArgs) }
    }
}

// FFM, and the two opens `PeerAuditToken` needs for the client side of the desktop IPC gate
// (`ControlServerCheck`). ⚠⚠ Without the opens a packaged app refuses the genuine Control. Applied at
// both JVM sites; `ControlServerCheckTest` checks every desktop app has them.
val PEER_GATE_JVM_ARGS = listOf(
    "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens", "java.base/java.io=ALL-UNNAMED",
    "--enable-native-access=ALL-UNNAMED",
)

// jpackage embeds `javaHome`, defaulting to the Gradle daemon's JVM — point it at the FFM toolchain,
// resolved tolerantly (`:control-desktop` explains why a missing 25 must not fail configuration).
val ffmRuntime: String? = runCatching {
    javaToolchains
        .launcherFor { languageVersion.set(JavaLanguageVersion.of(FFM_JDK)) }
        .get().metadata.installationPath.asFile.absolutePath
}.getOrNull()

compose.desktop {
    application {
        mainClass = "app.tenshu.calendar.MainKt"
        jvmArgs += PEER_GATE_JVM_ARGS
        ffmRuntime?.let { javaHome = it }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Pkg)
            packageName = "TenshuCalendar"
            packageVersion = "1.0.1"

            // ⚠⚠ **This is what makes macOS offer Calendar for a `.ics` file** (CA-6c).
            // jpackage turns it into a `CFBundleDocumentTypes` entry in the bundled
            // `Info.plist`; Launch Services reads that when the `.app` is first seen, and
            // only then does *Open With* list Calendar or let a user make it the default.
            //
            // ⚠⚠ It therefore does **nothing** for `:calendar-desktop:run`, which starts a
            // bare JVM and never builds a bundle. Testing this means `packageDmg` (or
            // `createDistributable`) and launching the built `.app` at least once —
            // `IcsDocumentOpens` handles the file either way, but nothing will hand it one
            // until Launch Services has seen the bundle.
            //
            // ⚠ Positional arguments, matching the plugin's
            // `fileAssociation(mimeType, extension, description, iconFile)`. No icon: the
            // app's own is what jpackage falls back to, and a distinct document icon is
            // part of the icon track, not this.
            fileAssociation("text/calendar", "ics", "iCalendar file")

            // Windows: the launcher .exe's icon (ADR 0026 §6b; scripts/windows-app-icons.py).
            windows { iconFile.set(project.file("icons/windows/calendar.ico")) }
            macOS {
                // Pin the CFBundleIdentifier explicitly — the desktop IPC gate
                // authenticates on the signed identifier, so it must not be left to the
                // plugin's derivation (desktop-ipc-client-identity.md P0.2).
                bundleID = "app.tenshu.calendar.ios"
                // LSMinimumSystemVersion (D10); jpackage's default (10.13) is refused for arm64-only.
                minimumSystemVersion = project.property("tenshu.macosDeploymentTarget").toString()
                // ⚠ macOS .icns must follow Apple's icon grid: the body is inset to
                // ~824/1024 (~100px transparent margin) with a continuous squircle —
                // NOT the full-bleed iOS/PWA tile, which makes the Dock icon ~25%
                // oversized vs every other app. Regen recipe: docs/adr/0026-app-icon-system.md §6.
                iconFile.set(project.file("icons/calendar.icns"))
                infoPlist {
                    // ⚠⚠ *Copy from this Mac* (CX-2c): without a calendar usage string TCC kills the
                    // process on first EventKit use. FullAccess is read from macOS 14, the plain key
                    // before it. `MacEventKit.isUsable` keys off their presence, so an unbundled
                    // `run` — which has neither — never touches EventKit.
                    extraKeysRawXml = """
                        <key>NSCalendarsFullAccessUsageDescription</key>
                        <string>Tenshu Calendar reads the calendars on this Mac so you can copy one into Tenshu. Nothing is read until you pick a calendar.</string>
                        <key>NSCalendarsUsageDescription</key>
                        <string>Tenshu Calendar reads the calendars on this Mac so you can copy one into Tenshu. Nothing is read until you pick a calendar.</string>
                    """.trimIndent()
                }
            }
        }
    }
}
