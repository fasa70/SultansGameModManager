package com.sultansgame.modmanager.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkModelsTest {
    private val inspection = ApkInspection(
        sourceLabel = "base.apk",
        packageName = "com.gametree.sultan.pd",
        versionCode = 1,
        versionName = "1.0",
        splitName = null,
        supportedAbis = setOf("arm64-v8a"),
        signerDigestsSha256 = setOf("official"),
        entryCount = 10,
        sizeBytes = 100,
        warnings = emptyList(),
    )

    @Test
    fun `verified patch requires install recovery and reinstall acknowledgements`() {
        val plan = plan(PatchMode.Verified, PatchConfirmation())

        assertTrue(plan.requiresConfirmation())
        assertFalse(
            plan.copy(
                confirmation = PatchConfirmation(
                    acknowledgedInstallRisk = true,
                    acknowledgedRecoveryLimit = true,
                    acknowledgedReinstallRequirement = true,
                ),
            ).requiresConfirmation(),
        )
    }

    @Test
    fun `experimental patch additionally requires backup and retry acknowledgements`() {
        val incomplete = plan(
            PatchMode.Experimental,
            PatchConfirmation(
                acknowledgedInstallRisk = true,
                acknowledgedRecoveryLimit = true,
                acknowledgedReinstallRequirement = true,
            ),
        )
        val complete = incomplete.copy(
            confirmation = incomplete.confirmation.copy(
                confirmedBackup = true,
                confirmedExperimentalRetry = true,
            ),
        )

        assertTrue(incomplete.requiresConfirmation())
        assertFalse(complete.requiresConfirmation())
    }

    private fun plan(mode: PatchMode, confirmation: PatchConfirmation) = PatchInstallPlan(
        transactionId = "transaction",
        source = PatchSource.InstalledGame,
        mode = mode,
        base = PatchArtifact("base.apk", "a".repeat(64), 1, inspection),
        splits = emptyList(),
        deviceCertificateSha256 = "b".repeat(64),
        confirmation = confirmation,
    )
}
