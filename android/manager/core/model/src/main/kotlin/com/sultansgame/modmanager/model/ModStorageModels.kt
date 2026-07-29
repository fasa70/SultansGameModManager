package com.sultansgame.modmanager.model

const val MOD_STORAGE_PROTOCOL_VERSION = 1
const val GAME_MOD_STORAGE_AUTHORITY = "com.gametree.sultan.pd.modstorage"
const val GAME_MOD_STORAGE_MANAGER_PACKAGE = "com.sultansgame.modmanager"
const val MOD_DEPLOYMENT_ORDER_STEP = 10

object ModStorageCall {
    const val STATUS = "status"
    const val LIST = "list"
    const val SYNC_SNAPSHOT = "syncSnapshot"
    const val REVOKE_AUTHORIZATION = "revokeAuthorization"

    const val KEY_PROTOCOL_VERSION = "protocolVersion"
    const val KEY_REVISION = "revision"
    const val KEY_SNAPSHOT_DIGEST = "snapshotDigest"
    const val KEY_ALLOW_EXTERNAL_REPLACEMENT = "allowExternalReplacement"
    const val KEY_RESULT_CODE = "resultCode"
    const val KEY_RESULT_REASON = "resultReason"
    const val KEY_STATUS = "status"
}

enum class ModStorageAvailability {
    Available,
    ProviderMissing,
    Unauthorized,
    Incompatible,
    GameRunning,
    Unknown,
}

enum class ModStorageFailureCode {
    None,
    ProviderMissing,
    Unauthorized,
    ProtocolMismatch,
    GameRunning,
    ExternalChangesDetected,
    InvalidSnapshot,
    ValidationFailed,
    TransferInterrupted,
    CommitFailed,
    InternalError,
    Unknown,
}

data class DeploymentEntry(
    val cacheKey: String,
    val contentDigestSha256: String,
    val displayName: String,
    val enabled: Boolean,
    val order: Int,
) {
    init {
        require(cacheKey.matches(Regex("[0-9a-f]{64}"))) { "cacheKey must be a SHA-256 digest" }
        require(contentDigestSha256 == cacheKey) { "cache key and content digest must match" }
        require(order >= 0) { "order must not be negative" }
    }

    val directoryName: String
        get() = "%06d--%s".format(order, cacheKey)
}

data class DeploymentSnapshot(
    val revision: String,
    val entries: List<DeploymentEntry>,
    val snapshotDigestSha256: String,
    val allowExternalReplacement: Boolean = false,
) {
    init {
        require(revision.matches(Regex("[0-9a-f-]{36}"))) { "revision must be a UUID" }
        require(snapshotDigestSha256.matches(Regex("[0-9a-f]{64}"))) { "snapshot digest must be SHA-256" }
        require(entries.filter(DeploymentEntry::enabled).map(DeploymentEntry::order).distinct().size ==
            entries.count(DeploymentEntry::enabled)) { "enabled entries must have unique orders" }
    }

    val enabledEntries: List<DeploymentEntry>
        get() = entries.filter(DeploymentEntry::enabled).sortedBy(DeploymentEntry::order)
}

data class GameModEntry(
    val directoryName: String,
    val displayName: String?,
    val contentDigestSha256: String?,
    val sizeBytes: Long,
    val managedBySnapshot: Boolean,
) {
    init {
        require(sizeBytes >= 0) { "size must not be negative" }
    }
}

data class GameModStorageStatus(
    val availability: ModStorageAvailability,
    val protocolVersion: Int? = null,
    val revision: String? = null,
    val mods: List<GameModEntry> = emptyList(),
    val failureCode: ModStorageFailureCode = ModStorageFailureCode.None,
    val reason: String? = null,
) {
    val isReady: Boolean
        get() = availability == ModStorageAvailability.Available && failureCode == ModStorageFailureCode.None
}

data class ModStorageSyncResult(
    val status: GameModStorageStatus,
    val appliedRevision: String? = null,
)
