package app.tenshu.notify

/**
 * Loads the IANA time-zone database into the JS test bundle.
 *
 * ⚠⚠ **Declaring the npm dependency is not enough — nothing imports it, so webpack never bundles
 * it.** `kotlinx-datetime` delegates to js-joda on Kotlin/JS, and `@js-joda/core` alone knows UTC
 * and fixed offsets only; without this `TimeZone.of("Europe/Berlin")` throws
 * `IllegalTimeZoneException: Invalid zone ID`, which is what failed all nine
 * `AppleAlertScheduleTest` cases on the browser target while passing on every other one.
 *
 * ⚠ The property is what pulls the module in: a bare `external object` emits no import at all.
 * Nothing reads [jsJodaTimeZoneDatabase] and nothing needs to — a top-level property initialiser
 * runs when the module loads, and a **test** bundle is built in development mode with no DCE. ⚠⚠ Do
 * not "clean up" this apparently-unused property; deleting it puts the nine failures back.
 */
@JsModule("@js-joda/timezone")
@JsNonModule
private external object JsJodaTimeZoneModule

internal val jsJodaTimeZoneDatabase: Any = JsJodaTimeZoneModule
