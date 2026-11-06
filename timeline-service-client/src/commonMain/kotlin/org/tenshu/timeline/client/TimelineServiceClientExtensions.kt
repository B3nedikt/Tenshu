package org.tenshu.timeline.client

import org.tenshu.core.common.TenshuClient
import org.tenshu.core.timeline.TimelineService

val TenshuClient.timelineService: TimelineService
    get() = getService(TimelineService.ID)
