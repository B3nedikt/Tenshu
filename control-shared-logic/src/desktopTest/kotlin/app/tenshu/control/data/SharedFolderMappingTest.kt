package app.tenshu.control.data

import org.tenshu.identity.SharedRealmMembership
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure half of the SH-0 unstub: the join between a `SharedRealmMembership` row and
 * the two per-realm reads that give it a name and a member count. Extracted (like
 * `offlineMapsSnapshot`) so the rules that are easy to get *quietly* wrong — ownership,
 * and the two distinct nulls — are testable without a client or a relay.
 */
class SharedFolderMappingTest {

    private fun facts(
        realmId: String = "realm-1",
        role: String = SharedRealmMembership.Role.MEMBER,
        wrapperName: String? = "Holiday photos",
        memberCount: Int? = 3,
    ) = SharedRealmFacts(realmId, role, wrapperName, memberCount)

    @Test
    fun ownerRole_marksTheFolderAsOwnedBySelf() {
        assertTrue(sharedFolderSummary(facts(role = SharedRealmMembership.Role.OWNER)).ownerSelf)
        assertFalse(sharedFolderSummary(facts(role = SharedRealmMembership.Role.MEMBER)).ownerSelf)
    }

    @Test
    fun anUnsyncedWrapperRoot_leavesTheNameNull_ratherThanInventingOne() {
        // The joiner's first moments: the membership row is there, the wrapper-root
        // SharedFile that names the realm has not arrived. The UI owns the fallback.
        assertNull(sharedFolderSummary(facts(wrapperName = null)).name)
    }

    @Test
    fun anUnreadableRoster_leavesTheCountNull_neverZero() {
        // The distinction the whole nullable exists for: "we could not look" must not
        // render as "0 members", which is a wrong answer stated confidently.
        assertNull(sharedFolderSummary(facts(memberCount = null)).memberCount)
        assertEquals(0, sharedFolderSummary(facts(memberCount = 0)).memberCount)
    }

    @Test
    fun fieldsCarryThroughUnchanged() {
        val summary = sharedFolderSummary(
            facts(realmId = "realm-band", wrapperName = "Band practice", memberCount = 2)
        )
        assertEquals("realm-band", summary.realmId)
        assertEquals("Band practice", summary.name)
        assertEquals(2, summary.memberCount)
    }

    @Test
    fun ordering_putsOwnedFoldersFirst_thenSortsStablyByRealmId() {
        val rows = listOf(
            SharedFolderSummary("realm-z", "Z", 1, ownerSelf = false),
            SharedFolderSummary("realm-b", "B", 1, ownerSelf = true),
            SharedFolderSummary("realm-a", "A", 1, ownerSelf = false),
            SharedFolderSummary("realm-c", "C", 1, ownerSelf = true),
        )

        val ordered = orderSharedFolders(rows).map { it.realmId }

        // Owned first, and within each group a total order on realm id — the rows carry
        // no timestamp (SharedRealmMembership has no joinedAt), so realm id is what
        // keeps the list from reshuffling between reads.
        assertEquals(listOf("realm-b", "realm-c", "realm-a", "realm-z"), ordered)
    }

    @Test
    fun ordering_isStableAcrossRepeatedReads() {
        val rows = MockShareManager.SAMPLE_FOLDERS.shuffled()
        assertEquals(orderSharedFolders(rows), orderSharedFolders(rows.reversed()))
    }
}
