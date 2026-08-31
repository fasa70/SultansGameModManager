package com.sultansgame.modmanager.platform.saveeditor

/** The user-facing groups derived from the provider's safe relative save paths. */
data class SaveFileSelection(
    val slots: List<SaveSlotFile>,
    val otherFiles: List<String>,
)

data class SaveSlotFile(
    val slot: Int,
    val fileName: String,
)

internal object SaveFileSelectionClassifier {
    fun classify(files: List<String>): SaveFileSelection {
        val slots = files.mapNotNull { fileName ->
            SaveArchiveIndex.slotOfFileName(fileName)?.let { slot -> SaveSlotFile(slot, fileName) }
        }.distinctBy { it.slot }.sortedBy { it.slot }
        val slotNames = slots.mapTo(mutableSetOf()) { it.fileName }
        val otherFiles = files.filter { fileName ->
            !fileName.contains('/') &&
                fileName !in slotNames && fileName != "global.json" && fileName != "user_archive.json"
        }.distinct()
        return SaveFileSelection(slots, otherFiles)
    }
}
