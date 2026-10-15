package im.flare.todo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flare.todo.AppGraph
import im.flare.todo.data.ShareLink
import im.flare.todo.data.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** 내가 만든 유효한 외부 공유 링크 목록. 링크 복사·보내기·공유 취소. */
@Composable
fun ShareLinksScreen(onBack: () -> Unit, onOpenTodo: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by AppGraph.session.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var links by remember { mutableStateOf<List<ShareLink>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var revoking by remember { mutableStateOf<ShareLink?>(null) }

    LaunchedEffect(reloadKey) {
        try {
            links = AppGraph.repo.shareLinks()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.userMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("공유 링크") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = links
        when {
            list == null && error != null -> MessageBox(error!!, Modifier.padding(padding), "다시 시도") { reloadKey++ }
            list == null -> LoadingBox(Modifier.padding(padding))
            list.isEmpty() -> MessageBox("유효한 공유 링크가 없습니다.\n첨부파일의 공유 버튼으로 만들 수 있습니다.", Modifier.padding(padding))
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(list, key = { it.id }) { link ->
                    val remaining = runCatching {
                        Duration.between(Instant.now(), Instant.parse(link.expiresAt)).toMinutes().coerceAtLeast(0) / 1440.0
                    }.getOrDefault(0.0)
                    ListItem(
                        modifier = if (link.todoId != null) Modifier.clickable { onOpenTodo(link.todoId) } else Modifier,
                        headlineContent = { Text(link.attachmentName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                (link.todoTitle ?: "본문 이미지") +
                                    "\n${formatInstant(link.expiresAt, session.timezone)} 만료 (${describeDays(remaining)} 남음)"
                            )
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = {
                                    copyToClipboard(context, link.url)
                                }) { Icon(Icons.Default.ContentCopy, "링크 복사") }
                                IconButton(onClick = { shareText(context, link.url) }) { Icon(Icons.Default.Share, "보내기") }
                                IconButton(onClick = { revoking = link }) {
                                    Icon(Icons.Default.LinkOff, "공유 취소", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    revoking?.let { link ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text("공유 취소") },
            text = { Text("'${link.attachmentName}' 공유를 취소하시겠습니까? 이 링크로는 더 이상 받을 수 없게 됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    revoking = null
                    scope.launch {
                        try {
                            AppGraph.repo.revokeShare(link.id)
                            reloadKey++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            snackbar.showSnackbar(e.userMessage())
                        }
                    }
                }) { Text("공유 취소") }
            },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text("닫기") } },
        )
    }
}
