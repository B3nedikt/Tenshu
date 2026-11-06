package app.tenshu.control.di

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.common.app.PrimaryHandler
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.preference.RealmSetting
import org.tenshu.preference.Setting
import org.tenshu.preference.StringAdapter

/**
 * The user's *"which app opens this?"* choice, read from an ordinary realm preference row
 * (`control-cross-app-open.md` TT-4).
 *
 * ⚠⚠ **It lives here, not in `:core-common`, because `:core-common` must not depend on
 * `:preference-service`.** `appOpenTargetImplementationFactory` takes the lookup as a lambda for
 * exactly that reason — the same shape `deviceId` already uses.
 *
 * ⭐ **No new wire surface.** The choice is a plain [RealmSetting], so whatever writes preferences
 * today writes this one too; TT-4 needed a *reader* and an id scheme, not an operation.
 *
 * ⚠ Read per call rather than cached, and absence means "no preference" — which is the compiled-in
 * order, never a refusal. A user who has never expressed a choice must get the default behaviour,
 * not none.
 */
internal fun ExecutionContext.preferredHandlerFor(type: String): String? =
    StringAdapter.fromData(readRealmSettingValue(PrimaryHandler.preferenceId(type)))
        ?.takeIf { it.isNotEmpty() }

/**
 * The stored polymorphic `value` of a realm-scoped preference row, or `null`.
 *
 * ⚠ A local copy of `ProxyInferenceConsent`'s private helper rather than a shared one: the two
 * modules do not depend on each other, and hoisting it into `:preference-service` would make that
 * module depend on `:core-common`'s proxy surface for the sake of eight lines.
 */
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
