package com.sultansgame.modmanager.platform.merge

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MergeBridgeException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class PythonRemapResult(
    val conflicts: List<PythonRemapConflict>,
    val warnings: List<PythonRemapWarning>,
    val bestEffort: Boolean,
    val remappedEntries: Int,
    val roots: List<File>,
    val mergedOutput: File,
)

data class PythonRemapConflict(
    val entityType: String,
    val id: String,
    val modIndexes: List<Int>,
)

data class PythonRemapWarning(
    val code: String,
    val message: String,
    val entityType: String? = null,
    val count: Int? = null,
)

/** Serializes Chaquopy access because the upstream adapter uses global DataManager state. */
class ChaquopyMergeBridge(private val context: Context) {
    private val mutex = Mutex()
    private val json = Json

    suspend fun remap(
        inputRoots: List<File>,
        catalogFile: File,
        outputRoot: File,
    ): PythonRemapResult = mutex.withLock {
        if (inputRoots.size < 2) throw MergeBridgeException("至少需要两个 Mod")
        if (!catalogFile.isFile) throw MergeBridgeException("ID Catalog 不可读")
        inputRoots.forEach { root ->
            if (!root.isDirectory || root.isSymbolicLink) {
                throw MergeBridgeException("Mod 工作目录不可安全访问：${root.name}")
            }
        }
        try {
            ensurePythonStarted()
            val request = buildJsonObject {
                put("input_roots", JsonArray(inputRoots.map { JsonPrimitive(it.absolutePath) }))
                put("catalog_path", JsonPrimitive(catalogFile.absolutePath))
                put("output_root", JsonPrimitive(outputRoot.absolutePath))
            }
            val response = Python.getInstance()
                .getModule("android_merge_worker")
                .callAttr("run_json", request.toString())
                .toJava(String::class.java)
            decodeResponse(response, outputRoot, inputRoots.size)
        } catch (error: CancellationException) {
            throw error
        } catch (error: MergeBridgeException) {
            throw error
        } catch (error: Throwable) {
            throw MergeBridgeException(
                "Python 合并运行时失败：${error.message ?: error::class.java.simpleName}",
                error,
            )
        }
    }

    private fun ensurePythonStarted() {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
    }

    private fun decodeResponse(text: String, outputRoot: File, expectedRootCount: Int): PythonRemapResult {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (error: Throwable) {
            throw MergeBridgeException("Python 返回结果不是有效 JSON", error)
        }
        val status = root["status"]?.jsonPrimitive?.contentOrNull
            ?: throw MergeBridgeException("Python 返回缺少 status")
        if (status != "ok") {
            val reason = root["error"]?.jsonPrimitive?.contentOrNull ?: "输入无法完成重映射"
            throw MergeBridgeException("Python 合并失败：$reason")
        }
        val rootNames = root["roots"]?.jsonArray?.map { value ->
            value.jsonPrimitive.contentOrNull ?: throw MergeBridgeException("Python 返回了无效工作目录")
        } ?: throw MergeBridgeException("Python 返回缺少 remapped roots")
        if (rootNames.size != expectedRootCount ||
            rootNames.toSet().size != rootNames.size ||
            rootNames.any { it.isBlank() || it.contains('/') || it.contains('\\') || it == "." || it == ".." }
        ) {
            throw MergeBridgeException("Python 返回的工作目录数量或路径无效")
        }
        val outputCanonical = outputRoot.canonicalFile
        val roots = rootNames.map { name ->
            val candidate = File(outputRoot, name).canonicalFile
            if (candidate.parentFile != outputCanonical || !candidate.isDirectory || candidate.isSymbolicLink) {
                throw MergeBridgeException("Python 返回的工作目录越界：$name")
            }
            candidate
        }
        val mergedName = root["merged_output"]?.jsonPrimitive?.contentOrNull
            ?: throw MergeBridgeException("Python 返回缺少 merged_output")
        if (mergedName.isBlank() || mergedName.contains('/') || mergedName.contains('\\') || mergedName == "." || mergedName == "..") {
            throw MergeBridgeException("Python 返回了无效合并输出目录")
        }
        val mergedOutput = File(outputRoot, mergedName).canonicalFile
        if (mergedOutput.parentFile != outputCanonical || !mergedOutput.isDirectory || mergedOutput.isSymbolicLink) {
            throw MergeBridgeException("Python 返回的合并输出目录越界：$mergedName")
        }
        val conflicts = root["conflicts"]?.jsonObject.orEmpty().flatMap { (entity, values) ->
            values.jsonObject.map { (id, indexes) ->
                val modIndexes = indexes.jsonArray.map { it.jsonPrimitive.int }
                if (modIndexes.any { it !in 0 until expectedRootCount }) {
                    throw MergeBridgeException("Python 返回了越界 Mod 索引")
                }
                PythonRemapConflict(entity, id, modIndexes)
            }
        }
        val warnings = root["warnings"]?.jsonArray?.map { value ->
            val warning = value.jsonObject
            val code = warning["code"]?.jsonPrimitive?.contentOrNull
                ?: throw MergeBridgeException("Python 返回了无效 warning code")
            val severity = warning["severity"]?.jsonPrimitive?.contentOrNull
                ?: throw MergeBridgeException("Python 返回了无效 warning severity")
            if (severity != "warning") throw MergeBridgeException("Python 返回了不支持的 warning severity")
            val message = warning["message"]?.jsonPrimitive?.contentOrNull
                ?: throw MergeBridgeException("Python 返回了无效 warning message")
            val entityType = warning["entity_type"]?.jsonPrimitive?.contentOrNull
            val count = warning["count"]?.jsonPrimitive?.int
            if (count != null && count < 0) throw MergeBridgeException("Python 返回了无效 warning count")
            PythonRemapWarning(code, message, entityType, count)
        }.orEmpty()
        val bestEffort = root["best_effort"]?.jsonPrimitive?.content == "true"
        val remappedEntries = root["remap_tables"]?.jsonObject?.values?.sumOf { table ->
            table.jsonObject.values.sumOf { value -> value.jsonObject.size }
        } ?: 0
        return PythonRemapResult(conflicts, warnings, bestEffort, remappedEntries, roots, mergedOutput)
    }
}

private val File.isSymbolicLink: Boolean
    get() = java.nio.file.Files.isSymbolicLink(toPath())

private val JsonPrimitive.int: Int
    get() = content.toIntOrNull() ?: throw MergeBridgeException("Python 返回了无效整数")
