package top.apricityx.workshop.workshop

import kotlinx.serialization.Serializable
import java.io.File

data class WorkshopDownloadRequest(
    val appId: UInt,
    val publishedFileId: ULong,
    val outputDir: File,
)

@Serializable
enum class DownloadState {
    Idle,
    Resolving,
    Connecting,
    Downloading,
    Paused,
    Success,
    Failed,
}

data class DownloadedFileInfo(
    val relativePath: String,
    val sizeBytes: Long,
    val modifiedEpochMillis: Long,
)

sealed interface DownloadEvent {
    data class StateChanged(val state: DownloadState) : DownloadEvent
    data class LogAppended(val line: String) : DownloadEvent
    data class Progress(
        val writtenBytes: Long,
        val totalBytes: Long?,
        val completedChunks: Int? = null,
        val totalChunks: Int? = null,
        val completedFiles: Int? = null,
        val totalFiles: Int? = null,
    ) : DownloadEvent
    data class FileCompleted(val file: DownloadedFileInfo) : DownloadEvent
    data class Completed(val files: List<DownloadedFileInfo>) : DownloadEvent
    data class Failed(
        val message: String,
        val failure: DownloadFailure,
    ) : DownloadEvent
}

sealed interface DownloadFailure {
    val retryable: Boolean

    data object MetadataUnavailable : DownloadFailure { override val retryable = false }
    data object NotOwnedOrUnavailable : DownloadFailure { override val retryable = false }
    data object UnsafeOrInvalidContent : DownloadFailure { override val retryable = false }
    data object ResponseTooLarge : DownloadFailure { override val retryable = false }
    data object SizeMismatch : DownloadFailure { override val retryable = false }
    data object ChecksumMismatch : DownloadFailure { override val retryable = false }
    data object InsufficientStorage : DownloadFailure { override val retryable = true }
    data class HttpFailure(val statusCode: Int?) : DownloadFailure {
        override val retryable: Boolean = statusCode == 408 || statusCode == 429 || (statusCode != null && statusCode >= 500)
    }
    data object Network : DownloadFailure { override val retryable = true }
}

sealed interface ResolvedWorkshopItem {
    val title: String
    val metadataJson: String

    data class DirectUrlItem(
        val fileName: String,
        val fileUrl: String,
        val size: Long?,
        override val title: String,
        override val metadataJson: String,
    ) : ResolvedWorkshopItem

    data class UgcManifestItem(
        val manifestId: ULong,
        val depotId: UInt,
        override val title: String,
        override val metadataJson: String,
    ) : ResolvedWorkshopItem
}

class WorkshopDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)
