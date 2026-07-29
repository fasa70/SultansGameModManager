package com.sultansgame.modmanager.model

enum class CacheSource {
    SafTree,
    SafArchive,
    Workshop,
}

enum class CachedModState {
    Cached,
    Rejected,
    SourcePermissionLost,
}

data class CachedMod(
    val cacheKey: String,
    val contentDigestSha256: String,
    val displayName: String,
    val source: CacheSource,
    val sizeBytes: Long,
    val importedAtEpochMillis: Long,
    val state: CachedModState,
    val publishedFileId: PublishedFileId? = null,
)

enum class ImportStage {
    Selecting,
    Copying,
    Validating,
    Cached,
    Rejected,
    Cancelled,
}

enum class ImportFailureCode {
    SourceUnavailable,
    UnsafePath,
    DuplicatePath,
    CaseCollision,
    DepthExceeded,
    EntryLimitExceeded,
    TotalSizeExceeded,
    FileTooLarge,
    InvalidManifest,
    ReadFailure,
    CommitFailure,
}

data class ImportFailure(
    val stage: ImportStage,
    val code: ImportFailureCode,
    val sourceLabel: String,
    val occurredAtEpochMillis: Long,
)
