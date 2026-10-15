package im.flare.todo.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flare.todo.AppGraph
import im.flare.todo.data.Attachment
import im.flare.todo.data.ShareCreated
import im.flare.todo.data.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("공유 링크", text))
}

fun shareText(context: Context, text: String) {
    context.startActivity(
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
    )
}

/** 첨부 한 줄. 보기 화면은 저장·공유, 편집 화면은 제거 버튼을 단다. */
@Composable
fun AttachmentRow(
    attachment: Attachment,
    onDownload: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Icon(if (attachment.isFile) Icons.AutoMirrored.Filled.InsertDriveFile else Icons.Default.Image, null)
        },
        headlineContent = { Text(attachment.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(formatSize(attachment.size) + if (attachment.isFile) "" else " · 본문 이미지")
        },
        trailingContent = {
            Row {
                if (onDownload != null) IconButton(onClick = onDownload) { Icon(Icons.Default.Download, "저장") }
                if (onShare != null) IconButton(onClick = onShare) { Icon(Icons.Default.Share, "외부 공유 링크") }
                if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "첨부 제거") }
            }
        },
    )
}

/**
 * 외부 공유 링크 만들기: 유효기간(일, 소수 가능)을 정하면 로그인 없이 받을 수 있는 링크가 나온다.
 * "S3 직접 링크"를 켜면 앱을 거치지 않는 S3 서명 URL을 바로 만든다 (최대 7일, 취소 불가, 기본값은 서버 설정).
 */
@Composable
fun ShareLinkDialog(attachment: Attachment, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val timezone = AppGraph.session.state.collectAsStateWithLifecycle().value.timezone
    var daysText by remember { mutableStateOf("7") }
    var direct by remember { mutableStateOf(false) }
    var presignedDays by remember { mutableStateOf(7.0) }
    var presignedMaxDays by remember { mutableStateOf(7.0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var created by remember { mutableStateOf<ShareCreated?>(null) }
    val days = daysText.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }
    val tooLong = direct && days != null && days > presignedMaxDays

    // S3 직접 링크 기본 유효기간은 서버 DB 설정에서 온다
    LaunchedEffect(Unit) {
        runCatching { AppGraph.repo.attachmentConfig() }.onSuccess {
            presignedDays = it.sharePresignedDays
            presignedMaxDays = it.sharePresignedMaxDays
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("외부 공유 링크") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(attachment.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val link = created
                if (link == null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) {
                            direct = !direct
                            daysText = if (direct) formatDays(presignedDays) else "7"
                        },
                    ) {
                        Checkbox(
                            checked = direct,
                            enabled = !busy,
                            onCheckedChange = {
                                direct = it
                                daysText = if (it) formatDays(presignedDays) else "7"
                            },
                        )
                        Column {
                            Text("S3 직접 링크", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "앱을 거치지 않는 S3 주소. 최대 ${formatDays(presignedMaxDays)}일, 취소 불가",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = daysText,
                        onValueChange = { daysText = it },
                        label = { Text("유효기간 (일)") },
                        isError = tooLong,
                        supportingText = {
                            Text(
                                when {
                                    days == null -> "0보다 큰 값을 입력하세요. 예: 1.5 = 1일 12시간"
                                    tooLong -> "S3 직접 링크는 최대 ${formatDays(presignedMaxDays)}일까지"
                                    else -> "${describeDays(days)} 후 만료"
                                }
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    SelectionContainer { Text(link.url, style = MaterialTheme.typography.bodySmall) }
                    Text(
                        "${formatInstant(link.expiresAt, timezone)}까지 누구나 이 링크로 받을 수 있습니다." +
                            if (link.direct) " 이 S3 링크는 취소할 수 없고 공유 링크 목록에도 나오지 않습니다." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            val link = created
            if (link == null) {
                TextButton(
                    enabled = days != null && !tooLong && !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            try {
                                created = AppGraph.repo.share(attachment.id, days!!, direct)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                error = e.userMessage()
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("링크 만들기") }
            } else {
                Row {
                    TextButton(onClick = { copyToClipboard(context, link.url) }) { Text("복사") }
                    TextButton(onClick = { shareText(context, link.url) }) { Text("보내기") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}
