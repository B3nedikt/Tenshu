package org.tenshu.core.file

import org.tenshu.core.TString

/**
 * Interface representing a file or folder name.
 * Deriving the name from the path is preferred for now to avoid redundant properties.
 */
interface FileName : TString {
    companion object {
        const val ID = "tenshu/file/name"
    }
}
