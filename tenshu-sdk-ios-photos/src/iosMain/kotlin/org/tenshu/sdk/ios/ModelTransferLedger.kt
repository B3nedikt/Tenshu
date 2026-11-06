package org.tenshu.sdk.ios

import org.tenshu.inference.proxy.catalog.ModelSessionTransfer

/**
 * The ledger of the model download's background session, as Swift names it
 * (`photos-ios/Sources/ModelDownload.swift`). ⚠ All of it is the class it extends, which lives
 * beside the downloader so the two are tested together; this is only the name the umbrella
 * exports.
 */
class ModelTransferLedger(offered: Boolean) : org.tenshu.inference.proxy.catalog.ModelTransferLedger(offered)

/** One task the session holds that is still running or waiting: its identifier and its ref. */
class SessionTransfer(taskId: Long, ref: String) : ModelSessionTransfer(taskId, ref)
