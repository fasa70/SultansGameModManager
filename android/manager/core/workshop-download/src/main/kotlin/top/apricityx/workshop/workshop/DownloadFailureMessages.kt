package top.apricityx.workshop.workshop

import java.io.IOException

internal fun Throwable.toDownloadFailure(): DownloadFailure {
    val messages = generateSequence(this) { it.cause }
        .mapNotNull { it.message?.trim()?.takeIf(String::isNotBlank) }
        .toList()
    val message = messages.joinToString(" ")
    val httpCode = Regex("(?:HTTP|failed:)\\s*(\\d{3})", RegexOption.IGNORE_CASE)
        .find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()

    return when {
        message.contains("consumer app", ignoreCase = true) ||
            message.contains("GetPublishedFileDetails", ignoreCase = true) ||
            message.contains("Steam did not return workshop details", ignoreCase = true) ||
            message.contains("result=", ignoreCase = true) -> DownloadFailure.MetadataUnavailable
        message.contains("file_url", ignoreCase = true) ||
            message.contains("hcontent_file", ignoreCase = true) ||
            message.contains("not owned", ignoreCase = true) ||
            message.contains("unsupported workshop file type", ignoreCase = true) -> DownloadFailure.NotOwnedOrUnavailable
        message.contains("exceeds the size limit", ignoreCase = true) ||
            message.contains("response exceeds", ignoreCase = true) -> DownloadFailure.ResponseTooLarge
        message.contains("length mismatch", ignoreCase = true) ||
            message.contains("size mismatch", ignoreCase = true) -> DownloadFailure.SizeMismatch
        message.contains("checksum", ignoreCase = true) ||
            message.contains("adler32", ignoreCase = true) -> DownloadFailure.ChecksumMismatch
        message.contains("unsupported", ignoreCase = true) ||
            message.contains("invalid", ignoreCase = true) ||
            message.contains("encrypted", ignoreCase = true) ||
            message.contains("decompression", ignoreCase = true) -> DownloadFailure.UnsafeOrInvalidContent
        httpCode != null -> DownloadFailure.HttpFailure(httpCode)
        this is IOException || messages.any { it.contains("timeout", ignoreCase = true) } -> DownloadFailure.Network
        else -> DownloadFailure.Network
    }
}

internal fun Throwable.userVisibleDownloadFailureMessage(): String {
    val messages = generateSequence(this) { it.cause }
        .mapNotNull { throwable ->
            throwable.message
                ?.trim()
                ?.takeIf(String::isNotBlank)
        }
        .toList()

    return messages.lastOrNull(::isSteamCdnUnauthorizedFailure)
        ?: messages.firstOrNull()
        ?: (this::class.simpleName ?: "Download failed")
}

private const val STEAM_CDN_UNAUTHORIZED_MESSAGE = "Steam CDN request failed: 401"

internal fun isSteamCdnUnauthorizedFailure(message: String?): Boolean =
    message?.contains(STEAM_CDN_UNAUTHORIZED_MESSAGE) == true
