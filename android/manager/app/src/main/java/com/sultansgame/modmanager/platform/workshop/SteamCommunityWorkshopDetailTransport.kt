package com.sultansgame.modmanager.platform.workshop

import com.sultansgame.modmanager.model.PublishedFileId
import com.sultansgame.modmanager.workshop.PublicWorkshopDetail
import com.sultansgame.modmanager.workshop.PublicWorkshopDetailParser
import com.sultansgame.modmanager.workshop.PublicWorkshopDetailTransport
import com.sultansgame.modmanager.workshop.WorkshopHttpPolicy
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class SteamCommunityWorkshopDetailTransport : PublicWorkshopDetailTransport {
    override fun getPublishedFileDetail(publishedFileId: PublishedFileId): PublicWorkshopDetail? {
        val detailUrl = "https://steamcommunity.com/sharedfiles/filedetails/?id=$publishedFileId&l=schinese"
        check(WorkshopHttpPolicy.isAllowedCommunityDetailUrl(detailUrl, publishedFileId))
        val connection = (URL(detailUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("Accept", "text/html")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            if (connection.responseCode !in 200..299 || connection.contentLengthLong > MAXIMUM_HTML_SIZE_BYTES) return null
            val html = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val content = StringBuilder()
                val buffer = CharArray(8192)
                while (content.length <= MAXIMUM_HTML_SIZE_BYTES) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    content.append(buffer, 0, count)
                }
                content.takeIf { it.length <= MAXIMUM_HTML_SIZE_BYTES }?.toString()
            } ?: return null
            PublicWorkshopDetailParser.parse(html)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val MAXIMUM_HTML_SIZE_BYTES = 2 * 1024 * 1024
        const val USER_AGENT = "SultansGameModManager/0.1 (GPLv3; Steam Workshop detail browser)"
    }
}
