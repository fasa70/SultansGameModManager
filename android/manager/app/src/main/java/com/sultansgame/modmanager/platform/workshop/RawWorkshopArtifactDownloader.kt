package com.sultansgame.modmanager.platform.workshop

import com.sultansgame.modmanager.model.DownloadFailureCode
import com.sultansgame.modmanager.workshop.WorkshopHttpPolicy
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

data class DownloadedWorkshopArtifact(
    val file: File,
    val sizeBytes: Long,
    val digestSha256: String,
)

sealed interface RawArtifactDownloadResult {
    data class Downloaded(val artifact: DownloadedWorkshopArtifact) : RawArtifactDownloadResult
    data class Failed(val code: DownloadFailureCode) : RawArtifactDownloadResult
}

class RawWorkshopArtifactDownloader(private val cacheRoot: File) {
    fun download(url: String, declaredSizeBytes: Long?): RawArtifactDownloadResult {
        if (!WorkshopHttpPolicy.isAllowedArtifactUrl(url)) return RawArtifactDownloadResult.Failed(DownloadFailureCode.UnsafeUrl)
        if (declaredSizeBytes != null && declaredSizeBytes < 0L) return RawArtifactDownloadResult.Failed(DownloadFailureCode.SizeMismatch)
        if (!cacheRoot.mkdirs() && !cacheRoot.isDirectory) return RawArtifactDownloadResult.Failed(DownloadFailureCode.Network)
        val partial = File(cacheRoot, ".${UUID.randomUUID()}.part")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("Accept-Encoding", "identity")
        }
        return try {
            val responseCode = connection.responseCode
            if (responseCode in 300..399) return RawArtifactDownloadResult.Failed(DownloadFailureCode.UnsafeUrl)
            if (responseCode !in 200..299) return RawArtifactDownloadResult.Failed(httpFailure(responseCode))
            val contentLength = connection.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }
            if (declaredSizeBytes != null && contentLength != null && contentLength != declaredSizeBytes) {
                return RawArtifactDownloadResult.Failed(DownloadFailureCode.SizeMismatch)
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total = Math.addExact(total, count.toLong())
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (declaredSizeBytes != null && total != declaredSizeBytes) return RawArtifactDownloadResult.Failed(DownloadFailureCode.SizeMismatch)
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            val completed = File(cacheRoot, "$hex.download")
            if (completed.exists()) partial.delete() else if (!partial.renameTo(completed)) {
                return RawArtifactDownloadResult.Failed(DownloadFailureCode.Network)
            }
            RawArtifactDownloadResult.Downloaded(DownloadedWorkshopArtifact(completed, total, hex))
        } catch (_: Exception) {
            RawArtifactDownloadResult.Failed(DownloadFailureCode.Network)
        } finally {
            partial.delete()
            connection.disconnect()
        }
    }

    private fun httpFailure(code: Int): DownloadFailureCode = when (code) {
        401, 403 -> DownloadFailureCode.LoginRequired
        404 -> DownloadFailureCode.NotOwnedOrUnavailable
        else -> DownloadFailureCode.HttpFailure
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 60_000
    }
}
