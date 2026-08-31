package com.sultansgame.modmanager.platform.game

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.sultansgame.modmanager.bridge.LoaderBridge
import com.sultansgame.modmanager.model.GAME_MOD_STORAGE_AUTHORITY
import com.sultansgame.modmanager.model.GameModDirectoryEntry
import com.sultansgame.modmanager.model.GameModSyncAvailability
import com.sultansgame.modmanager.model.GameModSyncFailureCode
import com.sultansgame.modmanager.model.GameModSyncItem
import com.sultansgame.modmanager.model.GameModSyncStatus
import com.sultansgame.modmanager.model.GameSaveAvailability
import com.sultansgame.modmanager.model.GameSaveFailureCode
import com.sultansgame.modmanager.model.GameSaveStatus
import com.sultansgame.modmanager.model.ModStorageCall
import com.sultansgame.modmanager.model.SaveStorageCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class AndroidModStorageLoaderBridge(
    private val context: Context,
    private val cacheRoot: File,
    /** 期望的 loader revision；-1 表示管理器内嵌模板不可读，调用会被游戏侧拒绝。 */
    private val expectedLoaderRevision: Int = -1,
) : LoaderBridge {
    private companion object {
        const val GAME_PACKAGE = "com.gametree.sultan.pd"
        const val KEY_MANAGER_CACHE_KEYS = "managerCacheKeys"

        /** Byte thresholds for progress callbacks: at least every 256 KiB, plus every file end. */
        const val PROGRESS_REPORT_THRESHOLD_BYTES = 256L * 1024
        const val PROGRESS_REPORT_BUFFER_BYTES = 256 * 1024
    }

    private val uri = Uri.parse("content://$GAME_MOD_STORAGE_AUTHORITY")

    override suspend fun listMods(): GameModSyncStatus = withContext(Dispatchers.IO) { call(ModStorageCall.LIST_MODS, requestBundle()) }

    override suspend fun syncMod(
        item: GameModSyncItem,
        onProgress: ((writtenBytes: Long, totalBytes: Long) -> Unit)?,
    ): GameModSyncStatus = withContext(Dispatchers.IO) {
        supervisorScope {
            val pipe = ParcelFileDescriptor.createPipe()
            val writer = async(Dispatchers.IO) {
                pipe[1].use { descriptor ->
                    DataOutputStream(BufferedOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(descriptor))).use { output -> writeMod(output, item, onProgress) }
                }
            }
            try {
                val bundle = requestBundle().apply {
                    putString(ModStorageCall.KEY_CACHE_KEY, item.cacheKey)
                    putParcelable(ModStorageCall.KEY_INPUT, pipe[0])
                }
                val status = call(ModStorageCall.SYNC_MOD, bundle)
                if (!status.isReady) {
                    closeQuietly(pipe[0])
                    closeQuietly(pipe[1])
                    withContext(NonCancellable) { if (writer.isActive) writer.cancelAndJoin() }
                    return@supervisorScope status
                }
                closeQuietly(pipe[0])
                val writerFailure = try { writer.await(); null } catch (error: CancellationException) { throw error } catch (error: Exception) { error }
                if (writerFailure == null) status else unavailable(GameModSyncAvailability.Unknown, GameModSyncFailureCode.TransferInterrupted, writerFailure.message ?: "Mod 数据传输中断。")
            } finally {
                withContext(NonCancellable) {
                    closeQuietly(pipe[0])
                    closeQuietly(pipe[1])
                    if (writer.isActive) writer.cancelAndJoin()
                }
            }
        }
    }

    override suspend fun removeManagedMod(cacheKey: String): GameModSyncStatus = withContext(Dispatchers.IO) {
        call(ModStorageCall.REMOVE_MANAGED_MOD, requestBundle().apply { putString(ModStorageCall.KEY_CACHE_KEY, cacheKey) })
    }

    override suspend fun listSaveUsers(): GameSaveStatus = withContext(Dispatchers.IO) {
        callSave(SaveStorageCall.LIST_SAVE_USERS, requestBundle())
    }

    override suspend fun listSaveFiles(uid: String): GameSaveStatus = withContext(Dispatchers.IO) {
        callSave(SaveStorageCall.LIST_SAVE_FILES, requestBundle().apply { putString(SaveStorageCall.KEY_SAVE_USER, uid) })
    }

    /**
     * Reads a save through a pipe rather than the reply Bundle.
     *
     * The Binder transaction buffer is ~1 MB per process and a Bundle String is
     * parcelled as UTF-16, so a Bundle-carried save would cap out around half a
     * megabyte. Streaming matches how [syncMod] already moves bulk data and
     * leaves save size bounded by storage instead.
     */
    override suspend fun readSave(uid: String, fileName: String): GameSaveStatus = withContext(Dispatchers.IO) {
        supervisorScope {
            val pipe = ParcelFileDescriptor.createPipe()
            // The provider blocks writing into a full pipe, so the reader has to
            // run concurrently with the call rather than after it.
            val reader = async(Dispatchers.IO) {
                pipe[0].use { descriptor ->
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                }
            }
            try {
                val bundle = requestBundle().apply {
                    putString(SaveStorageCall.KEY_SAVE_USER, uid)
                    putString(SaveStorageCall.KEY_SAVE_FILE, fileName)
                    putParcelable(SaveStorageCall.KEY_OUTPUT, pipe[1])
                }
                val status = callSave(SaveStorageCall.READ_SAVE, bundle)
                closeQuietly(pipe[1])
                if (!status.isReady) {
                    closeQuietly(pipe[0])
                    withContext(NonCancellable) { if (reader.isActive) reader.cancelAndJoin() }
                    return@supervisorScope status
                }
                val bytes = try {
                    reader.await()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    return@supervisorScope saveUnavailable(
                        GameSaveAvailability.Unknown,
                        GameSaveFailureCode.TransferInterrupted,
                        error.message ?: "存档数据传输中断。",
                    )
                }
                val expected = status.contentLength
                if (expected != null && expected != bytes.size.toLong()) {
                    return@supervisorScope saveUnavailable(
                        GameSaveAvailability.Unknown,
                        GameSaveFailureCode.TransferInterrupted,
                        "存档数据不完整（应为 $expected 字节，实际 ${bytes.size} 字节）。",
                    )
                }
                status.copy(content = bytes.toString(Charsets.UTF_8))
            } finally {
                withContext(NonCancellable) {
                    closeQuietly(pipe[0])
                    closeQuietly(pipe[1])
                    if (reader.isActive) reader.cancelAndJoin()
                }
            }
        }
    }

    /** Writes a save through a pipe; see [readSave] for why the Bundle is unused. */
    override suspend fun writeSave(uid: String, fileName: String, content: String): GameSaveStatus = withContext(Dispatchers.IO) {
        supervisorScope {
            val bytes = content.toByteArray(Charsets.UTF_8)
            val pipe = ParcelFileDescriptor.createPipe()
            val writer = async(Dispatchers.IO) {
                pipe[1].use { descriptor ->
                    ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { it.write(bytes) }
                }
            }
            try {
                val bundle = requestBundle().apply {
                    putString(SaveStorageCall.KEY_SAVE_USER, uid)
                    putString(SaveStorageCall.KEY_SAVE_FILE, fileName)
                    putParcelable(SaveStorageCall.KEY_INPUT, pipe[0])
                }
                val status = callSave(SaveStorageCall.WRITE_SAVE, bundle)
                closeQuietly(pipe[0])
                if (!status.isReady) {
                    closeQuietly(pipe[1])
                    withContext(NonCancellable) { if (writer.isActive) writer.cancelAndJoin() }
                    return@supervisorScope status
                }
                val writerFailure = try {
                    writer.await()
                    null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    error
                }
                if (writerFailure != null) {
                    return@supervisorScope saveUnavailable(
                        GameSaveAvailability.Unknown,
                        GameSaveFailureCode.TransferInterrupted,
                        writerFailure.message ?: "存档数据传输中断。",
                    )
                }
                // The provider commits only a complete copy, so a byte-count
                // mismatch means the file on disk is not what we sent.
                val expected = status.contentLength
                if (expected != null && expected != bytes.size.toLong()) {
                    return@supervisorScope saveUnavailable(
                        GameSaveAvailability.Unknown,
                        GameSaveFailureCode.TransferInterrupted,
                        "存档写入不完整（应为 ${bytes.size} 字节，实际 $expected 字节）。",
                    )
                }
                status
            } finally {
                withContext(NonCancellable) {
                    closeQuietly(pipe[0])
                    closeQuietly(pipe[1])
                    if (writer.isActive) writer.cancelAndJoin()
                }
            }
        }
    }

    private fun requestBundle() = Bundle().apply { putInt(ModStorageCall.KEY_EXPECTED_REVISION, expectedLoaderRevision) }

    private fun callSave(method: String, extras: Bundle): GameSaveStatus = try {
        parseSaveResult(context.contentResolver.call(uri, method, null, extras))
    } catch (error: CancellationException) {
        throw error
    } catch (_: SecurityException) {
        saveUnavailable(GameSaveAvailability.Unauthorized, GameSaveFailureCode.ProviderAccessDenied, "Android 系统拒绝访问游戏存档服务；请重新修补并安装匹配的游戏版本。")
    } catch (_: IllegalArgumentException) {
        saveActivationRequiredStatus()
    } catch (error: Exception) {
        saveUnavailable(GameSaveAvailability.Unknown, GameSaveFailureCode.InternalError, error.message ?: "无法与游戏存档服务通信。")
    }

    private fun parseSaveResult(bundle: Bundle?): GameSaveStatus {
        if (bundle == null) return saveActivationRequiredStatus()
        val code = bundle.getString(ModStorageCall.KEY_RESULT_CODE).orEmpty()
        val reason = bundle.getString(ModStorageCall.KEY_RESULT_REASON)
        val availability = when (code) {
            "ok" -> GameSaveAvailability.Available
            "unauthorized" -> GameSaveAvailability.Unauthorized
            "incompatible" -> GameSaveAvailability.Incompatible
            else -> GameSaveAvailability.Unknown
        }
        val failure = when (code) {
            "ok" -> GameSaveFailureCode.None
            "unauthorized" -> GameSaveFailureCode.Unauthorized
            "incompatible" -> GameSaveFailureCode.ProtocolMismatch
            "saveNotFound" -> GameSaveFailureCode.NotFound
            "saveTooLarge" -> GameSaveFailureCode.TooLarge
            "validationFailed" -> GameSaveFailureCode.JsonInvalid
            "commitFailed" -> GameSaveFailureCode.CommitFailed
            "insufficientStorage" -> GameSaveFailureCode.InsufficientStorage
            "failed" -> GameSaveFailureCode.InternalError
            "invalid" -> GameSaveFailureCode.InvalidName
            else -> GameSaveFailureCode.Unknown
        }
        val users = bundle.getStringArrayList(SaveStorageCall.KEY_SAVE_USERS).orEmpty()
        val files = bundle.getStringArrayList(SaveStorageCall.KEY_SAVE_FILES).orEmpty()
        val length = bundle.getLong(SaveStorageCall.KEY_SAVE_LENGTH, -1L).takeIf { it >= 0 }
        // Save content arrives over a pipe, not in this bundle; readSave fills it in.
        return GameSaveStatus(
            availability = availability,
            users = users,
            files = files,
            contentLength = length,
            failureCode = failure,
            reason = reason,
        )
    }

    private fun saveActivationRequiredStatus(): GameSaveStatus {
        val provider = context.packageManager.resolveContentProvider(GAME_MOD_STORAGE_AUTHORITY, 0)
        if (provider?.packageName != GAME_PACKAGE) return saveUnavailable(GameSaveAvailability.ProviderMissing, GameSaveFailureCode.ProviderMissing, "游戏内存档服务未安装；请重新修补并安装匹配的游戏版本。")
        val enabled = context.packageManager.getComponentEnabledSetting(android.content.ComponentName(provider.packageName, provider.name)) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (!enabled) return saveUnavailable(GameSaveAvailability.ProviderMissing, GameSaveFailureCode.ProviderMissing, "游戏内存档服务已被禁用；请重新修补并安装匹配的游戏版本。")
        return saveUnavailable(GameSaveAvailability.Unknown, GameSaveFailureCode.Unknown, "无法与游戏存档服务通信。")
    }

    private fun saveUnavailable(availability: GameSaveAvailability, failure: GameSaveFailureCode, reason: String) =
        GameSaveStatus(availability = availability, failureCode = failure, reason = reason)

    private fun call(method: String, extras: Bundle): GameModSyncStatus = try {
        parseResult(context.contentResolver.call(uri, method, null, extras))
    } catch (error: CancellationException) {
        throw error
    } catch (_: SecurityException) {
        unavailable(GameModSyncAvailability.Unauthorized, GameModSyncFailureCode.ProviderAccessDenied, "Android 系统拒绝访问游戏 Mod 同步服务；请重新修补并安装匹配的游戏版本。")
    } catch (_: IllegalArgumentException) {
        activationRequiredStatus()
    } catch (error: Exception) {
        unavailable(GameModSyncAvailability.Unknown, GameModSyncFailureCode.InternalError, error.message ?: "无法与游戏 Mod 同步服务通信。")
    }

    private fun parseResult(bundle: Bundle?): GameModSyncStatus {
        if (bundle == null) return activationRequiredStatus()
        val code = bundle.getString(ModStorageCall.KEY_RESULT_CODE).orEmpty()
        val reason = bundle.getString(ModStorageCall.KEY_RESULT_REASON)
        val availability = when (code) {
            "ok" -> GameModSyncAvailability.Available
            "unauthorized" -> GameModSyncAvailability.Unauthorized
            "incompatible" -> GameModSyncAvailability.Incompatible
            else -> GameModSyncAvailability.Unknown
        }
        val failure = when (code) {
            "ok" -> GameModSyncFailureCode.None
            "unauthorized" -> GameModSyncFailureCode.Unauthorized
            "incompatible" -> GameModSyncFailureCode.ProtocolMismatch
            "invalid" -> GameModSyncFailureCode.InvalidMod
            "validationFailed" -> GameModSyncFailureCode.ValidationFailed
            "commitFailed" -> GameModSyncFailureCode.CommitFailed
            "insufficientStorage" -> GameModSyncFailureCode.InsufficientStorage
            "failed" -> GameModSyncFailureCode.InternalError
            else -> GameModSyncFailureCode.Unknown
        }
        val names = bundle.getStringArrayList(ModStorageCall.KEY_MOD_NAMES).orEmpty()
        val managerKeys = bundle.getStringArrayList(KEY_MANAGER_CACHE_KEYS).orEmpty()
        val mods = names.mapIndexed { index, name -> GameModDirectoryEntry(name, managerKeys.getOrNull(index)?.takeIf(String::isNotBlank)) }
        return GameModSyncStatus(availability, mods, failure, reason)
    }

    private fun activationRequiredStatus(): GameModSyncStatus {
        val provider = context.packageManager.resolveContentProvider(GAME_MOD_STORAGE_AUTHORITY, 0)
        if (provider?.packageName != GAME_PACKAGE) return unavailable(GameModSyncAvailability.ProviderMissing, GameModSyncFailureCode.ProviderMissing, "游戏内 Mod 同步服务未安装；请重新修补并安装匹配的游戏版本。")
        val enabled = context.packageManager.getComponentEnabledSetting(android.content.ComponentName(provider.packageName, provider.name)) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (!enabled) return unavailable(GameModSyncAvailability.ProviderMissing, GameModSyncFailureCode.ProviderMissing, "游戏内 Mod 同步服务已被禁用；请重新修补并安装匹配的游戏版本。")
        return unavailable(GameModSyncAvailability.ActivationRequired, GameModSyncFailureCode.ActivationRequired, "请先启动游戏并保持在后台，然后返回 Manager；系统会自动继续同步。")
    }

    private fun unavailable(availability: GameModSyncAvailability, failure: GameModSyncFailureCode, reason: String) = GameModSyncStatus(availability = availability, failureCode = failure, reason = reason)
    private fun closeQuietly(descriptor: ParcelFileDescriptor) { try { descriptor.close() } catch (_: Exception) {} }

    private fun writeMod(
        output: DataOutputStream,
        item: GameModSyncItem,
        onProgress: ((writtenBytes: Long, totalBytes: Long) -> Unit)?,
    ) {
        val root = File(cacheRoot, item.cacheKey)
        require(root.isDirectory) { "Mod 缓存已不存在：${item.displayName}" }
        val files = root.walkTopDown().filter(File::isFile).sortedBy { it.relativeTo(root).invariantSeparatorsPath }.toList()
        val totalBytes = files.sumOf(File::length)
        var writtenBytes = 0L
        var lastReportedBytes = 0L
        val report = { ->
            // A file may grow between sizing and copying; keep the reported value consistent.
            val written = minOf(writtenBytes, totalBytes)
            if (written != lastReportedBytes) {
                lastReportedBytes = written
                onProgress?.invoke(written, totalBytes)
            }
        }
        output.writeInt(files.size)
        report()
        files.forEach { file ->
            val relativePath = file.relativeTo(root).invariantSeparatorsPath
            output.writeUTF(relativePath)
            output.writeLong(file.length())
            output.writeUTF(sha256(file))
            if (onProgress != null) {
                val buffer = ByteArray(PROGRESS_REPORT_BUFFER_BYTES)
                while (true) {
                    val count = FileInputStream(file).use { input -> input.read(buffer) }
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    writtenBytes += count
                    if (writtenBytes - lastReportedBytes >= PROGRESS_REPORT_THRESHOLD_BYTES) report()
                }
            } else {
                FileInputStream(file).use { input -> writtenBytes += input.copyTo(output) }
            }
            report()
        }
        output.flush()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
