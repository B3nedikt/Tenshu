package app.tenshu.contacts.di

import app.tenshu.contacts.di.contactsSchemaConfigs
import org.tenshu.core.common.assertClientGraphRegisters
import org.tenshu.contact.ContactService
import org.tenshu.preference.PreferenceService
import org.tenshu.storage.StorageService
import org.tenshu.sync.SyncService
import kotlin.test.Test

/**
 * Every service Contacts reaches is registered in its client graph.
 *
 * ⚠⚠ The failure this guards is **silent**: `getService` errors on an unregistered schema and the
 * app's repositories catch, so a missing registration renders as absent data rather than as a
 * crash. The announce gate cannot see it either — it compares only services the client declares.
 * `assertClientGraphRegisters` carries the full reasoning and Photos' §18.4 is the worked example.
 *
 * ⚠ [reached] is grepped, not derived — the compiler cannot connect a Gradle dependency to a
 * runtime list two modules away. **When a repository reaches a new service, add it here and to
 * `contactsSchemaConfigs`.**
 */
class ContactsClientGraphTest {

    private val reached = mapOf(
        "people, identities, invites and the handshake" to ContactService.ID,
        "the owner's display name" to PreferenceService.ID,
        "the realm pull fence behind the update banner" to SyncService.ID,
        "the realm storage banner" to StorageService.ID,
    )

    @Test
    fun everyServiceTheAppReachesIsRegistered() =
        assertClientGraphRegisters("Contacts", contactsSchemaConfigs, reached)
}
