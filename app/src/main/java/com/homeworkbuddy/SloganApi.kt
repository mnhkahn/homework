package com.homeworkbuddy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

const val DEFAULT_HOME_SLOGAN = "按顺序完成就好！"

/** Loads the optional slogan shown in the home-screen header. */
object SloganApi {
    private const val ENDPOINT = "https://www.cyeam.com/api/slogan"

    suspend fun fetch(): String? = withContext(Dispatchers.IO) {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) return@withContext null
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .optString("slogan")
                .trim()
                .takeIf(String::isNotBlank)
        } finally {
            connection.disconnect()
        }
    }
}
