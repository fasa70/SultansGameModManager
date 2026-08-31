package com.sultansgame.modmanager.platform.game

import com.sultansgame.modmanager.apk.LoaderSplitRevision
import com.sultansgame.modmanager.model.Compatibility
import com.sultansgame.modmanager.model.CompatibilityReport
import com.sultansgame.modmanager.model.DeviceSigningKeyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameReadinessTest {
    private val certificate = "aa11bb22cc33dd44"
    private val otherCertificate = "ffffeeee"

    private fun snapshot(
        signerDigests: Set<String> = setOf(certificate),
        splitNames: Set<String> = setOf("base", "config.arm64_v8a", "modloader"),
    ) = InstalledGameSnapshot(
        packageName = "com.gametree.sultan.pd",
        versionCode = 10005L,
        versionName = "1.0.5",
        signerDigestsSha256 = signerDigests,
        splitNames = splitNames,
        artifacts = InstalledGameArtifacts("/data/app/base.apk", emptyList()),
        compatibility = CompatibilityReport(Compatibility.Unverified, emptyList()),
    )

    private fun evaluate(
        probe: GameProbeResult?,
        certificateSha256: String? = certificate,
        keyState: DeviceSigningKeyState = DeviceSigningKeyState.Ready,
        expected: LoaderSplitRevision = LoaderSplitRevision.Known(1),
        installed: LoaderSplitRevision = LoaderSplitRevision.Known(1),
    ): Pair<GameReadiness, Boolean> {
        var invoked = false
        val result = evaluateGameReadiness(
            probe = probe,
            deviceCertificateSha256 = certificateSha256,
            deviceKeyState = keyState,
            loaderSplitName = "modloader",
            expectedRevision = expected,
            installedRevision = {
                invoked = true
                installed
            },
        )
        return result to invoked
    }

    @Test
    fun `null probe is checking`() {
        val (readiness, invoked) = evaluate(null)
        assertEquals(GameReadiness.Checking, readiness)
        assertFalse(invoked)
    }

    @Test
    fun `not installed maps to not installed`() {
        val (readiness, invoked) = evaluate(GameProbeResult.NotInstalled)
        assertEquals(GameReadiness.NotInstalled, readiness)
        assertFalse(invoked)
    }

    @Test
    fun `probe failure maps to probe failed`() {
        val (readiness, invoked) = evaluate(GameProbeResult.Failed("不可读"))
        assertEquals(GameReadiness.ProbeFailed("不可读"), readiness)
        assertFalse(invoked)
    }

    @Test
    fun `missing after migration takes precedence over signature`() {
        val (readiness, invoked) = evaluate(
            GameProbeResult.Found(snapshot()),
            certificateSha256 = null,
            keyState = DeviceSigningKeyState.MissingAfterMigration,
        )
        assertEquals(GameReadiness.Unpatched("1.0.5（10005）", UnpatchedReason.DeviceKeyLost), readiness)
        assertFalse(invoked)
    }

    @Test
    fun `missing device key without migration marker is key missing`() {
        val (readiness, invoked) = evaluate(
            GameProbeResult.Found(snapshot()),
            certificateSha256 = null,
            keyState = DeviceSigningKeyState.NotCreated,
        )
        assertEquals(GameReadiness.Unpatched("1.0.5（10005）", UnpatchedReason.DeviceKeyMissing), readiness)
        assertFalse(invoked)
    }

    @Test
    fun `foreign signature without loader split is official install`() {
        val (readiness, invoked) = evaluate(
            GameProbeResult.Found(
                snapshot(
                    signerDigests = setOf(otherCertificate),
                    splitNames = setOf("base", "config.arm64_v8a"),
                ),
            ),
        )
        assertEquals(GameReadiness.Unpatched("1.0.5（10005）", UnpatchedReason.OfficialInstall), readiness)
        assertFalse(invoked)
    }

    @Test
    fun `foreign signature with loader split is foreign signer`() {
        val (readiness, invoked) = evaluate(
            GameProbeResult.Found(snapshot(signerDigests = setOf(otherCertificate))),
        )
        assertEquals(GameReadiness.Unpatched("1.0.5（10005）", UnpatchedReason.ForeignSigner), readiness)
        assertFalse(invoked)
    }

    @Test
    fun `multi digest history reports foreign signer`() {
        val (readiness, invoked) = evaluate(
            GameProbeResult.Found(snapshot(signerDigests = setOf(certificate, otherCertificate))),
        )
        assertEquals(GameReadiness.Unpatched("1.0.5（10005）", UnpatchedReason.ForeignSigner), readiness)
        assertFalse(invoked)
    }

    @Test
    fun `device signature without loader split is loader missing`() {
        val (readiness, invoked) = evaluate(
            GameProbeResult.Found(snapshot(splitNames = setOf("base", "config.arm64_v8a"))),
        )
        assertEquals(GameReadiness.Unpatched("1.0.5（10005）", UnpatchedReason.LoaderSplitMissing), readiness)
        assertFalse(invoked)
    }

    @Test
    fun `unreadable expected revision downgrades to ready with note`() {
        val (readiness, invoked) = evaluate(
            GameProbeResult.Found(snapshot()),
            expected = LoaderSplitRevision.Unreadable("坏"),
            installed = LoaderSplitRevision.Known(3),
        )
        assertTrue(invoked)
        val ready = readiness as GameReadiness.Ready
        assertEquals(3, ready.revision)
        assertTrue(ready.note.orEmpty().contains("内嵌模板"))
    }

    @Test
    fun `unreadable installed revision downgrades to ready without revision`() {
        val (readiness, invoked) = evaluate(
            GameProbeResult.Found(snapshot()),
            installed = LoaderSplitRevision.Unreadable("打不开"),
        )
        assertTrue(invoked)
        val ready = readiness as GameReadiness.Ready
        assertNull(ready.revision)
        assertTrue(ready.note.orEmpty().contains("打不开"))
    }

    @Test
    fun `absent installed revision counts as pre-revision zero`() {
        val (readiness, invoked) = evaluate(
            GameProbeResult.Found(snapshot()),
            expected = LoaderSplitRevision.Known(1),
            installed = LoaderSplitRevision.Absent,
        )
        assertTrue(invoked)
        assertEquals(GameReadiness.UpgradeRequired("1.0.5（10005）", 0, 1), readiness)
    }

    @Test
    fun `older installed revision requires upgrade`() {
        val (readiness, _) = evaluate(
            GameProbeResult.Found(snapshot()),
            installed = LoaderSplitRevision.Known(2),
            expected = LoaderSplitRevision.Known(5),
        )
        assertEquals(GameReadiness.UpgradeRequired("1.0.5（10005）", 2, 5), readiness)
    }

    @Test
    fun `equal revisions are ready`() {
        val (readiness, _) = evaluate(
            GameProbeResult.Found(snapshot()),
            installed = LoaderSplitRevision.Known(4),
            expected = LoaderSplitRevision.Known(4),
        )
        assertEquals(GameReadiness.Ready("1.0.5（10005）", 4), readiness)
    }

    @Test
    fun `newer installed revision marks manager outdated`() {
        val (readiness, _) = evaluate(
            GameProbeResult.Found(snapshot()),
            installed = LoaderSplitRevision.Known(6),
            expected = LoaderSplitRevision.Known(5),
        )
        assertEquals(GameReadiness.ManagerOutdated("1.0.5（10005）", 6, 5), readiness)
    }

    @Test
    fun `version label falls back to version code`() {
        assertEquals("1.0.5（10005）", snapshot().versionLabel())
        assertEquals("10005", snapshot().let { it.copy(versionName = null) }.versionLabel())
        assertEquals("10005", snapshot().let { it.copy(versionName = " ") }.versionLabel())
    }
}
