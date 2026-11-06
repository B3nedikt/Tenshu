package org.tenshu.inference.proxy.coreml

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.autoreleasepool
import org.tenshu.inference.proxy.PREFLIGHT_REVISION
import org.tenshu.inference.proxy.PreflightKey
import org.tenshu.inference.proxy.RuntimeFingerprint
import org.tenshu.inference.proxy.catalog.InstalledModelData
import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.catalog.ModelEntryData
import org.tenshu.inference.proxy.onnx.VisionModel
import platform.Foundation.NSBundle
import platform.Foundation.NSProcessInfo

/**
 * What this build, on this OS, would make a vector with (`ios-on-device-inference.md` §3.2).
 *
 * ⚠ The digests come from the install record, so a replaced archive changes the answer the moment
 * the store commits it. The other three are constants of the build and the OS.
 */
fun iosRuntimeFingerprint(
    installed: List<InstalledModelData>,
    osBuild: String = currentOsBuild(),
): RuntimeFingerprint {
    val checkpoint: InstalledModelData? =
        installed.firstOrNull { it.modelId == VisionModel.PinnedSigLip.modelId }
    return RuntimeFingerprint(
        visionArchiveSha256 = checkpoint?.digestOf(ModelArtifactRole.VISION),
        textArchiveSha256 = checkpoint?.digestOf(ModelArtifactRole.TEXT),
        computeUnits = CoreMlComputeUnits.SHIPPED.name,
        osBuild = osBuild,
        preprocessingRevision = IOS_PREPROCESSING_REVISION,
    )
}

/**
 * What a preflight verdict on this build, this OS and this install is an answer about
 * ([PreflightKey]). ⚠ Readiness's key; the custody takes [iosRuntimeFingerprint] and nothing wider.
 */
fun iosPreflightKey(
    installed: List<InstalledModelData>,
    appBuild: String = currentAppBuild(),
    osBuild: String = currentOsBuild(),
): PreflightKey = PreflightKey(
    runtime = iosRuntimeFingerprint(installed, osBuild),
    vocabularySha256 = installed.firstOrNull { it.modelId == VisionModel.PinnedSigLip.modelId }
        ?.digestOf(ModelArtifactRole.VOCABULARY),
    appBuild = appBuild,
    preflightRevision = PREFLIGHT_REVISION,
)

/**
 * The [PreflightKey] [entry] would have **once installed** on this build and OS: the key
 * [iosPreflightKey] reads from the install record after a commit of exactly that entry (the
 * record carries the entry's digests). ⚠ What lets a Models screen ask whether a download would
 * be answered at once, before 743 MB are fetched (§14.5 item 0).
 */
fun iosPreflightKeyOf(
    entry: ModelEntryData,
    appBuild: String = currentAppBuild(),
    osBuild: String = currentOsBuild(),
): PreflightKey {
    fun digestOf(role: String): String? = entry.artifacts.firstOrNull { it.role == role }?.sha256
    return PreflightKey(
        runtime = RuntimeFingerprint(
            visionArchiveSha256 = digestOf(ModelArtifactRole.VISION),
            textArchiveSha256 = digestOf(ModelArtifactRole.TEXT),
            computeUnits = CoreMlComputeUnits.SHIPPED.name,
            osBuild = osBuild,
            preprocessingRevision = IOS_PREPROCESSING_REVISION,
        ),
        vocabularySha256 = digestOf(ModelArtifactRole.VOCABULARY),
        appBuild = appBuild,
        preflightRevision = PREFLIGHT_REVISION,
    )
}

/**
 * `1.0 (1)`: the bundle's marketing version and build. A process with no bundle has neither.
 *
 * ⚠⚠ Constant across every build today: `photos-ios/project.yml` sets no version, so xcodegen
 * writes `1.0` and `1` (`ios-drive-parity.md` records the same of Drive). It tells two builds
 * apart only once shipped builds are numbered; see [PreflightKey.appBuild].
 */
@OptIn(BetaInteropApi::class)
private fun currentAppBuild(): String = autoreleasepool {
    val bundle: NSBundle = NSBundle.mainBundle
    val version: String? = bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
    val build: String? = bundle.objectForInfoDictionaryKey("CFBundleVersion") as? String
    "${version ?: "?"} (${build ?: "?"})"
}

private fun InstalledModelData.digestOf(role: String): String? =
    artifacts.firstOrNull { it.role == role }?.sha256

/** `Version 27.0 (Build 24A434)`: the build is what a new Core ML compiler arrives with. */
@OptIn(BetaInteropApi::class)
private fun currentOsBuild(): String =
    autoreleasepool { NSProcessInfo.processInfo.operatingSystemVersionString }
