package com.homeworkbuddy

import java.net.URI

/** Only the visible first-party HTTPS page may request microphone access. */
internal object WebMicrophonePolicy {
    fun allows(origin: String, pageUrl: String?): Boolean = runCatching {
        val source = URI(origin)
        val page = URI(pageUrl ?: return false)
        val host = source.host?.lowercase() ?: return false
        source.scheme == "https" && source.rawUserInfo == null &&
            (host == "cyeam.com" || host.endsWith(".cyeam.com")) &&
            page.scheme == "https" && page.rawUserInfo == null &&
            host.equals(page.host, ignoreCase = true) && port(source) == port(page)
    }.getOrDefault(false)

    private fun port(uri: URI): Int = if (uri.port == -1) 443 else uri.port
}
