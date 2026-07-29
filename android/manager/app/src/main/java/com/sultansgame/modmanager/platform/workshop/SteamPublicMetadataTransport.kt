package com.sultansgame.modmanager.platform.workshop

import com.sultansgame.modmanager.model.PublishedFileId
import com.sultansgame.modmanager.workshop.PublicWorkshopMetadata
import com.sultansgame.modmanager.workshop.PublicWorkshopMetadataTransport
import com.sultansgame.modmanager.workshop.WorkshopHttpPolicy
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class SteamPublicMetadataTransport : PublicWorkshopMetadataTransport {
    override fun getPublishedFileDetails(appId: UInt, publishedFileId: PublishedFileId): PublicWorkshopMetadata? {
        val endpoint = "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/"
        check(WorkshopHttpPolicy.isAllowedMetadataUrl(endpoint))
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
        }
        val request = "itemcount=1&publishedfileids%5B0%5D=" +
            URLEncoder.encode(publishedFileId.toString(), StandardCharsets.UTF_8.name())
        return try {
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(request) }
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            parse(body, appId, publishedFileId)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(body: String, appId: UInt, requestedId: PublishedFileId): PublicWorkshopMetadata? = runCatching {
        val detail = JSONObject(body)
            .getJSONObject("response")
            .getJSONArray("publishedfiledetails")
            .optJSONObject(0)
            ?: return null
        val id = PublishedFileId.parse(detail.optString("publishedfileid")) ?: return null
        if (id != requestedId) return null
        PublicWorkshopMetadata(
            consumerAppId = detail.optLong("consumer_app_id", -1).takeIf { it > 0 }?.toUInt(),
            publishedFileId = id,
            resultCode = detail.optInt("result", -1),
            title = detail.optString("title").takeIf(String::isNotBlank),
            fileUrl = detail.optString("file_url").takeIf(String::isNotBlank),
            previewUrl = detail.optString("preview_url").takeIf(String::isNotBlank),
            updatedAtEpochSeconds = detail.optLong("time_updated", -1).takeIf { it >= 0 },
            declaredSizeBytes = detail.optLong("file_size", -1).takeIf { it >= 0 },
        )
    }.getOrNull()

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
    }
}
