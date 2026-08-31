package com.sultansgame.modmanager.ui

import com.sultansgame.modmanager.ManagerUiState
import com.sultansgame.modmanager.PatchUiState
import com.sultansgame.modmanager.platform.game.GameProbeResult
import com.sultansgame.modmanager.platform.game.GameReadiness
import com.sultansgame.modmanager.platform.game.UnpatchedReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartPresentationTest {
    private fun stateOf(readiness: GameReadiness, patch: PatchUiState = PatchUiState.ChooseSource) =
        ManagerUiState(gameReadiness = readiness, patch = patch)

    @Test
    fun `checking disables primary action`() {
        val presentation = startPresentation(stateOf(GameReadiness.Checking))
        assertEquals("正在检测游戏状态", presentation.title)
        assertFalse(presentation.primaryEnabled)
        assertNull(presentation.primaryDestination)
        assertTrue(presentation.diagnostics.contains("修补状态：正在检测"))
    }

    @Test
    fun `not installed offers local import`() {
        val presentation = startPresentation(stateOf(GameReadiness.NotInstalled))
        assertEquals("检测到游戏未安装", presentation.title)
        assertEquals("从本地导入游戏安装包", presentation.primaryLabel)
        assertTrue(presentation.primaryEnabled)
        assertNull(presentation.primaryDestination)
        assertTrue(presentation.diagnostics.contains("修补状态：游戏未安装"))
    }

    @Test
    fun `probe failure offers local import`() {
        val presentation = startPresentation(stateOf(GameReadiness.ProbeFailed("权限不足")))
        assertEquals("无法确认游戏状态", presentation.title)
        assertEquals("从本地导入游戏安装包", presentation.primaryLabel)
        assertTrue(presentation.diagnostics.contains("修补状态：无法确认（权限不足）"))
    }

    @Test
    fun `every unpatched reason keeps one title but distinct bodies`() {
        val bodies = mutableSetOf<String>()
        UnpatchedReason.entries.forEach { reason ->
            val presentation = startPresentation(stateOf(GameReadiness.Unpatched("1.0.5（10005）", reason)))
            assertEquals("游戏未修补，请修补游戏以使用Mod服务", presentation.title)
            assertEquals("导入游戏安装包", presentation.primaryLabel)
            assertNull(presentation.primaryDestination)
            assertTrue(presentation.body.contains("1.0.5（10005）"))
            assertTrue(presentation.diagnostics.contains("修补状态：未修补（${reason.name}）"))
            assertTrue(bodies.add(presentation.body))
        }
        assertEquals(UnpatchedReason.entries.size, bodies.size)
    }

    @Test
    fun `upgrade required shows both revisions`() {
        val presentation = startPresentation(stateOf(GameReadiness.UpgradeRequired("1.0.5（10005）", 1, 2)))
        assertEquals("修补版本需升级，请重新修补游戏", presentation.title)
        assertEquals("重新修补游戏", presentation.primaryLabel)
        assertTrue(presentation.body.contains("1"))
        assertTrue(presentation.body.contains("2"))
        assertNull(presentation.primaryDestination)
        assertTrue(presentation.diagnostics.contains("已安装 revision 1，管理器提供 2"))
    }

    @Test
    fun `ready navigates to library`() {
        val presentation = startPresentation(stateOf(GameReadiness.Ready("1.0.5（10005）", 2)))
        assertEquals("游戏已就绪", presentation.title)
        assertEquals("前往管理 Mod", presentation.primaryLabel)
        assertEquals(Destination.Library, presentation.primaryDestination)
        assertTrue(presentation.diagnostics.contains("修补状态：已就绪（loader revision 2）"))
    }

    @Test
    fun `ready with note appends note to diagnostics`() {
        val presentation = startPresentation(stateOf(GameReadiness.Ready("1.0.5（10005）", null, "读取失败")))
        assertTrue(presentation.diagnostics.contains("读取失败"))
        assertTrue(presentation.diagnostics.contains("未确认"))
    }

    @Test
    fun `manager outdated stays usable and navigates to library`() {
        val presentation = startPresentation(stateOf(GameReadiness.ManagerOutdated("1.0.5（10005）", 3, 2)))
        assertEquals("游戏已就绪", presentation.title)
        assertEquals(Destination.Library, presentation.primaryDestination)
        assertTrue(presentation.diagnostics.contains("管理器较旧"))
    }

    @Test
    fun `completed patch navigates to library`() {
        val presentation = startPresentation(stateOf(GameReadiness.Ready("1.0.5（10005）", 1), PatchUiState.Completed("tx"))
        )
        assertEquals("前往管理 Mod", presentation.primaryLabel)
        assertEquals(Destination.Library, presentation.primaryDestination)
    }

    @Test
    fun `completed patch no longer claims workshop browsing`() {
        val presentation = startPresentation(stateOf(GameReadiness.Ready("1.0.5（10005）", 1), PatchUiState.Completed("tx")))
        assertFalse(presentation.primaryLabel.contains("创意工坊"))
    }

    @Test
    fun `probe diagnostic line always present`() {
        listOf(
            GameReadiness.Checking,
            GameReadiness.NotInstalled,
            GameReadiness.ProbeFailed("x"),
            GameReadiness.Unpatched("1.0.5（10005）", UnpatchedReason.OfficialInstall),
            GameReadiness.UpgradeRequired("1.0.5（10005）", 0, 1),
            GameReadiness.Ready("1.0.5（10005）", 1),
            GameReadiness.ManagerOutdated("1.0.5（10005）", 2, 1),
        ).forEach { readiness ->
            val presentation = startPresentation(stateOf(readiness))
            assertTrue(presentation.diagnostics.contains("修补状态："))
        }
    }
}
