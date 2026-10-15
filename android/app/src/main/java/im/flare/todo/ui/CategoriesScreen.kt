package im.flare.todo.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flare.todo.AppGraph
import im.flare.todo.data.Category
import im.flare.todo.data.SessionState
import im.flare.todo.data.userMessage
import im.flare.todo.notify.PinnedSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 카테고리 추가·삭제·순서 변경과, 카테고리별 "상태표시줄에 띄우기" 설정 */
@Composable
fun CategoriesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by AppGraph.session.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val revision by AppGraph.repo.revision.collectAsStateWithLifecycle()

    var categories by remember { mutableStateOf<List<Category>?>(null) }
    var newName by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Category?>(null) }
    var notificationsAllowed by remember { mutableStateOf(AppGraph.pinned.hasPermission()) }
    var canPromote by remember { mutableStateOf(AppGraph.pinned.canPromote()) }
    // 알림 권한을 묻는 동안 기억해 둘, 켜려던 카테고리
    var pendingPin by remember { mutableStateOf<Long?>(null) }

    // 시스템 설정에 다녀오면 권한 상태가 바뀌었을 수 있다.
    LifecycleResumeEffect(Unit) {
        notificationsAllowed = AppGraph.pinned.hasPermission()
        canPromote = AppGraph.pinned.canPromote()
        onPauseOrDispose { }
    }

    fun attempt(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                snackbar.showSnackbar(e.userMessage())
            }
        }
    }

    LaunchedEffect(revision) {
        try {
            categories = AppGraph.repo.categories()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (categories == null) categories = emptyList()
            snackbar.showSnackbar(e.userMessage())
        }
    }

    fun setPinned(id: Long, on: Boolean) {
        scope.launch {
            val ids = AppGraph.session.state.value.pinnedIds
            AppGraph.session.setPinned(if (on) ids + id else ids - id)
            PinnedSync.apply(context.applicationContext)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAllowed = granted
        val id = pendingPin
        pendingPin = null
        if (granted && id != null) setPinned(id, true)
        else if (!granted) scope.launch { snackbar.showSnackbar("알림 권한이 있어야 상태표시줄에 띄울 수 있습니다.") }
    }

    fun togglePin(id: Long, on: Boolean) {
        if (on && !notificationsAllowed) {
            pendingPin = id
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            setPinned(id, on)
        }
    }

    fun move(index: Int, delta: Int) {
        val list = categories?.toMutableList() ?: return
        val target = index + delta
        if (target !in list.indices) return
        list.add(target, list.removeAt(index))
        categories = list
        attempt { AppGraph.repo.reorderCategories(list.map { it.id }) }
    }

    fun add() {
        val name = newName.trim()
        if (name.isEmpty()) return
        attempt {
            AppGraph.repo.addCategory(name)
            newName = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("카테고리 · 상태표시줄") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = categories
        if (list == null) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "스위치를 켠 카테고리는 미완료 할 일이 있는 동안 상태표시줄에 계속 표시됩니다. " +
                            "알림을 누르면 해당 카테고리 목록이 열립니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                ListItem(
                    headlineContent = { Text("라이브 업데이트로 강조") },
                    supportingContent = {
                        Column {
                            Text("상태표시줄 칩과 Now Bar에 건수를 표시합니다 (Android 16).")
                            if (Build.VERSION.SDK_INT >= 36 && session.promoted && !canPromote) {
                                TextButton(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                            )
                                        }
                                    },
                                    contentPadding = PaddingValues(0.dp),
                                ) { Text("시스템 설정에서 라이브 알림 허용하기") }
                            }
                        }
                    },
                    trailingContent = {
                        Switch(checked = session.promoted, onCheckedChange = { on ->
                            scope.launch {
                                AppGraph.session.setPromoted(on)
                                PinnedSync.apply(context.applicationContext)
                            }
                        })
                    },
                )
                HorizontalDivider()
            }
            item {
                ListItem(
                    headlineContent = { Text("전체 미완료") },
                    supportingContent = { Text("카테고리와 관계없이 모든 미완료 할 일") },
                    trailingContent = {
                        Switch(
                            checked = SessionState.PIN_ALL in session.pinnedIds,
                            onCheckedChange = { togglePin(SessionState.PIN_ALL, it) },
                            modifier = Modifier.semantics { contentDescription = "전체 미완료 상태표시줄에 띄우기" },
                        )
                    },
                )
            }
            itemsIndexed(list, key = { _, c -> c.id }) { index, c ->
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(c.name) },
                    supportingContent = { Text("미완료 ${c.todoCount}건") },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = c.id in session.pinnedIds,
                                onCheckedChange = { togglePin(c.id, it) },
                                modifier = Modifier.semantics { contentDescription = "${c.name} 상태표시줄에 띄우기" },
                            )
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "${c.name} 더보기") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("위로") },
                                        leadingIcon = { Icon(Icons.Default.ArrowUpward, null) },
                                        enabled = index > 0,
                                        onClick = { menu = false; move(index, -1) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("아래로") },
                                        leadingIcon = { Icon(Icons.Default.ArrowDownward, null) },
                                        enabled = index < list.lastIndex,
                                        onClick = { menu = false; move(index, 1) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("삭제") },
                                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                                        onClick = { menu = false; deleting = c },
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.animateItem(),
                )
            }
            item {
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { if (it.length <= 50) newName = it },
                        label = { Text("새 카테고리") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { add() }),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { add() }, enabled = newName.isNotBlank()) { Icon(Icons.Default.Add, "추가") }
                }
            }
        }
    }

    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("카테고리 삭제") },
            text = { Text("'${c.name}' 카테고리를 삭제하면 해당 할 일의 카테고리가 해제됩니다. 삭제하시겠습니까?") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    attempt {
                        AppGraph.repo.deleteCategory(c.id)
                        if (c.id in session.pinnedIds) setPinned(c.id, false)
                    }
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } },
        )
    }
}
