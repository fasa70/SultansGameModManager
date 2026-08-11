package com.sultansgame.modmanager.model

const val MAXIMUM_MOD_FILE_SIZE_BYTES: Long = 16L * 1024L * 1024L
const val MAXIMUM_MOD_CONFIG_FILE_SIZE_BYTES: Long = MAXIMUM_MOD_FILE_SIZE_BYTES
const val MAXIMUM_MOD_MEDIA_FILE_SIZE_BYTES: Long = Long.MAX_VALUE
const val MAXIMUM_MOD_PATH_DEPTH: Int = 8

enum class ModFileKind {
    Config,
    Image,
    Audio,
}

enum class RejectionReason {
    InvalidName,
    InvalidModDirectory,
    SymbolicLink,
    DepthExceeded,
    FileTypeOrSize,
    ReadFailure,
    InvalidManifest,
}

data class RejectedEntry(
    val path: String,
    val reason: RejectionReason,
)

data class ModFile(
    val relativePath: String,
    val sizeBytes: Long,
    val kind: ModFileKind,
)

data class ModManifest(
    val name: String,
    val description: String?,
    val version: String?,
    val tags: List<String>,
)

data class ModRecord(
    val directoryName: String,
    val files: List<ModFile>,
    val manifest: ModManifest? = null,
) {
    val loadOrderKey: String
        get() = directoryName
}

data class ModScanResult(
    val mods: List<ModRecord>,
    val rejectedEntries: List<RejectedEntry>,
) {
    init {
        require(mods.zipWithNext().all { (left, right) -> left.directoryName <= right.directoryName }) {
            "mods must use the native loader's ascending directory order"
        }
    }
}

enum class LoaderRuntimeState(val nativeCode: Int) {
    NotStarted(0),
    WaitingForIl2Cpp(1),
    Initializing(2),
    Ready(3),
    Failed(4),
    Unsupported(5),
    Unknown(Int.MIN_VALUE),
    ;

    companion object {
        fun fromNativeCode(code: Int): LoaderRuntimeState =
            entries.firstOrNull { it.nativeCode == code } ?: Unknown
    }
}

enum class LoaderFailure(val nativeCode: Int) {
    None(0),
    InvalidContext(1),
    ExternalFilesUnavailable(2),
    Il2CppTimeout(3),
    Il2CppNoLoadHandle(4),
    Il2CppRequiredSymbolMissing(5),
    Il2CppDomainUnavailable(6),
    UnexpectedNativeException(7),
    UnsupportedGameVersion(8),
    HookInstallFailed(9),
    Il2CppReflectionUnavailable(10),
    Unknown(Int.MIN_VALUE),
    ;

    companion object {
        fun fromNativeCode(code: Int): LoaderFailure =
            entries.firstOrNull { it.nativeCode == code } ?: Unknown
    }
}

data class LoaderStatus(
    val state: LoaderRuntimeState,
    val failure: LoaderFailure,
    val rawStateCode: Int,
    val rawFailureCode: Int,
)
