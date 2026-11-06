package app.tenshu.fit.data

internal actual fun fitLog(message: String, error: Throwable?) {
    println("$FIT_LOG_TAG: $message")
    error?.printStackTrace()
}
