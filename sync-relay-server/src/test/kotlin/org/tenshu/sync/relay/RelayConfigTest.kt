package org.tenshu.sync.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayConfigTest {

    @Test
    fun fromEnv_emptyEnv_usesDefaults_withDevModeOff() {
        val config = RelayConfig.fromEnv { null }
        // ⚠⚠ Fail closed (relay-metadata-minimisation.md D13): no DEV_MODE ⇒ no X-Dev-Owner bypass.
        assertEquals(false, config.devMode)
        assertFalse(config.isDevModePubliclyExposed())
        assertEquals("0.0.0.0", config.bindHost)
        assertEquals(8080, config.port)
        assertNull(config.databaseUrl)
        assertNull(config.blobDir)
        assertTrue(config.corsAllowedOrigins.isEmpty())
    }

    @Test
    fun fromEnv_s3_allFourPresent_isConfigured() {
        val env = mapOf(
            "S3_ENDPOINT" to "https://fsn1.your-objectstorage.com",
            "S3_BUCKET" to "tenshu-relay",
            "S3_ACCESS_KEY" to "AK",
            "S3_SECRET_KEY" to "SK",
        )
        val config = RelayConfig.fromEnv { env[it] }
        assertTrue(config.s3Configured)
        assertFalse(config.s3PartiallyConfigured)
        assertEquals(RelayConfig.DEFAULT_S3_REGION, config.s3Region)
    }

    @Test
    fun fromEnv_s3_absent_isNeitherConfiguredNorPartial() {
        // The default path: no S3 env at all keeps FileBlobStore, so an existing deployment is
        // unchanged by the backend merely existing.
        val config = RelayConfig.fromEnv { null }
        assertFalse(config.s3Configured)
        assertFalse(config.s3PartiallyConfigured)
    }

    @Test
    fun fromEnv_s3_missingOneSetting_isPartial_soStartupCanRefuse() {
        // ⚠ The case worth failing loudly on: three of four set means someone intended S3, and a
        // silent fallback would write the bytes to the volume instead — where unreclaimed growth
        // is an outage rather than a bill (2026-08-14). Application.kt turns this into a refusal.
        val env = mapOf(
            "S3_ENDPOINT" to "https://fsn1.your-objectstorage.com",
            "S3_BUCKET" to "tenshu-relay",
            "S3_ACCESS_KEY" to "AK",
        )
        val config = RelayConfig.fromEnv { env[it] }
        assertFalse(config.s3Configured)
        assertTrue(config.s3PartiallyConfigured)
    }

    @Test
    fun fromEnv_corsAllowedOrigins_parsesCommaSeparatedAndTrims() {
        val env = mapOf(
            "CORS_ALLOWED_ORIGINS" to " https://app.example.com , https://staging.example.com:8443 ,, ",
        )
        val config = RelayConfig.fromEnv { env[it] }
        assertEquals(
            listOf("https://app.example.com", "https://staging.example.com:8443"),
            config.corsAllowedOrigins,
        )
    }

    @Test
    fun fromEnv_deploymentShape_parsesAll() {
        val env = mapOf(
            "DEV_MODE" to "false",
            "BIND_HOST" to "0.0.0.0",
            "PORT" to "8080",
            "DATABASE_URL" to "jdbc:sqlite:/data/relay.db",
            "BLOB_DIR" to "/data/relay-blobs",
        )
        val config = RelayConfig.fromEnv { env[it] }
        assertEquals(false, config.devMode)
        assertEquals("jdbc:sqlite:/data/relay.db", config.databaseUrl)
        assertEquals("/data/relay-blobs", config.blobDir)
        // The deployment shape (devMode off) passes validate on a public bind.
        config.validate()
    }

    @Test
    fun fromEnv_devMode_isOnlyEverAnExplicitTrue() {
        assertEquals(true, RelayConfig.fromEnv { if (it == "DEV_MODE") "true" else null }.devMode)
        assertEquals(false, RelayConfig.fromEnv { if (it == "DEV_MODE") "false" else null }.devMode)
        // ⚠ Anything unparseable fails closed too — a typo must never switch the bypass on.
        for (raw in listOf("", " ", "yes", "1", "TRUE ", "on")) {
            assertEquals(false, RelayConfig.fromEnv { if (it == "DEV_MODE") raw else null }.devMode, "DEV_MODE='$raw'")
        }
    }

    @Test
    fun fromEnv_blankValues_fallBackToDefaults() {
        // Blank PORT/BIND_HOST/DATABASE_URL must not override with empty strings.
        val env = mapOf("BIND_HOST" to "", "PORT" to "", "DATABASE_URL" to " ")
        val config = RelayConfig.fromEnv { env[it] }
        assertEquals("0.0.0.0", config.bindHost)
        assertEquals(8080, config.port)
        assertNull(config.databaseUrl)
    }

    @Test
    fun isDevModePubliclyExposed_onlyForDevModeOnNonLoopback() {
        assertTrue(RelayConfig(devMode = true, bindHost = "0.0.0.0", port = 8080).isDevModePubliclyExposed())
        assertFalse(RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080).isDevModePubliclyExposed())
        assertFalse(RelayConfig(devMode = false, bindHost = "0.0.0.0", port = 8080).isDevModePubliclyExposed())
    }

    @Test
    fun production_loopback_validates() {
        // devMode=false on loopback is fine.
        RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080).validate()
    }

    @Test
    fun production_publicBind_validates() {
        // devMode=false on a public bind is fine.
        RelayConfig(devMode = false, bindHost = "0.0.0.0", port = 8080).validate()
        RelayConfig(devMode = false, bindHost = "10.0.0.5", port = 8080).validate()
    }

    @Test
    fun devMode_loopback_validates() {
        RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080).validate()
        RelayConfig(devMode = true, bindHost = "::1", port = 8080).validate()
        RelayConfig(devMode = true, bindHost = "localhost", port = 8080).validate()
    }

    @Test
    fun devMode_publicBind_refusesToStart() {
        // The interlock from sync-backend-relay-plan.md §4.1: dev_mode=true must NEVER ship
        // with a non-loopback bind. Refusing to start is the load-bearing behaviour.
        assertFailsWith<InvalidRelayConfigException> {
            RelayConfig(devMode = true, bindHost = "0.0.0.0", port = 8080).validate()
        }
        assertFailsWith<InvalidRelayConfigException> {
            RelayConfig(devMode = true, bindHost = "192.168.1.50", port = 8080).validate()
        }
        assertFailsWith<InvalidRelayConfigException> {
            RelayConfig(devMode = true, bindHost = "tenshu-relay.fly.dev", port = 8080).validate()
        }
    }

    // ---- CR-2 B5r: the content sweep (`content-plane-cr2-b5-spec.md` D1/D2) ----

    @Test
    fun contentSweep_defaultsToOff_with15MinuteGraceAnd5MinuteCadence() {
        val config = RelayConfig.fromEnv { null }
        assertEquals(ContentSweepMode.OFF, config.contentSweepMode)
        assertEquals(15 * 60_000L, config.contentGraceMs)
        assertEquals(5 * 60_000L, config.contentSweepIntervalMs)
    }

    @Test
    fun contentSweep_parsesAllThree() {
        val env = mapOf(
            "CONTENT_SWEEP_MODE" to "armed",
            "CONTENT_GRACE_MS" to "60000",
            "CONTENT_SWEEP_INTERVAL_MS" to "1000",
        )
        val config = RelayConfig.fromEnv { env[it] }
        assertEquals(ContentSweepMode.ARMED, config.contentSweepMode)
        assertEquals(60_000L, config.contentGraceMs)
        assertEquals(1_000L, config.contentSweepIntervalMs)
        assertEquals(ContentSweepMode.DRY_RUN, RelayConfig.fromEnv { if (it == "CONTENT_SWEEP_MODE") "DRY_RUN" else null }.contentSweepMode)
    }

    @Test
    fun contentSweep_anUnknownModeOrABadDuration_refusesToStart() {
        // ⚠ A typo for `armed` must not silently run `off`, and a typo for `off` must never arm.
        assertFailsWith<InvalidRelayConfigException> { RelayConfig.fromEnv { if (it == "CONTENT_SWEEP_MODE") "armd" else null } }
        assertFailsWith<InvalidRelayConfigException> { RelayConfig.fromEnv { if (it == "CONTENT_GRACE_MS") "0" else null } }
        assertFailsWith<InvalidRelayConfigException> { RelayConfig.fromEnv { if (it == "CONTENT_SWEEP_INTERVAL_MS") "5m" else null } }
    }

    @Test
    fun fromEnv_storageCeiling_isGiB_offWhenUnset_andRefusedWhenMistyped() {
        assertNull(RelayConfig.fromEnv { null }.storageCeilingBytes)
        assertNull(RelayConfig.fromEnv { if (it == "STORAGE_CEILING_GIB") " " else null }.storageCeilingBytes)
        assertEquals(
            2L * 1024 * 1024 * 1024,
            RelayConfig.fromEnv { if (it == "STORAGE_CEILING_GIB") "2" else null }.storageCeilingBytes,
        )
        // ⚠ A typo must not silently mean "no ceiling".
        for (bad in listOf("2GB", "0", "-1", "1.5")) {
            assertFailsWith<InvalidRelayConfigException>(bad) {
                RelayConfig.fromEnv { if (it == "STORAGE_CEILING_GIB") bad else null }
            }
        }
    }
}
