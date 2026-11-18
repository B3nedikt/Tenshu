package org.tenshu.systemtest

import app.tenshu.calendar.data.CalendarDeviceIdentity
import app.tenshu.calendar.data.TenshuCalendarRepository
import app.tenshu.mia.data.TenshuConversationRepository
import app.tenshu.mia.model.MessageBody
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.tenshu.mia.MiaSource
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.set
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **Mia asks back, and the chat hears the answer** (`mia-query-layer.md` §25, CL-2), through the
 * real `TenshuConversationRepository` over a real proxy.
 *
 * ⚠⚠ What this pins is the client's routing, which no proxy test can see: while Mia's question
 * is open, the next send goes to `replyToMia`, never `askMia`. Sent to `askMia`, "9" would be planned
 * from nothing. And a chip answers only its own question.
 */
class MiaClarifySystemTest {

    /** Wednesday 15 July 2026, 12:00 UTC. */
    private val now = 1_784_116_800_000L
    private val tomorrow = LocalDate(2026, 7, 16)

    private class Fixture(val system: TestSystem, val mia: TenshuConversationRepository, val calendars: TenshuCalendarRepository)

    private suspend fun fixture(): Fixture {
        val system = TestSystem.builder().build()
        val phone = system.client(deviceId = "phone", role = TestClientRole.RealmHost)
        val calendars = TenshuCalendarRepository(
            client = phone.tenshuClient,
            deviceIdentity = CalendarDeviceIdentity { phone.syncManager.currentDeviceId },
            reachability = null,
        )
        calendars.ensureDefaultCalendar()
        phone.tenshuClient.preferenceService.set(MiaSource.CALENDAR.writePreference!!, true)
        val mia = TenshuConversationRepository(phone.tenshuClient, now = { now }, timeZoneId = { "UTC" })
        return Fixture(system, mia, calendars)
    }

    private suspend fun TenshuConversationRepository.last(): MessageBody = messages().first().last().body

    /** ⭐⭐ The whole turn through the real chat: question, typed answer, card, tap, row. */
    @Test
    fun aTypedAnswerGoesToTheOpenQuestion_andOnlyTheTapWrites() = runBlocking<Unit> {
        val f = fixture()
        try {
            f.mia.send("add dentist tomorrow")
            val asked = assertIs<MessageBody.Clarification>(f.mia.last())
            assertEquals("What time tomorrow?", asked.question)
            assertEquals(listOf("Morning 09:00", "Afternoon 14:00", "Evening 19:00"), asked.choices.map { it.label })

            // ⚠⚠ "9" reaches replyToMia. Through askMia it would be planned from nothing.
            f.mia.send("9")
            val card = assertIs<MessageBody.Confirmation>(f.mia.last())
            assertTrue(card.detail.contains("09:00"), card.detail)
            assertTrue(f.calendars.occurrencesOn(tomorrow).isEmpty(), "an answer never writes")

            f.mia.confirm(card.token)
            val event = f.calendars.occurrencesOn(tomorrow).single()
            assertEquals("dentist", event.title)
            assertEquals(LocalDateTime(2026, 7, 16, 9, 0), event.startLocal)

            // The answered question's chips are closed.
            val question = f.mia.messages().first().map { it.body }.filterIsInstance<MessageBody.Clarification>().single()
            assertFalse(question.open)
        } finally {
            f.system.shutdown()
        }
    }

    @Test
    fun aChipShowsItsLabel_andSendsItsReply() = runBlocking<Unit> {
        val f = fixture()
        try {
            f.mia.send("add dentist tomorrow")
            val asked = assertIs<MessageBody.Clarification>(f.mia.last())
            val evening = asked.choices.single { it.label == "Evening 19:00" }

            f.mia.answer(asked.token, evening.label, evening.reply)
            val thread = f.mia.messages().first()
            assertEquals("Evening 19:00", (thread[thread.size - 2].body as MessageBody.Text).text, "the user is shown the label")
            val card = assertIs<MessageBody.Confirmation>(thread.last().body)

            f.mia.confirm(card.token)
            assertEquals(LocalDateTime(2026, 7, 16, 19, 0), f.calendars.occurrencesOn(tomorrow).single().startLocal)
        } finally {
            f.system.shutdown()
        }
    }

    /** ⚠⚠ A chip from an older question answers nothing, least of all the newer question. */
    @Test
    fun aStaleChipCannotAnswerANewerQuestion() = runBlocking<Unit> {
        val f = fixture()
        try {
            f.mia.send("add dentist tomorrow")
            val first = assertIs<MessageBody.Clarification>(f.mia.last())
            f.mia.send("add dinner tomorrow")
            assertIs<MessageBody.Clarification>(f.mia.last())
            val before = f.mia.messages().first().size

            val chip = first.choices.first()
            f.mia.answer(first.token, chip.label, chip.reply)

            assertEquals(before, f.mia.messages().first().size, "the stale chip sent nothing")
            assertFalse(
                f.mia.messages().first().map { it.body }.filterIsInstance<MessageBody.Clarification>().first().open,
                "the superseded question renders closed",
            )
        } finally {
            f.system.shutdown()
        }
    }

    /** A new question while Mia is asking is answered as a new question, and closes the old one. */
    @Test
    fun aNewQuestionInsteadOfAnAnswerIsAnsweredAsOne() = runBlocking<Unit> {
        val f = fixture()
        try {
            f.mia.send("add dentist tomorrow")
            f.mia.send("what is on my calendar tomorrow")
            assertFalse(f.mia.last() is MessageBody.Confirmation)
            assertFalse(f.mia.last() is MessageBody.Clarification)
            // The next send is an ordinary question again, not a reply to a closed one.
            f.mia.send("add dentist tomorrow at 9")
            assertIs<MessageBody.Confirmation>(f.mia.last())
        } finally {
            f.system.shutdown()
        }
    }

    /** ⭐ CL-4 through the real chat: "20:00 instead?" replaces the card, and the new card writes. */
    @Test
    fun theCardsAlternativeReplacesIt_andTheNewCardWrites() = runBlocking<Unit> {
        val f = fixture()
        try {
            f.mia.send("add dinner tomorrow at 8")
            val first = assertIs<MessageBody.Confirmation>(f.mia.last())
            val alternative = first.alternatives.single()
            assertEquals("20:00 instead?", alternative.label)

            f.mia.alternative(first.token, alternative.label, alternative.reply)
            val second = assertIs<MessageBody.Confirmation>(f.mia.last())
            assertTrue(second.detail.contains("20:00"), second.detail)
            val old = f.mia.messages().first().map { it.body }.filterIsInstance<MessageBody.Confirmation>().first()
            assertTrue(old.replaced, "the 08:00 card is marked replaced")

            f.mia.confirm(second.token)
            assertEquals(LocalDateTime(2026, 7, 16, 20, 0), f.calendars.occurrencesOn(tomorrow).single().startLocal)
        } finally {
            f.system.shutdown()
        }
    }
}
