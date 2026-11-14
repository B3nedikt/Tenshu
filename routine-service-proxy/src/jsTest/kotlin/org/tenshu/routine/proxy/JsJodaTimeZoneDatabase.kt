package org.tenshu.routine.proxy

/**
 * Loads the IANA time-zone database into the JS test bundle — the twin of `:app-notify`'s file of
 * the same name, which explains why a declared npm dependency alone is not enough.
 *
 * ⚠⚠ Do not "clean up" this apparently-unused property: nothing reads it, but it is what makes
 * webpack bundle the module. Delete it and `RoutineMultiDeviceTest` + `RoutineExecutionTest` go
 * red on the browser target only, as zero runs rather than as an exception.
 */
@JsModule("@js-joda/timezone")
@JsNonModule
private external object JsJodaTimeZoneModule

internal val jsJodaTimeZoneDatabase: Any = JsJodaTimeZoneModule
