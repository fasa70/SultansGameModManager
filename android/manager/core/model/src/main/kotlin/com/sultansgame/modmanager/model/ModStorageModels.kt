package com.sultansgame.modmanager.model

const val GAME_MOD_STORAGE_AUTHORITY = "com.gametree.sultan.pd.modstorage"
/** 游戏 loader split 内用于唤醒 :modstorage 进程的跳板 Activity（全限定类名）。 */
const val GAME_MOD_STORAGE_KICKSTART_ACTIVITY = "com.gametree.sultan.pd.mod.ModServiceKickstartActivity"
const val GAME_MOD_STORAGE_MANAGER_PACKAGE = "com.sultansgame.modmanager"
const val MANAGER_MOD_DIRECTORY_PREFIX = "sgmm-"

object ModStorageCall {
    const val LIST_MODS = "listMods"
    const val SYNC_MOD = "syncMod"
    const val REMOVE_MANAGED_MOD = "removeManagedMod"

    /** Manager 声称它那一侧 loader 的 revision；与游戏侧 split 内 assets/modloader/revision 比对。 */
    const val KEY_EXPECTED_REVISION = "expectedRevision"
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
    /** Provider 已注册但不可达（游戏包处于 stopped 状态）：可由跳板 Activity 冷启动恢复。 */
    ActivationRequired,
    ProviderMissing,
    Unauthorized,
    Incompatible,
    Unknown,
}

enum class GameSaveFailureCode {
    None,
    ActivationRequired,
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

/**
 * Progress of streaming one mod's files to the game-side Provider.
 *
 * Counts describe what the Manager has written into the transfer pipe. The Provider validates and
 * commits after the last byte arrives, so reaching [totalBytes] means the transfer finished, not
 * that the mod is already visible in the game directory.
 */
data class GameModSyncTransferProgress(
    val writtenBytes: Long,
    val totalBytes: Long,
) {
    init {
        require(writtenBytes >= 0 && totalBytes >= 0) { "byte counts must not be negative" }
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
