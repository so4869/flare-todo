package im.flare.todo.ui

import android.net.Uri
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import im.flare.todo.AppGraph
import im.flare.todo.data.Attachment
import im.flare.todo.data.Category
import im.flare.todo.data.TodoRequest
import im.flare.todo.data.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class EditViewModel(private val id: Long?, initialCategoryIds: List<Long>) : ViewModel() {
    private val repo = AppGraph.repo

    val isNew = id == null
    var ready by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var categories by mutableStateOf<List<Category>>(emptyList())
        private set

    var title by mutableStateOf("")
    var categoryIds by mutableStateOf(initialCategoryIds.toSet())
    var completed by mutableStateOf(false)
    var initialBody = ""
        private set

    /** 할 일에 연결될 첨부 파일 (본문 이미지는 본문에서 관리하므로 여기 없다) */
    var files by mutableStateOf<List<Attachment>>(emptyList())
        private set

    /** 올리는 중인 파일 이름 → 진행률 */
    var uploads by mutableStateOf<Map<String, Float>>(emptyMap())
        private set
    var saving by mutableStateOf(false)
        private set

    /** 제목·카테고리·완료·첨부가 바뀌었는지 (본문은 편집기가 따로 추적한다) */
    var fieldsDirty by mutableStateOf(false)

    private val saveLock = Mutex()
    private var savedId = id

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            loadError = null
            try {
                coroutineScope {
                    val cats = async { repo.categories() }
                    val todo = id?.let { async { repo.todo(it) } }
                    runCatching { repo.ensureCdnSession() }.onFailure { if (it is CancellationException) throw it }
                    categories = cats.await()
                    todo?.await()?.let { t ->
                        title = t.title
                        categoryIds = t.categories.map { it.id }.toSet()
                        completed = t.completed
                        initialBody = t.body.orEmpty()
                        files = t.attachments.filter { it.isFile }
                    }
                }
                ready = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                loadError = e.userMessage()
            }
        }
    }

    fun upload(uris: List<Uri>, onError: (String) -> Unit) {
        uris.forEach { uri ->
            val key = uri.toString()
            viewModelScope.launch {
                uploads = uploads + (key to 0f)
                try {
                    val attachment = repo.uploadFile(uri) { p -> uploads = uploads + (key to p) }
                    files = files + attachment
                    fieldsDirty = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    onError(e.userMessage())
                } finally {
                    uploads = uploads - key
                }
            }
        }
    }

    fun removeFile(attachment: Attachment) {
        files = files - attachment
        fieldsDirty = true
    }

    /** @return 저장했으면 true. 새 할 일은 처음 저장한 뒤부터 같은 할 일을 고친다. */
    suspend fun save(body: String): Boolean = saveLock.withLock {
        val request = TodoRequest(
            title = title.trim(),
            body = body,
            categoryIds = categoryIds.toList(),
            completed = completed,
            attachmentIds = files.map { it.id },
        )
        saving = true
        try {
            val saved = savedId?.let { repo.updateTodo(it, request) } ?: repo.createTodo(request)
            savedId = saved.id
            fieldsDirty = false
            true
        } finally {
            saving = false
        }
    }

    val canAutoSave get() = savedId != null && title.isNotBlank() && uploads.isEmpty()
}

private const val AUTO_SAVE_INTERVAL_MS = 60_000L

@Composable
fun TodoEditScreen(
    id: Long?,
    initialCategoryIds: List<Long>,
    onDone: () -> Unit,
    vm: EditViewModel = viewModel { EditViewModel(id, initialCategoryIds) },
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val serverUrl = remember { AppGraph.session.state.value.serverUrl }
    var editor by remember { mutableStateOf<WebView?>(null) }
    var editorReady by remember { mutableStateOf(false) }
    var showFiles by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    fun toast(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    suspend fun bodyDirty(): Boolean = editorReady && editor?.evalString("isDirty()") == "1"

    suspend fun save(auto: Boolean): Boolean {
        val view = editor
        if (view == null || !editorReady) return false
        if (vm.title.isBlank()) {
            if (!auto) snackbar.showSnackbar("제목을 입력하세요.")
            return false
        }
        if (vm.uploads.isNotEmpty()) {
            if (!auto) snackbar.showSnackbar("파일을 올리는 중입니다. 잠시 후 다시 저장하세요.")
            return false
        }
        return try {
            val html = view.evalString("getHtml()").orEmpty()
            vm.save(html)
            view.evaluateJavascript("clearDirty()", null)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!auto) snackbar.showSnackbar(e.userMessage())
            false
        }
    }

    fun leave() {
        scope.launch {
            if (vm.fieldsDirty || bodyDirty()) confirmDiscard = true else onDone()
        }
    }

    BackHandler(onBack = ::leave)

    // 웹과 같이, 이미 저장된 할 일을 고치는 동안에는 1분마다 자동 저장한다.
    LaunchedEffect(editorReady) {
        if (!editorReady) return@LaunchedEffect
        while (true) {
            delay(AUTO_SAVE_INTERVAL_MS)
            if (vm.canAutoSave && !vm.saving && (vm.fieldsDirty || bodyDirty()) && save(auto = true)) {
                snackbar.showSnackbar("자동 저장됨")
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.upload(uris, ::toast)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            try {
                val image = AppGraph.repo.uploadImage(uri)
                editor?.evaluateJavascript("insertImage(${Json.encodeToString(image.url)})", null)
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
                title = { Text(if (vm.isNew) "새 할 일" else "할 일 수정") },
                navigationIcon = { IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                actions = {
                    IconButton(onClick = { showFiles = true }, enabled = vm.ready) {
                        BadgedBox(badge = {
                            val count = vm.files.size + vm.uploads.size
                            if (count > 0) Badge { Text(count.toString()) }
                        }) { Icon(Icons.Default.AttachFile, "첨부파일") }
                    }
                    TextButton(
                        enabled = vm.ready && editorReady && !vm.saving,
                        onClick = { scope.launch { if (save(auto = false)) onDone() } },
                    ) { Text(if (vm.saving) "저장 중…" else "저장") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            vm.loadError != null -> MessageBox(vm.loadError!!, Modifier.padding(padding), "다시 시도", vm::load)
            !vm.ready -> LoadingBox(Modifier.padding(padding))
            else -> Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().imePadding()) {
                OutlinedTextField(
                    value = vm.title,
                    onValueChange = {
                        if (it.length <= 200) {
                            vm.title = it
                            vm.fieldsDirty = true
                        }
                    },
                    label = { Text("제목") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = vm.completed, onCheckedChange = {
                            vm.completed = it
                            vm.fieldsDirty = true
                        })
                        Text("완료", Modifier.padding(end = 8.dp))
                    }
                    vm.categories.forEach { c ->
                        val selected = c.id in vm.categoryIds
                        FilterChip(
                            selected = selected,
                            onClick = {
                                vm.categoryIds = if (selected) vm.categoryIds - c.id else vm.categoryIds + c.id
                                vm.fieldsDirty = true
                            },
                            label = { Text(c.name) },
                        )
                    }
                }
                HorizontalDivider()

                val colors = htmlColors()
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { context ->
                        createEditorWebView(context, serverUrl, colors, vm.initialBody) { editorReady = true }.also { editor = it }
                    },
                    onRelease = {
                        editor = null
                        it.destroy()
                    },
                )

                EditorToolbar(
                    onCommand = { name -> editor?.editorCommand(name) },
                    onHeading = { editor?.evaluateJavascript("toggleHeading()", null) },
                    onLink = { linkDialog = true },
                    onImage = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
            }
        }
    }

    if (showFiles) {
        ModalBottomSheet(onDismissRequest = { showFiles = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                Text(
                    "첨부파일",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (vm.files.isEmpty() && vm.uploads.isEmpty()) {
                    Text(
                        "첨부된 파일이 없습니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                vm.files.forEach { a -> AttachmentRow(a, onRemove = { vm.removeFile(a) }) }
                vm.uploads.forEach { (_, progress) ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text("올리는 중… ${(progress * 100).toInt()}%") },
                        supportingContent = { LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) },
                    )
                }
                OutlinedButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Default.AttachFile, null, Modifier.padding(end = 8.dp))
                    Text("파일 추가")
                }
                Text(
                    "추가·제거한 첨부는 할 일을 저장해야 반영됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }

    if (linkDialog) {
        var url by remember { mutableStateOf("https://") }
        AlertDialog(
            onDismissRequest = { linkDialog = false },
            title = { Text("링크") },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("주소") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    linkDialog = false
                    if (url.isNotBlank()) editor?.evaluateJavascript("insertLink(${Json.encodeToString(url.trim())})", null)
                }) { Text("넣기") }
            },
            dismissButton = { TextButton(onClick = { linkDialog = false }) { Text("취소") } },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("저장하지 않고 나갈까요?") },
            text = { Text("바꾼 내용이 저장되지 않았습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDone()
                }) { Text("나가기") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("계속 편집") } },
        )
    }
}

@Composable
private fun EditorToolbar(onCommand: (String) -> Unit, onHeading: () -> Unit, onLink: () -> Unit, onImage: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            @Composable
            fun Tool(icon: ImageVector, label: String, onClick: () -> Unit) {
                IconButton(onClick = onClick) { Icon(icon, label) }
            }
            Tool(Icons.Default.FormatBold, "굵게") { onCommand("bold") }
            Tool(Icons.Default.FormatItalic, "기울임") { onCommand("italic") }
            Tool(Icons.Default.FormatUnderlined, "밑줄") { onCommand("underline") }
            Tool(Icons.Default.FormatStrikethrough, "취소선") { onCommand("strikeThrough") }
            Tool(Icons.Default.Title, "제목", onHeading)
            Tool(Icons.AutoMirrored.Filled.FormatListBulleted, "글머리 기호") { onCommand("insertUnorderedList") }
            Tool(Icons.Default.FormatListNumbered, "번호 목록") { onCommand("insertOrderedList") }
            Tool(Icons.Default.Link, "링크", onLink)
            Tool(Icons.Default.Image, "이미지", onImage)
            Tool(Icons.Default.FormatClear, "서식 지우기") { onCommand("removeFormat") }
            Tool(Icons.AutoMirrored.Filled.Undo, "실행 취소") { onCommand("undo") }
            Tool(Icons.AutoMirrored.Filled.Redo, "다시 실행") { onCommand("redo") }
        }
    }
}
