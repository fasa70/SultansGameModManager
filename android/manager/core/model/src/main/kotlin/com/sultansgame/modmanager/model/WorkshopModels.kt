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

data class WorkshopDateRangeFilter(
    val startEpochSeconds: Long = 0L,
    val endEpochSeconds: Long = 0L,
) {
    val isActive: Boolean
        get() = startEpochSeconds > 0L || endEpochSeconds > 0L

    fun normalized(): WorkshopDateRangeFilter {
        val start = startEpochSeconds.coerceAtLeast(0L)
        val end = endEpochSeconds.coerceAtLeast(0L)
        return if (start > 0L && end > 0L && start > end) {
            copy(startEpochSeconds = end, endEpochSeconds = start)
        } else {
            copy(startEpochSeconds = start, endEpochSeconds = end)
        }
    }
}

data class WorkshopBrowseQuery(
    val sectionKey: String = SECTION_ITEMS,
    val sortKey: String = SORT_TREND,
    val periodDays: Int = 7,
    val searchText: String = "",
    val requiredTags: Set<String> = emptySet(),
    val excludedTags: Set<String> = emptySet(),
    val showIncompatible: Boolean = false,
    val createdDateRange: WorkshopDateRangeFilter = WorkshopDateRangeFilter(),
    val updatedDateRange: WorkshopDateRangeFilter = WorkshopDateRangeFilter(),
    val page: Int = 1,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    fun normalized(): WorkshopBrowseQuery {
        val normalizedRequired = requiredTags.map(String::trim).filter(String::isNotBlank).toSortedSet()
        val normalizedExcluded = excludedTags.map(String::trim).filter(String::isNotBlank).toSortedSet() - normalizedRequired
        return copy(
            sectionKey = sectionKey.trim().ifBlank { SECTION_ITEMS },
            sortKey = sortKey.trim().ifBlank { SORT_TREND },
            searchText = searchText.trim(),
            requiredTags = normalizedRequired,
            excludedTags = normalizedExcluded,
            createdDateRange = createdDateRange.normalized(),
            updatedDateRange = updatedDateRange.normalized(),
            page = page.coerceAtLeast(1),
            pageSize = normalizePageSize(pageSize),
        )
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 9
        val PAGE_SIZE_OPTIONS = listOf(9, 18, 30)
        const val SECTION_ITEMS = "readytouseitems"
        const val SECTION_MY_SUBSCRIPTIONS = "mysubscriptions"
        const val SORT_TREND = "trend"
        const val SORT_TOP_RATED = "toprated"
        const val SORT_MOST_RECENT = "mostrecent"
        const val SORT_LAST_UPDATED = "lastupdated"
        const val SORT_TOTAL_UNIQUE_SUBSCRIBERS = "totaluniquesubscribers"

        fun normalizePageSize(pageSize: Int): Int =
            pageSize.takeIf { it in PAGE_SIZE_OPTIONS } ?: DEFAULT_PAGE_SIZE
    }
}

data class WorkshopBrowsePage(
    val items: List<WorkshopItem>,
    val totalCount: Int,
    val page: Int,
    val hasMore: Boolean,
    val sectionOptions: List<WorkshopBrowseSectionOption>,
    val sortOptions: List<WorkshopBrowseSortOption>,
    val periodOptions: List<WorkshopBrowsePeriodOption>,
    val tagGroups: List<WorkshopBrowseTagGroup>,
    val supportsIncompatibleFilter: Boolean,
)

data class WorkshopBrowseSectionOption(val key: String, val label: String)
data class WorkshopBrowseSortOption(val key: String, val label: String, val supportsPeriod: Boolean)
data class WorkshopBrowsePeriodOption(val days: Int, val label: String)

enum class WorkshopBrowseTagGroupSelectionMode { IncludeExclude, SingleSelect }

data class WorkshopBrowseTagGroup(
    val label: String,
    val tags: List<WorkshopBrowseTagOption>,
    val selectionMode: WorkshopBrowseTagGroupSelectionMode = WorkshopBrowseTagGroupSelectionMode.IncludeExclude,
)

data class WorkshopBrowseTagOption(val value: String, val label: String)

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
    val shortDescription: String = "",
    val authorProfileUrl: String? = null,
    val detailUrl: String? = null,
    val createdAtEpochSeconds: Long? = null,
    val subscriptions: Int? = null,
    val creatorSteamId: ULong? = null,
    val contentManifestId: ULong? = null,
    val childPublishedFileIds: List<PublishedFileId> = emptyList(),
    val tags: List<String> = emptyList(),
    val isDownloadInfoResolved: Boolean = false,
) {
    val canDirectDownload: Boolean get() = !fileUrl.isNullOrBlank()
    val canSteamContentDownload: Boolean get() = contentManifestId != null && contentManifestId > 0u
    val canDownload: Boolean get() = canDirectDownload || canSteamContentDownload
}

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
    val boundAccountHash: String? = null,
    val stage: DownloadStage,
    val title: String = "",
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val failure: DownloadFailureCode? = null,
    val rawArtifactDigestSha256: String? = null,
    val completedFileCount: Int = 0,
    val pauseRequested: Boolean = false,
    val attemptGeneration: Long = 1L,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
)

sealed interface SteamAuthState {
    data object SignedOut : SteamAuthState
    data object SigningIn : SteamAuthState
    data class SignedIn(val accountName: String, val steamId: Long) : SteamAuthState
    data class SteamGuardRequired(val challenge: String) : SteamAuthState
    data class VerifyingSteamGuard(val challenge: String) : SteamAuthState
    data class SteamAuthStatusUnknown(val challenge: String) : SteamAuthState
    data class AwaitingConfirmation(val challenge: String) : SteamAuthState
    data object AuthenticationUnavailable : SteamAuthState
}
