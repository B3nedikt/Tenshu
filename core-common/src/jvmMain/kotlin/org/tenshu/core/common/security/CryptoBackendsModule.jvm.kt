package org.tenshu.core.common.security

import org.koin.core.module.Module
import org.koin.dsl.module

actual fun cryptoBackendsModule(): Module = module {
    single<Ed25519Backend> { BouncyCastleEd25519Backend() }
    single<X25519Backend> { BouncyCastleX25519Backend() }
    single<AeadBackend> { BouncyCastleAeadBackend() }
    single<KdfBackend> { BouncyCastleKdfBackend() }
}
