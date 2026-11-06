package org.tenshu.core.file

import org.tenshu.core.TConstant
import org.tenshu.core.schema.ConstantSchema
import org.tenshu.core.schema.constant

sealed interface FileCategory : TConstant {

    /**
     * Images (photos, screenshots, etc.).
     */
    object Image : FileCategory {
        override val schema: ConstantSchema = constant("${ID}/image", Schema)
    }

    /**
     * Audio files (music, recordings).
     */
    object Audio : FileCategory {
        override val schema: ConstantSchema = constant("${ID}/audio", Schema)
    }

    /**
     * Video files (movies, clips).
     */
    object Video : FileCategory {
        override val schema: ConstantSchema = constant("${ID}/video", Schema)
    }

    /**
     * Standard user files (PDFs, text files).
     */
    object Document : FileCategory {
        override val schema: ConstantSchema = constant("${ID}/document", Schema)
    }

    /**
     * Archive files (zip, tar, etc.).
     */
    object Archive : FileCategory {
        override val schema: ConstantSchema = constant("${ID}/archive", Schema)
    }

    /**
     * Unknown or uncategorized files.
     */
    object Other : FileCategory {
        override val schema: ConstantSchema = constant("${ID}/other", Schema)
    }

    companion object {
        const val ID = "tenshu/file/category"
        val Schema: ConstantSchema = constant(ID)
    }
}
