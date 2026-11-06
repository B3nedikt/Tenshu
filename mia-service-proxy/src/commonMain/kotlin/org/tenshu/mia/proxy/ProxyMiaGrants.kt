package org.tenshu.mia.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.mia.MiaSource
import org.tenshu.preference.BooleanAdapter
import org.tenshu.preference.RealmSetting
import org.tenshu.preference.Setting

/**
 * Which sources the user currently allows Mia to read (`mia-query-layer.md` MQ-D6), read
 * straight off the proxy DB. The in-proxy counterpart of the client-side preference surface,
 * mirroring `ProxySyncRules.readFileSyncRuleSet`.
 *
 * ⚠ **Read per question, not cached.** A grant is a security decision, and a revocation that
 * takes effect on the *next* question is the behaviour a user expects when they flip a switch;
 * a cached set would keep answering from a source they just turned off for as long as the
 * process lived. The read is a handful of point lookups against rows that are almost always
 * resident.
 *
 * ⚠ **Absent means granted** — the row only exists once the user has touched the switch, and
 * `MiaSource`'s default is `true`. Note the direction: this is the one place where "no row"
 * must *not* fail closed, because failing closed here would silently disable an assistant
 * that has never been configured. The fail-closed posture lives one layer down, in the
 * validator, which refuses anything not on the allowlist this produces.
 */
internal fun ExecutionContext.readMiaGrants(): Set<MiaSource> =
    MiaSource.entries.filterTo(mutableSetOf()) { source ->
        BooleanAdapter.fromData(readRealmSettingValue(source.preference.id))
            ?: source.preference.default
    }

/**
 * Whether the user has granted Mia **write** access to [source] (MQ-D6 as amended).
 *
 * ⚠⚠ **Absent means NOT granted here — the opposite of the read grant, deliberately.** A read
 * grant defaults on because reading was the shipped behaviour and defaulting it off would break
 * a working assistant; writing has never happened, so silence means no. Those are different
 * questions and they get different answers.
 *
 * ⚠ Write implies read: a write intent resolves its slots against existing data first, so a
 * write grant without the read one is unsatisfiable and is treated as absent.
 */
internal fun ExecutionContext.canWrite(source: MiaSource): Boolean {
    val write = source.writePreference ?: return false
    if (source !in readMiaGrants()) return false
    return BooleanAdapter.fromData(readRealmSettingValue(write.id)) ?: write.default
}

/** The stored polymorphic `value` of the realm-scoped `preferenceId` row, or null. */
private fun ExecutionContext.readRealmSettingValue(preferenceId: String): Data? {
    val dataId = DataId.StringDataId.create(
        context = context,
        type = RealmSetting.ID,
        keyValue = preferenceId,
    )
    val stored = database.getData(dataId) ?: return null
    return when (stored) {
        is Entity<*> -> runCatching { stored.getFieldValue<Data>(Setting.Field.VALUE) }.getOrNull()
        is Composite -> runCatching { stored.getField<Data>(Setting.Field.VALUE) }.getOrNull()
        else -> null
    }
}
