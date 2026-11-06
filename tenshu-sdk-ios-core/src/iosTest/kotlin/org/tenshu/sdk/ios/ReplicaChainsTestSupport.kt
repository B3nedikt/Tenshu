package org.tenshu.sdk.ios

import org.tenshu.core.common.proxy.db.sqlite.ReplicaBuild
import org.tenshu.core.common.proxy.db.sqlite.ReplicaMigrationSteps
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaStore

/**
 * No chain registered: every step absent. ⚠ Test-only since B.24: production binds the registry's
 * chains (`RegistryReplicaMigrationSteps`), and a wipe job is given `WipeJobsNeverMigrate`.
 */
internal val NoMigrationChains: ReplicaMigrationSteps = ReplicaMigrationSteps { _, _ -> null }

/**
 * The binding's open with no chain, for the tests whose builds have every floor at the version
 * (so a store behind wipes, the premise they test). ⚠ Not in `iosMain`: a production open without
 * its chains would wipe a store it can migrate.
 */
internal fun SharedReplicaBinding.open(build: ReplicaBuild): SharedReplicaStore = open(build, NoMigrationChains)
