package org.tenshu.timeline.test

import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.timeline.client.timelineServiceClientModule

fun createTimelineTestRegistry(): SchemaRegistry =
    createTimelineServiceCommonTestRegistry().apply {
        registerConfig(timelineServiceClientModule)
    }
