import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// ── Shared macOS code-signing for the desktop apps ────────────────────────────
//
// `docs/proposals/desktop-ipc-client-identity.md` P0. Applied from every
// `*-desktop` module:
//
//     apply(from = rootProject.file("gradle/desktop-app-signing.gradle.kts"))
//
// WHY THIS EXISTS. The desktop IPC peer-credential gate (P1) authenticates a
// connecting app by checking its *running* code against a requirement that pins
// `anchor apple generic` + `certificate leaf[subject.OU] = <TEAM_ID>`. That check
// rejects an ad-hoc signature, and every locally packaged Tenshu app was ad-hoc
// (`Signature=adhoc`, `TeamIdentifier=not set`) — including Control's own peers.
// So the install path has to sign with a real identity *before* the verifier can
// resolve anything. This task is that step.
//
// It also replaces three near-identical copies of the teamId/identity resolution
// (root `build.gradle.kts` for XcodeGen, `control-desktop`, `drive-desktop`).
//
// `--options runtime` remains opt-in. When enabled, the task signs the bundled runtime and
// extracted native libraries with no host entitlements, then signs the app with the
// `disable-library-validation` entitlement if configured. Runtime `jspawnhelper` is the one
// executable exception: MAS gives it app-sandbox + inherit because the bundled JVM launches it
// as the intermediate child for ProcessBuilder. Signing the runtime inside-out keeps the team
// identifiers consistent; leaving it ad-hoc would make hardened runtime reject the JVM.
// Nested dylib signatures do not affect the P1 gate: `SecCodeCheckValidity` on the running
// process is satisfied by the main executable's signature, but they are required for a
// notarizable/MAS bundle and for library validation.
//
// `-Ptenshu.developerIdRelease=true` is the notarizable Developer ID mode
// (`scripts/macos-release.sh`, `desktop-macos-distribution.md` §2.3/§2.4): hardened runtime on every
// Mach-O, a secure timestamp on every signature, `cs.allow-jit` on the host (HotSpot maps its code
// cache MAP_JIT and traps without it under the hardened runtime, §4.3.1), and **no**
// `disable-library-validation` — every dylib the JVM can load is pre-extracted into the bundle and
// signed by us (`embedMasNativeLibraries`), so library validation passes. The hardened runtime also
// gates protected resources; the matching entitlements are derived from the bundle's own
// `NS…UsageDescription` keys, so an app that asks for the camera or the calendar is entitled to it
// and no other is. It is the identity, not this flag, that makes a build notarizable: the release
// script refuses anything but a Developer ID Application identity unless told it is a pipeline test.
//
// ⚠ Configuration cache: every value the `doLast` closure touches is declared
// *inside* the `register` block. Capturing a script-level `val` drags the
// non-serializable build-script object into the cache and breaks it — the same rule
// `control-desktop`'s own tasks document.

/**
 * Resolution precedence for both signing values, matching the convention already
 * used for `sdk.dir`-style per-developer config: `-P<name>` (override / CI) →
 * `$ENV` (CI) → `local.properties` (per-developer, gitignored).
 *
 * ⚠ This is *build-time* config — which identity we sign **with**. It is
 * deliberately NOT the same thing as the runtime trust policy (which teams the P1
 * verifier **accepts**). That one must be compiled in, because a trust list read
 * from a property or environment variable at runtime is writable by exactly the
 * hostile local process the gate exists to stop. See the proposal §5.
 */
fun resolveSigningValue(project: Project, propertyKey: String, envKey: String): String? =
    (project.findProperty(propertyKey) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }
        ?: project.rootProject.file("local.properties")
            .takeIf { it.exists() }
            // ⚠ A reader, not an InputStream: `Properties.load(InputStream)` decodes ISO-8859-1,
            // which mangles a non-ASCII certificate name ("Löbbecke" → "LÃ¶bbecke") and makes
            // codesign report "no identity found" for an identity that is right there.
            ?.let { file -> Properties().apply { file.reader(Charsets.UTF_8).use { load(it) } } }
            ?.getProperty(propertyKey)
            ?.takeIf { it.isNotBlank() }

/**
 * Entitlement templates for nested bundles that must not be signed with the host's plist,
 * keyed by the item's file name under `Contents/Helpers`, `Contents/PlugIns`, or `Contents/Library/LoginItems`. `@TEAM_ID@`
 * is substituted. Set from the module **before** applying this script:
 *
 *     extra["tenshu.nestedEntitlements"] =
 *         mapOf("TenshuFileProvider.appex" to "macos/entitlements/drive-fileprovider.entitlements")
 */
fun nestedEntitlementTemplates(project: Project): Map<String, File> =
    (project.findProperty("tenshu.nestedEntitlements") as? Map<*, *>).orEmpty()
        .map { (name, path) -> name.toString() to project.file(path.toString()) }
        .toMap()

/** Provisioning profiles for nested MAS bundles, keyed like [nestedEntitlementTemplates]. */
fun nestedProvisioningProfiles(project: Project): Map<String, File> =
    (project.findProperty("tenshu.nestedProfiles") as? Map<*, *>).orEmpty()
        .map { (name, path) ->
            val profile = File(path.toString()).let { if (it.isAbsolute) it else project.rootProject.file(it) }
            name.toString() to profile
        }
        .toMap()

tasks.register("signDesktopApp") {
    group = "macos"
    description =
        "Code-signs this module's .app with the configured Apple identity so the desktop IPC " +
            "peer-credential gate can attest it (desktop-ipc-client-identity.md P0)."

    // `createDistributable` (build/compose/binaries/main/app), not
    // `createReleaseDistributable` (…/main-release/app) — the former is what
    // `packageControlApp` and the documented local install path produce.
    // ⚠ The pre-existing sign tasks depend on the *release* distributable but then
    // walk the shared `compose/binaries` root and take the first `.app` they find,
    // so which bundle they signed depended on what else happened to be in `build/`.
    // Scoping the search to one directory is what makes this deterministic.
    dependsOn("createDistributable")

    // Helpers (tray-agent, qr-scan-helper, image-decode-helper, …) are copied into
    // the bundle by per-module `embed*` tasks. Signing has to happen after they land
    // or the bundle seal covers a bundle that no longer exists.
    mustRunAfter(tasks.matching { it.name.startsWith("embed") })

    val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    val teamId = resolveSigningValue(project, "tenshu.teamId", "TENSHU_TEAM_ID")
    val identity = resolveSigningValue(project, "tenshu.signingIdentity", "TENSHU_SIGNING_IDENTITY")
    val developerIdRelease = project.findProperty("tenshu.developerIdRelease")?.toString()?.toBoolean() == true
    val hardenedRuntime = project.hasProperty("tenshu.hardenedRuntime") || developerIdRelease
    val appDir = layout.buildDirectory.dir("compose/binaries/main/app")
    val generatedEntitlements = layout.buildDirectory.file("tmp/desktop-app.entitlements")
    val nestedEntitlementsDir = layout.buildDirectory.dir("tmp/nested-entitlements")
    val nestedTemplates = nestedEntitlementTemplates(project)
    val nestedProfiles = nestedProvisioningProfiles(project)
    val masSandbox = project.findProperty("tenshu.masSandbox")?.toString()?.toBoolean() == true
    val hostEntitlementsTemplate = (project.findProperty("tenshu.hostEntitlements") as String?)
        ?.let { project.file(it) }
    val provisioningProfile = (project.findProperty("tenshu.provisioningProfile") as String?)
        ?.let { path ->
            File(path).let { if (it.isAbsolute) it else project.rootProject.file(path) }
        }
    // Mirror `SocketPathResolver.TEAM_ID_PROPERTY` / `APP_GROUP_SUFFIX` in `:core-common`.
    val teamIdJvmProperty = "tenshu.ipc.teamId"
    val appGroupSuffix = "app.tenshu.shared"
    val moduleName = project.name
    val buildNumber = project.findProperty("tenshu.buildNumber")?.toString()?.takeIf { it.isNotBlank() }

    onlyIf { isMac }

    doLast {
        if (teamId.isNullOrBlank() || identity.isNullOrBlank()) {
            logger.warn(
                "signDesktopApp ($moduleName): SKIPPED — set tenshu.teamId + tenshu.signingIdentity " +
                    "via local.properties, -P, or TENSHU_TEAM_ID / TENSHU_SIGNING_IDENTITY. " +
                    "The .app was built but is still ad-hoc signed, so the desktop IPC gate " +
                    "cannot attest it (desktop-ipc-client-identity.md P0)."
            )
            return@doLast
        }
        if (masSandbox && hostEntitlementsTemplate?.isFile != true) {
            error("signDesktopApp ($moduleName): -Ptenshu.masSandbox=true requires a host entitlement template")
        }
        if (masSandbox && provisioningProfile?.isFile != true) {
            error("signDesktopApp ($moduleName): -Ptenshu.masSandbox=true requires -Ptenshu.provisioningProfile=<.provisionprofile>")
        }
        if (masSandbox && developerIdRelease) {
            error("signDesktopApp ($moduleName): -Ptenshu.masSandbox and -Ptenshu.developerIdRelease are two channels; pick one")
        }
        if (developerIdRelease && buildNumber == null) {
            error(
                "signDesktopApp ($moduleName): -Ptenshu.developerIdRelease=true requires -Ptenshu.buildNumber " +
                    "(a release must carry a CFBundleVersion that grows, desktop-macos-distribution.md D9)"
            )
        }

        val root = appDir.get().asFile
        val app = root.listFiles()?.firstOrNull { it.name.endsWith(".app") && it.isDirectory }
            ?: error("No .app found in $root — did createDistributable run?")

        if (masSandbox && buildNumber == null) {
            error(
                "signDesktopApp ($moduleName): -Ptenshu.masSandbox=true requires -Ptenshu.buildNumber " +
                    "(App Store Connect refuses a second upload with the same CFBundleVersion)"
            )
        }
        if (buildNumber != null) {
            // App Store Connect requires a monotonically changing numeric CFBundleVersion, and so
            // does any updater comparing two Developer ID releases (D9).
            // Compose's packageVersion is the marketing version, so the upload build number
            // is applied to the generated host plist immediately before it is signed.
            val info = app.resolve("Contents/Info.plist")
            val versionPattern = Regex(
                "(<key>CFBundleVersion</key>\\s*<string>)[^<]+(</string>)",
            )
            val versionMatch = versionPattern.find(info.readText())
                ?: error("signDesktopApp ($moduleName): host CFBundleVersion is missing")
            info.writeText(
                info.readText().replaceRange(
                    versionMatch.range,
                    "${versionMatch.groupValues[1]}$buildNumber${versionMatch.groupValues[2]}",
                ),
            )
        }

        if (masSandbox) {
            provisioningProfile!!.copyTo(app.resolve("Contents/embedded.provisionprofile"), overwrite = true)
            logger.lifecycle("signDesktopApp ($moduleName): embedded MAS profile ${provisioningProfile.name}")
        }

        // ── Pin the bundle to its App Group container ─────────────────────────────
        // The team we sign WITH is the team whose group container the app uses: the launcher
        // cfg gets `-Dtenshu.ipc.teamId`, so `SocketPathResolver` / `DesktopAppPaths` resolve to
        // `~/Library/Group Containers/<TEAM>.app.tenshu.shared` no matter how the app is
        // started. That is the only directory the sandboxed Drive File Provider can reach, and
        // until this pin existed the root depended on whether the launching shell happened to
        // export TENSHU_TEAM_ID (docs/tech-debt.md "Giving the desktop build a Team ID…").
        // ⚠ Written BEFORE signing — the cfg is sealed by the bundle signature.
        // ⚠ Every desktop app must be repackaged together: a pinned Control and an unpinned
        // client look for the socket in different directories.
        // ⚠ The pin is the whole contract: a packaged app uses the container and nothing else.
        // `~/.tenshu` is never adopted (removed before the production reset — `DesktopAppPaths`).
        val pin = "java-options=-D$teamIdJvmProperty="
        val cfgs = app.resolve("Contents/app").listFiles { f -> f.extension == "cfg" }.orEmpty()
        if (cfgs.isEmpty()) error("signDesktopApp ($moduleName): no launcher .cfg under ${app.name}/Contents/app")
        cfgs.forEach { cfg ->
            val lines = cfg.readLines().filterNot { it.startsWith(pin) }.toMutableList()
            val section = lines.indexOf("[JavaOptions]")
            if (section < 0) error("signDesktopApp ($moduleName): ${cfg.name} has no [JavaOptions] section")
            lines.add(section + 1, pin + teamId)
            cfg.writeText(lines.joinToString("\n") + "\n")
        }

        // The App Group entitlement is what lets an unsandboxed process use that container
        // without the "would like to access data from other apps" prompt (macOS 15+). A
        // Team-ID-prefixed group is authorised by the signature itself, so it needs no
        // provisioning profile — unlike the iOS `group.` form.
        // Hardened runtime + a jpackage runtime only coexist with library validation
        // disabled — see the header note.
        fun entitlementsPlist(body: String) =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
            $body
            </dict>
            </plist>
            """.trimIndent()
        // ⚠ MAS only. A Mac App Store signature must itself carry the application and team
        // identifiers its embedded profile grants; embedding the profile does not put them in the
        // signature, and the validator rejects the mismatch. They are copied from the bundle's own
        // profile rather than spelled in the templates: the File Provider template is shared with
        // Developer ID builds, where a restricted key with no profile behind it gets the process
        // killed at launch. The key is `com.apple.application-identifier` on macOS — the bare
        // `application-identifier` is the iOS spelling.
        fun plistValue(plist: File, keyPath: String): String? {
            val process = ProcessBuilder("/usr/libexec/PlistBuddy", "-c", "Print $keyPath", plist.absolutePath)
                .redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText().trim()
            return out.takeIf { process.waitFor() == 0 && it.isNotEmpty() }
        }
        fun withProfileIdentity(entitlements: String, bundle: File): String {
            if (!masSandbox) return entitlements
            val profile = bundle.resolve("Contents/embedded.provisionprofile")
            if (!profile.isFile) return entitlements
            val decoded = File.createTempFile("tenshu-profile", ".plist")
            try {
                val cms = ProcessBuilder(
                    "security", "cms", "-D", "-i", profile.absolutePath, "-o", decoded.absolutePath,
                ).redirectErrorStream(true).start()
                val cmsOut = cms.inputStream.bufferedReader().readText()
                if (cms.waitFor() != 0) error("signDesktopApp ($moduleName): cannot decode $profile\n$cmsOut")
                val appIdKey = "com.apple.application-identifier"
                val keys = listOf(appIdKey, "com.apple.developer.team-identifier")
                val values = keys.associateWith { key ->
                    plistValue(decoded, ":Entitlements:$key")
                        ?: error("signDesktopApp ($moduleName): ${bundle.name}'s profile grants no $key")
                }
                val bundleId = plistValue(bundle.resolve("Contents/Info.plist"), ":CFBundleIdentifier")
                    ?: error("signDesktopApp ($moduleName): ${bundle.name} has no CFBundleIdentifier")
                check(values.getValue(appIdKey) == "$teamId.$bundleId") {
                    "signDesktopApp ($moduleName): ${bundle.name} embeds a profile for " +
                        "'${values.getValue(appIdKey)}', want '$teamId.$bundleId'"
                }
                val stripped = keys.fold(entitlements) { text, key ->
                    text.replace(Regex("<key>${Regex.escape(key)}</key>\\s*<string>[^<]*</string>\\s*"), "")
                }
                val end = stripped.lastIndexOf("</dict>")
                check(end >= 0) { "signDesktopApp ($moduleName): entitlements for ${bundle.name} have no <dict>" }
                val snippet = values.entries.joinToString("") { (k, v) -> "    <key>$k</key>\n    <string>$v</string>\n" }
                return stripped.substring(0, end) + snippet + stripped.substring(end)
            } finally {
                decoded.delete()
            }
        }

        val appEntitlements = generatedEntitlements.get().asFile
        appEntitlements.parentFile.mkdirs()
        val templateText = if (masSandbox) {
            withProfileIdentity(hostEntitlementsTemplate!!.readText().replace("@TEAM_ID@", teamId), app)
        } else {
            null
        }
        // Developer ID release: the hardened runtime refuses protected resources to a process
        // without the matching entitlement, even after the user granted TCC access. Each one is
        // granted exactly when the bundle declares the usage string macOS shows for it.
        val resourceEntitlements = if (developerIdRelease) {
            val info = app.resolve("Contents/Info.plist").readText()
            linkedMapOf(
                "NSCameraUsageDescription" to "com.apple.security.device.camera",
                "NSMicrophoneUsageDescription" to "com.apple.security.device.audio-input",
                "NSCalendarsUsageDescription" to "com.apple.security.personal-information.calendars",
                "NSCalendarsFullAccessUsageDescription" to "com.apple.security.personal-information.calendars",
                "NSContactsUsageDescription" to "com.apple.security.personal-information.addressbook",
                "NSLocationUsageDescription" to "com.apple.security.personal-information.location",
                "NSLocationWhenInUseUsageDescription" to "com.apple.security.personal-information.location",
                "NSPhotoLibraryUsageDescription" to "com.apple.security.personal-information.photos-library",
            ).filterKeys { info.contains("<key>$it</key>") }.values.distinct()
        } else {
            emptyList()
        }
        appEntitlements.writeText(
            templateText ?: entitlementsPlist(
                "<key>com.apple.security.application-groups</key>" +
                    "<array><string>$teamId.$appGroupSuffix</string></array>" +
                    when {
                        developerIdRelease -> "<key>com.apple.security.cs.allow-jit</key><true/>" +
                            resourceEntitlements.joinToString("") { "<key>$it</key><true/>" }
                        hardenedRuntime -> "<key>com.apple.security.cs.disable-library-validation</key><true/>"
                        else -> ""
                    }
            )
        )
        // Developer ID release: a helper gets its own plist — the App Group (the tray, the claim
        // helper and the domain registrar open the container) plus only the resource entitlements
        // that helper uses — never the host's allow-jit. ⚠ A protected resource still needs the
        // host to declare its usage string: an entitlement the host does not ask for is not granted.
        val helperResourceNeeds = mapOf("qr-scan-helper" to setOf("com.apple.security.device.camera"))
        fun helperEntitlementsFor(item: File): File {
            val out = nestedEntitlementsDir.get().asFile.resolve("${item.name}.developer-id.entitlements")
            out.parentFile.mkdirs()
            val needs = helperResourceNeeds[item.name].orEmpty().filter { it in resourceEntitlements }
            out.writeText(
                entitlementsPlist(
                    "<key>com.apple.security.application-groups</key>" +
                        "<array><string>$teamId.$appGroupSuffix</string></array>" +
                        needs.joinToString("") { "<key>$it</key><true/>" }
                )
            )
            return out
        }
        if (developerIdRelease) {
            logger.lifecycle(
                "signDesktopApp ($moduleName): Developer ID release — hardened runtime, secure timestamp, " +
                    "allow-jit${resourceEntitlements.joinToString("") { ", " + it.substringAfterLast('.') }}"
            )
        }
        if (masSandbox) logger.lifecycle("signDesktopApp ($moduleName): App Sandbox ON")
        val runtimeArgs = if (hardenedRuntime) listOf("--options", "runtime") else emptyList()
        if (hardenedRuntime && !developerIdRelease) {
            logger.lifecycle("signDesktopApp ($moduleName): hardened runtime ON (library validation disabled)")
        }

        // `--timestamp=none` keeps local signing offline and fast. A Developer ID release needs a
        // secure timestamp on every signature (notarization rejects one without), which means a
        // round trip to Apple's timestamp server per Mach-O.
        val timestampArg = if (developerIdRelease) "--timestamp" else "--timestamp=none"
        fun codesign(target: File, entitlements: File? = appEntitlements) {
            // Captured, not inherited: under the Gradle daemon inherited output goes to the
            // daemon's own stdout, so a failure used to arrive as a bare exit code.
            val command = (listOf("codesign", "--force", timestampArg) + runtimeArgs).toMutableList()
            entitlements?.let { command += listOf("--entitlements", it.absolutePath) }
            command += listOf("--sign", identity, target.absolutePath)
            val process = ProcessBuilder(command)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exit = process.waitFor()
            if (exit != 0) error("codesign failed for ${target.name} with exit code $exit\n$output")
        }

        if (masSandbox) {
            // jpackage gives its nested runtime the host's bundle identifier. App Store Connect
            // treats that as a duplicate bundle, so give the runtime its own child identifier
            // before sealing it (the spike's package-mas.sh performs the same fix-up).
            val appInfo = app.resolve("Contents/Info.plist")
            val runtimeInfo = app.resolve("Contents/runtime/Contents/Info.plist")
            val appBundleId = Regex(
                "<key>CFBundleIdentifier</key>\\s*<string>([^<]+)</string>",
            ).find(appInfo.readText())?.groupValues?.get(1)
                ?: error("signDesktopApp ($moduleName): host bundle identifier is missing")
            require(runtimeInfo.isFile) {
                "signDesktopApp ($moduleName): bundled runtime Info.plist is missing"
            }
            val runtimeId = "$appBundleId.runtime"
            val runtimePlist = runtimeInfo.readText()
            val identifierPattern = Regex(
                "(<key>CFBundleIdentifier</key>\\s*<string>)[^<]+(</string>)",
            )
            check(identifierPattern.containsMatchIn(runtimePlist)) {
                "signDesktopApp ($moduleName): runtime bundle identifier is missing"
            }
            val identifierMatch = identifierPattern.find(runtimePlist)
                ?: error("signDesktopApp ($moduleName): runtime bundle identifier is missing")
            runtimeInfo.writeText(
                runtimePlist.replaceRange(
                    identifierMatch.range,
                    "${identifierMatch.groupValues[1]}$runtimeId${identifierMatch.groupValues[2]}",
                ),
            )
        }

        // Sign raw Mach-O files in the bundled runtime and Contents/app first. The MAS native
        // staging task removes jar-contained copies, so every executable the app can load is now
        // signed in place. These files receive no host entitlements.
        fun isMachO(file: File): Boolean {
            val process = ProcessBuilder("file", "--brief", file.absolutePath)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            return process.waitFor() == 0 && output.contains("Mach-O")
        }
        sequenceOf(app.resolve("Contents/runtime"), app.resolve("Contents/app"))
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().asSequence() }
            .filter { it.isFile && isMachO(it) }
            .toList()
            .forEach { file ->
                val template = if (masSandbox && file.name == "jspawnhelper") {
                    nestedTemplates["jspawnhelper"]
                } else {
                    null
                }
                if (masSandbox && file.name == "jspawnhelper" && template == null) {
                    error("signDesktopApp ($moduleName): MAS jspawnhelper entitlement template is missing")
                }
                val entitlements = template?.let { templateFile ->
                    val out = nestedEntitlementsDir.get().asFile.resolve("${file.name}.entitlements")
                    out.parentFile.mkdirs()
                    out.writeText(templateFile.readText().replace("@TEAM_ID@", teamId))
                    out
                }
                codesign(file, entitlements)
            }

        // The jpackage runtime is itself a signed bundle. Re-seal it after signing the
        // Mach-O files inside it, without applying the host's App Sandbox entitlements.
        val runtimeBundle = app.resolve("Contents/runtime")
        if (runtimeBundle.isDirectory) {
            codesign(runtimeBundle, entitlements = null)
        }

        // Sign inside-out: nested executables must be sealed before the bundle that
        // contains them, or the outer seal is computed over stale contents.
        // Deliberately NOT `--deep` (Apple deprecates it, and it silently re-signs
        // the embedded JDK we specifically want left alone).
        // ⚠ Contents/MacOS counts too, minus the launcher itself (the bundle signature covers
        // that one): a second executable there is nested code like any helper, and left alone
        // it keeps the linker's ad-hoc signature inside a team-signed bundle.
        val launcher = app.resolve("Contents/MacOS/${app.name.removeSuffix(".app")}")
        val nested = listOf(
            "Contents/Helpers",
            "Contents/PlugIns",
            "Contents/Library/LoginItems",
            "Contents/MacOS",
        )
            .map { app.resolve(it) }
            .filter { it.isDirectory }
            .flatMap { it.listFiles()?.toList().orEmpty() }
            .filterNot { it == launcher }

        if (masSandbox) {
            nestedProfiles.forEach { (name, profile) ->
                val bundle = nested.singleOrNull { it.name == name }
                    ?: error("signDesktopApp ($moduleName): nested profile target '$name' is missing")
                require(profile.isFile) {
                    "signDesktopApp ($moduleName): nested MAS profile for $name is missing: ${profile.absolutePath}"
                }
                profile.copyTo(bundle.resolve("Contents/embedded.provisionprofile"), overwrite = true)
                logger.lifecycle("signDesktopApp ($moduleName): embedded nested MAS profile ${profile.name} in $name")
            }
        }

        // ⚠⚠ A nested bundle carrying its OWN provisioning profile must not be signed with
        // the host's entitlement plist. For a bundle already signed by Xcode (for example the
        // Touch ID helper), leaving it untouched preserves its restricted entitlements. A
        // profile-bearing bundle built by swiftc (the MAS login item) is different: it has a
        // profile but no signature yet, so its explicit nested template must be applied.
        val carriesOwnProfile: (File) -> Boolean = {
            File(it, "Contents/embedded.provisionprofile").isFile
        }
        val preSigned = nested.filter { carriesOwnProfile(it) && nestedTemplates[it.name] == null }
        val assertEntitledLater = mutableListOf<Pair<File, File>>()
        val toSign = nested.filterNot { it in preSigned }
        // A nested bundle that must NOT inherit the host's entitlements declares its own
        // template (`tenshu.nestedEntitlements`). The File Provider .appex is the case: it is
        // sandboxed where the host is not, and signing it with the host's plist would drop the
        // sandbox — fileproviderd then refuses to load it, with no message anywhere near here.
        toSign.forEach { item ->
            val template = nestedTemplates[item.name]
            if (template == null) {
                // ⚠ MAS: a nested executable with no template would get the host's sandbox plist (no
                // `inherit`, a restricted identity with no profile). Every one must declare its own
                // in the module's `tenshu.nestedEntitlements`; `embedJarHelpers` can add new ones.
                if (masSandbox && item.isFile) {
                    error(
                        "signDesktopApp ($moduleName): MAS nested executable ${item.name} has no entitlement " +
                            "template — add it to the module's masSandbox nested entitlements (child-inherit)"
                    )
                }
                if (developerIdRelease && item.isFile) {
                    val entitlements = helperEntitlementsFor(item)
                    codesign(item, entitlements)
                    assertEntitledLater += item to entitlements
                } else {
                    codesign(item)
                }
            } else {
                val out = nestedEntitlementsDir.get().asFile.resolve("${item.name}.entitlements")
                out.parentFile.mkdirs()
                out.writeText(withProfileIdentity(template.readText().replace("@TEAM_ID@", teamId), item))
                codesign(item, out)
            }
        }
        preSigned.forEach {
            logger.lifecycle(
                "signDesktopApp ($moduleName): left ${it.name} untouched — it carries its own " +
                    "provisioning profile and has no nested entitlement template."
            )
        }
        codesign(app)

        // ⚠⚠ Verify rather than trust the skip. This is the guard the original bug got past: the
        // build reported a correctly signed bundle with the right nested count and was wrong about
        // the one thing that mattered. An empty entitlement dump on a profile-carrying helper means
        // the strip happened anyway, and shipping that is worse than failing here.
        // ⚠ Recomputed from `nested`, NOT reused from `preSigned`: the check must cover every
        // profile-carrying bundle regardless of how the skip above classified it, or a future edit
        // to the partition would silently switch the guard off along with the behaviour it guards.
        nested.filter(carriesOwnProfile).forEach { helper ->
            val dump = ProcessBuilder("codesign", "-d", "--entitlements", "-", helper.absolutePath)
                .redirectErrorStream(true).start()
                .inputStream.bufferedReader().readText()
            if (!dump.contains("keychain-access-groups") &&
                !dump.contains("com.apple.security") &&
                !dump.contains("<key>") &&
                !dump.contains("[Key]")
            ) {
                error(
                    "signDesktopApp ($moduleName): ${helper.name} carries a provisioning profile " +
                        "but has NO entitlements after signing — they were stripped, and the " +
                        "capability it exists for (e.g. the data-protection keychain) will fail " +
                        "silently at runtime.\n$dump"
                )
            }
        }

        // Same guard for what this task signs itself: every key a template (or the host plist)
        // asks for must be readable back off the signed item.
        fun entitlementsOf(target: File): String =
            ProcessBuilder("codesign", "-d", "--entitlements", "-", "--xml", target.absolutePath)
                .redirectErrorStream(true).start()
                .inputStream.bufferedReader().readText()
        val keyPattern = Regex("<key>([^<]+)</key>")
        fun assertEntitled(target: File, wanted: String) {
            val have = entitlementsOf(target)
            val missing = keyPattern.findAll(wanted).map { it.groupValues[1] }.filterNot { have.contains(it) }.toList()
            if (missing.isNotEmpty()) {
                error("signDesktopApp ($moduleName): ${target.name} is missing entitlements $missing after signing.\n$have")
            }
        }
        assertEntitled(app, appEntitlements.readText())
        assertEntitledLater.forEach { (item, entitlements) -> assertEntitled(item, entitlements.readText()) }
        toSign.forEach { item ->
            if (nestedTemplates[item.name] != null) {
                assertEntitled(item, nestedEntitlementsDir.get().asFile.resolve("${item.name}.entitlements").readText())
            }
        }
        nestedTemplates.keys
            .filterNot { name -> name == "jspawnhelper" || nested.any { it.name == name } }
            .forEach {
            logger.warn("signDesktopApp ($moduleName): '$it' has an entitlements template but is not in the bundle.")
        }

        // Fail loudly rather than shipping an unattestable bundle: the whole point
        // of P0 is that `TeamIdentifier` stops being "not set", and a silent
        // fallback to ad-hoc is exactly the state this task exists to end.
        val out = ProcessBuilder("codesign", "-dv", app.absolutePath)
            .redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText()
        val stamped = out.lineSequence().firstOrNull { it.startsWith("TeamIdentifier=") }
            ?.removePrefix("TeamIdentifier=")?.trim()
        if (stamped != teamId) {
            error(
                "signDesktopApp ($moduleName): signed ${app.name} but TeamIdentifier is " +
                    "'${stamped ?: "absent"}', expected '$teamId'. The desktop IPC gate would " +
                    "reject this bundle.\n$out"
            )
        }
        logger.lifecycle(
            "Signed ${app.name} → TeamIdentifier=$stamped " +
                "(${nested.size} nested, ${preSigned.size} pre-signed left intact)"
        )
    }
}

// Every desktop app runs this helper at startup (`AppGroupContainerClaim`), because macOS refuses
// a development-signed app its App Group container until the app has asked for it natively —
// docs/tech-debt.md "A packaged desktop app is refused its own App Group container". `signDesktopApp`
// signs it like any helper, with the app's own App Group entitlement. An `embed*` task, so
// `packageDesktopApp` includes it with no per-module wiring.
tasks.register("embedAppGroupClaim") {
    group = "macos"
    description = "Embeds the App Group container claim helper into this module's .app/Contents/Helpers."
    val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    onlyIf { isMac }
    if (isMac) dependsOn(":macos-helpers:compileAppGroupClaim")
    dependsOn("createDistributable")
    val helperBin = project(":macos-helpers").layout.buildDirectory
        .file("appgroup-claim/appgroup-claim").get().asFile
    val appDir = layout.buildDirectory.dir("compose/binaries/main/app")
    doLast {
        val root = appDir.get().asFile
        val app = root.listFiles()?.firstOrNull { it.name.endsWith(".app") && it.isDirectory }
            ?: error("No .app found in $root — did createDistributable run?")
        val dest = app.resolve("Contents/Helpers").apply { mkdirs() }.resolve("appgroup-claim")
        helperBin.copyTo(dest, overwrite = true)
        dest.setExecutable(true, false)
        logger.lifecycle("Embedded appgroup-claim → ${dest.absolutePath}")
    }
}

// ── Helpers that ride in jars ─────────────────────────────────────────────────
//
// `qr-scan-helper` (`:ui-qr-scanner`), `image-decode-helper` and `map-snapshot-helper`
// (`:macos-helpers`) are swiftc executables shipped as classpath resources, so a dev `:run` can
// extract one to a temp file (`HelperBinary`). A packaged app must run the copy in
// `Contents/Helpers/<name>` instead, which `signDesktopApp` signs like any helper. Control and Drive
// embed two of them from their own build scripts; every other app carried all of them only in the
// jar. This copies each one a jar carries into the bundle when the bundle has none, so every
// packaged app runs signed helpers (measured 2026-11-06: all ten carry `:macos-helpers`, and the
// notary service rejected Control for the three unsigned jar copies).
// ⚠ The names are mirrored in `embedMasNativeLibraries`, which strips the jar copies in a release
// build and refuses any other Mach-O it finds in a jar.
tasks.register("embedJarHelpers") {
    group = "macos"
    description = "Copies helper executables that ride in jars into this module's .app/Contents/Helpers."
    val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    val appDir = layout.buildDirectory.dir("compose/binaries/main/app")
    onlyIf { isMac }
    dependsOn("createDistributable")
    // A module's own embed task (built from source) wins; this only fills the gaps.
    mustRunAfter(tasks.matching { it.name.startsWith("embed") && it.name != "embedJarHelpers" && it.name != "embedMasNativeLibraries" })
    doLast {
        val jarHelpers = listOf("qr-scan-helper", "image-decode-helper", "map-snapshot-helper")
        val root = appDir.get().asFile
        val app = root.listFiles()?.firstOrNull { it.name.endsWith(".app") && it.isDirectory }
            ?: error("No .app found in $root — did createDistributable run?")
        val helpers = app.resolve("Contents/Helpers")
        app.resolve("Contents/app")
            .listFiles { file -> file.isFile && file.extension.equals("jar", ignoreCase = true) }.orEmpty()
            .sortedBy { it.name }
            .forEach { jar ->
                ZipFile(jar).use { zip ->
                    jarHelpers.forEach { name ->
                        val entry = zip.getEntry(name) ?: return@forEach
                        val dest = helpers.resolve(name)
                        if (dest.isFile) return@forEach
                        helpers.mkdirs()
                        zip.getInputStream(entry).use { input -> dest.outputStream().use { input.copyTo(it) } }
                        dest.setExecutable(true, false)
                        logger.lifecycle("Embedded $name from ${jar.name} → ${dest.absolutePath}")
                    }
                }
            }
    }
}

/**
 * Embed tasks this module must NOT wire into `packageDesktopApp` by default.
 *
 * Set from the module before or after applying this script — it is read lazily:
 *
 *     extra["tenshu.optInEmbedTasks"] = listOf("embedKeychainHelper")
 *
 * The one real case is a helper that is *signing-gated* and therefore hard-fails when
 * unconfigured (Control's Touch ID keychain helper needs a provisioning profile minted in the
 * Xcode GUI first). Making that a default dependency would break the umbrella on every
 * unsigned / free-team build — which is the opposite of the point.
 */
fun optInEmbedTasks(project: Project): Set<String> =
    when (val declared = project.findProperty("tenshu.optInEmbedTasks")) {
        null -> emptySet()
        is Collection<*> -> declared.map { it.toString() }.toSet()
        else -> declared.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

// ── MAS native-library staging ────────────────────────────────────────────────
//
// App Review does not accept a release bundle that hides foreign-platform Mach-O files in jars,
// and a sandboxed app must not unpack executable code into its container. ONNX Runtime documents
// `onnxruntime.native.path`; AndroidX SQLite's bundled loader documents the analogous
// `androidx.sqlite.driver.bundled.path`. Stage both libraries in Contents/app/native, point the
// launcher at those directories, and fail closed if a new jar introduces an un-audited native
// entry. It runs for the two release channels — MAS (`tenshu.masSandbox`) and the notarized Developer
// ID build (`tenshu.developerIdRelease`), where the notary service rejects an unsigned Mach-O inside a
// jar and library validation refuses a dylib a library unpacks to a temp directory. Development
// builds keep the jars as they are.
// ⚠⚠ Mach-O is found by its **magic bytes**, not its file name. The extension-only audit this
// replaced passed `qr-scan-helper`, `image-decode-helper` and `map-snapshot-helper` (no extension)
// straight through, and the notary service rejected the first real Control submission for them
// (2026-11-06). Those three are dropped from the jars once `embedJarHelpers` has put a copy in
// `Contents/Helpers`; any other Mach-O fails the build.
tasks.register("embedMasNativeLibraries") {
    group = "macos"
    description = "Pre-extracts audited arm64 native libraries from jars for the Mac App Store bundle."

    val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    val masSandbox = project.findProperty("tenshu.masSandbox")?.toString()?.toBoolean() == true
    val developerIdRelease = project.findProperty("tenshu.developerIdRelease")?.toString()?.toBoolean() == true
    val appDir = layout.buildDirectory.dir("compose/binaries/main/app")
    onlyIf { isMac && (masSandbox || developerIdRelease) }
    dependsOn("createDistributable")
    mustRunAfter("embedJarHelpers")

    doLast {
        val root = appDir.get().asFile
        val app = root.listFiles()?.firstOrNull { it.name.endsWith(".app") && it.isDirectory }
            ?: error("No .app found in $root — did createDistributable run?")
        val appContents = app.resolve("Contents/app")
        // Mirrors `embedJarHelpers`.
        val jarHelpers = setOf("qr-scan-helper", "image-decode-helper", "map-snapshot-helper")
        // 64/32-bit thin Mach-O in either byte order, or a fat header. A fat header shares
        // 0xCAFEBABE with a .class file; there the next word is the slice count (small), where a
        // class file has its major version (>= 45).
        fun isMachO(head: ByteArray, length: Int): Boolean {
            if (length < 8) return false
            fun u32(offset: Int) = ((head[offset].toLong() and 0xff) shl 24) or
                ((head[offset + 1].toLong() and 0xff) shl 16) or
                ((head[offset + 2].toLong() and 0xff) shl 8) or
                (head[offset + 3].toLong() and 0xff)
            return when (u32(0)) {
                0xfeedfacfL, 0xfeedfaceL, 0xcffaedfeL, 0xcefaedfeL -> true
                0xcafebabeL, 0xcafebabfL -> u32(4) < 45
                else -> false
            }
        }
        val jars = appContents.listFiles { file -> file.isFile && file.extension.equals("jar", ignoreCase = true) }.orEmpty()
        val nativeSuffixes = setOf(".dylib", ".jnilib", ".so", ".dll")
        val onnxPrefix = "ai/onnxruntime/native/"
        val skikoArm64 = appContents.resolve("libskiko-macos-arm64.dylib")
        val nativeProperties = linkedMapOf<String, String>()
        var rewrittenJars = 0

        fun extract(zip: ZipFile, entryName: String, target: File) {
            val entry = zip.getEntry(entryName)
                ?: error("${app.name}: expected $entryName in ${target.name}'s jar")
            target.parentFile.mkdirs()
            zip.getInputStream(entry).use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            target.setExecutable(true, false)
        }

        fun isJarSignature(name: String): Boolean {
            if (!name.startsWith("META-INF/")) return false
            return name.endsWith(".SF", ignoreCase = true) ||
                name.endsWith(".RSA", ignoreCase = true) ||
                name.endsWith(".DSA", ignoreCase = true) ||
                name.endsWith(".EC", ignoreCase = true)
        }

        fun rewriteJar(jar: File, drop: (String) -> Boolean) {
            val temporary = File(jar.parentFile, "${jar.name}.mas.tmp")
            ZipFile(jar).use { input ->
                ZipOutputStream(temporary.outputStream().buffered()).use { output ->
                    input.entries().asSequence().forEach { entry ->
                        if (drop(entry.name) || isJarSignature(entry.name)) return@forEach
                        output.putNextEntry(ZipEntry(entry.name))
                        if (!entry.isDirectory) input.getInputStream(entry).use { it.copyTo(output) }
                        output.closeEntry()
                    }
                }
            }
            if (!jar.delete() || !temporary.renameTo(jar)) {
                temporary.delete()
                error("${app.name}: could not replace rewritten jar ${jar.name}")
            }
            rewrittenJars++
        }

        jars.forEach { jar ->
            val names = ZipFile(jar).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
            val hasOnnx = names.any { it.startsWith(onnxPrefix) && it.endsWith(".dylib") }
            val sqliteEntry = names.singleOrNull {
                it.startsWith("natives/osx_arm64/") && it.endsWith("/libsqliteJni.dylib")
            }
            val hasSqlite = sqliteEntry != null
            val skikoEntries = names.filter {
                it.startsWith("libskiko-") &&
                    (it.endsWith(".dylib", ignoreCase = true) || it.endsWith(".sha256", ignoreCase = true))
            }
            val nativeEntries = names.filter { name -> nativeSuffixes.any { name.endsWith(it, ignoreCase = true) } }
            val machOEntries = ZipFile(jar).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .filter { entry ->
                        zip.getInputStream(entry).use { input ->
                            val head = ByteArray(8)
                            isMachO(head, input.readNBytes(head, 0, head.size))
                        }
                    }
                    .map { it.name }
                    .toList()
            }
            val helperEntries = machOEntries.filter { it in jarHelpers }
            val isKnownNative = { name: String ->
                (hasOnnx && name.startsWith(onnxPrefix)) ||
                    (hasSqlite && name.startsWith("natives/")) ||
                    name in skikoEntries ||
                    name in helperEntries
            }
            val unsupported = (nativeEntries + machOEntries).distinct().filterNot(isKnownNative)
            if (unsupported.isNotEmpty()) {
                error("${app.name}: un-audited native entries in ${jar.name}: ${unsupported.joinToString()}")
            }
            if (helperEntries.isNotEmpty()) {
                helperEntries.forEach { name ->
                    if (!app.resolve("Contents/Helpers/$name").isFile) {
                        error("${app.name}: ${jar.name} carries $name but Contents/Helpers/$name is missing — did embedJarHelpers run?")
                    }
                }
                rewriteJar(jar) { it in helperEntries }
            }

            if (hasOnnx) {
                val target = app.resolve("Contents/app/native/onnxruntime")
                ZipFile(jar).use { zip ->
                    extract(zip, "${onnxPrefix}osx-aarch64/libonnxruntime.dylib", target.resolve("libonnxruntime.dylib"))
                    extract(zip, "${onnxPrefix}osx-aarch64/libonnxruntime4j_jni.dylib", target.resolve("libonnxruntime4j_jni.dylib"))
                }
                nativeProperties["onnxruntime.native.path"] = "native/onnxruntime"
                rewriteJar(jar) { it.startsWith(onnxPrefix) }
            }
            if (hasSqlite) {
                val target = app.resolve("Contents/app/native/sqlite/libsqliteJni.dylib")
                ZipFile(jar).use { zip -> extract(zip, sqliteEntry!!, target) }
                nativeProperties["androidx.sqlite.driver.bundled.path"] = "native/sqlite"
                rewriteJar(jar) { it.startsWith("natives/") }
            }
            if (skikoEntries.isNotEmpty()) {
                if (!skikoArm64.isFile) {
                    error("${app.name}: ${jar.name} contains Skiko native code but the extracted arm64 dylib is missing")
                }
                rewriteJar(jar) { it in skikoEntries }
            }
        }

        // A failed signing attempt leaves the generated bundle staged. Recognize that state so
        // retrying the same archive command does not depend on createDistributable rebuilding an
        // already-up-to-date bundle.
        if (appContents.resolve("native/onnxruntime/libonnxruntime.dylib").isFile &&
            appContents.resolve("native/onnxruntime/libonnxruntime4j_jni.dylib").isFile
        ) {
            nativeProperties["onnxruntime.native.path"] = "native/onnxruntime"
        }
        if (appContents.resolve("native/sqlite/libsqliteJni.dylib").isFile) {
            nativeProperties["androidx.sqlite.driver.bundled.path"] = "native/sqlite"
        }

        if (nativeProperties.isNotEmpty()) {
            val cfgs = appContents.listFiles { file -> file.isFile && file.extension == "cfg" }.orEmpty()
            if (cfgs.isEmpty()) error("${app.name}: no launcher .cfg under $appContents")
            cfgs.forEach { cfg ->
                val lines = cfg.readLines()
                    .filterNot { line -> nativeProperties.keys.any { key -> line.startsWith("java-options=-D$key=") } }
                    .toMutableList()
                val section = lines.indexOf("[JavaOptions]")
                if (section < 0) error("${app.name}: ${cfg.name} has no [JavaOptions] section")
                nativeProperties.entries.forEachIndexed { index, (key, relativePath) ->
                    lines.add(section + 1 + index, "java-options=-D$key=\$APPDIR/$relativePath")
                }
                cfg.writeText(lines.joinToString("\n") + "\n")
            }
        }
        logger.lifecycle(
            "${app.name}: staged ${nativeProperties.size} bundle-local native paths and rewrote " +
                "$rewrittenJars jar(s) for ${if (masSandbox) "MAS" else "the Developer ID release"}"
        )
    }
}

// ── The one-stop install build ────────────────────────────────────────────────
//
// WHY THIS EXISTS. `signDesktopApp` is only *registered* by this script; until now nothing
// chained it, so the ordinary packaging path (`createDistributable` / `packageDmg`) produced an
// **ad-hoc** bundle. `:control-desktop` alone had an umbrella (`packageControlApp`) that pulled
// signing in; every other desktop app therefore shipped unattestable, and the P1 peer-credential
// gate refused it — silently, because an unattested peer resolves to no app and registration is a
// deliberate no-op (`desktop-ipc-client-identity.md` D3). 2026-08-10: that is exactly how
// `/Applications/TenshuDrive.app` came to be `Signature=adhoc, TeamIdentifier=not set`, which
// presented as an endless loading spinner plus a missing tray entry, and read like a sync bug.
//
// ⚠⚠ Running `signDesktopApp` ON ITS OWN is not equivalent, and looks like it is: it depends on
// `createDistributable`, which regenerates the bundle **without** `Contents/Helpers/` (those are
// per-module `embed*` tasks), and still reports `Signed … → TeamIdentifier=…` — just `(0 nested)`.
// The nested count is the only tell. Depending on both, in that order, is the whole job here.
// See `docs/tech-debt.md` "A locally rebuilt desktop bundle is ad-hoc signed".
tasks.register("packageDesktopApp") {
    group = "macos"
    description =
        "Builds this module's .app with its helpers embedded AND a real Apple identity, ready to " +
            "copy to /Applications. Add -Ptenshu.masSandbox=true and a provisioning profile for " +
            "the Mac App Store packaging path. Fails if the result would not be attestable by the " +
            "desktop IPC gate (desktop-ipc-client-identity.md P0)."

    // `signDesktopApp` already `dependsOn(createDistributable)` and `mustRunAfter` every `embed*`,
    // so naming the embeds here fixes *inclusion*; ordering is already correct.
    dependsOn("signDesktopApp")
    dependsOn(tasks.matching { it.name.startsWith("embed") && it.name !in optInEmbedTasks(project) })

    val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    val teamId = resolveSigningValue(project, "tenshu.teamId", "TENSHU_TEAM_ID")
    val identity = resolveSigningValue(project, "tenshu.signingIdentity", "TENSHU_SIGNING_IDENTITY")
    val masSandbox = project.findProperty("tenshu.masSandbox")?.toString()?.toBoolean() == true
    val appDir = layout.buildDirectory.dir("compose/binaries/main/app")
    val moduleName = project.name

    onlyIf { isMac }

    doLast {
        val root = appDir.get().asFile
        val app = root.listFiles()?.firstOrNull { it.name.endsWith(".app") && it.isDirectory }
            ?: error("No .app found in $root — did createDistributable run?")

        // Fail, don't warn (docs/tech-debt.md fix #2). `signDesktopApp` self-skips with a
        // `logger.warn` when signing is unconfigured — invisible in thousands of lines of Gradle
        // output — and the build would otherwise still print a cheerful "ready →". Attestability is
        // the entire premise of the P0 gate, so an unattestable bundle is a broken build, not a
        // degraded one. This is the task's LAST word, so it cannot be scrolled past.
        if (teamId.isNullOrBlank() || identity.isNullOrBlank()) {
            error(
                "packageDesktopApp ($moduleName): signing is not configured, so ${app.name} is " +
                    "ad-hoc signed and the desktop IPC gate will refuse it — the app will show no " +
                    "tray entry and its UI will hang on a loading state.\n" +
                    "Set tenshu.teamId + tenshu.signingIdentity in local.properties (or -P, or " +
                    "TENSHU_TEAM_ID / TENSHU_SIGNING_IDENTITY).\n" +
                    "To build an unsigned bundle deliberately, run createDistributable instead."
            )
        }

        val out = ProcessBuilder("codesign", "-dv", app.absolutePath)
            .redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText()
        val stamped = out.lineSequence().firstOrNull { it.startsWith("TeamIdentifier=") }
            ?.removePrefix("TeamIdentifier=")?.trim()
        if (stamped != teamId) {
            error(
                "packageDesktopApp ($moduleName): ${app.name} carries TeamIdentifier " +
                    "'${stamped ?: "absent"}', expected '$teamId'. The desktop IPC gate would " +
                    "reject this bundle.\n$out"
            )
        }

        // The nested count is the only signal that the helpers actually made it in — a bundle
        // signed with 0 nested when it should have 3 is the "tray is gone" failure, pre-shipped.
        val nested = listOf("Contents/Helpers", "Contents/PlugIns")
            .map { app.resolve(it) }
            .filter { it.isDirectory }
            .flatMap { it.listFiles()?.toList().orEmpty() }
        logger.lifecycle("${app.name} ready → ${app.absolutePath}")
        logger.lifecycle("  TeamIdentifier=$stamped · ${nested.size} nested binaries embedded")
        logger.lifecycle(
            "Install: rm -rf /Applications/${app.name} && cp -R \"${app.absolutePath}\" /Applications/"
        )
    }
}
