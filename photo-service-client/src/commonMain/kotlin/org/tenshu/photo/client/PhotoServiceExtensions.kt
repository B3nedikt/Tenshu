package org.tenshu.photo.client

import org.tenshu.core.common.TenshuClient
import org.tenshu.photo.PhotoService

val TenshuClient.photoService: PhotoService
    get() = getService(PhotoService.ID)
