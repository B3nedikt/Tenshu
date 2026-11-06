package app.tenshu.control.billing

import org.koin.dsl.module

/**
 * The Stripe rail (`-Ptenshu.android.billingRail=stripe`): nothing is bound. An APK distributed
 * outside Play must run without Google Play services, and the missing `StoreBilling` is how the UI
 * knows to use the browser checkout. ⚠ Compiled instead of `src/billingPlay`, so this build links
 * no Play Billing code at all (`control-shared/build.gradle.kts`).
 */
val androidBillingModule = module { }
