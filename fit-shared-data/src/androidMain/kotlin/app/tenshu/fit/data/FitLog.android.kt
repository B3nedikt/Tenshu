package app.tenshu.fit.data

import android.util.Log

internal actual fun fitLog(message: String, error: Throwable?) {
    if (error != null) Log.w(FIT_LOG_TAG, message, error) else Log.i(FIT_LOG_TAG, message)
}
