@file:OptIn(DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The web apps' remembered view settings (`docs/proposals/web-apps-layout.md` §3.6, L6) round-trip
 * through the REAL runtime's DEVICE rows, via the exported JS surface the apex gateway calls.
 */
// Extends [SdkRuntimeTest]: it builds the runtime, so the relay must be pinned to the sink first.
class ViewSettingsWebTest : SdkRuntimeTest() {

    @Test
    fun driveListing_andPane_roundTrip_andTheSortRowIsNativesFormat() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val wrote: dynamic = DriveSettings.setListing("modified", true, "grid").await()
        assertEquals(true, wrote.ok, "write: ${JSON.stringify(wrote)}")
        assertEquals(true, DriveSettings.setDetailsPane(true).await().ok)

        val read: dynamic = DriveSettings.get().await()
        assertEquals(true, read.ok, "read: ${JSON.stringify(read)}")
        assertEquals("modified", read.key)
        assertEquals(true, read.descending)
        assertEquals("grid", read.layout)
        assertEquals(true, read.detailsPane)
        // Written rows report themselves as stored (a missing one would read as the default).
        assertEquals(true, read.sortStored)
        assertEquals(true, read.layoutStored)
        assertEquals(true, read.paneStored)

        // ⚠⚠ The row native Drive reads on this device's id: its codec, verbatim.
        val deviceId = JsRuntime.syncManager.currentDeviceId
        val raw = devicePref(WebViewPreferences.DriveListSort, deviceId)
        assertEquals("MODIFIED:desc", (raw as Read.Ok).value)
        assertEquals("GRID", (devicePref(WebViewPreferences.DriveListViewMode, deviceId) as Read.Ok).value)
    }

    @Test
    fun badValuesAreRefused_neverStored() = GlobalScope.promise {
        JsRuntime.awaitReady()
        assertEquals("bad_value", DriveSettings.setListing("colour", false, "list").await().error.kind)
        assertEquals("bad_value", DriveSettings.setListing("name", false, "tiles").await().error.kind)
        assertEquals("bad_value", PhotosSettings.setDensity("decades").await().error.kind)
        assertEquals("bad_value", PhotosSettings.setColumns(2).await().error.kind)
        assertEquals("bad_value", PhotosSettings.setColumns(9).await().error.kind)
    }

    @Test
    fun photosDensity_roundTrips() = GlobalScope.promise {
        JsRuntime.awaitReady()
        assertEquals(true, PhotosSettings.setDensity("years").await().ok)
        assertEquals("years", PhotosSettings.get().await().density)
        assertEquals(true, PhotosSettings.setDensity("days").await().ok)
        assertEquals("days", PhotosSettings.get().await().density)
    }

    @Test
    fun photosColumns_roundTrip() = GlobalScope.promise {
        JsRuntime.awaitReady()
        assertEquals(true, PhotosSettings.setColumns(3).await().ok)
        assertEquals(3, PhotosSettings.get().await().columns)
        assertEquals(true, PhotosSettings.setColumns(8).await().ok)
        assertEquals(8, PhotosSettings.get().await().columns)
    }

    @Test
    fun photosLayout_roundTrips_eachSwitchOnItsOwn() = GlobalScope.promise {
        JsRuntime.awaitReady()
        assertEquals(true, PhotosSettings.setLayout(mosaic = false, fit = false).await().ok)
        assertEquals(false, PhotosSettings.get().await().mosaic)
        assertEquals(true, PhotosSettings.setLayout(mosaic = true, fit = false).await().ok)
        assertEquals(true, PhotosSettings.get().await().mosaic)
        assertEquals(false, PhotosSettings.get().await().fit)
        assertEquals(true, PhotosSettings.setLayout(mosaic = false, fit = true).await().ok)
        assertEquals(false, PhotosSettings.get().await().mosaic)
        assertEquals(true, PhotosSettings.get().await().fit)
    }

    @Test
    fun nativesCodec_decodesWhatNativeWrites_andDefaultsWhatItCannot() {
        assertEquals("name" to false, decodeDriveSort(""))
        assertEquals("size" to true, decodeDriveSort("SIZE:desc"))
        assertEquals("created" to false, decodeDriveSort("CREATED:asc"))
        assertEquals("name" to false, decodeDriveSort("SHOE_SIZE:desc"))
        assertEquals("NAME:asc", encodeDriveSort("name", false))
        assertEquals(null, encodeDriveSort("colour", true))
    }
}
