package app.tenshu.control.ui

import app.tenshu.control.data.BillingRail

/**
 * Stripe's checkout and billing portal only on the Stripe rail: a Play build sells through Play
 * Billing and must not carry a CTA that steers users to external payment (Google Play Payments
 * policy). The rail is generated from `-Ptenshu.android.billingRail` (`play` by default).
 */
actual val inAppCheckoutEnabled: Boolean = ANDROID_BILLING_RAIL == BillingRail.STRIPE

actual val billingRail: BillingRail = ANDROID_BILLING_RAIL

actual val faceClusteringRunsHere: Boolean = true
