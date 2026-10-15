package im.flare.todo.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/** 기기(One UI)의 색상 팔레트를 그대로 따른다. */
@Composable
fun FlareTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = scheme, content = content)
}

private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

fun zoneOf(timezone: String): ZoneId = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.systemDefault())

/** 서버의 ISO-8601 시각을 사용자 시간대의 "yyyy-MM-dd HH:mm" 으로 */
fun formatInstant(iso: String?, timezone: String): String {
    if (iso.isNullOrBlank()) return ""
    return runCatching { DATE_TIME.format(Instant.parse(iso).atZone(zoneOf(timezone))) }.getOrDefault(iso)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

/** 1.5 → "1일 12시간" */
fun describeDays(days: Double): String {
    val totalMinutes = (days * 1440).roundToLong()
    val d = totalMinutes / 1440
    val h = totalMinutes % 1440 / 60
    val m = totalMinutes % 60
    return listOfNotNull(
        "${d}일".takeIf { d > 0 },
        "${h}시간".takeIf { h > 0 },
        "${m}분".takeIf { m > 0 },
    ).joinToString(" ").ifEmpty { "0분" }
}

fun Color.toCss(): String = "#%06X".format(toArgb() and 0xFFFFFF)

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun MessageBox(message: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            if (actionLabel != null) Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** 7.0 → "7", 1.5 → "1.5" (입력칸·안내문용) */
fun formatDays(days: Double): String =
    if (days == days.toLong().toDouble()) days.toLong().toString() else days.toString()
