package app.tenshu.control.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.app.HandlerTable
import org.tenshu.core.common.app.PrimaryHandler
import org.tenshu.core.toTString
import org.tenshu.preference.RealmSetting
import org.tenshu.preference.common.preferenceServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The **reader** half of the user's primary-handler choice (`control-cross-app-open.md` TT-4).
 *
 * ⚠⚠ `OpenTargetTest` proves the resolver honours a preference it is *handed*; nothing there can
 * fail if the id scheme and the stored row disagree, because the lambda is a fake. This is the test
 * that joins the two — it writes an ordinary [RealmSetting] the way a settings screen would, and
 * reads it back through the production lookup. Without it, `preferenceId` could name a row nobody
 * ever writes and every other test would still be green.
 */
class PrimaryHandlerPreferenceTest {

    private val placeType = HandlerTable.PLACE_TYPE

    /**
     * ⚠ The preference module has to be registered explicitly: a bare `TestProxy` carries the core
     * registry only, and without this a `RealmSetting` cannot be *created*, so the tests would fail
     * on the fixture rather than on the behaviour.
     */
    private fun CoroutineScope.proxy(): TestProxy = TestProxy(
        schemaRegistry = createCoreTestRegistry().apply {
            registerConfig(preferenceServiceCommonModule)
        },
        coroutineScope = this,
    )

    @Test
    fun aChoiceWrittenAsAnOrdinaryRealmSettingIsReadBack() = runTest {
        val proxy = backgroundScope.proxy()
        proxy.executionContext.saveData(
            RealmSetting.create(
                context = proxy.tContext,
                preferenceId = PrimaryHandler.preferenceId(placeType),
                value = "app.tenshu.maps".toTString(proxy.tContext),
            )
        )

        assertEquals(
            "app.tenshu.maps",
            proxy.executionContext.preferredHandlerFor(placeType),
        )
    }

    @Test
    fun noRowMeansNoPreference_notARefusal() = runTest {
        // ⚠ Absence is the compiled-in order, never an error: a user who has never expressed a
        // choice must get the default behaviour rather than none.
        val proxy = backgroundScope.proxy()

        assertNull(proxy.executionContext.preferredHandlerFor(placeType))
    }

    @Test
    fun anEmptyValueIsNoPreference() = runTest {
        // ⚠⚠ Clearing a choice is how a settings UI says "back to the default", and an empty string
        // is what a text-valued preference clears to. Treated as a *value*, it would be an appId
        // matching no candidate — harmless today, but it would make "cleared" and "nonsense" the
        // same state, so a later stricter check could not tell them apart.
        val proxy = backgroundScope.proxy()
        proxy.executionContext.saveData(
            RealmSetting.create(
                context = proxy.tContext,
                preferenceId = PrimaryHandler.preferenceId(placeType),
                value = "".toTString(proxy.tContext),
            )
        )

        assertNull(proxy.executionContext.preferredHandlerFor(placeType))
    }

    @Test
    fun theIdIsNamespacedAndDerivedFromTheType() = runTest {
        // ⚠ Pinned because it is a STORED key: changing it silently orphans every choice a user
        // has already made, and nothing else in the system would notice.
        assertEquals(
            "tenshu/app/primaryHandler/$placeType",
            PrimaryHandler.preferenceId(placeType),
        )
    }
}
