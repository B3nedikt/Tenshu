package org.tenshu.sdk.ios

import app.tenshu.contacts.data.RepositoryResult
import app.tenshu.contacts.data.TenshuContactsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ⚠⚠ The read that `ContactsLocalRuntimeTest.aWrittenContactIsReadBack` caught timing out in 2 of
 * 15 runs, asked often enough to fail every time the defect is back.
 *
 * `contacts()` is a `combine` of four relation reads, which subscribe **concurrently** on one
 * session. `StreamRegistry` kept its per-stream maps unguarded, so two registrations at once could
 * lose one's registration token; that stream then judged its own initial evaluation "ended" and
 * sent no snapshot, and the list never emitted — measured at 12 lost lists in 150 fresh
 * assemblies on the simulator (`core-client-resubscription.md` §4.9). The JVM guard is
 * `StreamRegistryConcurrentRegistrationTest`; this is the same defect on the platform it was seen
 * on, through the production graph.
 *
 * Half the reads go through the app's reachability wrapper and half bypass it, so a stall that
 * returns names which half it is in.
 */
class ContactsConcurrentSubscribeTest {

    @Test
    fun everyFreshAssemblyListsTheContactItWrote() = runBlocking {
        val stalls = mutableListOf<String>()
        repeat(ASSEMBLIES) { i ->
            val assembly = ContactsLocalRuntime.assemble(
                replicaDirectory = NSTemporaryDirectory() + "contacts-concurrent-subscribe-" + Random.nextLong().toString(16),
                secureStorage = InMemorySecureStorageDelegate(),
            )
            try {
                val written = assembly.contactsRepository.createContact("Mira Okonkwo")
                assertTrue(written is RepositoryResult.Success, "assembly $i: the write failed: $written")
                val bare = i % 2 == 0
                val repository = if (bare) TenshuContactsRepository(assembly.tenshuClient, reachability = null) else assembly.contactsRepository
                // ⚠ Not a sleep: the row arrives in milliseconds when the read is healthy; the
                // bound only turns a read that never emits into a named failure.
                val listed = withTimeoutOrNull(10_000) { repository.contacts().first { it.isNotEmpty() } }
                if (listed == null) stalls += "assembly $i (${if (bare) "bare repository" else "with reachability"})"
            } finally {
                // As `ContactsLocalRuntimeTest.closeAssemblies`: a teardown failure must not mask the read's.
                runCatching { assembly.close() }.onFailure { println("assembly $i close: $it") }
            }
        }
        if (stalls.isNotEmpty()) fail("${stalls.size} of $ASSEMBLIES fresh reads never listed the written contact: $stalls")
    }

    private companion object {
        /** At the measured ~8% per read, 100 reads miss a live defect with odds of about 1 in 4 000. */
        const val ASSEMBLIES = 100
    }
}
