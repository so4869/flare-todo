package im.flare.todo.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flare.todo.AppGraph
import im.flare.todo.data.ApiException
import im.flare.todo.data.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

/** "todo.flare.im" 처럼 적어도 되게 스킴을 붙이고 끝의 / 를 뗀다. */
private fun normalizeServerUrl(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    return if (trimmed.contains("://")) trimmed else "https://$trimmed"
}

@Composable
fun LoginScreen(onLoggedIn: () -> Unit, onRegister: () -> Unit) {
    val activity = LocalActivity.current as FragmentActivity
    val scope = rememberCoroutineScope()
    val session by AppGraph.session.state.collectAsStateWithLifecycle()

    var server by rememberSaveable { mutableStateOf(session.serverUrl) }
    var username by rememberSaveable { mutableStateOf(session.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf("") }
    var showServer by rememberSaveable { mutableStateOf(false) }
    var autoPrompted by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 비밀번호 로그인 직후 지문 등록을 권할 때의 (아이디, 비밀번호)
    var offer by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun finish() {
        AppGraph.onLoggedIn()
        onLoggedIn()
    }

    fun attempt(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.userMessage()
            } finally {
                busy = false
            }
        }
    }

    fun passwordLogin() = attempt {
        if (username.isBlank() || password.isEmpty()) throw ApiException(0, "아이디와 비밀번호를 입력하세요.")
        AppGraph.session.setServerUrl(normalizeServerUrl(server))
        AppGraph.repo.login(username.trim(), password)
        val state = AppGraph.session.state.value
        if (AppGraph.biometric.isAvailable(activity) && !state.bioEnabled && !state.bioOfferDeclined) {
            offer = username.trim() to password
        } else {
            finish()
        }
    }

    fun biometricLogin() = attempt {
        val (user, pass) = AppGraph.biometric.unlock(activity) ?: return@attempt
        try {
            AppGraph.repo.login(user, pass)
        } catch (e: ApiException) {
            if (e.status != 401) throw e
            AppGraph.biometric.disable()
            throw ApiException(401, "저장된 비밀번호가 더 이상 맞지 않습니다. 비밀번호로 로그인하세요.")
        } catch (e: IOException) {
            // 오프라인이어도 아직 유효할 수 있는 기존 토큰이 있으면 들여보낸다.
            if (AppGraph.session.state.value.token == null) throw e
        }
        finish()
    }

    // 지문 로그인을 켜 두었으면 앱을 열자마자 지문을 묻는다.
    LaunchedEffect(Unit) {
        if (session.bioEnabled && !autoPrompted) {
            autoPrompted = true
            biometricLogin()
        }
    }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Icon(Icons.Default.CheckCircle, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Flare Todo", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))

            Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("아이디") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("비밀번호") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { passwordLogin() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

                Button(onClick = { passwordLogin() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "로그인 중…" else "로그인")
                }
                if (session.bioEnabled) {
                    FilledTonalButton(onClick = { biometricLogin() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Fingerprint, null, Modifier.padding(end = 8.dp))
                        Text("지문으로 로그인")
                    }
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            AppGraph.session.setServerUrl(normalizeServerUrl(server))
                            onRegister()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("회원가입") }

                TextButton(onClick = { showServer = !showServer }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("서버 설정", style = MaterialTheme.typography.labelMedium)
                }
                AnimatedVisibility(showServer) {
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { Text("서버 주소") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    offer?.let { (user, pass) ->
        AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Default.Fingerprint, null) },
            title = { Text("지문으로 로그인") },
            text = { Text("다음부터 비밀번호 대신 지문으로 로그인할까요? 설정에서 언제든 바꿀 수 있습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    offer = null
                    scope.launch {
                        try {
                            AppGraph.biometric.enroll(activity, user, pass)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // 등록에 실패해도 로그인은 이미 끝났다. 설정에서 다시 켤 수 있다.
                        }
                        finish()
                    }
                }) { Text("사용") }
            },
            dismissButton = {
                TextButton(onClick = {
                    offer = null
                    scope.launch {
                        AppGraph.session.setBioOfferDeclined()
                        finish()
                    }
                }) { Text("나중에") }
            },
        )
    }
}

@Composable
fun RegisterScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val session by AppGraph.session.state.collectAsStateWithLifecycle()
    var username by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    fun submit() {
        error = when {
            username.isBlank() || email.isBlank() || password.isEmpty() -> "모든 항목을 입력하세요."
            password != confirm -> "비밀번호가 일치하지 않습니다."
            else -> null
        }
        if (error != null || busy) return
        scope.launch {
            busy = true
            try {
                AppGraph.repo.register(username.trim(), email.trim(), password)
                done = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.userMessage()
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("회원가입") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "서버: ${session.serverUrl}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(username, { username = it }, label = { Text("아이디") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                email, { email = it }, label = { Text("이메일") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                password, { password = it }, label = { Text("비밀번호") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                confirm, { confirm = it }, label = { Text("비밀번호 확인") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { submit() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "가입 중…" else "가입하기")
            }
        }
    }

    if (done) {
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text("회원가입 완료") },
            text = { Text("가입한 아이디로 로그인하세요.") },
            confirmButton = { TextButton(onClick = onBack) { Text("확인") } },
        )
    }
}
