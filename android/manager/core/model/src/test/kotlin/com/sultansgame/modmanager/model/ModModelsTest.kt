package com.sultansgame.modmanager.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ModModelsTest {
    @Test
    fun `native runtime codes retain unknown values without crashing`() {
        assertSame(LoaderRuntimeState.Ready, LoaderRuntimeState.fromNativeCode(3))
        assertSame(LoaderRuntimeState.Unknown, LoaderRuntimeState.fromNativeCode(99))
        assertSame(LoaderFailure.HookInstallFailed, LoaderFailure.fromNativeCode(9))
        assertSame(LoaderFailure.Unknown, LoaderFailure.fromNativeCode(-7))
    }

    @Test
    fun `scan result requires native compatible ordering`() {
        val result = ModScanResult(
            mods = listOf(
                ModRecord("0001-first", emptyList()),
                ModRecord("0002-last", emptyList()),
            ),
            rejectedEntries = emptyList(),
        )

        assertEquals("0001-first", result.mods.first().loadOrderKey)
    }
}
