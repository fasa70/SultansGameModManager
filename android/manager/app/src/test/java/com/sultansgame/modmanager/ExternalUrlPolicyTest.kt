package com.sultansgame.modmanager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalUrlPolicyTest {
    @Test fun allowsCanonicalWorkshopNativeUrl() { assertTrue(isAllowedWorkshopNativeUrl(WORKSHOP_NATIVE_URL)) }

    @Test fun rejectsUntrustedWorkshopUrls() {
        listOf(
            "http://github.com/cjtestuse/Workshop-Native",
            "https://evil.example/cjtestuse/Workshop-Native",
            "https://github.com/other/Workshop-Native",
            "https://github.com/cjtestuse/Workshop-Native/issues/1",
            "https://github.com/cjtestuse/Workshop-Native?redirect=https://evil.example",
            "https://user:pass@github.com/cjtestuse/Workshop-Native",
        ).forEach { assertFalse(it, isAllowedWorkshopNativeUrl(it)) }
    }
}
