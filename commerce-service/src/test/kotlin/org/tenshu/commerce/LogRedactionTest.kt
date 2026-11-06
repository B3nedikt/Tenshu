package org.tenshu.commerce

import jakarta.mail.SendFailedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogRedactionTest {

    @Test
    fun anAddressIsReducedToItsDomain() {
        assertEquals("***@example.com", emailDomain("Jane.Doe@example.com"))
        assertEquals("***", emailDomain("not-an-address"))
        assertEquals("***", emailDomain(null))
    }

    @Test
    fun theDevTagIsTheDomainPlusAStableShortHash() {
        val tag = emailTag(" Jane.Doe@Example.com ")
        assertTrue(Regex("""^\*\*\*@Example\.com#[0-9a-f]{8}$""").matches(tag), tag)
        // Case/whitespace-insensitive, so the operator's `shasum` over the plain address matches.
        assertEquals(tag.substringAfter('#'), emailTag("jane.doe@example.com").substringAfter('#'))
        assertTrue(emailTag("bob@example.com") != emailTag("jane.doe@example.com"))
        assertFalse("jane" in tag.lowercase())
    }

    @Test
    fun anIdIsReducedToAPrefix() {
        assertEquals("3f2a9c1e…", shortId("3f2a9c1e-7b44-4c1d-9d0e-1a2b3c4d5e6f"))
        assertEquals("-", shortId(null))
    }

    @Test
    fun aThrowableIsDescribedWithoutTheAddressesItsMessagesEcho() {
        // The real shape: Jakarta Mail names the rejected recipient in the message, and again in the
        // nested server reply.
        val failure = SendFailedException(
            "Invalid Addresses",
            RuntimeException("550 5.1.1 <jane.doe@example.com>: Recipient address rejected"),
        )
        val described = describeForLog(failure)
        assertFalse("jane.doe" in described, described)
        assertTrue("jakarta.mail.SendFailedException: Invalid Addresses" in described, described)
        assertTrue("<***@example.com>: Recipient address rejected" in described, described)
    }
}
