package org.tenshu.preference.client.impl

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.ServiceRo
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.PreferenceService
import org.tenshu.preference.RealmSetting

internal class PreferenceServiceRo(
    override val requestChain: RequestChain,
    context: TContext,
) : PreferenceService, ServiceRo(requestChain, context) {

    override val components: Map<String, TComponent>
        get() = context.components

    override val realmSettings: TList<RealmSetting>
        get() = serviceRelation(PreferenceService.Relations.REALM_SETTINGS, RealmSetting.Schema)

    override val deviceSettings: TList<DeviceSetting>
        get() = serviceRelation(PreferenceService.Relations.DEVICE_SETTINGS, DeviceSetting.Schema)
}
