package com.sultansgame.modmanager.model

data class ApkInspection(
    val sourceLabel: String,
    val packageName: String?,
    val versionCode: Long?,
    val versionName: String?,
    val splitName: String?,
    val supportedAbis: Set<String>,
    val signerDigestsSha256: Set<String>,
    val entryCount: Int,
    val sizeBytes: Long,
    val warnings: List<String>,
)

enum class Compatibility {
    Candidate,
    Unsupported,
    Unverified,
}

data class CompatibilityReport(
    val compatibility: Compatibility,
    val reasons: List<String>,
)

data class ApkSigningRequirement(
    val requireV1: Boolean = true,
    val requireV2: Boolean = true,
    val requireV3: Boolean = false,
)

data class GameProfile(
    val id: String = "official-android-2026-07-27",
    val packageNames: Set<String>,
    val signingDigestsSha256: Set<String>,
    val requiredAbi: String = "arm64-v8a",
    val versionCodes: Set<Long> = emptySet(),
    val loaderSplitName: String = "modloader",
    val loaderTemplateSha256: String? = null,
    val nativeLoaderSha256: String? = null,
    val providerProtocolVersion: Int? = null,
    val signingRequirement: ApkSigningRequirement = ApkSigningRequirement(),
) {
    fun matchesVerified(
        inspection: ApkInspection,
        availableAbis: Set<String> = inspection.supportedAbis,
    ): Boolean =
        inspection.packageName in packageNames &&
            requiredAbi in availableAbis &&
            inspection.signerDigestsSha256.any(signingDigestsSha256::contains) &&
            versionCodes.isNotEmpty() && inspection.versionCode in versionCodes
}

enum class PatchSource {
    InstalledGame,
    SelectedApk,
    SelectedApks,
}

enum class PatchMode {
    Verified,
    Experimental,
}

enum class DeviceSigningKeyState {
    NotCreated,
    Ready,
    MissingAfterMigration,
}

enum class PatchStage {
    Idle,
    InspectingInput,
    ClassifyingProfile,
    AwaitingConfirmation,
    PreparingArtifacts,
    AwaitingGameUninstall,
    AwaitingInstallPermission,
    AwaitingSystemInstall,
    VerifyingInstall,
    Completed,
    Failed,
}

enum class PatchFailure {
    UnsupportedInput,
    IncompletePackageSet,
    DeviceKeyMissing,
    SigningUnavailable,
    SplitUnavailable,
    InstallPermissionMissing,
    SystemInstallCancelled,
    SystemInstallFailed,
    PostInstallVerificationFailed,
    InternalError,
}

data class PatchConfirmation(
    val acknowledgedInstallRisk: Boolean = false,
    val acknowledgedRecoveryLimit: Boolean = false,
    val acknowledgedReinstallRequirement: Boolean = false,
    val confirmedBackup: Boolean = false,
    val confirmedExperimentalRetry: Boolean = false,
) {
    fun permits(mode: PatchMode): Boolean =
        acknowledgedInstallRisk && acknowledgedRecoveryLimit && acknowledgedReinstallRequirement &&
            (mode == PatchMode.Verified || (confirmedBackup && confirmedExperimentalRetry))
}

data class PatchArtifact(
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long,
    val inspection: ApkInspection,
)

data class PatchInstallPlan(
    val transactionId: String,
    val source: PatchSource,
    val mode: PatchMode,
    val base: PatchArtifact,
    val splits: List<PatchArtifact>,
    val deviceCertificateSha256: String,
    val confirmation: PatchConfirmation,
    val profileId: String? = null,
) {
    val artifacts: List<PatchArtifact>
        get() = listOf(base) + splits

    fun requiresConfirmation(): Boolean = !confirmation.permits(mode)
}

data class PatchInputClassification(
    val source: PatchSource,
    val mode: PatchMode,
    val compatibility: CompatibilityReport,
    val profileId: String?,
)
