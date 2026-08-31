package com.sultansgame.modmanager.ui

import com.sultansgame.modmanager.GameModSyncProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameModSyncProgressTextTest {
    @Test
    fun `transfer includes position name and byte counts`() {
        val text = gameModSyncProgressText(
            GameModSyncProgress(
                operationIndex = 2,
                operationCount = 3,
                displayName = "测试 Mod",
                isRemoval = false,
                writtenBytes = 12L * 1024 * 1024,
                totalBytes = 45L * 1024 * 1024,
            ),
        )
        assertEquals("正在同步 Mod 到游戏目录（2/3）：测试 Mod · 12.0 MiB / 45.0 MiB…", text)
    }

    @Test
    fun `zero-byte mod omits byte counts`() {
        val text = gameModSyncProgressText(
            GameModSyncProgress(
                operationIndex = 1,
                operationCount = 1,
                displayName = "空 Mod",
                isRemoval = false,
                writtenBytes = 0,
                totalBytes = 0,
            ),
        )
        assertEquals("正在同步 Mod 到游戏目录（1/1）：空 Mod…", text)
    }

    @Test
    fun `removal omits byte counts`() {
        val text = gameModSyncProgressText(
            GameModSyncProgress(
                operationIndex = 1,
                operationCount = 2,
                displayName = "测试 Mod",
                isRemoval = true,
                writtenBytes = 0,
                totalBytes = 0,
            ),
        )
        assertEquals("正在同步 Mod 到游戏目录（1/2）：正在从游戏中移除 测试 Mod…", text)
    }

    @Test
    fun `vanished mod falls back to removed label`() {
        val text = gameModSyncProgressText(
            GameModSyncProgress(
                operationIndex = 1,
                operationCount = 1,
                displayName = null,
                isRemoval = true,
                writtenBytes = 0,
                totalBytes = 0,
            ),
        )
        assertEquals("正在同步 Mod 到游戏目录（1/1）：正在从游戏中移除 已删除的 Mod…", text)
    }

    @Test
    fun `written bytes beyond total stay coherent`() {
        val text = gameModSyncProgressText(
            GameModSyncProgress(
                operationIndex = 1,
                operationCount = 1,
                displayName = "测试 Mod",
                isRemoval = false,
                writtenBytes = 500L,
                totalBytes = 100L,
            ),
        )
        assertTrue(text.contains("500 B / 100 B"))
    }
}
