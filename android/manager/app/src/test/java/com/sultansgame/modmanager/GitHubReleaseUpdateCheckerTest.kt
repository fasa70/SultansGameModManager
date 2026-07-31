package com.sultansgame.modmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseUpdateCheckerTest {
    @Test
    fun `returns update for a newer semantic version`() {
        val result = runCheck(
            currentVersion = "0.1.0",
            response = successResponse(tag = "v0.1.1"),
        )

        assertEquals("0.1.1", (result as UpdateCheckResult.UpdateAvailable).update.version)
    }

    @Test
    fun `compares version numbers instead of strings`() {
        val result = runCheck(
            currentVersion = "0.1.9",
            response = successResponse(tag = "0.1.10"),
        )

        assertTrue(result is UpdateCheckResult.UpdateAvailable)
    }

    @Test
    fun `does not announce equal or older releases`() {
        assertEquals(UpdateCheckResult.NoUpdate, runCheck("0.1.0", successResponse(tag = "0.1.0")))
        assertEquals(UpdateCheckResult.NoUpdate, runCheck("0.1.1", successResponse(tag = "0.1.0")))
    }

    @Test
    fun `does not announce draft prerelease or malformed releases`() {
        assertEquals(UpdateCheckResult.NoUpdate, runCheck("0.1.0", successResponse(tag = "0.1.1", draft = true)))
        assertEquals(UpdateCheckResult.NoUpdate, runCheck("0.1.0", successResponse(tag = "0.1.1", prerelease = true)))
        assertEquals(UpdateCheckResult.NoUpdate, runCheck("0.1.0", successResponse(tag = "release-0.1.1")))
        assertEquals(UpdateCheckResult.NoUpdate, runCheck("0.1.0", successResponse(tag = "0.1.1-beta")))
        assertEquals(UpdateCheckResult.NoUpdate, runCheck("2147483648.0.0", successResponse(tag = "2147483649.0.0")))
    }

    @Test
    fun `rejects invalid release page addresses`() {
        assertEquals(UpdateCheckResult.Failed, runCheck("0.1.0", successResponse(url = "http://github.com/fasa70/SultansGameModManager/releases/tag/v0.1.1")))
        assertEquals(UpdateCheckResult.Failed, runCheck("0.1.0", successResponse(url = "https://example.com/fasa70/SultansGameModManager/releases/tag/v0.1.1")))
        assertEquals(UpdateCheckResult.Failed, runCheck("0.1.0", successResponse(url = "https://github.com/fasa70/SultansGameModManager/releases/tag/v0.1.1?redirect=https://example.com")))
        assertTrue(isAllowedReleasePageUrl("https://github.com/fasa70/SultansGameModManager/releases/tag/v0.1.1"))
        assertFalse(isAllowedReleasePageUrl("https://github.com/fasa70/SultansGameModManager/issues/1"))
    }

    @Test
    fun `fails silently for unsuccessful or invalid responses`() {
        assertEquals(UpdateCheckResult.Failed, runCheck("0.1.0", ReleaseHttpResponse(403, "{}")))
        assertEquals(UpdateCheckResult.Failed, runCheck("0.1.0", ReleaseHttpResponse(200, "not json")))
        assertEquals(UpdateCheckResult.Failed, runCheck("0.1.0", ReleaseHttpResponse(200, "{}")))
    }

    private fun runCheck(currentVersion: String, response: ReleaseHttpResponse): UpdateCheckResult =
        kotlinx.coroutines.runBlocking {
            GitHubReleaseUpdateChecker(GitHubReleaseTransport { response }).check(currentVersion)
        }

    private fun successResponse(
        tag: String = "0.1.1",
        url: String = "https://github.com/fasa70/SultansGameModManager/releases/tag/v0.1.1",
        draft: Boolean = false,
        prerelease: Boolean = false,
    ): ReleaseHttpResponse = ReleaseHttpResponse(
        code = 200,
        body = """
            {
              "tag_name": "$tag",
              "name": "Release $tag",
              "html_url": "$url",
              "body": "Changes",
              "draft": $draft,
              "prerelease": $prerelease
            }
        """.trimIndent(),
    )
}
