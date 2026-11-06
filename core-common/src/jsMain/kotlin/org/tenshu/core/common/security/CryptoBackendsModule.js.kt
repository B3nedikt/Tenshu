package org.tenshu.core.common.security

import org.koin.core.module.Module
import org.koin.dsl.module

actual fun cryptoBackendsModule(): Module = module {
    single<Ed25519Backend> { NobleEd25519Backend() }
    single<X25519Backend> { NobleX25519Backend() }
    single<AeadBackend> { NobleAeadBackend() }
    single<KdfBackend> { NobleKdfBackend() }
}
