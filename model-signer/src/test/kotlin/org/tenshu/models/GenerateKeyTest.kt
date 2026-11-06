package org.tenshu.models

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⚠⚠ **`generate-key` writes a secret, so its file handling is the part that must not regress.**
 *
 * The first shipped version called `out.parentFile?.mkdirs()` and dropped the result. An
 * uncreatable path therefore surfaced as a `FileNotFoundException` from the *write* — naming the
 * key file, and saying nothing about the directory that was the actual problem. The runbook's own
 * `/secure/model-keys` example hit exactly that: on modern macOS `/` is a read-only filesystem, so
 * no user, root included, could create it.
 */
class GenerateKeyTest {

    @Test
    fun itCreatesTheKeyAndItsDirectory() {
        val key = File(tempDir(), "nested/model-signing.key")
        generateKey(mapOf("--out" to key.path))

        assertTrue(key.isFile, "the key must be written")
        assertTrue(key.parentFile.isDirectory, "a missing parent directory must be created, not fatal")
        // 32-byte seed as hex.
        assertEquals(64, key.readText().trim().length, "the seed must be 32 bytes of hex")
    }

    /**
     * ⚠⚠ Owner-only, set explicitly rather than inherited. A key directory that takes the shell's
     * umask is group- and world-readable on a default macOS setup, and "it depends on the
     * operator's environment" is not a property to leave a signing key with.
     */
    @Test
    fun theKeyAndItsDirectoryAreOwnerOnly() {
        val key = File(tempDir(), "keys/model-signing.key")
        generateKey(mapOf("--out" to key.path))

        val perms = java.nio.file.Files.getPosixFilePermissions(key.parentFile.toPath())
        assertEquals(
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            ),
            perms,
            "the key directory must be 0700",
        )
        val keyPerms = java.nio.file.Files.getPosixFilePermissions(key.toPath())
        assertTrue(
            java.nio.file.attribute.PosixFilePermission.GROUP_READ !in keyPerms &&
                java.nio.file.attribute.PosixFilePermission.OTHERS_READ !in keyPerms,
            "the key file must not be group- or world-readable, got $keyPerms",
        )
    }

    /**
     * ⚠⚠ A second `generate-key` over a live key would orphan every device that has the old public
     * half pinned, and the old seed is unrecoverable. Refusing is the only safe answer.
     */
    @Test
    fun itRefusesToOverwriteAnExistingKey() {
        val key = File(tempDir(), "model-signing.key")
        generateKey(mapOf("--out" to key.path))
        val original = key.readText()

        // ⚠ The CLI exits the process on refusal, so this asserts the property that matters and is
        // observable either way: the bytes on disk are untouched.
        runCatching { generateKey(mapOf("--out" to key.path)) }
        assertEquals(original, key.readText(), "an existing signing key must never be rewritten")
    }

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "model-signer-test-" + System.nanoTime())
            .apply { mkdirs() }
}
