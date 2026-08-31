package com.sultansgame.modmanager.platform.saveeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveFileSelectionTest {
    @Test
    fun classifiesSlotsAndHidesInternalFiles() {
        val selection = SaveFileSelectionClassifier.classify(
            listOf(
                "global.json",
                "user_archive.json",
                "USERARCHIVE/003.json",
                "USERARCHIVE/000.json",
                "auto_save.json",
                "round_2.json",
            ),
        )
        assertEquals(listOf(0, 3), selection.slots.map { it.slot })
        assertEquals(listOf("USERARCHIVE/000.json", "USERARCHIVE/003.json"), selection.slots.map { it.fileName })
        assertEquals(listOf("auto_save.json", "round_2.json"), selection.otherFiles)
    }

    @Test
    fun ignoresDuplicateSlotAndKeepsOnlySupportedSlotNumbers() {
        val selection = SaveFileSelectionClassifier.classify(
            listOf(
                "USERARCHIVE/009.json",
                "USERARCHIVE/009.json",
                "USERARCHIVE/010.json",
                "USERARCHIVE/abc.json",
            ),
        )
        assertEquals(listOf(9), selection.slots.map { it.slot })
        assertTrue(selection.otherFiles.isEmpty())
    }

    @Test
    fun preservesOtherFilePathsForTheActionOnly() {
        val selection = SaveFileSelectionClassifier.classify(listOf("auto_save.json"))
        assertEquals(listOf("auto_save.json"), selection.otherFiles)
    }

    @Test
    fun dragsUnknownNestedPathsIntoNowhereRatherThanTheOtherList() {
        val selection = SaveFileSelectionClassifier.classify(
            listOf("USERARCHIVE/005/000.json", "top/broken.json"),
        )
        assertTrue(selection.slots.isEmpty())
        assertTrue(selection.otherFiles.isEmpty())
    }
}
