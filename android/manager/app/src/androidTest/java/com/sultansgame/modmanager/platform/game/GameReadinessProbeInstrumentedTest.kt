package com.sultansgame.modmanager.platform.game

import androidx.test.platform.app.InstrumentationRegistry
import com.sultansgame.modmanager.apk.LoaderSplitRevision
import com.sultansgame.modmanager.apk.LoaderSplitRevisionReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameReadinessProbeInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun embeddedTemplateDeclaresReadableRevision() {
        val reader = LoaderSplitRevisionReader()
        val revision = reader.read { context.assets.open("release/modloader-template-10005.apk") }
        assertTrue("内嵌模板 revision 必须可读：$revision", revision is LoaderSplitRevision.Known)
        assertTrue((revision as LoaderSplitRevision.Known).value >= 1)
    }

    @Test
    fun probeReportsNotInstalledWhenGameAbsent() {
        val probe = GameReadinessProbe(context, com.sultansgame.modmanager.platform.patch.DeviceSigningKeyStore(context))
        val readiness = probe.evaluate(GameProbeResult.NotInstalled)
        assertEquals(GameReadiness.NotInstalled, readiness)
    }
}
