package com.homeworkbuddy

import java.net.URI

/** Parent-provided web destinations; never launch file or application schemes. */
object HomeworkLinks {
    val studyAllowedHosts = listOf("cyeam.com", "trello.com", "pgyer.com")

    fun isWebUrl(value: String): Boolean = parse(value) != null

    /** Only the parent-supplied initial load bypasses the study navigation allowlist. */
    fun allowsNavigation(value: String): Boolean {
        val host = parse(value)?.host ?: return false
        return studyAllowedHosts.any { host.equals(it, true) || host.endsWith(".$it", true) }
    }

    private fun parse(value: String): URI? = runCatching {
        URI(value).takeIf {
            (it.scheme.equals("http", true) || it.scheme.equals("https", true)) &&
                !it.host.isNullOrBlank() && it.rawUserInfo == null && it.port in -1..65535
        }
    }.getOrNull()
}
