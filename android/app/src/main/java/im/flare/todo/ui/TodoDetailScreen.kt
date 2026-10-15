package im.flare.todo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import im.flare.todo.AppGraph
import im.flare.todo.data.Attachment
import im.flare.todo.data.Todo
import im.flare.todo.data.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class DetailViewModel(private val id: Long) : ViewModel() {
    private val repo = AppGraph.repo

    var todo by mutableStateOf<Todo?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var deleted = false

    init {
        viewModelScope.launch { repo.revision.collect { load() } }
    }

    private suspend fun load() {
        if (deleted) return
        try {
            // 본문 이미지가 첨부 CDN 쿠키를 필요로 하므로 본문을 그리기 전에 받아 둔다.
            runCatching { repo.ensureCdnSession() }.onFailure { if (it is CancellationException) throw it }
            todo = repo.todo(id)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.userMessage()
        }
    }

    fun retry() {
        viewModelScope.launch { load() }
    }

    fun toggle(onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                todo = repo.toggle(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onError(e.userMessage())
            }
        }
    }

    fun delete(onDone: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                deleted = true
                repo.deleteTodo(id)
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                deleted = false
                onError(e.userMessage())
            }
        }
    }
}

@Composable
fun TodoDetailScreen(
    id: Long,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    vm: DetailViewModel = viewModel { DetailViewModel(id) },
) {
    val scope = rememberCoroutineScope()
    val session by AppGraph.session.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    var showAttachments by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf<Attachment?>(null) }
    val todo = vm.todo

    fun toast(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun download(attachment: Attachment) {
        scope.launch {
            try {
                val path = AppGraph.downloader.download(attachment)
                snackbar.showSnackbar("다운로드를 시작했습니다 → $path")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                snackbar.showSnackbar(e.userMessage())
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("할 일") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                actions = {
                    if (todo != null) {
                        IconButton(onClick = { vm.toggle(::toast) }) {
                            if (todo.completed) Icon(Icons.Default.CheckCircle, "미완료로 되돌리기", tint = MaterialTheme.colorScheme.primary)
                            else Icon(Icons.Default.RadioButtonUnchecked, "완료 처리")
                        }
                        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "수정") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "삭제") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            todo == null && vm.error != null -> MessageBox(vm.error!!, Modifier.padding(padding), "다시 시도", vm::retry)
            todo == null -> LoadingBox(Modifier.padding(padding))
            else -> Column(Modifier.padding(padding).fillMaxSize()) {
                Column(
                    Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        todo.title,
                        style = MaterialTheme.typography.headlineSmall,
                        textDecoration = if (todo.completed) TextDecoration.LineThrough else null,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        todo.categories.forEach { CategoryTag(it.name) }
                        Text(
                            if (todo.completed) "완료" else "미완료",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (todo.completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Text(
                        buildString {
                            append("등록 ${formatInstant(todo.createdAt, session.timezone)}")
                            append("  ·  수정 ${formatInstant(todo.updatedAt, session.timezone)}")
                            if (todo.completedAt != null) append("\n완료 ${formatInstant(todo.completedAt, session.timezone)}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()

                if (todo.body.isNullOrBlank()) {
                    MessageBox("본문이 없습니다.", Modifier.weight(1f))
                } else {
                    HtmlView(todo.body, session.serverUrl, Modifier.weight(1f).fillMaxWidth())
                }

                if (todo.attachments.isNotEmpty()) {
                    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().clickable { showAttachments = true }) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.AttachFile, null, Modifier.size(20.dp))
                            Text("첨부파일 ${todo.attachments.size}개", Modifier.padding(start = 8.dp).weight(1f))
                            Text("보기", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }

    if (showAttachments && todo != null) {
        ModalBottomSheet(onDismissRequest = { showAttachments = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                Text(
                    "첨부파일",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                todo.attachments.forEach { a ->
                    AttachmentRow(a, onDownload = { download(a) }, onShare = { sharing = a })
                }
                Text(
                    "저장 위치: 내장 저장공간/Download/flare-todo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }

    sharing?.let { ShareLinkDialog(it) { sharing = null } }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("삭제") },
            text = { Text("이 할 일을 삭제하시겠습니까?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(onDone = onBack, onError = ::toast)
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}
