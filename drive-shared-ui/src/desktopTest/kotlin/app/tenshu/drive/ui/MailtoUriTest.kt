package app.tenshu.drive.ui

import app.tenshu.drive.ui.common.mailtoUri
import app.tenshu.drive.ui.share.ShareLinkUrl
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Desktop's share-out hands the link to the local mail composer. The encoding is
 * the whole risk in that hand-off: a Mode-A share URL carries its key in a `#…`
 * fragment, so an unencoded `#` would end the `mailto:` URI right there and the
 * composer would open with a link that is silently missing its key.
 */
class MailtoUriTest {

    @Test fun capabilityFragmentSurvivesTheHandOff() {
        val url = ShareLinkUrl(shareId = "abc", urlFragment = "s3cr3t").toCopyableString()

        val uri = URI(mailtoUri(body = url, subject = null))

        assertTrue(url.contains("#k="), "precondition: the share URL carries a key fragment")
        // The whole link must land in the body — nothing may leak into the URI's
        // own fragment, which is what an unencoded '#' would produce.
        assertEquals(null, uri.fragment)
        assertEquals("body=$url", uri.schemeSpecificPart.substringAfter('?'))
    }

    @Test fun subjectAndBodyArePercentEncoded() {
        val encoded = mailtoUri(body = "https://tenshu.app/s/a#k=x&y", subject = "A file: cat.jpg")

        assertEquals(
            "mailto:?subject=A%20file%3A%20cat.jpg&body=https%3A%2F%2Ftenshu.app%2Fs%2Fa%23k%3Dx%26y",
            encoded,
        )
        // Spaces as %20, not the form-encoding '+' that a mail client would show literally.
        assertTrue(!encoded.contains('+'))
    }

    @Test fun subjectIsOmittedWhenAbsent() {
        assertEquals("mailto:?body=hello", mailtoUri(body = "hello", subject = null))
    }
}
