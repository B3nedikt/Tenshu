package org.tenshu.core.common.proxy.db

import org.tenshu.core.common.SyntheticGenericHolder
import org.tenshu.core.common.SyntheticListHolder
import org.tenshu.core.common.SyntheticParent
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.number.toTInteger
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The store-equality predicate of `core-deterministic-invalidation.md` §8: a save is redundant only
 * when every entry it writes is already stored with identical content. Absent or unequal is news —
 * which is what makes a rehydrating store's first sight of its own history, and a peer's write,
 * non-redundant by construction.
 */
class SaveDataReportingChangeTest {

    @Test
    fun firstSave_isAChange_identicalResave_isNot_editedResave_is() {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val db = proxy.database

        assertTrue(
            db.saveDataReportingChange(SyntheticTestData.Factory.create(proxy.tContext, "row", 1L)),
            "an absent row is news — the rehydration case",
        )
        assertFalse(
            db.saveDataReportingChange(SyntheticTestData.Factory.create(proxy.tContext, "row", 1L)),
            "a fresh instance with identical content is redundant — the echo case",
        )
        assertTrue(
            db.saveDataReportingChange(SyntheticTestData.Factory.create(proxy.tContext, "row", 2L)),
            "a changed value is news — the peer-write case",
        )
    }

    /**
     * The answer is the OR over every entry one save writes. Growing a list leaves the holder's own
     * row byte-identical (the field stores the array row's hash), so only the array row and the new
     * member's row carry the change.
     *
     * ⚠ A changed *identified* child reached through its parent is **not** news, and correctly so:
     * `DbEntryMapper` writes such a child create-only, so the store does not change.
     */
    @Test
    fun aGrownList_isAChange_althoughTheHolderRowIsNot() {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val db = proxy.database
        fun holder(vararg names: String) = SyntheticListHolder.Factory.create(
            proxy.tContext,
            "h",
            names.map { SyntheticTestData.Factory.create(proxy.tContext, it, 1L) },
        )

        assertTrue(db.saveDataReportingChange(holder("a")))
        assertFalse(db.saveDataReportingChange(holder("a")), "an identical tree is redundant")
        assertTrue(db.saveDataReportingChange(holder("a", "b")), "a new member is news")

        fun parent(childValue: Long) = SyntheticParent.Factory.create(
            proxy.tContext,
            "p",
            SyntheticTestData.Factory.create(proxy.tContext, "c", childValue),
        )
        assertTrue(db.saveDataReportingChange(parent(1L)))
        assertFalse(
            db.saveDataReportingChange(parent(2L)),
            "an identified child is written create-only, so a changed one leaves the store as it was",
        )
    }

    @Test
    fun aGenericFieldReSave_comparesItsPayload() {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val db = proxy.database
        fun holder(value: Long) = SyntheticGenericHolder.Factory.create(
            proxy.tContext,
            name = "pref",
            value = value.toTInteger(proxy.tContext),
        )

        assertTrue(db.saveDataReportingChange(holder(42L)))
        assertFalse(db.saveDataReportingChange(holder(42L)), "an identical GenericValue is redundant")
        assertTrue(db.saveDataReportingChange(holder(43L)), "a changed GenericValue payload is news")
    }

    @Test
    fun byteArrays_compareByContent_notByReference() {
        fun entry(bytes: ByteArray) = ObjectStore.DbEntry(
            type = "t",
            dataIdHash = 1L,
            fields = listOf(ObjectStore.DbValue.ByteArrayValue(bytes)),
            properties = mapOf("p" to ObjectStore.DbValue.GenericValue("g", ObjectStore.DbValue.ByteArrayValue(bytes))),
        )
        // `==` is the trap: the data class compares the ByteArray by reference.
        assertFalse(entry(byteArrayOf(1, 2)) == entry(byteArrayOf(1, 2)))
        assertTrue(entry(byteArrayOf(1, 2)).sameContentAs(entry(byteArrayOf(1, 2))))
        assertFalse(entry(byteArrayOf(1, 2)).sameContentAs(entry(byteArrayOf(1, 3))))
        assertFalse(entry(byteArrayOf(1, 2)).sameContentAs(null), "absent is never the same")
    }
}
