package org.tenshu.core.common.app

import org.tenshu.core.app.OpenTargetRequest
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.entity.DataId
import org.tenshu.core.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * iOS's only carrier (`control-cross-app-open.md` §5i).
 *
 * ⚠⚠ The round-trip assertions matter more here than on any other platform: Android and desktop can
 * both push a *live composite* down an existing channel, so a serialisation bug shows up in one
 * place. On iOS every open goes through this encoding, so a variant that does not survive it is a
 * type that simply cannot be opened.
 */
class OpenTargetUrlTest {

    private val registry = createCoreTestRegistry()
    private val serializer = BinarySerializer()
    private val parser = BinaryParser(registry, registry)

    private fun requestFor(target: DataId) =
        OpenTargetRequest.create(context = createTestContext(), target = target)

    @Test
    fun theSchemeIsDerivedFromTheAppId() {
        // ⭐ CO-D4: a rendering of the identity, never a second identity.
        assertEquals("tenshu-drive", OpenTargetUrl.schemeFor("app.tenshu.drive"))
        assertEquals("tenshu-calendar", OpenTargetUrl.schemeFor("app.tenshu.calendar"))
    }

    @Test
    fun anAppIdWithNoDerivableSchemeIsRefused() {
        // ⚠ Null, not a guess: a scheme built from junk is registered by nobody, and `openURL`
        // then reports "nothing handles this" — which reads as "the app is not installed".
        assertNull(OpenTargetUrl.schemeFor("com.example.other"))
        assertNull(OpenTargetUrl.schemeFor("app.tenshu."))
        assertNull(OpenTargetUrl.schemeFor("app.tenshu.drive.android"))
    }

    @Test
    fun aSimpleRowSurvivesTheRoundTrip() {
        val context = createTestContext()
        val target = DataId.StringDataId.create(context, "tenshu/file", "/Photos/a b+c.jpg")
        val url = OpenTargetUrl.encode("app.tenshu.drive", requestFor(target), serializer)!!

        assertTrue(url.startsWith("tenshu-drive://open?t="), url)
        val decoded = OpenTargetUrl.decode(url, parser) as? DataId.StringDataId
        assertEquals("tenshu/file", decoded?.type?.get())
        // ⚠ The key contains a space and a '+' — the characters a naive query encoding loses.
        assertEquals("/Photos/a b+c.jpg", decoded?.keyValue?.get())
    }

    @Test
    fun aMultiPartOccurrenceSurvivesTheRoundTrip() {
        // ⚠⚠ The case CO-D5 deleted the `locator` parameter for. If a composite id did not survive
        // this encoding, a calendar occurrence would be unopenable on iOS specifically — the exact
        // shape of bug that only shows on one platform, long after the design was agreed.
        val context = createTestContext()
        val target = DataId.CompositeEntityDataId.create(
            context = context,
            type = "tenshu/calendar/eventOccurrence",
            keyValues = listOf(
                DataId.StringDataId.create(context, "tenshu/calendar/eventOccurrence/eventId", "e1"),
                DataId.StringDataId.create(
                    context,
                    "tenshu/calendar/eventOccurrence/recurrenceId",
                    "2026-08-22T09:00",
                ),
            ),
        )
        val url = OpenTargetUrl.encode("app.tenshu.calendar", requestFor(target), serializer)!!

        val decoded = OpenTargetUrl.decode(url, parser) as? DataId.CompositeEntityDataId
        assertEquals("tenshu/calendar/eventOccurrence", decoded?.type?.get())
    }

    @Test
    fun somethingThatIsNotOursDecodesToNothing() {
        // ⚠⚠ A scheme is not exclusive and any app can `openURL` at us, so every one of these is an
        // ordinary event. Throwing would let any app on the device kill ours with one call.
        assertNull(OpenTargetUrl.decode("tenshu-drive://something-else", parser))
        assertNull(OpenTargetUrl.decode("tenshu-drive://open?t=", parser))
        assertNull(OpenTargetUrl.decode("tenshu-drive://open?t=!!!not-base64!!!", parser))
        assertNull(OpenTargetUrl.decode("https://example.com/open?t=abc", parser))
        assertNull(OpenTargetUrl.decode("", parser))
    }
}
