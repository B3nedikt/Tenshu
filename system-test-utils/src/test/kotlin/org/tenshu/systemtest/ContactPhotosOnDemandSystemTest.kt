package org.tenshu.systemtest

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.tenshu.contact.ContactService
import org.tenshu.contact.ContactSource
import org.tenshu.contact.ContentId
import org.tenshu.contact.client.FullCardRead
import org.tenshu.contact.client.contactService
import org.tenshu.contact.client.fullVcard
import org.tenshu.contact.common.photoRefsIn
import org.tenshu.contact.importContact
import org.tenshu.contact.proxy.contactPhotosPath
import org.tenshu.contact.rawVcard
import org.tenshu.contact.sourceKey
import org.tenshu.contact.test.ContactPhotoFixtures
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.startPairing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * v2 contacts (`contacts-app.md` §9.10) across two devices — the twin of
 * [WorkoutStreamsOnDemandSystemTest]:
 *
 *  - the host imports a card whose 1 MiB portrait would make a row the iOS store refuses;
 *  - the joiner converges on a **small** `ContactSource` row whose card carries the placeholder
 *    and no photo bytes;
 *  - the joiner's export (`fullVcard`, a Maybe `resolve()` projecting `ContactSource.Photos`)
 *    fetches the sealed chunks on demand and reassembles the card **byte for byte**.
 *
 * Every name is made up.
 */
class ContactPhotosOnDemandSystemTest {

    @Test fun joinerExportsTheFullCardWithItsContentPlanePhoto() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client(deviceId = "host", role = TestClientRole.RealmHost, pollingIntervalMs = 1_000)
            val joiner = system.client(deviceId = "joiner", role = TestClientRole.JoiningDevice, pollingIntervalMs = 1_000)

            host.bootstrapAsHost(deviceName = "Host Tablet")
            host.tenshuClient.syncService.startPairing()
            val parsed = PairingUri.parse(host.syncManager.getPairingString()!!)
            joiner.tenshuClient.syncService.initiatePairing(
                ownerId = parsed.ownerId,
                pairingSecret = parsed.secret,
                deviceName = "Joiner Phone",
                otherDevicePublicKey = parsed.hostPairingPublicKey,
            )
            val request = host.tenshuClient.syncService.getPendingPairingRequests()
                .let { it.valueOrFail("getPendingPairingRequests")!!.toList().single() }
            host.tenshuClient.syncService.approvePairingRequest(request.deviceId.get())
            system.advanceUntil(timeout = 30.seconds) {
                val status = joiner.tenshuClient.syncService.getPairingStatus(
                    pairingSecret = parsed.secret,
                    otherDevicePublicKey = parsed.hostPairingPublicKey,
                )
                status is TResult.Success && status.value?.get() == true
            }

            val card = ContactPhotoFixtures.card(
                ContactPhotoFixtures.photoLine(ContactPhotoFixtures.image(1024 * 1024)),
                name = "Oskar Brandt",
            )
            assertTrue(host.tenshuClient.contactService.importContact(card) is TResult.Success)

            // The small row converges eagerly, placeholder and all…
            var row: ContactSource? = null
            system.advanceUntil(timeout = 30.seconds) {
                row = sources(joiner).singleOrNull()
                row != null
            }
            val stored = row!!.rawVcard.get()
            assertEquals(1, photoRefsIn(stored).size, "the joiner's card names its photo")
            assertTrue(stored.encodeToByteArray().size < 16 * 1024, "a ${stored.length}-char row")
            val contentId = (
                joiner.executionContext.database.getData(
                    DataId.StringDataId.create(type = ContactSource.ID, keyValue = row!!.sourceKey.get()),
                    asEntity = true,
                ) as Entity<*>
                ).get(ContactSource.ContentId)?.get()
            assertTrue(!contentId.isNullOrBlank(), "the row synced its content id")
            assertNull(
                joiner.fileDatabase.readBytes(contactPhotosPath(contentId!!)),
                "the photo is fetched on demand, never pushed into every receiver",
            )

            // …and the export fetches the photo on demand — re-issued until it lands, as a user
            // retrying would (the manifest is pushed after the row, ADR 0015).
            var full: FullCardRead = FullCardRead.PhotosUnavailable
            try {
                system.advanceUntil(timeout = 30.seconds) {
                    full = joiner.tenshuClient.contactService.fullVcard(row!!.sourceKey.get(), stored)
                    full is FullCardRead.Ok
                }
            } catch (e: TimeoutCancellationException) {
                fail("the joiner never assembled the full card: $full")
            }
            assertEquals(FullCardRead.Ok(card), full, "byte-exact across devices")
        } finally {
            system.shutdown()
        }
    }

    private fun sources(client: TestClient): List<ContactSource> {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = client.executionContext.context,
                type = TList.ID,
                relationId = ContactService.Relations.SOURCES,
            ),
            isEntity = false,
            elementSchemaId = ContactSource.ID,
        )
        return client.executionContext.database.getArrayData(ref)
            ?.mapNotNull { it as? ContactSource }
            ?.toList()
            .orEmpty()
    }
}
