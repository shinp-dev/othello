package com.example.othello

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val APP_CONFIG_URL = "https://chanriva.shinp-studio.com/api/app-config"
private const val APP_CONFIG_TIMEOUT_MILLIS = 10_000
private const val MAX_APP_CONFIG_BYTES = 8_192
private const val ANDROID_MIN_VERSION_CODE_FIELD = "android_min_version_code"

internal data class AppConfigHttpResponse(
    val statusCode: Int,
    val body: String,
)

internal fun interface AppConfigHttpTransport {
    suspend fun get(url: String): AppConfigHttpResponse
}

internal fun interface AppConfigFetcher {
    suspend fun fetchMinimumSupportedVersionCode(): Int
}

internal class CloudflareAppConfigFetcher(
    private val transport: AppConfigHttpTransport,
    private val endpoint: String = APP_CONFIG_URL,
) : AppConfigFetcher {
    override suspend fun fetchMinimumSupportedVersionCode(): Int {
        val response = transport.get(endpoint)
        if (response.statusCode !in 200..299) throw AppConfigFetchException()

        val root = try {
            Json.parseToJsonElement(response.body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: throw AppConfigFetchException()
        val minimum = root[ANDROID_MIN_VERSION_CODE_FIELD] as? JsonPrimitive
            ?: throw AppConfigFetchException()
        if (minimum.isString) throw AppConfigFetchException()
        return minimum.content.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: throw AppConfigFetchException()
    }
}

internal class UrlConnectionAppConfigHttpTransport(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AppConfigHttpTransport {
    override suspend fun get(url: String): AppConfigHttpResponse = withContext(dispatcher) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = APP_CONFIG_TIMEOUT_MILLIS
            connection.readTimeout = APP_CONFIG_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "application/json")
            val statusCode = connection.responseCode
            AppConfigHttpResponse(
                statusCode = statusCode,
                body = if (statusCode in 200..299) {
                    connection.inputStream.use(InputStream::readBoundedAppConfigBody)
                } else {
                    ""
                },
            )
        } finally {
            connection.disconnect()
        }
    }
}

private fun InputStream.readBoundedAppConfigBody(): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(1_024)
    var totalBytes = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        totalBytes += count
        if (totalBytes > MAX_APP_CONFIG_BYTES) throw IOException("App config response is too large")
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}

private class AppConfigFetchException : Exception()
