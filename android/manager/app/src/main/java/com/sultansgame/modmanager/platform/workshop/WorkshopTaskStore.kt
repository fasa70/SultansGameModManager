package com.sultansgame.modmanager.platform.workshop

import android.content.Context
import com.sultansgame.modmanager.model.DownloadFailureCode
import com.sultansgame.modmanager.model.DownloadStage
import com.sultansgame.modmanager.model.DownloadTask
import com.sultansgame.modmanager.model.PublishedFileId
import com.sultansgame.modmanager.model.WorkshopAccessMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

class WorkshopTaskStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableTasks = MutableStateFlow(load())
    val tasks: StateFlow<List<DownloadTask>> = mutableTasks

    fun get(id: String): DownloadTask? = mutableTasks.value.firstOrNull { it.id == id }

    fun upsert(task: DownloadTask) {
        mutableTasks.value = (mutableTasks.value.filterNot { it.id == task.id } + task)
            .sortedByDescending(DownloadTask::createdAtEpochMillis)
        persist(mutableTasks.value)
    }

    fun remove(id: String) {
        mutableTasks.value = mutableTasks.value.filterNot { it.id == id }
        persist(mutableTasks.value)
    }

    fun update(id: String, transform: (DownloadTask) -> DownloadTask) {
        get(id)?.let { upsert(transform(it).copy(updatedAtEpochMillis = System.currentTimeMillis())) }
    }

    private fun load(): List<DownloadTask> = runCatching {
        val raw = preferences.getString(KEY_TASKS, "[]") ?: "[]"
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                parse(array.getJSONObject(index))?.let(::add)
            }
        }.sortedByDescending(DownloadTask::createdAtEpochMillis)
    }.getOrDefault(emptyList())

    private fun persist(tasks: List<DownloadTask>) {
        val array = JSONArray()
        tasks.forEach { task ->
            array.put(
                JSONObject()
                    .put("id", task.id)
                    .put("publishedFileId", task.publishedFileId.toString())
                    .put("accessMode", task.accessMode.name)
                    .put("stage", task.stage.name)
                    .put("title", task.title)
                    .put("downloadedBytes", task.downloadedBytes)
                    .put("totalBytes", task.totalBytes)
                    .put("failure", task.failure?.name)
                    .put("digest", task.rawArtifactDigestSha256)
                    .put("completedFileCount", task.completedFileCount)
                    .put("createdAt", task.createdAtEpochMillis)
                    .put("updatedAt", task.updatedAtEpochMillis),
            )
        }
        preferences.edit().putString(KEY_TASKS, array.toString()).apply()
    }

    private fun parse(json: JSONObject): DownloadTask? {
        val id = json.optString("id").takeIf(String::isNotBlank) ?: return null
        val publishedFileId = PublishedFileId.parse(json.optString("publishedFileId")) ?: return null
        val stage = enumValueOrNull<DownloadStage>(json.optString("stage")) ?: return null
        return DownloadTask(
            id = id,
            appId = com.sultansgame.modmanager.model.SULTANS_GAME_APP_ID,
            publishedFileId = publishedFileId,
            accessMode = enumValueOrNull<WorkshopAccessMode>(json.optString("accessMode")) ?: WorkshopAccessMode.Anonymous,
            stage = stage,
            title = json.optString("title"),
            downloadedBytes = json.optLong("downloadedBytes", 0),
            totalBytes = json.optLong("totalBytes", -1).takeIf { it >= 0 },
            failure = enumValueOrNull<DownloadFailureCode>(json.optString("failure")),
            rawArtifactDigestSha256 = json.optString("digest").takeIf(String::isNotBlank),
            completedFileCount = json.optInt("completedFileCount", 0),
            createdAtEpochMillis = json.optLong("createdAt", System.currentTimeMillis()),
            updatedAtEpochMillis = json.optLong("updatedAt", System.currentTimeMillis()),
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
        enumValues<T>().firstOrNull { it.name == value }

    private companion object {
        const val PREFERENCES_NAME = "workshop-download-queue"
        const val KEY_TASKS = "tasks-v1"
    }
}
