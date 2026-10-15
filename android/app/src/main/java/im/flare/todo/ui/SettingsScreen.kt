package im.flare.todo.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flare.todo.AppGraph
import im.flare.todo.BuildConfig
import im.flare.todo.data.ApiException
import im.flare.todo.data.userMessage
import im.flare.todo.download.AttachmentDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.ZoneId

// 웹의 시간대 선택 목록과 같다. 기기 시간대가 목록에 없으면 맨 앞에 더한다.
private val TIMEZONES = listOf(
    "UTC", "Asia/Seoul", "Asia/Tokyo", "Asia/Shanghai", "Asia/Singapore", "Asia/Kolkata",
    "Europe/London", "Europe/Paris", "Europe/Berlin",
    "America/New_York", "America/Chicago", "America/Los_Angeles", "America/Sao_Paulo",
    "Australia/Sydney", "Pacific/Auckland",
)

@Composable
fun SettingsScreen(onBack: () -> Unit, onCategories: () -> Unit) {
    val activity = LocalActivity.current as FragmentActivity
    val scope = rememberCoroutineScope()
    val session by AppGraph.session.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var passwordDialog by remember { mutableStateOf(false) }
    var timezoneDialog by remember { mutableStateOf(false) }
    val biometricAvailable = remember { AppGraph.biometric.isAvailable(activity) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text("계정") },
                supportingContent = { Text("${session.username.orEmpty()}\n${session.serverUrl}") },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("지문으로 로그인") },
                supportingContent = {
                    Text(
                        if (biometricAvailable) "앱을 열 때 비밀번호 대신 지문으로 로그인합니다."
                        else "기기에 등록된 지문이 없습니다. 기기 설정에서 지문을 먼저 등록하세요."
                    )
                },
                trailingContent = {
                    Switch(
                        checked = session.bioEnabled,
                        enabled = biometricAvailable || session.bioEnabled,
                        onCheckedChange = { on ->
                            if (on) passwordDialog = true else attempt { AppGraph.biometric.disable() }
                        },
                    )
                },
            )
            ListItem(
                modifier = Modifier.clickable(onClick = onCategories),
                headlineContent = { Text("상태표시줄에 띄우기") },
                supportingContent = {
                    val count = session.pinnedIds.size
                    Text(if (count == 0) "카테고리별로 설정합니다. 현재 띄운 목록 없음" else "카테고리별로 설정합니다. 현재 ${count}개 목록 표시 중")
                },
            )
            ListItem(
                modifier = Modifier.clickable { timezoneDialog = true },
                headlineContent = { Text("시간대") },
                supportingContent = { Text(session.timezone) },
            )
            ListItem(
                headlineContent = { Text("첨부파일 저장 위치") },
                supportingContent = { Text("내장 저장공간/Download/${AttachmentDownloader.FOLDER}") },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("버전") },
                supportingContent = { Text(BuildConfig.VERSION_NAME) },
            )
        }
    }

    if (passwordDialog) {
        var password by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!busy) passwordDialog = false },
            title = { Text("비밀번호 확인") },
            text = {
                Column {
                    Text("지문 로그인에 쓸 비밀번호를 확인합니다.", Modifier.padding(bottom = 12.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("비밀번호") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = error != null,
                        supportingText = error?.let { { Text(it) } },
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && password.isNotEmpty(), onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            val username = session.username ?: throw ApiException(0, "로그인 정보가 없습니다.")
                            // 맞는 비밀번호인지 서버에 확인한 뒤에 저장한다 (로그인 실패는 세션 만료로 취급하지 않는다).
                            AppGraph.repo.login(username, password)
                            passwordDialog = false
                            if (AppGraph.biometric.enroll(activity, username, password)) {
                                snackbar.showSnackbar("지문 로그인을 켰습니다.")
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            if (passwordDialog) error = e.userMessage() else snackbar.showSnackbar(e.userMessage())
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("확인") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { passwordDialog = false }) { Text("취소") } },
        )
    }

    if (timezoneDialog) {
        val device = remember { ZoneId.systemDefault().id }
        val zones = remember { (listOf(device) + TIMEZONES).distinct() }
        AlertDialog(
            onDismissRequest = { timezoneDialog = false },
            title = { Text("시간대") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    zones.forEach { zone ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                timezoneDialog = false
                                attempt { AppGraph.repo.updateTimezone(zone) }
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = zone == session.timezone, onClick = null, modifier = Modifier.padding(12.dp))
                            Text(if (zone == device) "$zone (기기)" else zone, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { timezoneDialog = false }) { Text("닫기") } },
        )
    }
}
