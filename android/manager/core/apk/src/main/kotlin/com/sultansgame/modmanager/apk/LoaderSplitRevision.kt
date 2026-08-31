package com.sultansgame.modmanager.apk

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** 冻结 loader split 契约：纯文本十进制 revision entry。 */
const val LOADER_REVISION_ENTRY = "assets/modloader/revision"

/** 用于识别哪个 split 文件是 loader split 的特征 entry。 */
const val LOADER_NATIVE_ENTRY = "assets/modloader/arm64-v8a/modloader.bin"

sealed interface LoaderSplitRevision {
    /** 归档显式声明了 revision。 */
    data class Known(val value: Int) : LoaderSplitRevision

    /** 归档可读，但没有 revision entry：早于该契约的构建。 */
    data object Absent : LoaderSplitRevision

    /** 归档或 entry 读不出来；不允许由此得出任何版本结论。 */
    data class Unreadable(val reason: String) : LoaderSplitRevision
}

class LoaderSplitRevisionReader {
    /** 从可随机访问的 loader split 文件读取（设备上已安装的 split）。 */
    fun read(file: File): LoaderSplitRevision = runCatching {
        ZipFile(file).use { archive ->
            val entry = archive.getEntry(LOADER_REVISION_ENTRY)
                ?: return@runCatching LoaderSplitRevision.Absent
            if (entry.isDirectory) {
                return@runCatching LoaderSplitRevision.Unreadable("revision entry 不是文件")
            }
            parseLoaderRevision(archive.getInputStream(entry).use { it.readCapped(MAXIMUM_REVISION_BYTES) })
        }
    }.getOrElse { error -> LoaderSplitRevision.Unreadable(error.readableReason()) }

    /**
     * 从不可随机访问的来源读取，例如 AssetManager.open()。按归档顺序扫描，
     * 因此构建脚本必须把 revision 写在 native 负载之前。
     */
    fun read(source: () -> InputStream): LoaderSplitRevision = runCatching {
        source().use { input ->
            ZipInputStream(input).use { archive ->
                repeat(MAXIMUM_SCANNED_ENTRIES) {
                    val entry = archive.nextEntry ?: return@runCatching LoaderSplitRevision.Absent
                    if (entry.name == LOADER_REVISION_ENTRY) {
                        return@runCatching parseLoaderRevision(archive.readCapped(MAXIMUM_REVISION_BYTES))
                    }
                }
                LoaderSplitRevision.Unreadable("revision entry 不在前 $MAXIMUM_SCANNED_ENTRIES 个 ZIP entry 内")
            }
        }
    }.getOrElse { error -> LoaderSplitRevision.Unreadable(error.readableReason()) }

    /** 按 native 负载识别 loader split；splitSourceDirs 的顺序与 splitNames 不对应。 */
    fun isLoaderSplit(file: File): Boolean = runCatching {
        ZipFile(file).use { archive -> archive.getEntry(LOADER_NATIVE_ENTRY) != null }
    }.getOrDefault(false)

    private fun Throwable.readableReason(): String =
        message?.takeIf(String::isNotBlank) ?: this::class.java.simpleName
}

internal fun parseLoaderRevision(bytes: ByteArray): LoaderSplitRevision {
    val text = bytes.toString(Charsets.US_ASCII).trim()
    if (!REVISION_FORMAT.matches(text)) {
        return LoaderSplitRevision.Unreadable("revision 内容不是十进制正整数")
    }
    return text.toIntOrNull()?.let(LoaderSplitRevision::Known)
        ?: LoaderSplitRevision.Unreadable("revision 超出支持范围")
}

private fun InputStream.readCapped(limit: Int): ByteArray {
    val buffer = ByteArray(limit + 1)
    var filled = 0
    while (filled < buffer.size) {
        val count = read(buffer, filled, buffer.size - filled)
        if (count < 0) break
        filled += count
    }
    require(filled <= limit) { "revision entry 超过 $limit 字节" }
    return buffer.copyOf(filled)
}

/** 只接受规范形式：无前导零、无符号、最多 9 位。 */
private val REVISION_FORMAT = Regex("[1-9][0-9]{0,8}")
private const val MAXIMUM_REVISION_BYTES = 32
private const val MAXIMUM_SCANNED_ENTRIES = 64
