package im.flare.todo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import im.flare.todo.AppGraph
import im.flare.todo.data.Category
import im.flare.todo.data.ListFilter
import im.flare.todo.data.SessionState
import im.flare.todo.data.TodoSummary
import im.flare.todo.data.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ListViewModel : ViewModel() {
    private val repo = AppGraph.repo
    private val session = AppGraph.session
    private val json = AppGraph.api.json

    var filter by mutableStateOf(loadFilter())
        private set
    var todos by mutableStateOf<List<TodoSummary>>(emptyList())
        private set
    var categories by mutableStateOf<List<Category>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    private var job: Job? = null

    init {
        // 처음 한 번, 그리고 다른 화면에서 데이터가 바뀔 때마다 다시 불러온다.
        viewModelScope.launch { repo.revision.collect { reload() } }
    }

    private fun loadFilter(): ListFilter =
        session.state.value.listFilter
            ?.let { runCatching { json.decodeFromString(ListFilter.serializer(), it) }.getOrNull() }
            ?: ListFilter()

    fun updateFilter(value: ListFilter) {
        if (value == filter) return
        filter = value
        viewModelScope.launch { session.setListFilter(json.encodeToString(ListFilter.serializer(), value)) }
        reload()
    }

    fun reload() {
        job?.cancel()
        job = viewModelScope.launch {
            loading = true
            try {
                coroutineScope {
                    val cats = async { repo.categories() }
                    val list = async { repo.todos(filter) }
                    categories = cats.await()
                    todos = list.await()
                }
                error = null
                loaded = true
                // 삭제된 카테고리는 검색 조건에서 뺀다.
                val valid = filter.categoryIds.filter { id -> categories.any { it.id == id } }
                if (valid.size != filter.categoryIds.size) updateFilter(filter.copy(categoryIds = valid))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.userMessage()
            } finally {
                loading = false
            }
        }
    }

    fun toggle(todo: TodoSummary) {
        todos = todos.map { if (it.id == todo.id) it.copy(completed = !it.completed) else it }
        viewModelScope.launch {
            try {
                repo.toggle(todo.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.userMessage()
                reload()
            }
        }
    }
}

private val STATUS_TABS = listOf("all" to "전체", "active" to "미완료", "completed" to "완료")
private val SORTS = listOf(
    "createdAt_desc" to "등록일 최신순",
    "createdAt_asc" to "등록일 오래된순",
    "completedAt_desc" to "완료일 최신순",
    "completedAt_asc" to "완료일 오래된순",
)

@Composable
fun TodoListScreen(
    onOpen: (Long) -> Unit,
    onNew: (List<Long>) -> Unit,
    onCategories: () -> Unit,
    onShares: () -> Unit,
    onSettings: () -> Unit,
    onLoggedOut: () -> Unit,
    vm: ListViewModel = viewModel { ListViewModel() },
) {
    val scope = rememberCoroutineScope()
    val session by AppGraph.session.state.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }
    var sortMenu by remember { mutableStateOf(false) }
    var dateDialog by remember { mutableStateOf(false) }
    val filter = vm.filter

    // 상태표시줄 알림을 눌러 들어온 경우: 그 카테고리의 미완료 목록으로 맞춘다.
    val request by AppGraph.launchRequest.collectAsState()
    LaunchedEffect(request) {
        val r = request ?: return@LaunchedEffect
        AppGraph.launchRequest.value = null
        r.todoId?.let { onOpen(it); return@LaunchedEffect }
        val ids = if (r.categoryId == SessionState.PIN_ALL) emptyList() else listOf(r.categoryId)
        vm.updateFilter(ListFilter(status = "active", categoryIds = ids, sort = filter.sort))
        if (r.createNew) onNew(ids)
    }

    LaunchedEffect(vm.error) {
        vm.error?.let {
            if (vm.loaded) {
                snackbar.showSnackbar(it)
                vm.error = null
            }
        }
    }

    // 서랍이 열려 있을 때의 뒤로 가기는 앱을 나가지 않고 서랍만 닫는다.
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "${session.username.orEmpty()}님",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
                    )
                    Text(
                        "카테고리",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                    )
                    val itemPadding = Modifier.padding(horizontal = 12.dp)
                    NavigationDrawerItem(
                        label = { Text("전체") },
                        selected = filter.categoryIds.isEmpty(),
                        onClick = { vm.updateFilter(filter.copy(categoryIds = emptyList())) },
                        modifier = itemPadding,
                    )
                    vm.categories.forEach { c ->
                        val checked = c.id in filter.categoryIds
                        NavigationDrawerItem(
                            icon = { Checkbox(checked = checked, onCheckedChange = null) },
                            label = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            badge = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (c.id in session.pinnedIds) {
                                        Icon(Icons.Default.PushPin, "상태표시줄에 고정됨", Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text(c.todoCount.toString())
                                }
                            },
                            selected = checked,
                            onClick = {
                                val ids = if (checked) filter.categoryIds - c.id else filter.categoryIds + c.id
                                vm.updateFilter(filter.copy(categoryIds = ids))
                            },
                            modifier = itemPadding,
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    fun go(action: () -> Unit) {
                        scope.launch { drawer.close() }
                        action()
                    }
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Tune, null) },
                        label = { Text("카테고리 · 상태표시줄 설정") },
                        selected = false, onClick = { go(onCategories) }, modifier = itemPadding,
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Share, null) },
                        label = { Text("공유 링크") },
                        selected = false, onClick = { go(onShares) }, modifier = itemPadding,
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Settings, null) },
                        label = { Text("설정") },
                        selected = false, onClick = { go(onSettings) }, modifier = itemPadding,
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                        label = { Text("로그아웃") },
                        selected = false,
                        onClick = {
                            scope.launch {
                                AppGraph.repo.logout()
                                onLoggedOut()
                            }
                        },
                        modifier = itemPadding,
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Flare Todo") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "메뉴") }
                    },
                    actions = {
                        IconButton(onClick = { dateDialog = true }) {
                            BadgedBox(badge = { if (filter.hasDates) Badge() }) { Icon(Icons.Default.DateRange, "기간 검색") }
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "정렬") }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                SORTS.forEach { (value, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        leadingIcon = { if (filter.sort == value) Icon(Icons.Default.Check, null) },
                                        onClick = {
                                            sortMenu = false
                                            vm.updateFilter(filter.copy(sort = value))
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { onNew(filter.categoryIds) }) { Icon(Icons.Default.Add, "새 할 일") }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    STATUS_TABS.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = filter.status == value,
                            onClick = { vm.updateFilter(filter.copy(status = value)) },
                            shape = SegmentedButtonDefaults.itemShape(index, STATUS_TABS.size),
                        ) { Text(label) }
                    }
                }

                val selected = vm.categories.filter { it.id in filter.categoryIds }
                if (selected.isNotEmpty()) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        selected.forEach { c ->
                            InputChip(
                                selected = true,
                                onClick = { vm.updateFilter(filter.copy(categoryIds = filter.categoryIds - c.id)) },
                                label = { Text(c.name) },
                                trailingIcon = { Icon(Icons.Default.Close, "필터 해제", Modifier.size(16.dp)) },
                            )
                        }
                    }
                }

                Text(
                    "총 ${vm.todos.size}건",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )

                PullToRefreshBox(isRefreshing = vm.loading, onRefresh = vm::reload, modifier = Modifier.weight(1f)) {
                    when {
                        !vm.loaded && vm.error != null -> MessageBox(vm.error!!, actionLabel = "다시 시도", onAction = vm::reload)
                        vm.loaded && vm.todos.isEmpty() ->
                            // 비어 있어도 당겨서 새로고침이 되도록 스크롤 가능한 영역으로 둔다.
                            MessageBox("할 일이 없습니다.", Modifier.verticalScroll(rememberScrollState()))

                        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                            items(vm.todos, key = { it.id }) { todo ->
                                TodoRow(todo, session.timezone, onToggle = { vm.toggle(todo) }, onClick = { onOpen(todo.id) })
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                            }
                        }
                    }
                }
            }
        }
    }

    if (dateDialog) {
        DateFilterDialog(
            filter = filter,
            onApply = {
                dateDialog = false
                vm.updateFilter(it)
            },
            onDismiss = { dateDialog = false },
        )
    }
}

@Composable
private fun TodoRow(todo: TodoSummary, timezone: String, onToggle: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = todo.completed, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f).padding(top = 12.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                todo.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (todo.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (todo.completed) TextDecoration.LineThrough else null,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                todo.categories.forEach { CategoryTag(it.name) }
                Text(
                    buildString {
                        append(formatInstant(todo.createdAt, timezone))
                        if (todo.completedAt != null) append("  ✓ ${formatInstant(todo.completedAt, timezone)}")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun CategoryTag(name: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.AutoMirrored.Filled.Label, null,
            Modifier.size(14.dp).padding(end = 2.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun DateFilterDialog(filter: ListFilter, onApply: (ListFilter) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(filter) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("기간 검색") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("등록일", style = MaterialTheme.typography.labelLarge)
                DateRangeRow(draft.createdFrom, draft.createdTo) { from, to -> draft = draft.copy(createdFrom = from, createdTo = to) }
                Text("완료일", style = MaterialTheme.typography.labelLarge)
                DateRangeRow(draft.completedFrom, draft.completedTo) { from, to -> draft = draft.copy(completedFrom = from, completedTo = to) }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(draft) }) { Text("검색") } },
        dismissButton = {
            TextButton(onClick = {
                onApply(draft.copy(createdFrom = null, createdTo = null, completedFrom = null, completedTo = null))
            }) { Text("초기화") }
        },
    )
}

@Composable
private fun DateRangeRow(from: String?, to: String?, onChange: (String?, String?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DateField(from, Modifier.weight(1f)) { onChange(it, to) }
        Text("~")
        DateField(to, Modifier.weight(1f)) { onChange(from, it) }
    }
}

/** yyyy-MM-dd 날짜 하나를 고르는 버튼. 비우려면 달력에서 "지우기". */
@Composable
private fun DateField(value: String?, modifier: Modifier = Modifier, onChange: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text(value ?: "선택", maxLines = 1)
    }
    if (open) {
        // DatePicker 는 날짜를 UTC 자정의 epoch millis 로 다룬다.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = value?.let {
                runCatching { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    open = false
                    onChange(state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() })
                }) { Text("확인") }
            },
            dismissButton = {
                TextButton(onClick = {
                    open = false
                    onChange(null)
                }) { Text("지우기") }
            },
        ) { DatePicker(state) }
    }
}
