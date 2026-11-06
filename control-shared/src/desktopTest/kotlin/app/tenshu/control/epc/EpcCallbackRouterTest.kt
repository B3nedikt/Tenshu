package app.tenshu.control.epc

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpcCallbackRouterTest {

    @Test
    fun registers_routes_then_unregisters() = runBlocking {
        val router = EpcCallbackRouter()
        assertNull(router.route("epc:a"))

        var got: ByteArray? = null
        router.register("epc:a") { got = it; true }

        val push = router.route("epc:a")
        assertTrue(push != null)
        assertTrue(push!!.invoke("hi".encodeToByteArray()))
        assertContentEquals("hi".encodeToByteArray(), got)

        router.unregister("epc:a")
        assertNull(router.route("epc:a"))
    }

    @Test
    fun isolates_routes_by_client_id() = runBlocking {
        val router = EpcCallbackRouter()
        router.register("epc:a") { true }
        assertNull(router.route("epc:b"))
    }
}
