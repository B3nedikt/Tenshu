package org.tenshu.sync.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The count behind the "Uploading…" tail (`ios-sync-activity-indicator.md` §6a.1). */
@OptIn(ExperimentalCoroutinesApi::class)
class InFlightTransfersTest {

    @Test
    fun aHandOff_isInFlight_untilItsCompletion() {
        val transfers = InFlightTransfers()
        transfers.handedOff("a")
        transfers.handedOff("b")
        assertEquals(2, transfers.current)

        transfers.completed("a")
        assertEquals(1, transfers.current)
        transfers.completed("b")
        assertEquals(0, transfers.current)
    }

    @Test
    fun aCompletionForARefNotHeld_isIgnored_andNeverGoesBelowZero() {
        val transfers = InFlightTransfers()
        transfers.handedOff("a")

        transfers.completed("stray")
        transfers.completed("a")
        transfers.completed("a")

        assertEquals(0, transfers.current)
        transfers.handedOff("b")
        assertEquals(1, transfers.current, "an earlier surplus completion did not go into debt")
    }

    @Test
    fun oneRefHandedOffTwice_isTwoTransfers() {
        val transfers = InFlightTransfers()
        transfers.handedOff("a")
        transfers.handedOff("a")
        transfers.completed("a")
        assertEquals(1, transfers.current)
        transfers.completed("a")
        assertEquals(0, transfers.current)
    }

    @Test
    fun seeding_countsWhatTheUploaderAlreadyHolds() {
        val transfers = InFlightTransfers()
        transfers.beginSeed()
        transfers.seed(listOf("a", "b", "b"))
        assertEquals(3, transfers.current)

        transfers.completed("b")
        transfers.completed("b")
        transfers.completed("a")
        assertEquals(0, transfers.current)
    }

    @Test
    fun aTransferThatCompletesWhileTheListIsOutstanding_isNotSeededAsLive() {
        val transfers = InFlightTransfers()
        transfers.beginSeed()
        // The list was read with "a" still running; its completion arrives before the list does.
        transfers.completed("a")
        transfers.seed(listOf("a", "b"))

        assertEquals(1, transfers.current, "only b: a finished and will never be reported again")
        transfers.completed("b")
        assertEquals(0, transfers.current)
    }

    @Test
    fun aHandOffMadeWhileTheListIsOutstanding_isCountedOnce_whetherOrNotTheListHasIt() {
        val inTheList = InFlightTransfers()
        inTheList.beginSeed()
        inTheList.handedOff("a")
        inTheList.seed(listOf("a"))
        assertEquals(1, inTheList.current)

        val notInTheList = InFlightTransfers()
        notInTheList.beginSeed()
        notInTheList.handedOff("a")
        notInTheList.seed(emptyList())
        assertEquals(1, notInTheList.current)
    }

    @Test
    fun aCompletionBeforeSeedingBegan_doesNotReduceTheSeed() {
        val transfers = InFlightTransfers()
        transfers.completed("a") // a replayed event from before this launch asked
        transfers.beginSeed()
        transfers.seed(listOf("a"))
        assertEquals(1, transfers.current)
    }

    @Test
    fun theCountFlow_emitsOnChangeOnly() = runTest {
        val transfers = InFlightTransfers()
        val seen = mutableListOf<Int>()
        backgroundScope.launch { transfers.count.collect { seen += it } }
        runCurrent()

        transfers.handedOff("a")
        runCurrent()
        transfers.completed("stray")
        runCurrent()
        transfers.completed("a")
        runCurrent()

        assertEquals(listOf(0, 1, 0), seen)
    }
}
