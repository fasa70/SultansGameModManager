package com.sultansgame.modmanager.platform.game

import android.content.Context
import com.sultansgame.modmanager.apk.LoaderSplitRevision
import com.sultansgame.modmanager.apk.LoaderSplitRevisionReader
import com.sultansgame.modmanager.model.DeviceSigningKeyState
import com.sultansgame.modmanager.platform.patch.DeviceSigningKeyStore
import com.sultansgame.modmanager.platform.patch.LOADER_TEMPLATE_ASSET
import java.io.File

internal class GameReadinessProbe(
    private val context: Context,
    private val keyStore: DeviceSigningKeyStore,
    private val reader: LoaderSplitRevisionReader = LoaderSplitRevisionReader(),
) {
    /** 内嵌模板在进程生命周期内不变，只读一次（含失败结果也缓存）。 */
    private val expectedRevision: LoaderSplitRevision by lazy {
        reader.read { context.assets.open(LOADER_TEMPLATE_ASSET) }
    }

    /** 内嵌模板声明的 revision；读不到时返回 -1，此时调用会被游戏侧拒绝。 */
    fun embeddedTemplateRevision(): Int = (expectedRevision as? LoaderSplitRevision.Known)?.value ?: -1

    private var cachedInstalled: CachedRevision? = null

    /** 阻塞的 ZIP/KeyStore IO；只能在 Dispatchers.IO 上调用。 */
    fun evaluate(probe: GameProbeResult?): GameReadiness = evaluateGameReadiness(
        probe = probe,
        deviceCertificateSha256 = runCatching(keyStore::certificateSha256).getOrNull(),
        deviceKeyState = runCatching(keyStore::state).getOrDefault(DeviceSigningKeyState.NotCreated),
        loaderSplitName = LOADER_SPLIT_NAME,
        expectedRevision = expectedRevision,
        installedRevision = { installedRevision(probe) },
    )

    private fun installedRevision(probe: GameProbeResult?): LoaderSplitRevision {
        val paths = (probe as? GameProbeResult.Found)?.snapshot?.artifacts?.splitApkPaths.orEmpty()
        val split = locateLoaderSplit(paths)
            ?: return LoaderSplitRevision.Unreadable("未找到 modloader split 文件")
        val key = CachedRevisionKey(split.absolutePath, split.length(), split.lastModified())
        cachedInstalled?.takeIf { it.key == key }?.let { return it.revision }
        val revision = reader.read(split)
        cachedInstalled = CachedRevision(key, revision)
        return revision
    }

    /**
     * PackageManagerGameProbe 对 splitSourceDirs 做过排序，索引与 splitNames
     * 不再对应；先按安装器归一化后的文件名快速命中，再退回全量特征探测。
     */
    private fun locateLoaderSplit(paths: List<String>): File? {
        val files = paths.map(::File).filter(File::isFile)
        files.firstOrNull { it.name == LOADER_SPLIT_FILE_NAME }
            ?.takeIf(reader::isLoaderSplit)
            ?.let { return it }
        return files.firstOrNull(reader::isLoaderSplit)
    }

    private data class CachedRevisionKey(val path: String, val length: Long, val lastModified: Long)

    private data class CachedRevision(val key: CachedRevisionKey, val revision: LoaderSplitRevision)

    private companion object {
        const val LOADER_SPLIT_NAME = "modloader"
        const val LOADER_SPLIT_FILE_NAME = "split_modloader.apk"
    }
}
