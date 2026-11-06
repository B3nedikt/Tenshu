package app.tenshu.drive.ui.share

/**
 * Per-platform override for the Mode-A share-link viewer host
 * ([ShareLinkUrl.Companion.DEFAULT_HOST]). Returns `null` to accept the built-in apex
 * fallback. The JVM actual (covering desktop + Android, via the shared `jvmMain` source
 * set) reads `TENSHU_SHARE_HOST` / `-Dtenshu.shareHost`, mirroring the relay-URL config.
 */
internal expect fun platformShareHostOverride(): String?
