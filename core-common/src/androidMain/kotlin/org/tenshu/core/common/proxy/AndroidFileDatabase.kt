package org.tenshu.core.common.proxy

import java.io.File

interface AndroidFileDatabase : FileDatabase {

    fun getFile(androidInternalFilePath: String): File?
}