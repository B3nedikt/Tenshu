package app.tenshu.mia.data

import app.tenshu.mia.model.MessageBody
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MockConversationRepositoryTest {

    @Test
    fun sendAppendsUserMessageAndAKeywordRoutedReply() = runTest {
        val repository = MockConversationRepository()

        repository.send("Show me a photo from last summer")
        val afterPhoto = repository.messages().first()
        assertEquals(2, afterPhoto.size)
        assertTrue(afterPhoto[0].fromUser)
        assertIs<MessageBody.PhotoGrid>(afterPhoto[1].body)

        repository.send("Where was that café again?")
        assertIs<MessageBody.PlaceCard>(repository.messages().first().last().body)

        repository.send("Turn on the sofa lamp")
        assertIs<MessageBody.DeviceAck>(repository.messages().first().last().body)

        repository.send("What can you do?")
        assertIs<MessageBody.Text>(repository.messages().first().last().body)
    }

    @Test
    fun blankInputIsIgnored() = runTest {
        val repository = MockConversationRepository()
        repository.send("   ")
        assertTrue(repository.messages().first().isEmpty())
    }

    @Test
    fun historyIsSeededWithRichResultSessions() = runTest {
        val history = MockConversationRepository().history().first()
        assertEquals(2, history.size)
        assertTrue(history.any { c -> c.messages.any { it.body is MessageBody.PhotoGrid } })
        assertTrue(history.any { c -> c.messages.any { it.body is MessageBody.PlaceCard } })
    }
}
