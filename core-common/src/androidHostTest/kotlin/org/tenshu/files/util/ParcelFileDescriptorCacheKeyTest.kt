package org.tenshu.files.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The isolation property behind `ParcelFileDescriptorCache`'s sender-scoped API
 * (2026-08-21 IPC audit item #2). The cache itself holds `ParcelFileDescriptor`s, which a host
 * test cannot mint — but the whole of the guarantee lives in the key derivation, and that is
 * pure.
 */
class ParcelFileDescriptorCacheKeyTest {

    private val uri = "content://media/external/images/media/123"

    @Test
    fun twoClientsSendingTheSameUriOccupyDifferentSlots() {
        assertNotEquals(
            ParcelFileDescriptorCache.senderScopedKey("app.tenshu.drive.android", uri),
            ParcelFileDescriptorCache.senderScopedKey("com.attacker.app", uri),
        )
    }

    @Test
    fun oneClientSendingTheSameUriTwiceReusesItsOwnSlot() {
        assertEquals(
            ParcelFileDescriptorCache.senderScopedKey("app.tenshu.drive.android", uri),
            ParcelFileDescriptorCache.senderScopedKey("app.tenshu.drive.android", uri),
        )
    }

    /**
     * ⚠ The load-bearing case, and the reason the separator is NUL rather than something
     * legible. The key half is caller-supplied and may contain anything — including the
     * separator — while the clientId half is a package name resolved from the calling uid and
     * cannot contain NUL. So the true sender is always the prefix up to the *first* separator,
     * and no crafted key reaches another client's slot.
     */
    @Test
    fun aCraftedKeyCannotForgeAnotherClientsSlot() {
        val victim = ParcelFileDescriptorCache.senderScopedKey("app.tenshu.drive.android", uri)
        val forged = ParcelFileDescriptorCache.senderScopedKey(
            "com.attacker.app",
            "\u0000app.tenshu.drive.android\u0000$uri",
        )

        assertNotEquals(victim, forged)
        assertEquals("com.attacker.app", forged.substringBefore('\u0000'))
    }
}
