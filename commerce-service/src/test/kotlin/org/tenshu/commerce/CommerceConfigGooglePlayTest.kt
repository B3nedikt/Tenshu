package org.tenshu.commerce

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CommerceConfigGooglePlayTest {

    private val key = """{"client_email":"play@x.iam.gserviceaccount.com","private_key":"k"}"""

    private fun config(vararg env: Pair<String, String>) = CommerceConfig.fromEnv { name -> env.toMap()[name] }

    @Test
    fun unsetKey_meansTheRailIsOff() {
        assertNull(config().googlePlay)
    }

    @Test
    fun theKeyIsAcceptedRawOrAsBase64() {
        val b64 = Base64.getEncoder().encodeToString(key.toByteArray())
        assertEquals(key, config("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON" to key).googlePlay?.serviceAccountJson)
        assertEquals(key, config("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON" to b64).googlePlay?.serviceAccountJson)
        assertEquals(GooglePlayConfig.DEFAULT_PRODUCTS, config("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON" to key).googlePlay?.products)
    }

    @Test
    fun aKeyWithoutTheNotificationSecret_refusesToStart() {
        assertFailsWith<InvalidCommerceConfigException> { config("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON" to key).validate() }
        config("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON" to key, "GOOGLE_PLAY_RTDN_TOKEN" to "s").validate()
    }

    @Test
    fun toStringNeverShowsTheKeyOrSecret() {
        val text = config("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON" to key, "GOOGLE_PLAY_RTDN_TOKEN" to "s3cret").googlePlay.toString()
        assertFalse("private_key" in text || "s3cret" in text)
    }
}
