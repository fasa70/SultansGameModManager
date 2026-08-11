package com.sultansgame.modmanager.platform.storage

import android.os.StatFs
import com.sultansgame.modmanager.storage.StorageSpaceProbe
import java.io.File

class AndroidStorageSpaceProbe : StorageSpaceProbe {
    override fun availableBytes(path: File): Long {
        val location = when {
            path.isDirectory -> path
            path.exists() -> path.parentFile
            else -> path.parentFile
        } ?: return 0L
        return runCatching { StatFs(location.absolutePath).availableBytes }
            .getOrDefault(0L)
    }
}
