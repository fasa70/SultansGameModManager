package com.sultansgame.modmanager.model

const val MOD_STORAGE_PROTOCOL_VERSION = 2
const val GAME_MOD_STORAGE_AUTHORITY = "com.gametree.sultan.pd.modstorage"
const val GAME_MOD_STORAGE_MANAGER_PACKAGE = "com.sultansgame.modmanager"
const val MANAGER_MOD_DIRECTORY_PREFIX = "sgmm-"

object ModStorageCall {
    const val LIST_MODS = "listMods"
    const val SYNC_MOD = "syncMod"
    const val REMOVE_MANAGED_MOD = "removeManagedMod"

    const val KEY_PROTOCOL_VERSION = "protocolVersion"
    const val KEY_CACHE_KEY = "cacheKey"
    const val KEY_INPUT = "input"
    const val KEY_RESULT_CODE = "resultCode"
    const val KEY_RESULT_REASON = "resultReason"
    const val KEY_MOD_NAMES = "modNames"
}

object SaveStorageCall {
    const val LIST_SAVE_USERS = "listSaveUsers"
    const val LIST_SAVE_FILES = "listSaveFiles"
    const val READ_SAVE = "readSave"
    const val WRITE_SAVE = "writeSave"

    const val KEY_SAVE_USER = "saveUser"
    const val KEY_SAVE_FILE = "saveFile"
    /** Manager -> game pipe read end, used by writeSave. */
    const val KEY_INPUT = "input"
    /** Game -> manager pipe write end, used by readSave. */
    const val KEY_OUTPUT = "output"
    /** Bytes transferred, reported so the manager can verify a complete copy. */
    const val KEY_SAVE_LENGTH = "saveLength"
    const val KEY_SAVE_USERS = "saveUsers"
    const val KEY_SAVE_FILES = "saveFiles"
}

enum class GameSaveAvailability {
    Available,
    ProviderTooOld,
    ProviderMissing,
    Unauthorized,
    Incompatible,
    Unknown,
}

enum class GameSaveFailureCode {
    None,
    ProviderTooOld,
    ProviderMissing,
    ProviderAccessDenied,
    Unauthorized,
    ProtocolMismatch,
    InvalidName,
    NotFound,
    TooLarge,
    /** The pipe carrying the save broke before the whole file moved. */
    TransferInterrupted,
    JsonInvalid,
    CommitFailed,
    InsufficientStorage,
    InternalError,
    Unknown,
}

data class GameSaveStatus(
    val availability: GameSaveAvailability,
    val users: List<String> = emptyList(),
    val files: List<String> = emptyList(),
    val content: String? = null,
    /** Bytes the game reported transferring, for verifying a complete copy. */
    val contentLength: Long? = null,
    val failureCode: GameSaveFailureCode = GameSaveFailureCode.None,
    val reason: String? = null,
) {
    val isReady: Boolean
        get() = availability == GameSaveAvailability.Available && failureCode == GameSaveFailureCode.None
}

enum class GameModSyncAvailability {
    Available,
    ActivationRequired,
    ProviderMissing,
    Unauthorized,
    Incompatible,
    Unknown,
}

enum class GameModSyncFailureCode {
    None,
    ActivationRequired,
    ProviderMissing,
    ProviderAccessDenied,
    Unauthorized,
    ProtocolMismatch,
    InvalidMod,
    ValidationFailed,
    TransferInterrupted,
    CommitFailed,
    InsufficientStorage,
    InternalError,
    Unknown,
}

data class GameModSyncItem(
    val cacheKey: String,
    val contentDigestSha256: String,
    val displayName: String,
    val syncedToGame: Boolean,
) {
    init {
        require(cacheKey.matches(Regex("[0-9a-f]{64}"))) { "cacheKey must be a SHA-256 digest" }
        require(contentDigestSha256 == cacheKey) { "cache key and content digest must match" }
    }

    val directoryName: String
        get() = "$MANAGER_MOD_DIRECTORY_PREFIX$cacheKey"
}

enum class GameModSyncOperationType {
    Sync,
    Remove,
}

data class PendingGameModSyncOperation(
    val cacheKey: String,
    val type: GameModSyncOperationType,
) {
    init {
        require(cacheKey.matches(Regex("[0-9a-f]{64}"))) { "cacheKey must be a SHA-256 digest" }
    }
}

data class GameModDirectoryEntry(
    val directoryName: String,
    val managerCacheKey: String? = null,
) {
    init {
        require(directoryName.isNotBlank()) { "directoryName must not be blank" }
        require(managerCacheKey == null || managerCacheKey.matches(Regex("[0-9a-f]{64}"))) {
            "managerCacheKey must be a SHA-256 digest"
        }
    }

    val managedByManager: Boolean
        get() = managerCacheKey != null
}

data class GameModSyncStatus(
    val availability: GameModSyncAvailability,
    val mods: List<GameModDirectoryEntry> = emptyList(),
    val failureCode: GameModSyncFailureCode = GameModSyncFailureCode.None,
    val reason: String? = null,
) {
    val isReady: Boolean
        get() = availability == GameModSyncAvailability.Available && failureCode == GameModSyncFailureCode.None
}
