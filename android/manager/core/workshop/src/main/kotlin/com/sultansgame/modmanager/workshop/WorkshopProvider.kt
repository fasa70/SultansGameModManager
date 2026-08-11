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
    // Artifact bodies are streamed to disk; storage admission is handled by the target-volume budget.
    const val MAXIMUM_ARTIFACT_SIZE_BYTES: Long = Long.MAX_VALUE

    // New hosts must be verified against a public Steam response before inclusion.
    private val artifactHosts = setOf("api.steampowered.com", "steamusercontent-a.akamaihd.net")
    private val previewImageHosts = setOf(
        "steamusercontent-a.akamaihd.net",
        "images.akamai.steamusercontent.com",
        "images.steamusercontent.com",
        "steamuserimages-a.akamaihd.net",
    )

    fun isAllowedMetadataUrl(url: String): Boolean = isAllowed(url, setOf("api.steampowered.com"))

    fun isAllowedArtifactUrl(url: String): Boolean = isAllowed(url, artifactHosts)

    fun isAllowedPreviewImageUrl(url: String): Boolean = isAllowed(url, previewImageHosts)

    /**
     * Converts a Steam CDN preview URL to its canonical HTTPS form without
     * broadening the set of hosts that may supply preview images.
     */
    fun normalizePreviewImageUrl(url: String?): String? {
        val rawUrl = url?.trim().orEmpty()
        if (rawUrl.isEmpty()) return null
        return runCatching {
            val uri = URI(rawUrl)
            if (uri.userInfo != null || uri.host?.lowercase() !in previewImageHosts) return@runCatching null
            val normalized = when {
                uri.scheme.equals("https", ignoreCase = true) && uri.port in setOf(-1, 443) -> URI(
                    "https",
                    null,
                    uri.host,
                    -1,
                    uri.rawPath,
                    uri.rawQuery,
                    null,
                )
                uri.scheme.equals("http", ignoreCase = true) && uri.port in setOf(-1, 80) -> URI(
                    "https",
                    null,
                    uri.host,
                    -1,
                    uri.rawPath,
                    uri.rawQuery,
                    null,
                )
                else -> return@runCatching null
            }
            normalized.toString().takeIf(::isAllowedPreviewImageUrl)
        }.getOrNull()
    }

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
