package com.sultansgame.modmanager

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume

private const val GITHUB_RELEASE_API = "https://api.github.com/repos/fasa70/SultansGameModManager/releases/latest"
private const val GITHUB_RELEASE_PAGE_PREFIX = "https://github.com/fasa70/SultansGameModManager/releases/"
private const val UPDATE_CHECK_TIMEOUT_SECONDS = 10L

/** A verified GitHub Release that is newer than the installed application. */
data class AvailableUpdate(
    val version: String,
    val name: String,
    val releaseUrl: String,
    val notes: String,
)

sealed interface UpdateCheckResult {
    data class UpdateAvailable(val update: AvailableUpdate) : UpdateCheckResult
    data object NoUpdate : UpdateCheckResult
    data object Failed : UpdateCheckResult
}

fun interface UpdateChecker {
    suspend fun check(currentVersion: String): UpdateCheckResult
}

internal data class ReleaseHttpResponse(
    val code: Int,
    val body: String,
)

internal fun interface GitHubReleaseTransport {
    suspend fun loadLatestRelease(): ReleaseHttpResponse
}

internal class GitHubReleaseUpdateChecker(
    private val transport: GitHubReleaseTransport = OkHttpGitHubReleaseTransport(),
) : UpdateChecker {
    override suspend fun check(currentVersion: String): UpdateCheckResult = try {
        val current = SemanticVersion.parse(currentVersion) ?: return UpdateCheckResult.NoUpdate
        val response = transport.loadLatestRelease()
        if (response.code != 200) return UpdateCheckResult.Failed

        val release = Json.parseToJsonElement(response.body).jsonObject
        if (release.boolean("draft") || release.boolean("prerelease")) return UpdateCheckResult.NoUpdate

        val tag = release.string("tag_name") ?: return UpdateCheckResult.Failed
        val latest = SemanticVersion.parse(tag) ?: return UpdateCheckResult.NoUpdate
        if (latest <= current) return UpdateCheckResult.NoUpdate

        val releaseUrl = release.string("html_url")?.takeIf(::isAllowedReleasePageUrl)
            ?: return UpdateCheckResult.Failed
        UpdateCheckResult.UpdateAvailable(
            AvailableUpdate(
                version = latest.toString(),
                name = release.string("name").orEmpty().ifBlank { tag },
                releaseUrl = releaseUrl,
                notes = release.string("body").orEmpty().take(1_000),
            ),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        UpdateCheckResult.Failed
    }

    private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

    private fun kotlinx.serialization.json.JsonObject.boolean(name: String): Boolean =
        this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
}

internal class OkHttpGitHubReleaseTransport : GitHubReleaseTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(UPDATE_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun loadLatestRelease(): ReleaseHttpResponse {
        val request = Request.Builder()
            .url(GITHUB_RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "SultansGameModManager-Android")
            .build()
        return client.newCall(request).awaitResponse()
    }
}

private suspend fun Call.awaitResponse(): ReleaseHttpResponse = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, exception: java.io.IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(exception))
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                if (continuation.isActive) {
                    continuation.resume(ReleaseHttpResponse(it.code, it.body.string()))
                }
            }
        }
    })
}

internal fun isAllowedReleasePageUrl(url: String): Boolean {
    val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    return parsed.scheme == "https" &&
        parsed.host == "github.com" &&
        parsed.port == -1 &&
        parsed.userInfo == null &&
        parsed.query == null &&
        parsed.fragment == null &&
        parsed.path.startsWith("/fasa70/SultansGameModManager/releases/")
}

internal data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int = compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val pattern = Regex("^[vV]?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")

        fun parse(value: String): SemanticVersion? {
            val match = pattern.matchEntire(value.trim()) ?: return null
            return runCatching {
                SemanticVersion(
                    major = match.groupValues[1].toIntExact(),
                    minor = match.groupValues[2].toIntExact(),
                    patch = match.groupValues[3].toIntExact(),
                )
            }.getOrNull()
        }

        private fun String.toIntExact(): Int {
            val parsed = toLong()
            require(parsed <= Int.MAX_VALUE)
            return parsed.toInt()
        }
    }
}
