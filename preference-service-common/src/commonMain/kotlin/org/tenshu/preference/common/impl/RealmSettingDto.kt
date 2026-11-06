package org.tenshu.preference.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.preference.RealmSetting

internal class RealmSettingDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), RealmSetting {

    override val schema: CompositeSchema
        get() = RealmSetting.Schema
}
