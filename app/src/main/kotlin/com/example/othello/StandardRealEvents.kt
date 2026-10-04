package com.example.othello

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
private const val STANDARD_REAL_EVENT_PREVIEW_COUNT = 4

private val RealEventGold = Color(0xFFC59A54)
private val RealEventGoldMuted = Color(0xFF765D37)
private val RealEventGreen = Color(0xFF0B1715)
private val RealEventGreenElevated = Color(0xFF10221E)
private val RealEventRed = Color(0xFFD93C43)

internal data class StandardRealEvent(
    val prefectureCode: String,
    val prefectureName: String,
    val date: String,
    val eventName: String,
    val venueName: String,
    val sourceUrl: String,
    val retrievedDate: String,
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
        val retrievedDate = event.requiredString("retrievedDate", 10)

        val prefectureNumber = prefectureCode.toIntOrNull()
        if (prefectureCode.length != 2 || prefectureNumber == null || prefectureNumber !in 1..47) {
            throw StandardRealEventFormatException()
        }
        if (!date.isIsoDate() || !retrievedDate.isIsoDate()) {
            throw StandardRealEventFormatException()
        }
        val sourceUri = runCatching { URI(sourceUrl) }
            .getOrElse { throw StandardRealEventFormatException() }
        val sourceScheme = sourceUri.scheme?.lowercase()
        if ((sourceScheme != "http" && sourceScheme != "https") || sourceUri.host.isNullOrBlank()) {
            throw StandardRealEventFormatException()
        }

        StandardRealEvent(
            prefectureCode = prefectureCode,
            prefectureName = prefectureName,
            date = date,
            eventName = eventName,
            venueName = venueName,
            sourceUrl = sourceUrl,
            retrievedDate = retrievedDate,
        )
    }.sortedWith(
        compareBy<StandardRealEvent>(
            { it.prefectureCode },
            { it.date },
            { it.eventName },
        ),
    )
}

private fun String.isIsoDate(): Boolean =
    length == 10 && runCatching { LocalDate.parse(this) }.isSuccess

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

internal sealed interface StandardRealEventUiState {
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
internal fun StandardRealEventScreen(
    state: StandardRealEventUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var showAllEvents by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        color = ChanrivaColors.background,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            ChanrivaColors.background,
                            RealEventGreen.copy(alpha = 0.78f),
                            ChanrivaColors.background,
                        ),
                    ),
                ),
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
                StandardRealEventUiState.Loading -> {
                    item {
                        RealEventSectionHeader(
                            title = appString(R.string.standard_real_event_discovered_title),
                            supporting = appString(R.string.standard_real_event_discovered_supporting),
                        )
                    }
                    item {
                        Text(
                            text = appString(R.string.standard_real_event_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                StandardRealEventUiState.Failed -> {
                    item {
                        RealEventSectionHeader(
                            title = appString(R.string.standard_real_event_discovered_title),
                            supporting = appString(R.string.standard_real_event_discovered_supporting),
                        )
                    }
                    item {
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
                }

                is StandardRealEventUiState.Loaded -> {
                    val events = state.events.sortedWith(
                        compareBy<StandardRealEvent>({ it.date }, { it.prefectureCode }, { it.eventName }),
                    )
                    val prefectureCount = events.map { it.prefectureCode }.distinct().size

                    item {
                        RealEventSectionHeader(
                            title = appString(R.string.standard_real_event_discovered_title),
                            supporting = appString(R.string.standard_real_event_discovered_supporting),
                            trailing = if (events.isEmpty()) null else appString(
                                R.string.standard_real_event_summary,
                                prefectureCount,
                                events.size,
                            ),
                        )
                    }

                    if (events.isEmpty()) {
                        item {
                            Text(
                                text = appString(R.string.standard_real_event_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        val visibleEvents = if (showAllEvents) events else events.take(STANDARD_REAL_EVENT_PREVIEW_COUNT)
                        items(
                            items = visibleEvents,
                            key = { "${it.prefectureCode}:${it.date}:${it.eventName}" },
                        ) { event ->
                            StandardRealEventCard(
                                event = event,
                                onClick = { runCatching { uriHandler.openUri(event.sourceUrl) } },
                            )
                        }

                        if (events.size > STANDARD_REAL_EVENT_PREVIEW_COUNT) {
                            item {
                                OutlinedButton(
                                    onClick = { showAllEvents = !showAllEvents },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, RealEventGold),
                                ) {
                                    Text(
                                        text = if (showAllEvents) {
                                            appString(R.string.standard_real_event_less)
                                        } else {
                                            appString(R.string.standard_real_event_more, events.size)
                                        },
                                        color = RealEventGold,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                StandardOfficialOthelloBlockLinks()
            }
        }
    }
}

@Composable
internal fun RealEventSectionHeader(
    title: String,
    supporting: String,
    trailing: String? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 28.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(RealEventRed),
            )
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = ChanrivaColors.textPrimary,
            )
            trailing?.let {
                Text(
                    text = it,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(RealEventGreenElevated)
                        .border(1.dp, RealEventGoldMuted, RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = RealEventGold,
                    maxLines = 2,
                )
            }
        }
        Row(
            modifier = Modifier.padding(start = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = supporting,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodyMedium,
                color = ChanrivaColors.textSecondary,
            )
            Spacer(Modifier.width(8.dp))
            OthelloMiniDiscs()
        }
    }
}

@Composable
private fun OthelloMiniDiscs() {
    Row(horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(ChanrivaColors.blackDisc)
                .border(1.dp, RealEventGoldMuted, CircleShape),
        )
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(ChanrivaColors.whiteDisc)
                .border(1.dp, RealEventGoldMuted, CircleShape),
        )
    }
}

@Composable
private fun StandardRealEventCard(
    event: StandardRealEvent,
    onClick: () -> Unit,
) {
    val dateParts = event.date.split('-')
    val year = dateParts.getOrNull(0).orEmpty()
    val monthDay = if (dateParts.size == 3) "${dateParts[1]}/${dateParts[2]}" else event.date.replace('-', '/')

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = RealEventGreenElevated),
        border = BorderStroke(1.dp, RealEventGoldMuted),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            RealEventGreenElevated,
                            ChanrivaColors.surfaceElevated,
                        ),
                    ),
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 54.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(RealEventRed),
            )
            Column(
                modifier = Modifier.width(72.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = year,
                    style = MaterialTheme.typography.labelSmall,
                    color = ChanrivaColors.textSecondary,
                )
                Text(
                    text = monthDay,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = ChanrivaColors.textPrimary,
                    maxLines = 1,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = event.prefectureName.removeSuffix("都").removeSuffix("道").removeSuffix("府").removeSuffix("県"),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(ChanrivaColors.accentSoft.copy(alpha = 0.55f))
                        .border(1.dp, RealEventRed, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = ChanrivaColors.accent,
                    maxLines = 1,
                )
                Text(
                    text = event.eventName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ChanrivaColors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = event.venueName,
                    style = MaterialTheme.typography.bodySmall,
                    color = ChanrivaColors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.headlineSmall,
                color = RealEventGold,
            )
        }
    }
}
