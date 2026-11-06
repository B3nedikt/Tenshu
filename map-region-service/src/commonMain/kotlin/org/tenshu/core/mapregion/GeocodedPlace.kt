package org.tenshu.core.mapregion

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string

/**
 * A reverse-geocoded place: the venue label **and the locality tiers around it**.
 *
 * ### ⚠⚠ Why this type exists at all
 *
 * `ReverseGeocode` used to return a bare `TString`, so `ProxyReverseGeocoder` could only ever build
 * `GeocodeResult(label = …)` and `city`/`countryCode` defaulted to `""` on every lookup. On Android
 * the proxy tier is first in the geocoder chain, so **`city` was blank for every place ever
 * geocoded** — confirmed on a real store: nine geocoded places, nine blank cities.
 *
 * That silently disabled a shipped feature. `PlaceLabelEnricher`'s confidence-aware coarsening (4-B,
 * `place-geocoding.md` §4.3) reads
 * `row.accuracyM > COARSE_LABEL_ACCURACY_M && result.city.isNotBlank()` — and its second conjunct was
 * never true, so the rule **has never fired for anyone**. Its whole purpose is that a precise venue
 * name from an imprecise fix is *more* wrong than a coarse one; without it a 300 m-accuracy dwell on
 * a U-Bahn platform was confidently labelled with a car showroom 14 m away.
 *
 * ⚠ The fields were never a Photon limitation — `PhotonProperties` has parsed `city` all along. The
 * loss was the op's return type, one layer further out.
 */
interface GeocodedPlace : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The venue/POI label, or blank when nothing was found. */
    val label: TString
        get() = getField(Fields.LABEL)

    /** Locality — the coarsening tier a low-confidence fix falls back to. Blank when unknown. */
    val city: TString
        get() = getField(Fields.CITY)

    /** ISO country code, blank when unknown. */
    val countryCode: TString
        get() = getField(Fields.COUNTRY_CODE)

    companion object {
        const val ID = "${MapRegionService.ID}/geocodedPlace"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Fields.LABEL)
                string(Fields.CITY)
                string(Fields.COUNTRY_CODE)
            },
        )

        fun create(
            context: TContext,
            label: String,
            city: String,
            countryCode: String,
        ): GeocodedPlace = context.create(
            type = ID,
            fields = listOf(label, city, countryCode),
        )
    }

    object Fields {
        const val LABEL = "$ID/label"
        const val CITY = "$ID/city"
        const val COUNTRY_CODE = "$ID/countryCode"
    }
}
