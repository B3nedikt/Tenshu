import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

// Shared macOS code-signing (desktop-ipc-client-identity.md §6): registers
// `signDesktopApp`, which signs this module's .app with the configured Apple
// identity so the desktop IPC peer-credential gate can attest it. Self-skips when
// unconfigured; see the script for why hardened runtime is opt-in.
// The MAS-compatible login item is a separate nested app. It is signed with
// its own sandbox/App Group entitlements rather than inheriting Control's.
val masSandboxNestedEntitlements =
    if (providers.gradleProperty("tenshu.masSandbox").orNull?.toBoolean() == true) {
        mapOf(
            // MAS Control starts these helpers directly, so they need a child
            // sandbox rather than the host's broad entitlement set.
            "tray-agent" to "macos/entitlements/tray-agent-inherit.entitlements",
            "qr-scan-helper" to "macos/entitlements/child-inherit.entitlements",
            "image-decode-helper" to "macos/entitlements/child-inherit.entitlements",
            // Embedded from the `:macos-helpers` jar by `embedJarHelpers`.
            "map-snapshot-helper" to "macos/entitlements/child-inherit.entitlements",
            "applock-helper" to "macos/entitlements/child-inherit.entitlements",
            "appgroup-claim" to "macos/entitlements/child-inherit.entitlements",
            "jspawnhelper" to "macos/entitlements/child-inherit.entitlements",
        )
    } else {
        emptyMap()
    }
extra["tenshu.nestedEntitlements"] =
    mapOf(
        "TenshuControlLoginItem.app" to
            "macos/entitlements/control-login-item.entitlements",
    ) + masSandboxNestedEntitlements
// Used only with -Ptenshu.masSandbox=true. Keeping this opt-in preserves the
// unsandboxed Developer ID/development path while making MAS packaging explicit.
extra["tenshu.hostEntitlements"] = "macos/entitlements/control-mas.entitlements"
apply(from = rootProject.file("gradle/desktop-app-signing.gradle.kts"))

// --- macOS signing config resolution ---
// Precedence for both values: -P<name> (override / CI) → $ENV (CI) →
// local.properties (per-developer, gitignored — the comfortable default, the
// same convention the Android Gradle plugin uses for sdk.dir / signing). The
// `local.properties` keys are `tenshu.teamId` / `tenshu.signingIdentity`.
fun localProperty(project: Project, key: String): String? {
    val file = project.rootProject.file("local.properties")
    if (!file.exists()) return null
    // UTF-8 reader — an InputStream load is ISO-8859-1 and mangles a non-ASCII identity name.
    val props = Properties().apply { file.reader(Charsets.UTF_8).use { load(it) } }
    return props.getProperty(key)?.takeIf { it.isNotBlank() }
}

fun resolveTeamId(project: Project): String? =
    (project.findProperty("tenshu.teamId") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("TENSHU_TEAM_ID")?.takeIf { it.isNotBlank() }
        ?: localProperty(project, "tenshu.teamId")

fun resolveSigningIdentity(project: Project): String? =
    (project.findProperty("tenshu.signingIdentity") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("TENSHU_SIGNING_IDENTITY")?.takeIf { it.isNotBlank() }
        ?: localProperty(project, "tenshu.signingIdentity")

/** JDK for this app: compiles it AND becomes its jpackage-bundled runtime. */
val FFM_JDK = 25

// ── Toolchain: bundle a final-FFM runtime (desktop-qr-scanning.md §13.5) ───────
//
// jpackage embeds *this* JDK as the app's runtime, and the macOS camera scanner
// (§13) calls `java.lang.foreign`, final in JDK 22 — so the bundled runtime has to
// be 22+ or the first FFM call fails at runtime on a shipped app. 25 is the LTS;
// Gradle auto-provisions it (foojay resolver in settings.gradle.kts).
//
// Only the app modules and `:macos-helpers` move. The ~100 shared modules stay on
// `jvmToolchain(17)`: `:macos-helpers` compiles against 25's API but emits JVM-17
// bytecode, so nothing else in the graph has to know. Nothing consumes these app
// modules, so their own emitted bytecode can follow the toolchain.
// ── The desktop IPC gate's trust anchor, compiled in ──────────────────────────
//
// `desktop-ipc-client-identity.md` §5. Which Apple Team IDs may vouch for a peer is
// **runtime trust policy**, and it is deliberately NOT read from a property or an
// environment variable at runtime the way the *signing* config is: the gate exists to stop a
// hostile local process, and such a process could then simply add its own team. A shipped
// Control also has no `local.properties` to read. So the anchor is baked into the binary
// here, at build time, from the same value we sign with.
//
// A **set**, so a future team migration is additive (accept {old, new}, roll the fleet, drop
// old) rather than a flag day that re-keys every user's Keychain ACL at once. Supply extras
// via `tenshu.additionalTeamIds` (comma-separated) during such a transition.
val generatePeerTrustAnchor by tasks.registering {
    group = "macos"
    description = "Generates the compiled-in first-party Team ID set for the desktop IPC gate."

    val teamIds = buildSet {
        resolveTeamId(project)?.let { add(it) }
        ((project.findProperty("tenshu.additionalTeamIds") as String?)
            ?: System.getenv("TENSHU_ADDITIONAL_TEAM_IDS"))
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.let { addAll(it) }
    }
    // Windows (desktop-windows-port.md §3.2, D1): the Store publisher ids — the 13-character half of
    // a package family name after the `_` — that may vouch for our MSIX packages. Same rules as the
    // Team IDs: compiled in, a set (a publisher change is additive), never read at runtime. Empty
    // until the Partner Center account exists ⇒ no Windows peer attests and a strict Control admits
    // only under developer mode. A locally side-loaded test package's publisher id goes here too.
    val windowsPublisherIds = ((project.findProperty("tenshu.windowsPublisherIds") as String?)
        ?: System.getenv("TENSHU_WINDOWS_PUBLISHER_IDS"))
        ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSortedSet()
        ?: sortedSetOf()
    // ⚠ Checked here: a typo or an uppercase id would compile into an anchor that silently admits no one, and
    // the value is pasted into Kotlin source unescaped. 13 characters of the publisher-id alphabet.
    windowsPublisherIds.forEach { id ->
        require(Regex("^[0-9a-hjkmnp-tv-z]{13}$").matches(id)) {
            "tenshu.windowsPublisherIds: '$id' is not a publisher id (13 characters of 0-9 a-z without i l o u)"
        }
    }
    val outputDir = layout.buildDirectory.dir("generated/peerTrust")
    outputs.dir(outputDir)
    inputs.property("teamIds", teamIds.sorted().joinToString(","))
    inputs.property("windowsPublisherIds", windowsPublisherIds.joinToString(","))

    doLast {
        val pkg = outputDir.get().asFile.resolve("app/tenshu/control/ipc").apply { mkdirs() }
        val literal = teamIds.sorted().joinToString(", ") { "\"$it\"" }
        val windowsLiteral = windowsPublisherIds.joinToString(", ") { "\"$it\"" }
        pkg.resolve("PeerTrustAnchor.kt").writeText(
            """
            package app.tenshu.control.ipc

            /**
             * GENERATED — do not edit. See `generatePeerTrustAnchor` in
             * control-desktop/build.gradle.kts and `desktop-ipc-client-identity.md` P1.
             *
             * Empty when the build was not configured with a Team ID, in which case no peer
             * can attest and the gate observes nothing — the P0 signing step is what makes
             * this meaningful.
             */
            internal object PeerTrustAnchor {
                val FIRST_PARTY_TEAM_IDS: Set<String> = setOf($literal)

                /** Store publisher ids for our MSIX packages (Windows); empty ⇒ no Windows peer attests. */
                val FIRST_PARTY_WINDOWS_PUBLISHER_IDS: Set<String> = setOf($windowsLiteral)
            }
            """.trimIndent() + "\n",
        )
    }
}

kotlin {
    jvmToolchain(FFM_JDK)
    jvm()
    // The window's own icon on Windows — title bar, Alt+Tab (TenshuWindowIcon; ADR 0026 §6b).
    sourceSets.named("jvmMain") { resources.srcDir("icons/windows/window") }

    sourceSets {
        getByName("jvmMain") {
            kotlin.srcDir(generatePeerTrustAnchor)
            dependencies {
                implementation(project(":macos-helpers"))
                implementation(project(":windows-helpers"))
                // The peer gate's decisions, kept in a file — a packaged app's stdout is lost (an MSIX has none).
                implementation(project(":diagnostics"))
                implementation(project(":control-shared"))
                implementation(project(":ui-core"))
                implementation(project(":core-common"))
                implementation(compose.desktop.currentOs)
                implementation(libs.koinCore)
                implementation(libs.lifecycle.viewmodel.compose)
                implementation(libs.lifecycle.runtime.compose)
                implementation(libs.savedstate)
                implementation(libs.savedstate.compose)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.kotlinx.coroutines.test)
                implementation("org.slf4j:slf4j-simple:2.0.19")

            }
        }
        // `OssLicencesJsonTest` / `OssLicencesNoticeScreenTest`: the open-source notices this binary
        // ships (`docs/oss-licences.md`).
        getByName("jvmTest") {
            dependencies {
                implementation(libs.kotlin.test)
                // `OssLicencesNoticeScreenTest`: the real notices rendered on the shared screen.
                implementation(compose.desktop.uiTestJUnit4)
            }
        }
    }
}

// ── QR backend selection for bring-up (desktop-qr-scanning.md §13 D5) ─────────
//
// `-Ptenshu.qr.impl=ffm` bakes `-Dtenshu.qr.impl=ffm` into BOTH the dev `run` task and the
// packaged app's JVM options, so the in-process FFM scanner can be exercised A/B on the same
// source. Absent (the normal case) nothing is added and `forCurrentOs()` keeps the Swift
// helper.
//
// ⚠ The packaged app is the one that matters for the §13.4 first-grant test: camera consent is
// attributed to the responsible *bundle*, so an unsigned `gradle run` is a different TCC
// subject than TenshuControl.app and can be silently denied. A Gradle `-D` on the command line
// would only reach the Gradle daemon, never the forked app — hence a `-P` the build forwards
// explicitly.
val qrImpl: String? = (project.findProperty("tenshu.qr.impl") as String?)?.takeIf { it.isNotBlank() }
val desktopMarketingVersion = "1.0.1"

// Windows MSIX (desktop-windows-port.md §5.2, D1): `:control-desktop:packageMsix`; the Store build is
// docs/how-to/windows-store-submission.md. ⚠ The identity name is provisional until the Partner Center
// reservation, and must equal FirstPartyDesktopPeers.WINDOWS_CONTROL_PACKAGES and
// ControlServerCheck.WINDOWS_CONTROL_PACKAGE, or no app can reach this Control (packageMsix checks the pins).
extra["tenshu.msix.identityName"] = "Tenshu.Control"
// ⚠⚠ false until the name above IS Partner Center's Package/Identity/Name; a Store build refuses until then.
extra["tenshu.msix.identityReserved"] = false
extra["tenshu.msix.identityPins"] = listOf(
    "control-shared-logic/src/desktopMain/kotlin/app/tenshu/control/data/FirstPartyDesktopPeers.kt",
    "ui-core/src/desktopMain/kotlin/app/tenshu/ui/ipc/ControlServerCheck.kt",
)
extra["tenshu.msix.displayName"] = "Tenshu Control"
extra["tenshu.msix.appId"] = "Control"
extra["tenshu.msix.executable"] = "TenshuControl"
extra["tenshu.msix.version"] = desktopMarketingVersion
// The native Windows tray agent ships inside Control's package (desktop-windows-port.md §3.9, D5).
extra["tenshu.msix.trayAgentProject"] = "windows/TrayAgent/TrayAgent.csproj"
// ⚠ The agent is framework-dependent on the Windows App SDK; this must match the version its csproj pins
// (Microsoft.WindowsAppSDK 1.8.260921001 → runtime 8000.994.2142.0, from WindowsAppSDK-VersionInfo.h).
extra["tenshu.msix.windowsAppRuntime"] = "Microsoft.WindowsAppRuntime.1.8|8000.994.2142.0"
apply(from = rootProject.file("gradle/desktop-msix.gradle.kts"))

// Flags the desktop IPC peer-credential gate needs (desktop-ipc-client-identity.md §6).
// `PeerAuditToken` recovers the peer's file descriptor by reflecting into
// `sun.nio.ch.SocketChannelImpl` — the JDK exposes no public route — and that is a hard
// `InaccessibleObjectException` without the opens, not a degraded path. Declared once and
// applied at BOTH JVM sites (`run` and the packaged distribution) so they cannot drift.
val PEER_GATE_JVM_ARGS = listOf(
    "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens", "java.base/java.io=ALL-UNNAMED",
    "--enable-native-access=ALL-UNNAMED",
)

tasks.withType<JavaExec> {
    if (name == "run" || name == "jvmRun") {
        mainClass.set("app.tenshu.control.MainKt")
        // ⚠⚠ The dev-run flags go in `doFirst`, not here. Compose's `configureRunTask` calls
        // `setJvmArgs(app.jvmArgs)` AFTER this block and REPLACES the list, so anything set at
        // configuration time never reached the JVM — developer mode was always off on a dev run
        // (tech-debt.md "`:control-desktop:run` drops its own JVM flags"). `doFirst` runs after
        // Compose has configured the task. ⚠ Never move them onto
        // `compose.desktop.application.jvmArgs`: that also feeds the packaged app, which must never
        // carry `tenshu.release=false`. (`PEER_GATE_JVM_ARGS` and `-Dtenshu.qr.impl` already reach
        // both JVMs through that list, below.)
        val devArgs = buildList {
            // Dev run is NOT a release build: the dev-seed entitlement minter stays on so storage
            // works without a commerce service, and developer mode (A16) admits unsigned or
            // unpackaged local peers — a dev Drive, or the standalone Windows tray agent.
            // Override with -Dtenshu.release=true to exercise the release posture.
            add("-Dtenshu.release=false")
            // A Skiko UnsatisfiedLinkError fix on macOS only: METAL is not a Windows/Linux renderer.
            if (System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)) {
                add("-Dskiko.renderApi=METAL")
            }
        }
        doFirst { (this as JavaExec).jvmArgs(devArgs) }
    }
}

// jpackage does NOT follow `jvmToolchain` — it embeds whatever `javaHome` points
// at, defaulting to the Gradle *daemon* JVM. Left implicit, a 21 daemon silently
// bundles a 21 runtime and every FFM call fails on the shipped app while the build
// and all tests look green. So point it at the same toolchain explicitly.
//
// ⚠⚠ Resolve it TOLERANTLY, not eagerly. `.get()` here runs at *configuration* time, and
// Gradle configures every project in the build — so on a machine without JDK 25 an eager
// resolve fails `:core-common:desktopTest`, a module with nothing to do with desktop
// packaging, with an error naming this file. An environment with JDK 21 only and no foojay
// egress (the cloud sandbox, `docs/how-to/claude-web-environment.md`) could then run no
// task in the repo at all.
//
// ⚠ Degrading to `null` does NOT re-open the silent-21-runtime bug the paragraph above is
// about: `jvmToolchain(FFM_JDK)` already gates this module's *compile* tasks on the same
// JDK 25, and nothing can be packaged without first being compiled. So a missing 25 still
// fails any packaging build — at `compileKotlinJvm`, naming the toolchain, instead of taking
// the whole build's configuration down with it.
//
// ⚠ `-Ptenshu.jpackageJavaHome=<a full JDK 25>` (or `TENSHU_JPACKAGE_JAVA_HOME`) overrides the toolchain
// pick, for a machine whose only JDK 25 is Android Studio's bundled JBR: toolchain detection finds it, and
// the JBR ships WITHOUT `jpackage`, so `checkRuntime` fails (desktop-windows-port.md §5.2). Opt-in, so CI
// and the macOS release builds keep embedding exactly what they did.
val ffmRuntime: String? =
    ((project.findProperty("tenshu.jpackageJavaHome") as String?) ?: System.getenv("TENSHU_JPACKAGE_JAVA_HOME"))
        ?.takeIf { it.isNotBlank() }
        ?: runCatching {
            javaToolchains
                .launcherFor { languageVersion.set(JavaLanguageVersion.of(FFM_JDK)) }
                .get().metadata.installationPath.asFile.absolutePath
        }.getOrNull()

compose.desktop {
    application {
        qrImpl?.let { jvmArgs += "-Dtenshu.qr.impl=$it" }
        // The packaged twin of the `run` args above — see PEER_GATE_JVM_ARGS.
        jvmArgs += PEER_GATE_JVM_ARGS
        ffmRuntime?.let { javaHome = it }
        mainClass = "app.tenshu.control.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Pkg)
            packageName = "TenshuControl"
            packageVersion = desktopMarketingVersion
            // Windows: the launcher .exe's icon (ADR 0026 §6b; scripts/windows-app-icons.py).
            windows { iconFile.set(project.file("icons/windows/control.ico")) }
            macOS {
                // Reuse Control's iOS bundle ID so this macOS target can share one
                // App Store Connect record and remain eligible for Universal Purchase.
                // Desktop appIds stay platform-neutral; ControlDataBridge maps this ID
                // when launching through LaunchServices.
                bundleID = "app.tenshu.control.ios"
                // LSMinimumSystemVersion; jpackage's default (10.13) is refused for arm64-only.
                minimumSystemVersion = project.property("tenshu.macosDeploymentTarget").toString()
                // ⚠ macOS .icns must follow Apple's icon grid: the body is inset to
                // ~824/1024 (~100px transparent margin) with a continuous squircle —
                // NOT the full-bleed iOS/PWA tile, which makes the Dock icon ~25%
                // oversized vs every other app. Regen recipe: TrayAgent-style, see
                // docs/adr/0026-app-icon-system.md §6.
                iconFile.set(project.file("icons/control.icns"))
                // Menu-bar-only app (OneDrive/Dropbox model): LSUIElement hides the
                // Dock icon, so the casual Dock → "Quit" can no longer kill the
                // background daemon + sync. The window still opens (tray "Open
                // Control", Spotlight, Launchpad); the only deliberate full-quit is
                // the tray's Quit button. The hidden login-autostarted daemon also
                // gets no Dock icon — correct for a background agent.
                infoPlist {
                    // LSUIElement: menu-bar-only app (see above).
                    // NSCameraUsageDescription: required for the desktop QR-scan
                    // helper (Contents/Helpers/qr-scan-helper) — without it macOS
                    // kills the process on camera access (desktop-qr-scanning.md §8).
                    // CFBundleDisplayName: macOS titles the Touch ID / password sheet with it,
                    // because the check runs in-process (MacLocalAuthentication) — without it the
                    // sheet reads "TenshuControl is trying to …", the packageName.
                    extraKeysRawXml = """
                        <key>CFBundleDisplayName</key>
                        <string>Tenshu Control</string>
                        <key>LSApplicationCategoryType</key>
                        <string>public.app-category.utilities</string>
                        <key>LSUIElement</key>
                        <true/>
                        <key>NSCameraUsageDescription</key>
                        <string>Tenshu uses the camera to scan pairing QR codes.</string>
                    """.trimIndent()
                }
            }
        }
    }
}

// --- macOS App Group code-signing scaffold ---
// docs/proposals/desktop-macos-integration.md §2.1 / §3: the daemon must bind
// its sockets inside the App Group container so the sandboxed Drive File
// Provider extension can reach them. Compose's built-in signing doesn't cover
// the `com.apple.security.application-groups` entitlement, so we re-sign the
// produced .app with an entitlements plist injected from
// macos/entitlements/control.entitlements.
//
// Requires an Apple Developer Team ID + a codesigning identity. The task
// self-skips with a clear log line when unconfigured, so it never breaks a
// normal build. Run it explicitly with signing configured:
//
//   ./gradlew :control-desktop:signControlAppForAppGroup \
//       -Ptenshu.teamId=ABCDE12345 \
//       -Ptenshu.signingIdentity="Developer ID Application: Acme (ABCDE12345)"
//
// Then confirm the entitlement landed:  codesign -d --entitlements - <app>
tasks.register("signControlAppForAppGroup") {
    group = "macos"
    description =
        "Re-signs Control.app with the App Group entitlement (desktop-macos-integration.md §3 task 3)."
    dependsOn("createReleaseDistributable")

    val teamId = resolveTeamId(project)
    val identity = resolveSigningIdentity(project)
    val binariesDir = layout.buildDirectory.dir("compose/binaries")
    val entitlementsTemplate = project.file("macos/entitlements/control.entitlements")
    val generatedEntitlements = layout.buildDirectory.file("tmp/control.entitlements")

    doLast {
        if (teamId.isNullOrBlank() || identity.isNullOrBlank()) {
            logger.warn(
                "signControlAppForAppGroup: SKIPPED — set tenshu.teamId + tenshu.signingIdentity via " +
                    "local.properties, -P, or TENSHU_TEAM_ID / TENSHU_SIGNING_IDENTITY. " +
                    "The .app was built but not re-signed with the App Group entitlement."
            )
            return@doLast
        }

        val app = binariesDir.get().asFile.walkTopDown()
            .firstOrNull { it.name == "TenshuControl.app" && it.isDirectory }
            ?: error("TenshuControl.app not found under ${binariesDir.get().asFile} — did createReleaseDistributable run?")

        val out = generatedEntitlements.get().asFile
        out.parentFile.mkdirs()
        out.writeText(entitlementsTemplate.readText().replace("@TEAM_ID@", teamId))

        logger.lifecycle("Code-signing ${app.absolutePath} with App Group $teamId.app.tenshu.shared")
        val exit = ProcessBuilder(
            "codesign", "--force", "--deep", "--options", "runtime",
            "--entitlements", out.absolutePath,
            "--sign", identity,
            app.absolutePath
        ).inheritIO().start().waitFor()
        if (exit != 0) error("codesign failed with exit code $exit")
    }
}

// --- macOS SMAppService login item: build + embed into Control.app ----------------
//
// MAS builds cannot write ~/Library/LaunchAgents or invoke launchctl. This small
// sandboxed app is registered by the JVM through SMAppService.loginItem(...).
// Unsandboxed builds still use LaunchAgentManager; the bundled helper is simply
// inert there and keeps the fallback path intact.
tasks.register("buildLoginItem") {
    group = "macos"
    description = "Builds the sandbox-compatible SMAppService login item."
    val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    val source = project.file("macos/LoginItem/main.swift")
    val infoPlist = project.file("macos/LoginItem/Info.plist")
    val masSandbox = project.findProperty("tenshu.masSandbox")?.toString()?.toBoolean() == true
    val profile = (project.findProperty("tenshu.loginItemProvisioningProfile") as String?)
        ?.let { path ->
            File(path).let { if (it.isAbsolute) it else rootProject.file(path) }
        }
        ?: rootProject.file("build/macos-profiles/TenshuControl-LoginItem-MacAppStore.provisionprofile")
    val app = layout.buildDirectory.dir("macos/login-item/TenshuControlLoginItem.app")
    val minimumMacos = project.property("tenshu.macosDeploymentTarget").toString()
    val swiftTarget = "arm64-apple-macos${project.property("tenshu.macosDeploymentTarget")}"
    inputs.property("minimumMacos", minimumMacos)
    onlyIf { isMac }
    inputs.file(source)
    inputs.file(infoPlist)
    inputs.property("masSandbox", masSandbox)
    if (profile.isFile) inputs.file(profile)
    outputs.dir(app)
    doLast {
        val bundle = app.get().asFile
        val executable = bundle.resolve("Contents/MacOS/TenshuControlLoginItem")
        executable.parentFile.mkdirs()
        infoPlist.copyTo(bundle.resolve("Contents/Info.plist"), overwrite = true)
        ProcessBuilder(
            "/usr/libexec/PlistBuddy", "-c", "Set :LSMinimumSystemVersion $minimumMacos",
            bundle.resolve("Contents/Info.plist").absolutePath,
        ).inheritIO().start().waitFor().let { if (it != 0) error("PlistBuddy failed on the login item ($it)") }
        if (masSandbox) {
            if (!profile.isFile) {
                error("buildLoginItem: -Ptenshu.masSandbox=true requires ${profile.absolutePath}")
            }
            profile.copyTo(bundle.resolve("Contents/embedded.provisionprofile"), overwrite = true)
        } else {
            bundle.resolve("Contents/embedded.provisionprofile").delete()
        }
        val exit = ProcessBuilder(
            "xcrun", "--sdk", "macosx", "swiftc",
            "-O",
            "-target", swiftTarget,
            "-framework", "AppKit",
            "-framework", "ServiceManagement",
            source.absolutePath,
            "-o", executable.absolutePath,
        ).inheritIO().start().waitFor()
        if (exit != 0) error("swiftc failed building the login item (exit $exit)")
        executable.setExecutable(true, false)
        logger.lifecycle("Built SMAppService login item → ${bundle.absolutePath}")
    }
}

tasks.register("embedLoginItem") {
    group = "macos"
    description = "Embeds the SMAppService login item into TenshuControl.app."
    dependsOn("buildLoginItem", "createDistributable")
    val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    val loginItem = layout.buildDirectory.dir("macos/login-item/TenshuControlLoginItem.app")
    val appDir = layout.buildDirectory.dir("compose/binaries/main/app")
    onlyIf { isMac }
    doLast {
        val app = appDir.get().asFile.listFiles()
            ?.firstOrNull { it.name == "TenshuControl.app" && it.isDirectory }
            ?: error("TenshuControl.app not found in ${appDir.get().asFile}")
        val destination = app.resolve("Contents/Library/LoginItems/TenshuControlLoginItem.app")
        destination.deleteRecursively()
        destination.parentFile.mkdirs()
        loginItem.get().asFile.copyRecursively(destination, overwrite = true)
        logger.lifecycle("Embedded SMAppService login item → ${destination.absolutePath}")
    }
}

// --- macOS tray agent: build (swiftc) + embed into Control.app ---
// docs/proposals/desktop-macos-integration.md §6a. The Swift menu-bar tray
// (macos/TrayAgent) ships *inside* the bundle at Contents/Helpers/tray-agent and
// is autostarted as its own LaunchAgent (LaunchAgentManager.forTray) — independent
// of the daemon so it survives a Quit, which is what makes the tray's "Open
// Control" cold-launch meaningful. macOS-only (swiftc); both tasks self-skip
// elsewhere so a Linux/CI build never trips on them.
//
// NB: every value the doLast closures touch is declared *locally* in the register
// block (not at script scope) — capturing a script-level `val` drags in the
// non-serializable build-script object and breaks the configuration cache.
tasks.register("buildTrayAgent") {
    group = "macos"
    description = "Compiles the Swift menu-bar tray agent (macos/TrayAgent) with swiftc."
    val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    val sources = listOf(
        project.file("macos/TrayAgent/main.swift"),
        project.file("macos/TrayAgent/BridgeClient.swift"),
        project.file("macos/TrayAgent/PendingRequestNotifier.swift"),
        project.file("macos/TrayAgent/PairingApprovalNotifier.swift"),
        // Hands calendar reminders to macOS as UNCalendarNotificationTriggers.
        project.file("macos/TrayAgent/ReminderNotifier.swift"),
        // Publishes liveness + menu-bar placement for Control's tray guard
        // (desktop-tray-liveness.md §4.1).
        project.file("macos/TrayAgent/TrayHeartbeat.swift"),
        // The "Update Control to keep syncing" notification on a schema fence.
        project.file("macos/TrayAgent/SchemaFenceNotifier.swift"),
    )
    val out = layout.buildDirectory.file("macos/tray-agent")
    val swiftTarget = "arm64-apple-macos${project.property("tenshu.macosDeploymentTarget")}"
    inputs.property("swiftTarget", swiftTarget)
    onlyIf { isMac }
    inputs.files(sources)
    outputs.file(out)
    doLast {
        val outFile = out.get().asFile
        outFile.parentFile.mkdirs()
        val exit = ProcessBuilder(
            listOf("swiftc", "-O", "-target", swiftTarget, "-o", outFile.absolutePath) +
                sources.map { it.absolutePath }
        ).inheritIO().start().waitFor()
        if (exit != 0) error("swiftc failed building the tray agent (exit $exit)")
        logger.lifecycle("Built tray agent → ${outFile.absolutePath}")
    }
}

tasks.register("embedTrayAgent") {
    group = "macos"
    description =
        "Embeds the compiled tray agent into TenshuControl.app/Contents/Helpers (desktop-macos-integration.md §6a)."
    dependsOn("buildTrayAgent", "createDistributable")
    val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    val trayBin = layout.buildDirectory.file("macos/tray-agent")
    val binariesDir = layout.buildDirectory.dir("compose/binaries")
    onlyIf { isMac }
    doLast {
        val app = binariesDir.get().asFile.walkTopDown()
            .firstOrNull { it.name == "TenshuControl.app" && it.isDirectory }
            ?: error("TenshuControl.app not found under ${binariesDir.get().asFile} — did createDistributable run?")
        val helpers = File(app, "Contents/Helpers").apply { mkdirs() }
        val dest = File(helpers, "tray-agent")
        trayBin.get().asFile.copyTo(dest, overwrite = true)
        dest.setExecutable(true, false)
        logger.lifecycle("Embedded tray agent → ${dest.absolutePath}")
    }
}

// --- macOS QR-scan helper: embed into Control.app (desktop-qr-scanning.md §8) ---
// Mirrors embedTrayAgent. The helper is compiled once by :ui-qr-scanner (swiftc) for
// its desktop-jar resource; here we drop the same binary into
// Contents/Helpers/qr-scan-helper so the signed in-bundle copy is what
// HelperBinary.resolve() prefers at runtime (covered by the app's signature /
// --deep re-sign, unlike a temp-extracted unsigned binary). Run before signing.
// Same config-cache rule: only register-block locals in doLast.
tasks.register("embedQrScanHelper") {
    group = "macos"
    description =
        "Embeds the compiled qr-scan-helper into TenshuControl.app/Contents/Helpers (desktop-qr-scanning.md §8)."
    dependsOn(":ui-qr-scanner:compileQrScanHelper", "createDistributable")
    val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    val qrHelperBin = project(":ui-qr-scanner").layout.buildDirectory.file("qr-helper/qr-scan-helper").get().asFile
    val binariesDir = layout.buildDirectory.dir("compose/binaries")
    onlyIf { isMac }
    doLast {
        val app = binariesDir.get().asFile.walkTopDown()
            .firstOrNull { it.name == "TenshuControl.app" && it.isDirectory }
            ?: error("TenshuControl.app not found under ${binariesDir.get().asFile} — did createDistributable run?")
        val helpers = File(app, "Contents/Helpers").apply { mkdirs() }
        val dest = File(helpers, "qr-scan-helper")
        qrHelperBin.copyTo(dest, overwrite = true)
        dest.setExecutable(true, false)
        logger.lifecycle("Embedded qr-scan-helper → ${dest.absolutePath}")
    }
}

// --- macOS image-decode helper: embed into Control.app ---------------------
// Mirrors embedQrScanHelper. Control is the *proxy* on desktop, so it runs
// `JvmGetPreviewImplementation` — which needs this helper to thumbnail a HEIC at
// upload (`javax.imageio` has no HEIF plugin; drive-content-preview.md §3.1).
// The binary is compiled once by :macos-helpers (swiftc); the in-bundle copy is
// what HelperBinary.resolve() prefers at runtime, and unlike a temp-extracted
// unsigned binary it survives the hardened runtime. Run before signing.
tasks.register("embedImageDecodeHelper") {
    group = "macos"
    description =
        "Embeds the compiled image-decode-helper into TenshuControl.app/Contents/Helpers."
    dependsOn(":macos-helpers:compileImageDecodeHelper", "createDistributable")
    val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    val helperBin = project(":macos-helpers").layout.buildDirectory
        .file("image-helper/image-decode-helper").get().asFile
    val binariesDir = layout.buildDirectory.dir("compose/binaries")
    onlyIf { isMac }
    doLast {
        val app = binariesDir.get().asFile.walkTopDown()
            .firstOrNull { it.name == "TenshuControl.app" && it.isDirectory }
            ?: error("TenshuControl.app not found under ${binariesDir.get().asFile} — did createDistributable run?")
        val helpers = File(app, "Contents/Helpers").apply { mkdirs() }
        val dest = File(helpers, "image-decode-helper")
        helperBin.copyTo(dest, overwrite = true)
        dest.setExecutable(true, false)
        logger.lifecycle("Embedded image-decode-helper → ${dest.absolutePath}")
    }
}

// --- macOS app-lock helper: swiftc + embed into Control.app --------------
// ⚠⚠ **This is the same `main.swift` as the signed keychain helper below, built a second,
// entitlement-free way — and that is the whole point of it.**
//
// The helper below is Xcode-built and *signing-gated* because the **wrap-key** verbs
// (`enroll`/`unwrap`) touch the data-protection keychain, whose `keychain-access-groups`
// entitlement is restricted: without an embedded provisioning profile AMFI SIGKILLs the process.
// The two verbs that do **not** need that gate — `is-available` and `authenticate` are pure
// `LAContext.evaluatePolicy(.deviceOwnerAuthentication)` calls that touch no keychain item — are
// what Control's soft app-lock and every re-auth gate (`control-keep-device-unlocked.md` §6.1) run
// on, so they must not inherit it. A plain `swiftc` + ad-hoc-signed binary runs those two verbs
// fine, with no Apple account, no team id and no GUI-minted profile.
//
// ⚠⚠ It is deliberately named `applock-helper`, NOT `keychain-helper`. `bundledKeychainHelperFor`
// (the biometric path) accepts a bare `keychain-helper` because Developer-ID inside-out signing
// produces exactly that — a *full-capability* bare binary. If this entitlement-free build claimed
// that name, the biometric delegate would resolve it, `is-available` would answer "yes" (it is only
// an LAContext probe!), and the UI would offer an enrolment that always fails. The distinct name is
// what keeps "can present a prompt" and "can hold a wrap key" from collapsing into one answer.
//
// Same config-cache rule as the tray tasks: only register-block locals in doLast.
val appLockHelperSwift = file("macos/KeychainHelper/main.swift")
val appLockHelperInfoPlist = file("macos/KeychainHelper/AppLockHelper-Info.plist")
val appLockHelperOutDir = layout.buildDirectory.dir("macos/applock").get().asFile
val appLockHelperBin = File(appLockHelperOutDir, "applock-helper")

tasks.register<Exec>("compileAppLockHelper") {
    group = "macos"
    description =
        "Compiles the entitlement-free app-lock helper (KeychainHelper/main.swift) with swiftc, ad-hoc signed."
    onlyIf { System.getProperty("os.name").startsWith("Mac", ignoreCase = true) }
    inputs.file(appLockHelperSwift)
    inputs.file(appLockHelperInfoPlist)
    inputs.property("macosDeploymentTarget", project.property("tenshu.macosDeploymentTarget").toString())
    outputs.file(appLockHelperBin)
    // ⚠ `codesign -s -` (ad-hoc) and NO `--entitlements`: claiming the restricted
    // keychain-access-groups entitlement without a profile is the SIGKILL this build exists to
    // avoid. Ad-hoc is enough because the two verbs it serves need no entitlement at all.
    // The `-sectcreate __TEXT __info_plist` link gives the bare binary a CFBundleName, which is what
    // macOS titles the auth sheet with — "Tenshu Control", not the file name. It renames nothing,
    // so the load-bearing file name above is untouched.
    commandLine(
        "sh", "-c",
        "mkdir -p '${appLockHelperOutDir.absolutePath}' && " +
            "swiftc -O -target arm64-apple-macos${project.property("tenshu.macosDeploymentTarget")} " +
            "'${appLockHelperSwift.absolutePath}' -o '${appLockHelperBin.absolutePath}' " +
            "-Xlinker -sectcreate -Xlinker __TEXT -Xlinker __info_plist " +
            "-Xlinker '${appLockHelperInfoPlist.absolutePath}' && " +
            "codesign -f -s - '${appLockHelperBin.absolutePath}'",
    )
}

// Embedded by DEFAULT — unlike `embedKeychainHelper`, this one is not in
// `tenshu.optInEmbedTasks`, because it cannot fail for want of a signing identity.
tasks.register("embedAppLockHelper") {
    group = "macos"
    description =
        "Embeds the entitlement-free app-lock helper into TenshuControl.app/Contents/Helpers (RA-5)."
    dependsOn("compileAppLockHelper", "createDistributable")
    val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    val helperBin = layout.buildDirectory.file("macos/applock/applock-helper").get().asFile
    val binariesDir = layout.buildDirectory.dir("compose/binaries")
    onlyIf { isMac }
    doLast {
        val app = binariesDir.get().asFile.walkTopDown()
            .firstOrNull { it.name == "TenshuControl.app" && it.isDirectory }
            ?: error("TenshuControl.app not found under ${binariesDir.get().asFile} — did createDistributable run?")
        val helpers = File(app, "Contents/Helpers").apply { mkdirs() }
        val dest = File(helpers, "applock-helper")
        helperBin.copyTo(dest, overwrite = true)
        dest.setExecutable(true, false)
        logger.lifecycle("Embedded applock-helper → ${dest.absolutePath}")
    }
}

// --- macOS keychain helper: build (xcodebuild) + embed into Control.app ---
// docs/proposals/desktop-masterkeystore.md §4. The Touch ID /
// login-password unlock helper (macos/KeychainHelper) ships inside the bundle at
// Contents/Helpers/keychain-helper.app, beside the tray agent, so
// DesktopBiometricUnlockDelegate.bundledKeychainHelperFor resolves it bundle-
// relative with NO env var — biometric then works in every launch context.
//
// Unlike the tray, the helper needs an embedded *provisioning profile* (its
// data-protection-keychain entitlement is restricted, else AMFI kills it), so it
// is built with xcodebuild as a signed .app — NOT swiftc. On a free team the
// profile must be minted once in the Xcode GUI first (xcodebuild alone errors
// "No Account for Team"); thereafter this signs offline against it. We embed the
// whole signed .app (it carries the profile) and do NOT re-sign it (--deep would
// strip the entitlement). Production = Developer ID + notarization; macos-signing.md §3b.
//
// Same config-cache rule as the tray tasks: only register-block locals in doLast.
tasks.register("buildKeychainHelper") {
    group = "macos"
    description = "Builds + signs the Swift keychain helper (macos/KeychainHelper) as a .app via xcodebuild."
    val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    val team = resolveTeamId(project)
    val xcodeproj = project.file("macos/KeychainHelper/KeychainHelper.xcodeproj")
    val symroot = layout.buildDirectory.dir("macos/keychainhelper")
    onlyIf { isMac }
    doLast {
        if (team.isNullOrBlank()) {
            logger.warn(
                "buildKeychainHelper: skipped — set the team id via local.properties (tenshu.teamId=…), " +
                    "-Ptenshu.teamId, or TENSHU_TEAM_ID (and mint the profile once in the Xcode GUI, " +
                    "macos-signing.md §3b). Biometric stays dormant in the build."
            )
            return@doLast
        }
        val out = symroot.get().asFile.apply { mkdirs() }
        val exit = ProcessBuilder(
            "xcodebuild",
            "-project", xcodeproj.absolutePath,
            "-target", "keychain-helper",
            "-configuration", "Release",
            "DEVELOPMENT_TEAM=$team",
            "CODE_SIGN_STYLE=Automatic",
            "SYMROOT=${out.absolutePath}",
            "build",
        ).inheritIO().start().waitFor()
        if (exit != 0) {
            error("xcodebuild failed building the keychain helper (exit $exit) — mint the profile in the Xcode GUI first (macos-signing.md §3b)")
        }
        logger.lifecycle("Built keychain helper → ${out.resolve("Release/keychain-helper.app")}")
    }
}

tasks.register("embedKeychainHelper") {
    group = "macos"
    description =
        "Embeds the signed keychain-helper.app into TenshuControl.app/Contents/Helpers (desktop-masterkeystore.md Phase 2 Slice 2)."
    dependsOn("buildKeychainHelper", "createDistributable")
    val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    val helperApp = layout.buildDirectory.dir("macos/keychainhelper/Release/keychain-helper.app")
    val binariesDir = layout.buildDirectory.dir("compose/binaries")
    onlyIf { isMac }
    doLast {
        val src = helperApp.get().asFile
        if (!src.isDirectory) {
            logger.warn("embedKeychainHelper: skipped — $src not found (buildKeychainHelper skipped/failed). Biometric stays dormant in the build.")
            return@doLast
        }
        val app = binariesDir.get().asFile.walkTopDown()
            .firstOrNull { it.name == "TenshuControl.app" && it.isDirectory }
            ?: error("TenshuControl.app not found under ${binariesDir.get().asFile} — did createDistributable run?")
        // Copy the whole signed .app (it carries the provisioning profile); never
        // re-sign it here — that would strip the data-protection-keychain entitlement.
        // ⚠ The shared `signDesktopApp` honours the same rule: it skips profile-carrying
        // bundles and asserts the entitlements survived
        // (gradle/desktop-app-signing.gradle.kts; macos-signing.md §3b).
        val dest = File(app, "Contents/Helpers/keychain-helper.app")
        dest.deleteRecursively()
        src.copyRecursively(dest, overwrite = true)
        File(dest, "Contents/MacOS/keychain-helper").setExecutable(true, false)
        logger.lifecycle("Embedded keychain helper → ${dest.absolutePath}")
    }
}

// --- umbrella: build the complete, installable Control.app ---
// The embed-then-sign-then-verify chain lives in the shared `packageDesktopApp`
// (gradle/desktop-app-signing.gradle.kts), so every desktop app gets it — an app without it
// ships ad-hoc signed and unattestable.
//
// This task is what remains that is genuinely Control-specific: the menu-bar tray (the cat),
// which no other app has, and the opt-in Touch ID keychain helper.
//
// ⚠ The keychain helper is opt-in (`-Ptenshu.withKeychainHelper`) because it is signing-gated
// (needs a provisioning profile minted in the Xcode GUI first, macos-signing.md §3b) and
// hard-fails otherwise — so it must never be a default dependency or the umbrella breaks on an
// unsigned/free-team build. That is what `tenshu.optInEmbedTasks` keeps out of the shared task's
// automatic `embed*` wiring.
extra["tenshu.optInEmbedTasks"] = listOf("embedKeychainHelper")

if (project.hasProperty("tenshu.withKeychainHelper")) {
    tasks.named("packageDesktopApp") { dependsOn("embedKeychainHelper") }
}

tasks.register("packageControlApp") {
    group = "macos"
    description =
        "Builds the complete TenshuControl.app with the menu-bar tray + the app-lock helper embedded, " +
            "ready to copy to /Applications. Add -Ptenshu.withKeychainHelper to also embed the Touch ID " +
            "WRAP-KEY helper — that one requires signing (macos-signing.md §3b); the app-lock helper does not."
    // Everything shared — embeds, signing, the Team-ID verdict, the install line — comes from here.
    dependsOn("packageDesktopApp")

    val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    val appDir = layout.buildDirectory.dir("compose/binaries/main/app")
    onlyIf { isMac }
    doLast {
        // Scoped to `main/app` like the shared task. A `walkTopDown()` over the whole
        // `compose/binaries` root would take the first match it found, so which bundle it checked
        // would depend on what else happens to be left in `build/`.
        val app = appDir.get().asFile.listFiles()
            ?.firstOrNull { it.name == "TenshuControl.app" && it.isDirectory }
            ?: error("TenshuControl.app not found in ${appDir.get().asFile} — did createDistributable run?")
        val tray = File(app, "Contents/Helpers/tray-agent")
        if (!tray.exists()) error("tray-agent missing from ${app.absolutePath} — embedTrayAgent did not run (macOS only).")
        val qrHelper = File(app, "Contents/Helpers/qr-scan-helper")
        if (!qrHelper.exists()) error("qr-scan-helper missing from ${app.absolutePath} — embedQrScanHelper did not run (macOS only).")
    }
}
