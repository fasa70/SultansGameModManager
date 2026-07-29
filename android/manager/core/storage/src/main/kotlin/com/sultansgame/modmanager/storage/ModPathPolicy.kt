package com.sultansgame.modmanager.storage

import com.sultansgame.modmanager.model.MAXIMUM_MOD_FILE_SIZE_BYTES
import com.sultansgame.modmanager.model.MAXIMUM_MOD_PATH_DEPTH

const val MAXIMUM_MOD_ENTRY_COUNT = 10_000
const val MAXIMUM_MOD_TOTAL_SIZE_BYTES: Long = 512L * 1024L * 1024L

object ModPathPolicy {
    fun normalize(relativePath: String): String? {
        if (relativePath.isEmpty() || relativePath.startsWith('/') || relativePath.startsWith('\\')) return null
        val components = relativePath.split('/')
        if (components.size - 1 > MAXIMUM_MOD_PATH_DEPTH || components.any(::isUnsafeComponent)) return null
        return components.joinToString("/")
    }

    fun isUnsafeComponent(component: String): Boolean =
        component.isEmpty() || component == "." || component == ".." ||
            component.any { it == '/' || it == '\\' || it == NUL_CHARACTER }

    fun isSupportedSize(sizeBytes: Long): Boolean = sizeBytes in 0..MAXIMUM_MOD_FILE_SIZE_BYTES

    private const val NUL_CHARACTER: Char = 0.toChar()
}
