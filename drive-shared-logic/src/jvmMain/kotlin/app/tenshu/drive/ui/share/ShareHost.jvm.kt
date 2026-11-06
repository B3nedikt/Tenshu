package app.tenshu.drive.ui.share

/**
 * JVM (desktop + Android) actual: a deployment can point share links at a different web
 * origin via the `TENSHU_SHARE_HOST` env var or the `-Dtenshu.shareHost` system property.
 * Both are normally unset, so links fall back to the apex.
 *
 * (A production Android release would more idiomatically bake this via a BuildConfig field;
 * the env/property read is the interim seam shared with desktop until a release variant +
 * the KMP-library plugin's buildConfig support are wired.)
 */
internal actual fun platformShareHostOverride(): String? =
    System.getenv("TENSHU_SHARE_HOST")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("tenshu.shareHost")?.takeIf { it.isNotBlank() }
