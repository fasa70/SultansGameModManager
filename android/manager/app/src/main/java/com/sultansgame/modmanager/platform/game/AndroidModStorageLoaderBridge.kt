package com.sultansgame.modmanager.platform.game

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.sultansgame.modmanager.bridge.ApplyRequest
import com.sultansgame.modmanager.bridge.ApplyResult
import com.sultansgame.modmanager.bridge.LoaderBridge
import com.sultansgame.modmanager.model.DeploymentEntry
import com.sultansgame.modmanager.model.GameModEntry
import com.sultansgame.modmanager.model.GameModStorageStatus
import com.sultansgame.modmanager.model.GAME_MOD_STORAGE_AUTHORITY
import com.sultansgame.modmanager.model.LoaderFailure
import com.sultansgame.modmanager.model.LoaderRuntimeState
import com.sultansgame.modmanager.model.LoaderStatus
import com.sultansgame.modmanager.model.MOD_STORAGE_PROTOCOL_VERSION
import com.sultansgame.modmanager.model.ModStorageAvailability
import com.sultansgame.modmanager.model.ModStorageCall
import com.sultansgame.modmanager.model.ModStorageFailureCode
import com.sultansgame.modmanager.model.ModStorageSyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
    private val uri = Uri.parse("content://$GAME_MOD_STORAGE_AUTHORITY")

    override fun runtimeStatus(): Flow<LoaderStatus> = flowOf(
        LoaderStatus(
            state = LoaderRuntimeState.NotStarted,
            failure = LoaderFailure.None,
            rawStateCode = LoaderRuntimeState.NotStarted.nativeCode,
            rawFailureCode = LoaderFailure.None.nativeCode,
        ),
    )

    override suspend fun storageStatus(): GameModStorageStatus = withContext(Dispatchers.IO) {
        call(ModStorageCall.STATUS, requestBundle())
    }

    override suspend fun requestApply(request: ApplyRequest): ApplyResult = withContext(Dispatchers.IO) {
        val readPipe = ParcelFileDescriptor.createPipe()
        val writer = async(Dispatchers.IO) {
            readPipe[1].use { descriptor ->
                DataOutputStream(BufferedOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(descriptor))).use { output ->
                    writeSnapshot(output, request.snapshot.enabledEntries)
                }
            }
        }
        val bundle = requestBundle().apply {
            putString(ModStorageCall.KEY_REVISION, request.snapshot.revision)
            putString(ModStorageCall.KEY_SNAPSHOT_DIGEST, request.snapshot.snapshotDigestSha256)
            putBoolean(ModStorageCall.KEY_ALLOW_EXTERNAL_REPLACEMENT, request.snapshot.allowExternalReplacement)
            putBoolean("authorize", true)
            putParcelable("input", readPipe[0])
        }
        val status = try {
            call(ModStorageCall.SYNC_SNAPSHOT, bundle)
        } finally {
            readPipe[0].close()
        }
        val writerFailure = runCatching { writer.await() }.exceptionOrNull()
        if (writerFailure != null && status.isReady) {
            return@withContext ApplyResult.Rejected(
                unavailable(
                    ModStorageAvailability.Unknown,
                    ModStorageFailureCode.TransferInterrupted,
                    writerFailure.message ?: "Mod 数据传输中断",
                ),
            )
        }
        if (status.isReady) ApplyResult.Applied(ModStorageSyncResult(status, request.snapshot.revision))
        else ApplyResult.Rejected(status)
    }

    override suspend fun revokeStorageAuthorization(): GameModStorageStatus = withContext(Dispatchers.IO) {
        call(ModStorageCall.REVOKE_AUTHORIZATION, requestBundle())
    }

    private fun requestBundle() = Bundle().apply {
        putInt(ModStorageCall.KEY_PROTOCOL_VERSION, MOD_STORAGE_PROTOCOL_VERSION)
    }

    private fun call(method: String, extras: Bundle): GameModStorageStatus = try {
        parseResult(context.contentResolver.call(uri, method, null, extras))
    } catch (_: SecurityException) {
        unavailable(ModStorageAvailability.Unauthorized, ModStorageFailureCode.Unauthorized, "Manager 未获游戏 Mod 管理授权")
    } catch (_: IllegalArgumentException) {
        unavailable(ModStorageAvailability.ProviderMissing, ModStorageFailureCode.ProviderMissing, "游戏内 ModStorageProvider 不可用")
    } catch (error: Exception) {
        unavailable(ModStorageAvailability.Unknown, ModStorageFailureCode.InternalError, error.message ?: "无法与游戏 Mod 服务通信")
    }

    private fun parseResult(bundle: Bundle?): GameModStorageStatus {
        if (bundle == null) return unavailable(ModStorageAvailability.ProviderMissing, ModStorageFailureCode.ProviderMissing, "游戏未返回 Mod 状态")
        val code = bundle.getString(ModStorageCall.KEY_RESULT_CODE).orEmpty()
        val reason = bundle.getString(ModStorageCall.KEY_RESULT_REASON)
        val availability = when (code) {
            "ok" -> ModStorageAvailability.Available
            "unauthorized" -> ModStorageAvailability.Unauthorized
            "incompatible" -> ModStorageAvailability.Incompatible
            else -> ModStorageAvailability.Unknown
        }
        val failure = when (code) {
            "ok" -> ModStorageFailureCode.None
            "unauthorized" -> ModStorageFailureCode.Unauthorized
            "incompatible" -> ModStorageFailureCode.ProtocolMismatch
            "invalid" -> ModStorageFailureCode.InvalidSnapshot
            else -> ModStorageFailureCode.InternalError
        }
        val names = bundle.getStringArrayList("modNames").orEmpty()
        return GameModStorageStatus(
            availability = availability,
            protocolVersion = bundle.getInt(ModStorageCall.KEY_PROTOCOL_VERSION, MOD_STORAGE_PROTOCOL_VERSION),
            revision = bundle.getString(ModStorageCall.KEY_REVISION),
            mods = names.map { name -> GameModEntry(name, null, null, 0, name.matches(Regex("\\d{6}--[0-9a-f]{64}"))) },
            failureCode = failure,
            reason = reason,
        )
    }

    private fun unavailable(availability: ModStorageAvailability, failure: ModStorageFailureCode, reason: String) =
        GameModStorageStatus(availability = availability, failureCode = failure, reason = reason)

    private fun writeSnapshot(output: DataOutputStream, entries: List<DeploymentEntry>) {
        output.writeInt(entries.size)
        entries.forEach { entry ->
            val root = File(cacheRoot, entry.cacheKey)
            require(root.isDirectory) { "Mod 缓存已不存在：${entry.displayName}" }
            val files = root.walkTopDown().filter(File::isFile).sortedBy { it.relativeTo(root).invariantSeparatorsPath }.toList()
            output.writeUTF(entry.directoryName)
            output.writeInt(files.size)
            files.forEach { file ->
                val relativePath = file.relativeTo(root).invariantSeparatorsPath
                output.writeUTF(relativePath)
                output.writeLong(file.length())
                output.writeUTF(sha256(file))
                FileInputStream(file).use { input -> input.copyTo(output) }
            }
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
