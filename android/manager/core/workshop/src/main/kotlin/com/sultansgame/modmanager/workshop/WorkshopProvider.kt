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
    private val artifactHosts = setOf("api.steampowered.com", "steamusercontent-a.akamaihd.net")
    private val previewImageHosts = setOf(
        "steamusercontent-a.akamaihd.net",
        "images.akamai.steamusercontent.com",
        "steamuserimages-a.akamaihd.net",
    )

    fun isAllowedMetadataUrl(url: String): Boolean = isAllowed(url, setOf("api.steampowered.com"))

    fun isAllowedArtifactUrl(url: String): Boolean = isAllowed(url, artifactHosts)

    fun isAllowedPreviewImageUrl(url: String): Boolean = isAllowed(url, previewImageHosts)

    fun isAllowedCommunityDetailUrl(url: String, publishedFileId: PublishedFileId): Boolean = runCatching {
        val uri = URI(url)
        isAllowed(uri, setOf("steamcommunity.com")) &&
            uri.path.trimEnd('/') == "/sharedfiles/filedetails" &&
            uri.query
                ?.split('&')
                ?.any { it.substringBefore('=') == "id" && it.substringAfter('=', "").toULongOrNull() == publishedFileId.value } == true
    }.getOrDefault(false)

    fun isAllowedAuthorProfileUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        val pathSegments = uri.path.trim('/').split('/').filter(String::isNotBlank)
        isAllowed(uri, setOf("steamcommunity.com")) &&
            pathSegments.size == 2 &&
            pathSegments[0] in setOf("profiles", "id") &&
            pathSegments[1].isNotBlank() &&
            (pathSegments[0] != "profiles" || pathSegments[1].toULongOrNull() != null)
    }.getOrDefault(false)

    private fun isAllowed(url: String, hosts: Set<String>): Boolean = runCatching {
        isAllowed(URI(url), hosts)
    }.getOrDefault(false)

    private fun isAllowed(uri: URI, hosts: Set<String>): Boolean =
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null &&
            uri.host?.lowercase() in hosts &&
            uri.port in setOf(-1, 443)
}
