package com.sultansgame.modmanager.workshop

import com.sultansgame.modmanager.model.DownloadTask
import com.sultansgame.modmanager.model.PublishedFileId
import com.sultansgame.modmanager.model.WorkshopAccessMode
import com.sultansgame.modmanager.model.WorkshopAvailability
import com.sultansgame.modmanager.model.WorkshopItem
import java.util.concurrent.ConcurrentHashMap

data class PublicWorkshopMetadata(
    val consumerAppId: UInt?,
    val publishedFileId: PublishedFileId,
    val resultCode: Int,
    val title: String?,
    val fileUrl: String?,
    val previewUrl: String?,
    val updatedAtEpochSeconds: Long?,
    val declaredSizeBytes: Long?,
    val description: String? = null,
    val timeCreatedEpochSeconds: Long? = null,
    val creatorSteamId: ULong? = null,
    val tags: List<String> = emptyList(),
)

interface PublicWorkshopMetadataTransport {
    fun getPublishedFileDetails(appId: UInt, publishedFileId: PublishedFileId): PublicWorkshopMetadata?
}

class SteamPublicWorkshopProvider(
    private val transport: PublicWorkshopMetadataTransport,
    private val detailTransport: PublicWorkshopDetailTransport? = null,
) : WorkshopProvider {
    override fun getItem(
        appId: UInt,
        publishedFileId: PublishedFileId,
        accessMode: WorkshopAccessMode,
    ): WorkshopLookupResult = getItem(appId, publishedFileId, includeCommunityDetail = false)

    fun getItemWithCommunityDetail(
        appId: UInt,
        publishedFileId: PublishedFileId,
    ): WorkshopLookupResult = getItem(appId, publishedFileId, includeCommunityDetail = true)

    private fun getItem(
        appId: UInt,
        publishedFileId: PublishedFileId,
        includeCommunityDetail: Boolean,
    ): WorkshopLookupResult {
        val metadata = transport.getPublishedFileDetails(appId, publishedFileId)
            ?: return WorkshopLookupResult.Unavailable("无法获取公开 Workshop 元数据。")
        if (metadata.consumerAppId != appId || metadata.publishedFileId != publishedFileId) {
            return WorkshopLookupResult.Unavailable("Workshop 元数据与请求不匹配。")
        }
        val available = metadata.resultCode == RESULT_OK &&
            metadata.fileUrl != null && WorkshopHttpPolicy.isAllowedArtifactUrl(metadata.fileUrl)
        val availability = when {
            available -> WorkshopAvailability.PublicDownloadAvailable
            else -> WorkshopAvailability.Unavailable
        }
        val detail = detailTransport?.takeIf { includeCommunityDetail }?.getPublishedFileDetail(publishedFileId)
        return WorkshopLookupResult.Available(
            WorkshopItem(
                appId = appId,
                publishedFileId = publishedFileId,
                title = metadata.title.orEmpty().ifBlank { "Workshop 条目 $publishedFileId" },
                updatedAtEpochSeconds = metadata.updatedAtEpochSeconds,
                fileUrl = metadata.fileUrl?.takeIf(WorkshopHttpPolicy::isAllowedArtifactUrl),
                previewUrl = WorkshopHttpPolicy.normalizePreviewImageUrl(metadata.previewUrl)
                    ?: WorkshopHttpPolicy.normalizePreviewImageUrl(detail?.previewUrl),
                declaredSizeBytes = metadata.declaredSizeBytes?.takeIf { it >= 0 },
                availability = availability,
                description = metadata.description.orEmpty(),
                authorName = detail?.authorName.orEmpty(),
                shortDescription = metadata.description.orEmpty(),
                authorProfileUrl = detail?.authorProfileUrl?.takeIf(WorkshopHttpPolicy::isAllowedAuthorProfileUrl),
                detailUrl = detailUrlFor(publishedFileId),
                createdAtEpochSeconds = metadata.timeCreatedEpochSeconds,
                creatorSteamId = metadata.creatorSteamId,
                tags = metadata.tags,
                isDownloadInfoResolved = true,
            ),
        )
    }

    private fun detailUrlFor(publishedFileId: PublishedFileId): String =
        "https://steamcommunity.com/sharedfiles/filedetails/?id=$publishedFileId"

    private companion object {
        const val RESULT_OK = 1
    }
}

class InMemoryDownloadTaskRepository : DownloadTaskRepository {
    private val tasks = ConcurrentHashMap<String, DownloadTask>()

    override fun create(task: DownloadTask) {
        check(tasks.putIfAbsent(task.id, task) == null) { "下载任务已存在" }
    }

    override fun update(task: DownloadTask) {
        check(tasks.containsKey(task.id)) { "下载任务不存在" }
        tasks[task.id] = task
    }

    override fun get(id: String): DownloadTask? = tasks[id]

    override fun list(): List<DownloadTask> = tasks.values.sortedBy { it.id }
}
