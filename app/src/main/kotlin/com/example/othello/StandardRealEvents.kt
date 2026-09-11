package com.example.othello

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.example.othello.designsystem.ChanrivaColors
import com.example.othello.designsystem.ChanrivaScreenHeader
import com.example.othello.designsystem.ChanrivaSpacing
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val STANDARD_REAL_EVENTS_URL =
    "https://raw.githubusercontent.com/shinp-dev/chanriva-events/main/events.json"

private const val STANDARD_REAL_EVENTS_TIMEOUT_MILLIS = 10_000
private const val MAX_STANDARD_REAL_EVENTS_BYTES = 256 * 1024
private const val MAX_STANDARD_REAL_EVENT_COUNT = 500
private const val MAX_STANDARD_REAL_EVENT_TEXT_LENGTH = 160
private const val MAX_STANDARD_REAL_EVENT_URL_LENGTH = 2_048

internal data class StandardRealEvent(
    val prefectureCode: String,
    val prefectureName: String,
    val date: String,
    val eventName: String,
    val venueName: String,
    val sourceUrl: String,
)

internal data class StandardRealEventHttpResponse(
    val statusCode: Int,
    val body: String,
)

internal fun interface StandardRealEventHttpTransport {
    suspend fun get(url: String): StandardRealEventHttpResponse
}

internal fun interface StandardRealEventFetcher {
    suspend fun fetch(): List<StandardRealEvent>
}

internal class GitHubStandardRealEventFetcher(
    private val transport: StandardRealEventHttpTransport = UrlConnectionStandardRealEventHttpTransport(),
    private val endpoint: String = STANDARD_REAL_EVENTS_URL,
) : StandardRealEventFetcher {
    override suspend fun fetch(): List<StandardRealEvent> {
        val response = transport.get(endpoint)
        if (response.statusCode !in 200..299) throw StandardRealEventFetchException()
        return decodeStandardRealEvents(response.body)
    }
}

internal class UrlConnectionStandardRealEventHttpTransport(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : StandardRealEventHttpTransport {
    override suspend fun get(url: String): StandardRealEventHttpResponse = withContext(dispatcher) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.connectTimeout = STANDARD_REAL_EVENTS_TIMEOUT_MILLIS
            connection.readTimeout = STANDARD_REAL_EVENTS_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            val statusCode = connection.responseCode
            StandardRealEventHttpResponse(
                statusCode = statusCode,
                body = if (statusCode in 200..299) {
                    connection.inputStream.use(InputStream::readBoundedStandardRealEventsBody)
                } else {
                    ""
                },
            )
        } finally {
            connection.disconnect()
        }
    }
}

internal fun decodeStandardRealEvents(encoded: String): List<StandardRealEvent> {
    val array = try {
        Json.parseToJsonElement(encoded) as? JsonArray
    } catch (_: Exception) {
        null
    } ?: throw StandardRealEventFormatException()

    if (array.size > MAX_STANDARD_REAL_EVENT_COUNT) throw StandardRealEventFormatException()

    return array.map { element ->
        val event = element as? JsonObject ?: throw StandardRealEventFormatException()
        val prefectureCode = event.requiredString("prefectureCode", 2)
        val prefectureName = event.requiredString("prefectureName", MAX_STANDARD_REAL_EVENT_TEXT_LENGTH)
        val date = event.requiredString("date", 10)
        val eventName = event.requiredString("eventName", MAX_STANDARD_REAL_EVENT_TEXT_LENGTH)
        val venueName = event.requiredString("venueName", MAX_STANDARD_REAL_EVENT_TEXT_LENGTH)
        val sourceUrl = event.requiredString("sourceUrl", MAX_STANDARD_REAL_EVENT_URL_LENGTH)

        val prefectureNumber = prefectureCode.toIntOrNull()
        if (prefectureCode.length != 2 || prefectureNumber == null || prefectureNumber !in 1..47) {
            throw StandardRealEventFormatException()
        }
        if (date.length != 10 || runCatching { LocalDate.parse(date) }.isFailure) {
            throw StandardRealEventFormatException()
        }
        val sourceUri = runCatching { URI(sourceUrl) }
            .getOrElse { throw StandardRealEventFormatException() }
        if (sourceUri.scheme?.lowercase() !in setOf("http", "https") || sourceUri.host.isNullOrBlank()) {
            throw StandardRealEventFormatException()
        }

        StandardRealEvent(
            prefectureCode = prefectureCode,
            prefectureName = prefectureName,
            date = date,
            eventName = eventName,
            venueName = venueName,
            sourceUrl = sourceUrl,
        )
    }.sortedWith(
        compareBy<StandardRealEvent>(
            { it.prefectureCode },
            { it.date },
            { it.eventName },
        ),
    )
}

private fun JsonObject.requiredString(name: String, maxLength: Int): String {
    val primitive = this[name] as? JsonPrimitive ?: throw StandardRealEventFormatException()
    if (!primitive.isString) throw StandardRealEventFormatException()
    return primitive.content.trim()
        .takeIf { it.isNotEmpty() && it.length <= maxLength }
        ?: throw StandardRealEventFormatException()
}

private fun InputStream.readBoundedStandardRealEventsBody(): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4_096)
    var totalBytes = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        totalBytes += count
        if (totalBytes > MAX_STANDARD_REAL_EVENTS_BYTES) {
            throw IOException("Real event response is too large")
        }
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}

internal class StandardRealEventFetchException : Exception()
internal class StandardRealEventFormatException : Exception()

private sealed interface StandardRealEventUiState {
    data object Loading : StandardRealEventUiState
    data class Loaded(val events: List<StandardRealEvent>) : StandardRealEventUiState
    data object Failed : StandardRealEventUiState
}

@Composable
internal fun StandardRealEventRoute(onBack: () -> Unit) {
    val fetcher = remember { GitHubStandardRealEventFetcher() }
    StandardRealEventRoute(
        onBack = onBack,
        fetcher = fetcher,
    )
}

@Composable
internal fun StandardRealEventRoute(
    onBack: () -> Unit,
    fetcher: StandardRealEventFetcher,
) {
    var refreshGeneration by remember { mutableIntStateOf(0) }
    var state by remember(fetcher) {
        mutableStateOf<StandardRealEventUiState>(StandardRealEventUiState.Loading)
    }

    LaunchedEffect(fetcher, refreshGeneration) {
        state = StandardRealEventUiState.Loading
        state = try {
            StandardRealEventUiState.Loaded(fetcher.fetch())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            StandardRealEventUiState.Failed
        }
    }

    StandardRealEventScreen(
        state = state,
        onBack = onBack,
        onRetry = { refreshGeneration++ },
    )
}

@Composable
private fun StandardRealEventScreen(
    state: StandardRealEventUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current

    Surface(Modifier.fillMaxSize().statusBarsPadding()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(ChanrivaSpacing.page),
            verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
        ) {
            item {
                ChanrivaScreenHeader(
                    title = appString(R.string.standard_real_event),
                    onBack = onBack,
                    backLabel = appString(R.string.back),
                )
            }

            when (state) {
                StandardRealEventUiState.Loading -> item {
                    Text(
                        text = appString(R.string.standard_real_event_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                StandardRealEventUiState.Failed -> item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.control),
                    ) {
                        Text(
                            text = appString(R.string.standard_real_event_load_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onRetry) {
                            Text(appString(R.string.standard_real_event_retry))
                        }
                    }
                }

                is StandardRealEventUiState.Loaded -> {
                    if (state.events.isEmpty()) {
                        item {
                            Text(
                                text = appString(R.string.standard_real_event_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        state.events.groupBy { it.prefectureCode }.values.forEach { prefectureEvents ->
                            item {
                                Text(
                                    text = prefectureEvents.first().prefectureName,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            items(prefectureEvents) { event ->
                                Card(
                                    onClick = { runCatching { uriHandler.openUri(event.sourceUrl) } },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = ChanrivaColors.surfaceElevated,
                                    ),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(ChanrivaSpacing.section),
                                        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.control),
                                    ) {
                                        Text(
                                            text = event.date.replace('-', '/'),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = event.eventName,
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            text = event.venueName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = appString(R.string.standard_real_event_source),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
