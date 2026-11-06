package org.tenshu.timeline.test

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.timeline.common.timelineServiceCommonModule

fun createTimelineServiceCommonTestRegistry(): SchemaRegistry = createCoreTestRegistry().apply {
    registerConfig(timelineServiceCommonModule)
}
