package com.sultansgame.modmanager.ui

import com.sultansgame.modmanager.PatchUiState
import com.sultansgame.modmanager.PatchInputUiModel
import com.sultansgame.modmanager.model.Compatibility
import com.sultansgame.modmanager.model.CompatibilityReport
import com.sultansgame.modmanager.model.PatchInputClassification
import com.sultansgame.modmanager.model.PatchConfirmation
import com.sultansgame.modmanager.model.PatchMode
import com.sultansgame.modmanager.model.PatchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartOperationStatusTest {
    @Test
    fun importingShowsItsSafetyInstruction() {
        assertEquals(
            StartOperationStatus(
                title = "正在导入游戏安装包",
                body = "请不要关闭应用。",
            ),
            PatchUiState.Importing("正在导入 game.apks…").toStartOperationStatus(),
        )
    }

    @Test
    fun preparingShowsSafetyInstruction() {
        val input = PatchInputUiModel(
            source = PatchSource.SelectedApk,
            sourceLabel = "游戏安装包",
            versionLabel = "1.0",
            splitCount = 0,
            signerSummary = "签名",
            classification = PatchInputClassification(
                source = PatchSource.SelectedApk,
                mode = PatchMode.Verified,
                compatibility = CompatibilityReport(Compatibility.Candidate, emptyList()),
                profileId = "profile",
            ),
        )

        assertEquals(
            StartOperationStatus(
                title = "正在修补游戏安装包",
                body = "请不要关闭应用。",
            ),
            PatchUiState.Preparing(input).toStartOperationStatus(),
        )
    }

    @Test
    fun installationStagesExplainTheExpectedSystemAction() {
        assertEquals(
            StartOperationStatus(
                title = "正在请求系统安装",
                body = "请稍候，系统安装确认页面即将打开。",
            ),
            PatchUiState.SubmittingInstall("transaction").toStartOperationStatus(),
        )
        assertEquals(
            StartOperationStatus(
                title = "正在等待系统安装",
                body = "请在系统页面完成操作；完成后返回此处继续核验。",
            ),
            PatchUiState.AwaitingSystemInstall("transaction").toStartOperationStatus(),
        )
    }

    @Test
    fun singlePatchConfirmationSatisfiesVerifiedRequirementsOnly() {
        val confirmed = PatchConfirmation().withSinglePatchConfirmation(true)

        assertEquals(true, confirmed.acknowledgedInstallRisk)
        assertEquals(true, confirmed.acknowledgedRecoveryLimit)
        assertEquals(true, confirmed.acknowledgedReinstallRequirement)
        assertEquals(false, confirmed.confirmedBackup)
        assertEquals(false, confirmed.confirmedExperimentalRetry)
        assertEquals(true, confirmed.permits(PatchMode.Verified))
        assertEquals(false, confirmed.permits(PatchMode.Experimental))
    }

    @Test
    fun clearingSinglePatchConfirmationClearsVerifiedRequirements() {
        val cleared = PatchConfirmation(
            acknowledgedInstallRisk = true,
            acknowledgedRecoveryLimit = true,
            acknowledgedReinstallRequirement = true,
        ).withSinglePatchConfirmation(false)

        assertEquals(false, cleared.acknowledgedInstallRisk)
        assertEquals(false, cleared.acknowledgedRecoveryLimit)
        assertEquals(false, cleared.acknowledgedReinstallRequirement)
        assertEquals(false, cleared.permits(PatchMode.Verified))
    }
    @Test
    fun nonBusyStagesDoNotShowAnOperationStatus() {
        assertNull(PatchUiState.ChooseSource.toStartOperationStatus())
        assertNull(PatchUiState.Failed("导入失败").toStartOperationStatus())
        assertNull(PatchUiState.Completed("transaction").toStartOperationStatus())
    }
}
