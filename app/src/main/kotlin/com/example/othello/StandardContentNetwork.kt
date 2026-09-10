package com.example.othello

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val STANDARD_CONTENT_HTTP_TIMEOUT_MILLIS = 10_000
private const val STANDARD_CONTENT_MAX_INDEX_BYTES = 128 * 1024

internal data class StandardContentHttpResponse(
    val statusCode: Int,
    val body: String,
)

internal fun interface StandardContentIndexHttpTransport {
    suspend fun get(url: String): StandardContentHttpResponse
}

internal fun interface StandardContentIndexFetcher {
    suspend fun fetch(): StandardContentIndex
}

internal class RemoteStandardContentIndexFetcher(
    private val transport: StandardContentIndexHttpTransport,
    private val endpoint: String = STANDARD_CONTENT_INDEX_URL,
) : StandardContentIndexFetcher {
    override suspend fun fetch(): StandardContentIndex {
        val response = transport.get(endpoint)
        if (response.statusCode !in 200..299) {
            throw IOException("Standard content index returned HTTP ${response.statusCode}")
        }
        return parseStandardContentIndex(response.body)
    }
}

internal class UrlConnectionStandardContentIndexTransport(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : StandardContentIndexHttpTransport {
    override suspend fun get(url: String): StandardContentHttpResponse = withContext(dispatcher) {
        requireHttps(url)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = STANDARD_CONTENT_HTTP_TIMEOUT_MILLIS
            connection.readTimeout = STANDARD_CONTENT_HTTP_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            StandardContentHttpResponse(
                statusCode = status,
                body = if (status in 200..299) {
                    connection.inputStream.use { it.readBoundedUtf8(STANDARD_CONTENT_MAX_INDEX_BYTES) }
                } else {
                    ""
                },
            )
        } finally {
            connection.disconnect()
        }
    }
}

internal fun interface StandardContentPackDownloader {
    suspend fun download(url: String, destination: File, maxBytes: Long): Long
}

internal class UrlConnectionStandardContentPackDownloader(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : StandardContentPackDownloader {
    override suspend fun download(url: String, destination: File, maxBytes: Long): Long =
        withContext(dispatcher) {
            require(maxBytes in 1..STANDARD_CONTENT_MAX_PACK_BYTES)
            requireHttps(url)
            destination.parentFile?.mkdirs()
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = STANDARD_CONTENT_HTTP_TIMEOUT_MILLIS
                connection.readTimeout = STANDARD_CONTENT_HTTP_TIMEOUT_MILLIS
                connection.setRequestProperty("Accept", "application/zip, application/octet-stream")
                val status = connection.responseCode
                if (status !in 200..299) throw IOException("Standard content pack returned HTTP $status")
                val advertisedLength = connection.contentLengthLong
                if (advertisedLength > maxBytes) throw IOException("Standard content pack is too large")
                connection.inputStream.use { input ->
                    FileOutputStream(destination).use { output ->
                        input.copyBounded(output, maxBytes)
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
}

private fun requireHttps(url: String) {
    val parsed = URL(url)
    require(parsed.protocol.equals("https", ignoreCase = true)) {
        "Standard content transport accepts HTTPS only"
    }
}

private fun InputStream.readBoundedUtf8(maxBytes: Int): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4_096)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) throw IOException("Standard content index is too large")
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}

private fun InputStream.copyBounded(output: FileOutputStream, maxBytes: Long): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) throw IOException("Standard content pack exceeded download limit")
        output.write(buffer, 0, count)
    }
    output.fd.sync()
    return total
}
