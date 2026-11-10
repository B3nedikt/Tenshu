import java.security.MessageDigest

// ── MSIX packaging for the Windows desktop apps ───────────────────────────────
//
// `docs/proposals/desktop-windows-port.md` §5.2 (D1: the Microsoft Store is the first channel).
// Applied from a `*-desktop` module AFTER it sets, in `extra`:
//
//     extra["tenshu.msix.identityName"] = "Tenshu.Drive"     // Identity/@Name — the peer gate's package name
//     extra["tenshu.msix.displayName"]  = "Tenshu Drive"
//     extra["tenshu.msix.appId"]        = "Drive"            // Application/@Id
//     extra["tenshu.msix.executable"]   = "TenshuDrive"      // nativeDistributions.packageName
//     extra["tenshu.msix.version"]      = "1.0.1"            // the suite version (3 parts; a 4th is added)
//     apply(from = rootProject.file("gradle/desktop-msix.gradle.kts"))
//
// `packageMsix` lays the jpackage app-image out as an MSIX (AppxManifest.xml + assets), packs it with
// `makeappx` and, when a certificate is configured, signs it with `signtool`. Windows hosts only.
//
// ⚠⚠ This is the LOCAL, SIDE-LOAD build: the W2 peer-gate test and the W3 toast/reminder test need a
// process with package identity, which a `:run` never has. The Store build differs in one input — the
// Publisher Partner Center assigns — and is not signed by us at all (the Store re-signs).
//
// Logos: `<module>/icons/windows/msix/`, committed, from scripts/windows-app-icons.py (ADR 0026 §6b). Packed with a
// `resources.pri` so Windows picks the right size per place and DPI. ⚠ No fallback: a module without them fails
// here rather than shipping without logos.
//
// Inputs, each `-P<name>` → `$ENV`:
//  - tenshu.msix.publisher / TENSHU_MSIX_PUBLISHER — the manifest Publisher, which MUST equal the signing
//    certificate's subject. Default `CN=Tenshu Local Test`, the subject `scripts/windows/msix-test-cert.ps1` mints.
//  - tenshu.windowsSdkBin / TENSHU_WINDOWS_SDK_BIN — a directory holding makeappx.exe and signtool.exe.
//    Otherwise the newest `Windows Kits\10\bin\<ver>\x64`, then the NuGet `microsoft.windows.sdk.buildtools`.
//  - tenshu.msix.certPfx / TENSHU_MSIX_CERT_PFX (+ tenshu.msix.certPassword / TENSHU_MSIX_CERT_PASSWORD) —
//    default `%USERPROFILE%\.tenshu-dev\msix-test.pfx` if it exists. No certificate ⇒ an unsigned .msix,
//    which Windows will not install; the task says so.
//
// The task prints the package's PUBLISHER ID — the value Control's peer gate pins. Pass it to Control's
// build as `-Ptenshu.windowsPublisherIds=<id>` so the side-loaded Control admits the side-loaded apps.
//
// ⚠ Configuration cache: every value `doLast` touches is declared inside the `register` block (the rule
// gradle/desktop-app-signing.gradle.kts documents).
//
// Optional, Control only: extra["tenshu.msix.trayAgentProject"] = <path to TrayAgent.csproj> publishes the
// native tray agent (desktop-windows-port.md §3.9, D5) with `dotnet publish` and ships it inside the package as
// `tray-agent\`, where Control looks for it (`WindowsTrayAgent`). `dotnet`: -Ptenshu.dotnet / TENSHU_DOTNET,
// else `C:\Program Files\dotnet\dotnet.exe`, else `dotnet` on PATH.

val trayAgentProject = (project.extra.properties["tenshu.msix.trayAgentProject"] as String?)?.let { project.file(it) }
val publishTrayAgent = trayAgentProject?.let { csproj ->
    tasks.register<Exec>("publishTrayAgent") {
        group = "windows"
        description = "Publishes the native Windows tray agent (dotnet publish) for packageMsix."
        val isWindowsHost = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        onlyIf { isWindowsHost }
        val outDir = layout.buildDirectory.dir("tray-agent").get().asFile
        val dotnet = ((project.findProperty("tenshu.dotnet") as String?) ?: System.getenv("TENSHU_DOTNET"))
            ?: File("C:/Program Files/dotnet/dotnet.exe").takeIf { it.isFile }?.path
            ?: "dotnet"
        inputs.files(fileTree(csproj.parentFile) { include("**/*.cs", "**/*.csproj", "**/*.manifest"); exclude("bin/**", "obj/**") })
        // The cat comes from the Android vector the project embeds; a redrawn mark must republish.
        inputs.file(project.rootProject.file("control/src/main/res/drawable/ic_launcher_monochrome.xml"))
        outputs.dir(outDir)
        // ⚠ From empty: `dotnet publish -o` adds to the folder, and packageMsix ships the whole folder, so a DLL a
        // previous publish left behind would go into the package.
        doFirst { outDir.deleteRecursively() }
        commandLine(dotnet, "publish", csproj.path, "-c", "Release", "-o", outDir.path, "-nologo", "-v", "quiet")
    }
}

tasks.register("packageMsix") {
    group = "windows"
    description = "Packages this app's jpackage app-image as a (side-loadable) MSIX — Windows hosts only."
    dependsOn("createDistributable")
    publishTrayAgent?.let { dependsOn(it) }
    val trayAgentDir = publishTrayAgent?.let { layout.buildDirectory.dir("tray-agent").get().asFile }
    trayAgentDir?.let { inputs.dir(it).optional() }
    // The agent is framework-dependent on the Windows App SDK, so the package must declare the runtime
    // ("Name|MinVersion", from the module; it must match the SDK version the csproj pins).
    val windowsAppRuntime = (project.extra.properties["tenshu.msix.windowsAppRuntime"] as String?)
        ?.takeIf { trayAgentDir != null }?.split("|")
    inputs.property("windowsAppRuntime", windowsAppRuntime?.joinToString("|").orEmpty())

    val isWindowsHost = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    onlyIf { isWindowsHost }

    fun setting(property: String, env: String): String? =
        (project.findProperty(property) as String?)?.takeIf { it.isNotBlank() }
            ?: System.getenv(env)?.takeIf { it.isNotBlank() }

    val identityName = project.extra["tenshu.msix.identityName"] as String
    val displayName = project.extra["tenshu.msix.displayName"] as String
    val appId = project.extra["tenshu.msix.appId"] as String
    val executable = project.extra["tenshu.msix.executable"] as String
    val version = (project.extra["tenshu.msix.version"] as String).let { if (it.count { c -> c == '.' } == 2) "$it.0" else it }
    val publisher = setting("tenshu.msix.publisher", "TENSHU_MSIX_PUBLISHER") ?: "CN=Tenshu Local Test"
    val sdkBinOverride = setting("tenshu.windowsSdkBin", "TENSHU_WINDOWS_SDK_BIN")
    val userHome = System.getProperty("user.home")
    val certPfx = setting("tenshu.msix.certPfx", "TENSHU_MSIX_CERT_PFX")
        ?: File(userHome, ".tenshu-dev/msix-test.pfx").takeIf { it.isFile }?.absolutePath
    // The default matches scripts/windows/msix-test-cert.ps1 — a throwaway local cert, not a secret.
    val certPassword = setting("tenshu.msix.certPassword", "TENSHU_MSIX_CERT_PASSWORD") ?: "tenshu-local-test"
    val appImage = layout.buildDirectory.dir("compose/binaries/main/app/$executable").get().asFile
    val layoutDir = layout.buildDirectory.dir("msix/layout").get().asFile
    val msixFile = layout.buildDirectory.file("msix/$executable.msix").get().asFile
    val msixLogos = project.file("icons/windows/msix")
    val priDir = layout.buildDirectory.dir("msix/pri").get().asFile
    inputs.dir(msixLogos).optional()

    inputs.property("identity", "$identityName|$publisher|$version|$appId|$displayName")
    // ⚠⚠ The app-image is an input too. Without it Gradle called this task UP-TO-DATE after a code change
    // and kept the old package — a side-loaded Control without its gate log, a Drive without its Windows
    // server check, and an afternoon spent debugging binaries that were not the source (2026-11-10).
    inputs.dir(appImage).optional()
    inputs.property("certPfx", certPfx.orEmpty())
    outputs.file(msixFile)

    doLast {
        // The derivation Windows uses for a package family name's publisher half. Self-checked against
        // Microsoft's own publisher before use, so it cannot silently drift from `MsixPublisherId`
        // (:windows-helpers, pinned by MsixPublisherIdTest) — the two must agree.
        fun publisherIdOf(value: String): String {
            val alphabet = "0123456789abcdefghjkmnpqrstvwxyz"
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_16LE))
            var bits = 0L
            for (i in 0 until 8) bits = (bits shl 8) or (digest[i].toLong() and 0xFF)
            return (0 until 13).joinToString("") { group ->
                val shift = 59 - 5 * group
                val v = if (shift >= 0) (bits ushr shift) and 0x1F else (bits shl -shift) and 0x1F
                alphabet[v.toInt()].toString()
            }
        }
        check(publisherIdOf("CN=Microsoft Corporation, O=Microsoft Corporation, L=Redmond, S=Washington, C=US") == "8wekyb3d8bbwe") {
            "publisher-id derivation is broken — it no longer reproduces Microsoft's known id"
        }

        val exe = File(appImage, "$executable.exe")
        check(exe.isFile) { "no app-image launcher at $exe — did createDistributable run for this OS?" }

        // ── Layout: the app-image as-is, plus the manifest and the assets it names ──
        layoutDir.deleteRecursively()
        appImage.copyRecursively(layoutDir)
        // The native tray agent, beside the launcher, where Control's `WindowsTrayAgent` looks for it.
        trayAgentDir?.let { dir ->
            check(File(dir, "tray-agent.exe").isFile) { "publishTrayAgent produced no tray-agent.exe in $dir" }
            dir.copyRecursively(File(layoutDir, "tray-agent"))
        }
        val assets = File(layoutDir, "Assets").apply { mkdirs() }

        // The logos, qualified by size (`.targetsize-N`, `.scale-N`); the manifest names the bare files and
        // resources.pri (built below) resolves them.
        val logos = msixLogos.listFiles { f -> f.isFile && f.name.endsWith(".png") }.orEmpty()
        for (base in listOf("Square44x44Logo", "Square150x150Logo", "StoreLogo")) {
            check(logos.any { it.name.startsWith("$base.") }) {
                "no $base in $msixLogos — run scripts/windows-app-icons.py (docs/adr/0026-app-icon-system.md §6b)"
            }
        }
        logos.forEach { it.copyTo(File(assets, it.name), overwrite = true) }

        fun xml(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
        // The tray agent posts Control's toasts (desktop-windows-port.md §3.9 T2). A click is delivered over COM to
        // the activator declared here, which Windows starts if the agent is not running. ⚠ The CLSID is the
        // package's toast activator identity: never change it, or toasts already in the notification center stop
        // answering clicks.
        // Start at login (desktop-windows-port.md §3.5) is the package's startup task, also aimed at the agent: it
        // puts the icon up and starts Control hidden. ⚠ Off until the user turns it on (`Enabled="false"`): the
        // Store expects consent, as the Mac App Store does. ⚠ TaskId is `LoginStart.TaskId`; never rename it.
        val toastActivator = "6E0B6C0B-3F1D-4C1F-9C61-2F0E7A9B5D41"
        val applicationExtensions = if (trayAgentDir == null) "" else """
                  <Extensions>
                    <com:Extension Category="windows.comServer">
                      <com:ComServer>
                        <com:ExeServer Executable="tray-agent\tray-agent.exe" DisplayName="${xml(displayName)}" Arguments="----AppNotificationActivated:">
                          <com:Class Id="$toastActivator" />
                        </com:ExeServer>
                      </com:ComServer>
                    </com:Extension>
                    <desktop:Extension Category="windows.toastNotificationActivation">
                      <desktop:ToastNotificationActivation ToastActivatorCLSID="$toastActivator" />
                    </desktop:Extension>
                    <desktop:Extension Category="windows.startupTask" Executable="tray-agent\tray-agent.exe" EntryPoint="Windows.FullTrustApplication">
                      <desktop:StartupTask TaskId="TenshuControl" Enabled="false" DisplayName="${xml(displayName)}" />
                    </desktop:Extension>
                  </Extensions>
        """.trimIndent().prependIndent(" ".repeat(18)).trimStart()
        // ⚠ Indented deeper than the manifest template below: that template is trimIndent()-ed AFTER this is
        // interpolated, so a shallower line here would lower its margin and push `<?xml` off column 0 — which
        // makes the manifest invalid XML.
        File(layoutDir, "AppxManifest.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <Package
              xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
              xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10"
              xmlns:rescap="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities"
              xmlns:com="http://schemas.microsoft.com/appx/manifest/com/windows10"
              xmlns:desktop="http://schemas.microsoft.com/appx/manifest/desktop/windows10"
              IgnorableNamespaces="uap rescap com desktop">
              <Identity Name="${xml(identityName)}" Publisher="${xml(publisher)}" Version="$version" ProcessorArchitecture="x64" />
              <Properties>
                <DisplayName>${xml(displayName)}</DisplayName>
                <PublisherDisplayName>Tenshu</PublisherDisplayName>
                <Logo>Assets\StoreLogo.png</Logo>
              </Properties>
              <Dependencies>
                <TargetDeviceFamily Name="Windows.Desktop" MinVersion="10.0.17763.0" MaxVersionTested="10.0.26100.0" />
            ${windowsAppRuntime?.let { (name, min) -> "    <PackageDependency Name=\"$name\" MinVersion=\"$min\" Publisher=\"CN=Microsoft Corporation, O=Microsoft Corporation, L=Redmond, S=Washington, C=US\" />" }.orEmpty()}
              </Dependencies>
              <Resources>
                <Resource Language="en-us" />
              </Resources>
              <Applications>
                <Application Id="${xml(appId)}" Executable="${xml(executable)}.exe" EntryPoint="Windows.FullTrustApplication">
                  <uap:VisualElements DisplayName="${xml(displayName)}" Description="${xml(displayName)}"
                    BackgroundColor="transparent" Square150x150Logo="Assets\Square150x150Logo.png" Square44x44Logo="Assets\Square44x44Logo.png" />
            $applicationExtensions
                </Application>
              </Applications>
              <Capabilities>
                <rescap:Capability Name="runFullTrust" />
              </Capabilities>
            </Package>
            """.trimIndent() + "\n",
        )

        // ── Tools ──
        // A tools directory must hold makeappx and makepri (signtool only when signing).
        fun hasTools(dir: File) = File(dir, "makeappx.exe").isFile && File(dir, "makepri.exe").isFile
        fun sdkBin(): File? {
            sdkBinOverride?.let { return File(it).takeIf(::hasTools) }
            val kits = File("C:/Program Files (x86)/Windows Kits/10/bin")
            kits.listFiles()?.map { File(it, "x64") }?.filter(::hasTools)?.maxByOrNull { it.path }?.let { return it }
            val nuget = File(userHome, ".nuget/packages/microsoft.windows.sdk.buildtools")
            return nuget.walkTopDown().maxDepth(5)
                .filter { it.isDirectory && it.name == "x64" && hasTools(it) }
                .maxByOrNull { it.path }
        }
        val bin = sdkBin() ?: error(
            "makeappx.exe + makepri.exe not found. Install the Windows SDK (or the NuGet package " +
                "Microsoft.Windows.SDK.BuildTools) or pass -Ptenshu.windowsSdkBin=<dir with makeappx.exe, makepri.exe " +
                "and signtool.exe>. Layout left at $layoutDir",
        )
        fun run(vararg command: String) {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "${File(command[0]).name} failed:\n$output" }
        }

        // resources.pri: without it Windows resolves only the manifest's literal file names, and none exists —
        // the qualified logos would all be invisible. Indexed from a copy holding only the manifest and the
        // logos, so the index does not catalogue every jar and runtime file of the app-image as a "resource".
        priDir.deleteRecursively()
        File(layoutDir, "AppxManifest.xml").copyTo(File(priDir, "project/AppxManifest.xml"))
        assets.copyRecursively(File(priDir, "project/Assets"))
        val priConfig = File(priDir, "priconfig.xml")
        run(File(bin, "makepri.exe").path, "createconfig", "/cf", priConfig.path, "/dq", "en-US", "/pv", "10.0.0", "/o")
        run(
            File(bin, "makepri.exe").path, "new", "/pr", File(priDir, "project").path, "/cf", priConfig.path,
            "/mn", File(priDir, "project/AppxManifest.xml").path, "/of", File(layoutDir, "resources.pri").path, "/o",
        )

        msixFile.delete()
        run(File(bin, "makeappx.exe").path, "pack", "/o", "/d", layoutDir.path, "/p", msixFile.path)

        val id = publisherIdOf(publisher)
        if (certPfx != null) {
            run(File(bin, "signtool.exe").path, "sign", "/fd", "SHA256", "/f", certPfx, "/p", certPassword, msixFile.path)
            logger.lifecycle("Signed MSIX: $msixFile")
        } else {
            logger.warn("UNSIGNED MSIX (no certificate configured): $msixFile — Windows will refuse to install it. See scripts/windows/msix-test-cert.ps1.")
        }
        logger.lifecycle("Package family name: ${identityName}_$id")
        logger.lifecycle("Publisher id for Control's peer gate: -Ptenshu.windowsPublisherIds=$id")
    }
}
