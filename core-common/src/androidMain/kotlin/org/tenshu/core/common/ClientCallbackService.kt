package org.tenshu.core.common

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Service used by the Proxy to bind to the client app.
 * This ensures the client process is given higher priority by the Android OS.
 * It returns the functional IClientInterface binder provided via the internal bridge.
 */
class ClientCallbackService : Service() {

    override fun onBind(intent: Intent): IBinder? {
        Log.d("ClientCallbackService", "onBind")
        return bridge?.invoke()
    }

    companion object {
        /**
         * Internal bridge to allow the RemoteBinaryTaskExecutor to provide its stub
         * to the system-instantiated service.
         */
        internal var bridge: (() -> IBinder)? = null
    }
}
