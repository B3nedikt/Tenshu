package app.tenshu.control.billing

import app.tenshu.control.data.StoreBilling
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Binds [StoreBilling] to Play Billing. ⚠ This file is compiled only into a Play build
 * (`-Ptenshu.android.billingRail=play`, the default; see `control-shared/build.gradle.kts`); the
 * Stripe rail compiles `src/billingStripe`'s empty twin instead, so an APK outside Play neither
 * links the Play Billing library nor declares its permission.
 */
val androidBillingModule = module {
    single<StoreBilling> { PlayStoreBilling(androidContext()) }
}
