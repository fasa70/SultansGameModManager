package com.sultansgame.modmanager.storage

import com.sultansgame.modmanager.model.MAXIMUM_MOD_CONFIG_FILE_SIZE_BYTES
import com.sultansgame.modmanager.model.MAXIMUM_MOD_MEDIA_FILE_SIZE_BYTES
import com.sultansgame.modmanager.model.MAXIMUM_MOD_PATH_DEPTH
import java.util.Locale

const val MAXIMUM_MOD_ENTRY_COUNT = 10_000

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

    fun isSupportedSize(sizeBytes: Long, relativePath: String = ""): Boolean =
        sizeBytes in 0..maximumSizeBytes(relativePath)

    private fun maximumSizeBytes(relativePath: String): Long =
        if (relativePath.lowercase(Locale.ROOT).let {
                it.endsWith(".png") || it.endsWith(".wav") || it.endsWith(".mp3") || it.endsWith(".ogg")
            }) {
            MAXIMUM_MOD_MEDIA_FILE_SIZE_BYTES
        } else {
            MAXIMUM_MOD_CONFIG_FILE_SIZE_BYTES
        }

    private const val NUL_CHARACTER: Char = 0.toChar()
}
