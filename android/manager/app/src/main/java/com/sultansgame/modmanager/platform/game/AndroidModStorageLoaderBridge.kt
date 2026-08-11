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
import com.sultansgame.modmanager.model.MOD_STORAGE_PROTOCOL_VERSION
import com.sultansgame.modmanager.model.ModStorageCall
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
) : LoaderBridge {
    private companion object {
        const val GAME_PACKAGE = "com.gametree.sultan.pd"
        const val KEY_MANAGER_CACHE_KEYS = "managerCacheKeys"
    }

    private val uri = Uri.parse("content://$GAME_MOD_STORAGE_AUTHORITY")

    override suspend fun listMods(): GameModSyncStatus = withContext(Dispatchers.IO) { call(ModStorageCall.LIST_MODS, requestBundle()) }

    override suspend fun syncMod(item: GameModSyncItem): GameModSyncStatus = withContext(Dispatchers.IO) {
        supervisorScope {
            val pipe = ParcelFileDescriptor.createPipe()
            val writer = async(Dispatchers.IO) {
                pipe[1].use { descriptor ->
                    DataOutputStream(BufferedOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(descriptor))).use { output -> writeMod(output, item) }
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

    private fun requestBundle() = Bundle().apply { putInt(ModStorageCall.KEY_PROTOCOL_VERSION, MOD_STORAGE_PROTOCOL_VERSION) }

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

    private fun writeMod(output: DataOutputStream, item: GameModSyncItem) {
        val root = File(cacheRoot, item.cacheKey)
        require(root.isDirectory) { "Mod 缓存已不存在：${item.displayName}" }
        val files = root.walkTopDown().filter(File::isFile).sortedBy { it.relativeTo(root).invariantSeparatorsPath }.toList()
        output.writeInt(files.size)
        files.forEach { file ->
            val relativePath = file.relativeTo(root).invariantSeparatorsPath
            output.writeUTF(relativePath)
            output.writeLong(file.length())
            output.writeUTF(sha256(file))
            FileInputStream(file).use { input -> input.copyTo(output) }
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
