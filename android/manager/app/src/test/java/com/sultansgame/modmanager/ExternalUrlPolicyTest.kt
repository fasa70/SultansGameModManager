package com.sultansgame.modmanager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalUrlPolicyTest {
    @Test fun allowsCanonicalWorkshopNativeUrl() { assertTrue(isAllowedWorkshopNativeUrl(WORKSHOP_NATIVE_URL)) }

    @Test fun allowsCanonicalGitHubRepoUrl() { assertTrue(isAllowedGitHubRepoUrl(GITHUB_REPO_URL)) }

    @Test fun rejectsUntrustedGitHubRepoUrls() {
        listOf(
            "http://github.com/fasa70/SultansGameModManager",
            "https://evil.example/fasa70/SultansGameModManager",
            "https://github.com/other/SultansGameModManager",
            "https://github.com/fasa70/OtherRepo",
            "https://github.com/fasa70/SultansGameModManager/issues/1",
            "https://github.com/fasa70/SultansGameModManager?redirect=https://evil.example",
            "https://user:pass@github.com/fasa70/SultansGameModManager",
        ).forEach { assertFalse(it, isAllowedGitHubRepoUrl(it)) }
    }

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
