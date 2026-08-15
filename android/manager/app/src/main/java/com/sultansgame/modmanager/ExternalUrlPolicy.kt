package com.sultansgame.modmanager

internal const val WORKSHOP_NATIVE_URL = "https://github.com/cjtestuse/Workshop-Native"

internal fun isAllowedWorkshopNativeUrl(url: String): Boolean {
    val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    return parsed.scheme == "https" &&
        parsed.host == "github.com" &&
        parsed.port == -1 &&
        parsed.userInfo == null &&
        parsed.query == null &&
        parsed.fragment == null &&
        parsed.path == "/cjtestuse/Workshop-Native"
}
