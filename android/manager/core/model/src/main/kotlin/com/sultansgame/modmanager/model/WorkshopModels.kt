package com.sultansgame.modmanager.model

const val SULTANS_GAME_APP_ID = 3117820u

@JvmInline
value class PublishedFileId private constructor(val value: ULong) {
    override fun toString(): String = value.toString()

    companion object {
        fun parse(raw: String): PublishedFileId? =
            raw.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                ?.toULongOrNull()
                ?.takeIf { it > 0u }
                ?.let(::PublishedFileId)
    }
}

enum class WorkshopAccessMode {
    Anonymous,
    Account,
}

enum class WorkshopAvailability {
    PublicDownloadAvailable,
    LoginRequired,
    Unavailable,
}

data class WorkshopItem(
    val appId: UInt,
    val publishedFileId: PublishedFileId,
    val title: String,
    val updatedAtEpochSeconds: Long?,
    val fileUrl: String?,
    val previewUrl: String?,
    val declaredSizeBytes: Long?,
    val availability: WorkshopAvailability,
    val description: String = "",
    val authorName: String = "",
)

data class WorkshopSearchPage(
    val items: List<WorkshopItem>,
    val page: Int,
    val hasNextPage: Boolean,
)

enum class DownloadStage {
    Queued,
    ResolvingMetadata,
    AwaitingPublicUrl,
    Downloading,
    Paused,
    Verifying,
    AwaitingImportConfirmation,
    Importing,
    Imported,
    NeedsLogin,
    Failed,
    Cancelled,
}

enum class DownloadFailureCode {
    InvalidPublishedFileId,
    MetadataUnavailable,
    LoginRequired,
    NotOwnedOrUnavailable,
    UnsafeUrl,
    HttpFailure,
    Network,
    ResponseTooLarge,
    SizeMismatch,
    ChecksumMismatch,
    InvalidArtifact,
    ImportFailed,
    Cancelled,
}

data class DownloadTask(
    val id: String,
    val appId: UInt,
    val publishedFileId: PublishedFileId,
    val accessMode: WorkshopAccessMode,
    val stage: DownloadStage,
    val title: String = "",
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val failure: DownloadFailureCode? = null,
    val rawArtifactDigestSha256: String? = null,
    val completedFileCount: Int = 0,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
)

sealed interface SteamAuthState {
    data object SignedOut : SteamAuthState
    data object SigningIn : SteamAuthState
    data class SignedIn(val accountName: String, val steamId: Long) : SteamAuthState
    data class SteamGuardRequired(val challenge: String) : SteamAuthState
    data class AwaitingConfirmation(val challenge: String) : SteamAuthState
    data object AuthenticationUnavailable : SteamAuthState
}
