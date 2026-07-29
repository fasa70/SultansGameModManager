package com.sultansgame.modmanager.workshop

import com.sultansgame.modmanager.model.DownloadTask
import com.sultansgame.modmanager.model.PublishedFileId
import com.sultansgame.modmanager.model.WorkshopAccessMode
import com.sultansgame.modmanager.model.WorkshopItem
import java.net.URI

interface WorkshopProvider {
    fun getItem(appId: UInt, publishedFileId: PublishedFileId, accessMode: WorkshopAccessMode): WorkshopLookupResult
}

sealed interface WorkshopLookupResult {
    data class Available(val item: WorkshopItem) : WorkshopLookupResult
    data class Unavailable(val reason: String) : WorkshopLookupResult
}

interface DownloadTaskRepository {
    fun create(task: DownloadTask)
    fun update(task: DownloadTask)
    fun get(id: String): DownloadTask?
    fun list(): List<DownloadTask>
}

object WorkshopHttpPolicy {
    const val MAXIMUM_REDIRECTS = 5
    const val MAXIMUM_ARTIFACT_SIZE_BYTES: Long = 512L * 1024L * 1024L

    // New hosts must be verified against a public Steam response before inclusion.
    private val allowedHosts = setOf("api.steampowered.com", "steamusercontent-a.akamaihd.net")

    fun isAllowedMetadataUrl(url: String): Boolean = isAllowed(url, setOf("api.steampowered.com"))

    fun isAllowedArtifactUrl(url: String): Boolean = isAllowed(url, allowedHosts)

    private fun isAllowed(url: String, hosts: Set<String>): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null &&
            uri.host?.lowercase() in hosts &&
            uri.port in setOf(-1, 443)
    }.getOrDefault(false)
}
