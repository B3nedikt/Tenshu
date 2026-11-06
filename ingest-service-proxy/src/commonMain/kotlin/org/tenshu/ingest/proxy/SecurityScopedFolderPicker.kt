package org.tenshu.ingest.proxy

/**
 * Platform capability used by the ingest proxy's interactive folder-pick operation.
 * The implementation is supplied only by desktop Control; other hosts return an empty
 * result rather than pretending they can create a security-scoped grant.
 */
fun interface SecurityScopedFolderPicker {
    suspend fun pick(): String?
}
