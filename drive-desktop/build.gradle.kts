import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.IOException
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

// Shared macOS code-signing (desktop-ipc-client-identity.md P0): registers
// `signDesktopApp`, which signs this module's .app with the configured Apple
// identity so the desktop IPC peer-credential gate can attest it. Self-skips when
// unconfigured; see the script for why hardened runtime is opt-in.
//
// ⚠ Set BEFORE the apply: the File Provider .appex is sandboxed and the host is not, so it
// must be signed with its own entitlements rather than inherit the host's.
val masSandboxNestedEntitlements =
    if (providers.gradleProperty("tenshu.masSandbox").orNull?.toBoolean() == true) {
        mapOf(
            // MAS Drive starts these helpers directly, so they inherit the host sandbox.
            "tenshu-domain" to "macos/entitlements/child-inherit.entitlements",
            "image-decode-helper" to "macos/entitlements/child-inherit.entitlements",
            // Embedded from the `:macos-helpers` jar by `embedJarHelpers`.
            "map-snapshot-helper" to "macos/entitlements/child-inherit.entitlements",
            "qr-scan-helper" to "macos/entitlements/child-inherit.entitlements",
            "appgroup-claim" to "macos/entitlements/child-inherit.entitlements",
            "jspawnhelper" to "macos/entitlements/child-inherit.entitlements",
        )
    } else {
        emptyMap()
    }
extra["tenshu.nestedEntitlements"] =
    mapOf("TenshuFileProvider.appex" to "macos/entitlements/drive-fileprovider.entitlements") +
        masSandboxNestedEntitlements
val masSandboxNestedProfiles =
    if (providers.gradleProperty("tenshu.masSandbox").orNull?.toBoolean() == true) {
        mapOf(
            "TenshuFileProvider.appex" to
                (providers.gradleProperty("tenshu.fileProviderProvisioningProfile").orNull
                    ?: "build/macos-profiles/TenshuDrive-FileProvider-MacAppStore.provisionprofile"),
        )
    } else {
        emptyMap()
    }
extra["tenshu.nestedProfiles"] = masSandboxNestedProfiles
// Used only with -Ptenshu.masSandbox=true. The File Provider keeps its own
// nested entitlement template and provisioning profile above.
extra["tenshu.hostEntitlements"] = "macos/entitlements/drive-mas.entitlements"
apply(from = rootProject.file("gradle/desktop-app-signing.gradle.kts"))

/** JDK for this app: compiles it AND becomes its jpackage-bundled runtime. */
val FFM_JDK = 25

// ── Toolchain: bundle a final-FFM runtime (desktop-qr-scanning.md §13 P0) ──────
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
kotlin {
    jvmToolchain(FFM_JDK)
    jvm()
    // The window's own icon on Windows — title bar, Alt+Tab (TenshuWindowIcon; ADR 0026 §6b).
    sourceSets.named("jvmMain") { resources.srcDir("icons/windows/window") }

    sourceSets {
        getByName("jvmMain") {
            dependencies {
                implementation(project(":drive-shared-ui"))
                implementation(project(":ui-core"))
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
                implementation(libs.kotlinx.coroutines.test)
                implementation("org.slf4j:slf4j-simple:2.0.19")
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

// Windows MSIX (desktop-windows-port.md §5.2, D1): `:drive-desktop:packageMsix`. ⚠ The identity name is
// provisional until the Partner Center reservation, and must equal Drive's ClientBinding.WindowsPackage
// in BundledAppCatalogue, or Control's peer gate will not admit this package.
extra["tenshu.msix.identityName"] = "Tenshu.Drive"
extra["tenshu.msix.displayName"] = "Tenshu Drive"
extra["tenshu.msix.appId"] = "Drive"
extra["tenshu.msix.executable"] = "TenshuDrive"
extra["tenshu.msix.version"] = desktopMarketingVersion
apply(from = rootProject.file("gradle/desktop-msix.gradle.kts"))
// CFBundleVersion for the File Provider extension. The MAS archive script passes one UTC-minute
// number for host and extension alike (App Store Connect rejects a mismatch); a local build uses a
// fixed "1" so configuration never reads the clock (that would defeat the configuration cache).
val desktopBuildNumber =
    (project.findProperty("tenshu.buildNumber") as String?)?.takeIf { it.isNotBlank() } ?: "1"

// The client side of the desktop IPC gate (`ControlServerCheck` → `MacPeerAttestation`): FFM, and
// the two opens `PeerAuditToken` needs to reach a channel's file descriptor. ⚠⚠ Without the opens
// every attestation is `Unavailable` and a packaged app refuses the genuine Control; a dev run hides
// it (developer mode admits). Applied at BOTH JVM sites, `run` and the packaged app, as in
// control-desktop; `ControlServerCheckTest` checks every desktop app has them.
val PEER_GATE_JVM_ARGS = listOf(
    "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens", "java.base/java.io=ALL-UNNAMED",
    "--enable-native-access=ALL-UNNAMED",
)

tasks.withType<JavaExec> {
    if (name == "run" || name == "jvmRun") {
        mainClass.set("app.tenshu.drive.MainKt")
        // ⚠⚠ The dev-run flags go in `doFirst`, not here. Compose's `configureRunTask` calls
        // `setJvmArgs(app.jvmArgs)` AFTER this block and REPLACES the list, so anything set at
        // configuration time never reaches the JVM. `PEER_GATE_JVM_ARGS` and `-Dtenshu.qr.impl` reach
        // the dev run through `compose.desktop.application.jvmArgs`, below. ⚠ Never move these flags
        // onto that list: it also feeds the packaged app, which must never carry `tenshu.release=false`.
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

// jpackage does NOT follow `jvmToolchain` — it embeds whatever `javaHome` points
// at, defaulting to the Gradle *daemon* JVM. Left implicit, a 21 daemon silently
// bundles a 21 runtime and every FFM call fails on the shipped app while the build
// and all tests look green. So point it at the same toolchain explicitly.
//
// ⚠⚠ Resolve it TOLERANTLY, not eagerly. `.get()` here runs at *configuration* time, and
// Gradle configures every project in the build — so on a machine without JDK 25 this line
// used to fail `:core-common:desktopTest`, a module with nothing to do with desktop
// packaging, with an error naming this file. Not hypothetical: the cloud sandbox ships JDK
// 21 only and its egress proxy blocks foojay, so *no* task in the repo could run at all
// (`docs/how-to/claude-web-environment.md`).
//
// ⚠ Degrading to `null` does NOT re-open the silent-21-runtime bug the paragraph above is
// about: `jvmToolchain(FFM_JDK)` already gates this module's *compile* tasks on the same
// JDK 25, and nothing can be packaged without first being compiled. So a missing 25 still
// fails any packaging build — at `compileKotlinJvm`, naming the toolchain, instead of taking
// the whole build's configuration down with it.
// ⚠ `-Ptenshu.jpackageJavaHome` overrides the pick for a machine whose only JDK 25 is the JBR, which has
// no `jpackage` — see the same block in control-desktop/build.gradle.kts.
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
        // The packaged twin of the `run` args: see PEER_GATE_JVM_ARGS.
        jvmArgs += PEER_GATE_JVM_ARGS
        qrImpl?.let { jvmArgs += "-Dtenshu.qr.impl=$it" }
        ffmRuntime?.let { javaHome = it }
        mainClass = "app.tenshu.drive.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Pkg)
            packageName = "TenshuDrive"
            packageVersion = desktopMarketingVersion
            // Windows: the launcher .exe's icon (ADR 0026 §6b; scripts/windows-app-icons.py).
            windows { iconFile.set(project.file("icons/windows/drive.ico")) }
            macOS {
                // Reuse Drive's iOS bundle ID so this macOS target can share one
                // App Store Connect record and remain eligible for Universal Purchase.
                // Desktop appIds stay platform-neutral; ControlDataBridge maps this ID
                // when launching through LaunchServices.
                bundleID = "app.tenshu.drive.ios"
                // LSMinimumSystemVersion; jpackage's default (10.13) is refused for arm64-only.
                minimumSystemVersion = project.property("tenshu.macosDeploymentTarget").toString()
                // ⚠ See control-desktop: macOS .icns must use the inset (~824/1024)
                // squircle grid, not the full-bleed iOS/PWA tile, or the Dock icon
                // renders ~25% oversized vs other apps.
                iconFile.set(project.file("icons/drive.icns"))
                infoPlist {
                    // NSCameraUsageDescription: required by the QR-scan helper
                    // (Contents/Helpers/qr-scan-helper) that `RedeemInviteScreen`
                    // spawns to scan a contact invitation. A spawned helper's TCC
                    // identity is its *responsible process* — this app — so without
                    // the key macOS kills us on camera access, embedded helper or
                    // not (desktop-qr-scanning.md §8; Control carries the twin key).
                    extraKeysRawXml = """
                        <key>LSApplicationCategoryType</key>
                        <string>public.app-category.productivity</string>
                        <key>NSCameraUsageDescription</key>
                        <string>Tenshu uses the camera to scan invitation QR codes.</string>
                    """.trimIndent()
                }
            }
        }
    }
}

// ── macOS image-decode helper: embed into TenshuDrive.app ──────────────────────
//
// Mirrors :control-desktop's embedQrScanHelper. The helper is compiled once by
// :macos-helpers (swiftc) and rides in its jar as a classpath resource, from
// which `HelperBinary` can extract it at runtime — but an extracted *unsigned*
// binary is killed under the hardened runtime, so a packaged build needs the
// in-bundle copy that the app's own signature covers. Without it, HEIC previews
// silently fall back to "no preview" in a signed .app while working fine in dev.
tasks.register("embedImageDecodeHelper") {
    group = "macos"
    description =
        "Embeds the compiled image-decode-helper into TenshuDrive.app/Contents/Helpers."
    dependsOn(":macos-helpers:compileImageDecodeHelper", "createDistributable")
    val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    val helperBin = project(":macos-helpers").layout.buildDirectory
        .file("image-helper/image-decode-helper").get().asFile
    val binariesDir = layout.buildDirectory.dir("compose/binaries")
    onlyIf { isMac }
    doLast {
        val app = binariesDir.get().asFile.walkTopDown()
            .firstOrNull { it.name == "TenshuDrive.app" && it.isDirectory }
            ?: error("TenshuDrive.app not found under ${binariesDir.get().asFile} — did createDistributable run?")
        val dest = File(app, "Contents/Helpers").apply { mkdirs() }
            .resolve("image-decode-helper")
        helperBin.copyTo(dest, overwrite = true)
        dest.setExecutable(true, false)
        logger.lifecycle("Embedded image-decode-helper → ${dest.absolutePath}")
    }
}

// ── macOS QR-scan helper: embed into TenshuDrive.app ──────────────────────────
//
// Drive spawns `qr-scan-helper` from `RedeemInviteScreen` to scan a contact
// invitation, so it needs the same in-bundle copy Control has had since
// desktop-qr-scanning.md §8 — the classpath-extracted fallback is unsigned and
// dies under the hardened runtime. The binary is compiled once by :ui-qr-scanner
// (it belongs with the camera code); this only embeds it.
//
// The other half of making this work is `NSCameraUsageDescription` in the
// infoPlist above: a spawned helper's TCC identity is its responsible process,
// i.e. this app, so without the key macOS kills us on camera access no matter
// where the binary lives.
tasks.register("embedQrScanHelper") {
    group = "macos"
    description = "Embeds the compiled qr-scan-helper into TenshuDrive.app/Contents/Helpers."
    dependsOn(":ui-qr-scanner:compileQrScanHelper", "createDistributable")
    val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    val helperBin = project(":ui-qr-scanner").layout.buildDirectory
        .file("qr-helper/qr-scan-helper").get().asFile
    val binariesDir = layout.buildDirectory.dir("compose/binaries")
    onlyIf { isMac }
    doLast {
        val app = binariesDir.get().asFile.walkTopDown()
            .firstOrNull { it.name == "TenshuDrive.app" && it.isDirectory }
            ?: error("TenshuDrive.app not found under ${binariesDir.get().asFile} — did createDistributable run?")
        val dest = File(app, "Contents/Helpers").apply { mkdirs() }
            .resolve("qr-scan-helper")
        helperBin.copyTo(dest, overwrite = true)
        dest.setExecutable(true, false)
        logger.lifecycle("Embedded qr-scan-helper → ${dest.absolutePath}")
    }
}

// Both distributable *packaging* tasks pick the helpers up; each already depends
// on createDistributable, so ordering is embed-then-package.
tasks.matching { it.name == "packageDmg" || it.name == "packagePkg" }.configureEach {
    dependsOn("embedImageDecodeHelper", "embedQrScanHelper", "embedFileProviderExtension")
}

// ── macOS File Provider: build + embed into TenshuDrive.app ───────────────────
//
// desktop-macos-integration.md §5. Two artefacts, both from macos/FileProviderExtension:
//
//  - `Contents/PlugIns/TenshuFileProvider.appex` — the extension Finder talks to. Built by
//    xcodebuild from the XcodeGen spec (an extension bundle needs Info.plist processing and
//    the NSExtensionMain entry point), **unsigned**.
//  - `Contents/MacOS/tenshu-domain` — registers the NSFileProviderDomain, which is what
//    creates the Finder sidebar entry. swiftc, like the other helpers.
//
// Signing is `signDesktopApp`'s job (it runs after every `embed*`): the .appex gets
// drive-fileprovider.entitlements via `tenshu.nestedEntitlements` above, everything else the
// host's App Group plist.
//
// ⚠ Self-skips without a Team ID — the extension's Info.plist names the App Group, which is
// spelled with the team — so an unsigned `createDistributable` still works and simply has no
// Finder integration. `packageDesktopApp` refuses an unsigned bundle anyway.
tasks.register("embedFileProviderExtension") {
    group = "macos"
    description =
        "Builds the File Provider .appex + domain registrar and embeds both into TenshuDrive.app."
    dependsOn("createDistributable")
    val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    val teamId = (project.findProperty("tenshu.teamId") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("TENSHU_TEAM_ID")?.takeIf { it.isNotBlank() }
        ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
            Properties().apply { f.inputStream().use { load(it) } }.getProperty("tenshu.teamId")
        }?.takeIf { it.isNotBlank() }
    val sourceDir = project.file("macos/FileProviderExtension")
    val outDir = layout.buildDirectory.dir("macos/fileprovider")
    val binariesDir = layout.buildDirectory.dir("compose/binaries/main/app")
    // ⚠ Copied into task-local vals: `doLast` reading the script-level ones drags the script
    // object into the configuration cache, which refuses it and fails the build.
    val marketingVersion = desktopMarketingVersion
    val swiftTarget = "arm64-apple-macos${project.property("tenshu.macosDeploymentTarget")}"
    inputs.property("swiftTarget", swiftTarget)
    val buildNumber = desktopBuildNumber
    inputs.property("marketingVersion", marketingVersion)
    inputs.property("buildNumber", buildNumber)
    inputs.dir(sourceDir).withPropertyName("fileProviderSources")
    onlyIf { isMac }
    doLast {
        if (teamId == null) {
            logger.warn(
                "embedFileProviderExtension: SKIPPED — no Team ID (tenshu.teamId in local.properties, " +
                    "-P, or TENSHU_TEAM_ID). The bundle has no Finder integration."
            )
            return@doLast
        }
        fun run(workingDir: File, env: Map<String, String>, vararg command: String) {
            val log = File(outDir.get().asFile.apply { mkdirs() }, "${command.first()}.log")
            val exit = ProcessBuilder(*command).directory(workingDir)
                .apply { environment().putAll(env) }
                .redirectErrorStream(true).redirectOutput(log)
                .start().waitFor()
            if (exit != 0) {
                error("${command.first()} failed (exit $exit) — see ${log.absolutePath}\n${log.readLines().takeLast(30).joinToString("\n")}")
            }
        }
        val out = outDir.get().asFile
        try {
            run(
                sourceDir,
                mapOf(
                    "TENSHU_TEAM_ID" to teamId,
                    "TENSHU_MARKETING_VERSION" to marketingVersion,
                    "TENSHU_BUILD_NUMBER" to buildNumber,
                ),
                "xcodegen",
                "generate",
            )
        } catch (e: IOException) {
            error("embedFileProviderExtension: XcodeGen is not on PATH (`brew install xcodegen`). ${e.message}")
        }
        run(
            sourceDir, emptyMap(),
            "xcodebuild", "-project", "TenshuFileProvider.xcodeproj", "-target", "TenshuFileProvider",
            "-configuration", "Release",
            // arm64 only, like every other Mach-O in the bundle (desktop-macos-distribution.md D2):
            // Xcode's Release default is universal, and the x86_64 slice is dead weight that
            // `scripts/macos-release.sh` refuses.
            "ARCHS=arm64",
            "SYMROOT=${out.resolve("products").absolutePath}",
            "OBJROOT=${out.resolve("intermediates").absolutePath}",
            "build",
        )
        val registrar = out.resolve("tenshu-domain")
        run(
            sourceDir, emptyMap(),
            // `-parse-as-library`: a single-file module is otherwise "top-level code", which `@main` rejects.
            // ⚠ `xcrun --sdk macosx`, not bare `swiftc`: a Gradle daemon first started from an Xcode
            // iOS build phase keeps SDKROOT=…iPhoneOS…, and a bare swiftc then fails with "unable to
            // load standard library for target 'arm64-apple-macosx…'" on an otherwise healthy machine.
            "xcrun", "--sdk", "macosx", "swiftc", "-O", "-target", swiftTarget, "-parse-as-library",
            "DomainRegistrar.swift",
            "-o", registrar.absolutePath,
        )

        val app = binariesDir.get().asFile.resolve("TenshuDrive.app").takeIf { it.isDirectory }
            ?: error("TenshuDrive.app not found under ${binariesDir.get().asFile} — did createDistributable run?")
        val appex = out.resolve("products/Release/TenshuFileProvider.appex")
        val embedded = File(app, "Contents/PlugIns").apply { mkdirs() }.resolve(appex.name)
        // Replace, never merge: a stale file left inside a bundle is sealed into the signature.
        embedded.deleteRecursively()
        appex.copyRecursively(embedded)
        embedded.resolve("Contents/MacOS/TenshuFileProvider").setExecutable(true, false)
        // ⚠ Beside the launcher rather than in Contents/Helpers: run from Helpers, the File
        // Provider daemon saw a caller with no bundle at all. What is established is that the
        // helper registers when Drive spawns it from here (FileProviderExtension/README.md).
        val helper = File(app, "Contents/MacOS/tenshu-domain")
        registrar.copyTo(helper, overwrite = true)
        helper.setExecutable(true, false)
        logger.lifecycle("Embedded ${appex.name} + tenshu-domain → ${app.absolutePath}")
    }
}
