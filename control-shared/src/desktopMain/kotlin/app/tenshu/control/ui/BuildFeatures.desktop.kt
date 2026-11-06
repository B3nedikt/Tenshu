package app.tenshu.control.ui

/** On for desktop: there's no app store, so the in-app checkout CTA (→ Stripe in the browser) is fine. */
actual val inAppCheckoutEnabled: Boolean = true

/** No store on desktop: Stripe, in the browser. */
actual val billingRail: app.tenshu.control.data.BillingRail = app.tenshu.control.data.BillingRail.STRIPE

actual val faceClusteringRunsHere: Boolean = false
