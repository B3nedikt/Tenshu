package app.tenshu.control.ui

/** On for desktop: there's no app store, so the in-app checkout CTA (→ Stripe in the browser) is fine. */
actual val inAppCheckoutEnabled: Boolean = true

/** No store on desktop: Stripe, in the browser. */
actual val billingRail: app.tenshu.control.data.BillingRail = app.tenshu.control.data.BillingRail.STRIPE

/**
 * ⚠⚠ True on **macOS only** (`ios-face-grouping.md` §10.8): its face pair is Apple Vision + the ONNX
 * embedder, and this one desktop build also ships to Windows and Linux, which have no Vision. Decided
 * at run time, and the same test the proxy asks before it binds the backend and declares
 * `FaceModel.ONNX_VISION_RUNTIME` (`MacFaces.runsHere`) — keep the two in step.
 */
actual val faceClusteringRunsHere: Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true) &&
        runCatching { app.tenshu.macos.MacFaceVision.isAvailable }.getOrDefault(false)
