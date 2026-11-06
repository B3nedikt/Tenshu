package app.tenshu.photos

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.core.createEntity
import org.tenshu.core.entity.Entity
import org.tenshu.core.place.Place
import org.tenshu.core.place.city
import org.tenshu.core.place.countryCode
import org.tenshu.core.place.label
import org.tenshu.core.place.latE7
import org.tenshu.core.place.lngE7
import org.tenshu.core.place.placeId
import org.tenshu.place.common.placeServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A `Place` row shorter than this build's schema, read through the accessors.
 *
 * ## ⚠⚠ This test cannot fail if the fix is reverted — measured on BOTH targets
 *
 * Swapping `city` back to the non-null `getFieldValue` leaves every assertion below green on the
 * JVM **and** on `iosSimulatorArm64`. Both arms were run; neither discriminates.
 *
 * The reason is the shape of the read, not the target. `row.city?.get()` performs the null check
 * whatever `city` is declared as, so the null never reaches a dereference. The read that actually
 * dies is the **bare** one — `row.city.get()` — which is what a non-null accessor permits and what
 * `OptionalFieldValue`'s KDoc measured segfaulting (`TInteger#get` → `Kotlin_Any_getTypeInfo`,
 * `KERN_INVALID_ADDRESS at 0x0`). Against a nullable accessor that line **does not compile**, so no
 * test can contain both arms.
 *
 * ⚠⚠ **The guard is the type, and the type is a compile-time guard.** It does not make the crash
 * survivable; it makes the crashing line unwritable. These assertions document the contract that
 * results — absent reads as null, present still reads — and claim nothing more. A green run here is
 * not evidence the fix is present.
 *
 * ## Why `Place` in particular
 *
 * Timeline writes these rows and Photos reads them, so a row is whatever the Timeline that wrote it
 * knew how to write — and `CITY`, `COUNTRY_CODE` and `LABEL_SOURCE` were *appended* to a positional
 * composite, which is precisely the shape that leaves older rows short. The schema still declares
 * every one of them non-optional, which is why the nullability lives on the accessor and not on the
 * schema.
 */
class PlaceAccessorAbsenceTest {

    private val proxy = TestProxy(
        schemaRegistry = createCoreTestRegistry().apply {
            registerConfig(placeServiceCommonModule)
        },
    )

    /**
     * Positional, and short: the first five fields only, so everything appended after `LABEL` is
     * absent exactly as it would be in a row from an older Timeline.
     */
    private fun shortPlaceRow(): Entity<Place> = proxy.tContext.createEntity(
        type = Place.ID,
        fields = listOf("p1", 520_000_000L, 133_000_000L, 0L, "Blue Bottle"),
    )

    @Test
    fun aFieldTheWriterNeverWroteReadsAsNull() {
        val row = shortPlaceRow()

        assertNull(row.city, "city was appended after this row was written")
        assertNull(row.countryCode, "and so was the country code")
    }

    /** ⚠ The present half must keep working, or "safe" would just mean "always null". */
    @Test
    fun theFieldsTheWriterDidWriteStillRead() {
        val row = shortPlaceRow()

        assertEquals("p1", row.placeId?.get())
        assertEquals("Blue Bottle", row.label?.get())
        assertEquals(520_000_000L, row.latE7?.get())
        assertEquals(133_000_000L, row.lngE7?.get())
    }

    /**
     * ⚠⚠ Nullability is not the policy — it only makes the policy expressible. Which absences are
     * fatal to a row stays a per-field judgement, and it lives in `photoPlaceOrNull` where it is
     * decidable without a store: an absent `city` is a place, an absent `label` is not a section.
     */
    @Test
    fun theJudgementStaysWithTheReader() {
        val row = shortPlaceRow()

        assertNotNull(
            app.tenshu.photos.model.photoPlaceOrNull(
                placeId = row.placeId?.get(),
                label = row.label?.get(),
                city = row.city?.get(),
                latE7 = row.latE7?.get(),
                lngE7 = row.lngE7?.get(),
            ),
            "a place with no city is still a place",
        )

        val unlabelled = proxy.tContext.createEntity<Place>(
            type = Place.ID,
            fields = listOf("p2", 520_000_000L, 133_000_000L, 0L),
        )
        assertNull(
            app.tenshu.photos.model.photoPlaceOrNull(
                placeId = unlabelled.placeId?.get(),
                label = unlabelled.label?.get(),
                city = unlabelled.city?.get(),
                latE7 = unlabelled.latE7?.get(),
                lngE7 = unlabelled.lngE7?.get(),
            ),
            "an ungeocoded place is not a usable section",
        )
    }
}
