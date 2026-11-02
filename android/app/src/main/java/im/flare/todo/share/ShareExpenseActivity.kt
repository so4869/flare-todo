package im.flare.todo.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import im.flare.todo.AppGraph
import im.flare.todo.MainActivity
import im.flare.todo.data.userMessage
import im.flare.todo.notify.PinnedNotifier
import im.flare.todo.ui.FlareTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 갤러리 등의 공유 메뉴 "Flare Todo 비용": 받은 사진을 비용 카테고리의 이번 달 할 일에 첨부한다. */
class ShareExpenseActivity : ComponentActivity() {

    private val vm: ShareExpenseViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) vm.start(sharedUris(intent))
        setContent {
            FlareTheme {
                ShareExpenseDialog(vm, onClose = ::finish, onOpen = ::openTodo)
            }
        }
    }

    private fun sharedUris(intent: Intent?): List<Uri> = when (intent?.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }

    private fun openTodo(todoId: Long) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setData(Uri.parse("${PinnedNotifier.SCHEME}://todo/$todoId"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }
}

class ShareExpenseViewModel : ViewModel() {
    sealed interface State {
        data class Working(val done: Int, val total: Int) : State
        data class Done(val todoId: Long, val title: String, val added: Int, val failures: List<String>) : State
        data class Failed(val message: String) : State
    }

    var state by mutableStateOf<State>(State.Working(0, 0))
        private set

    fun start(uris: List<Uri>) {
        if (uris.isEmpty()) {
            state = State.Failed("공유받은 사진이 없습니다.")
            return
        }
        val session = AppGraph.session.state.value
        if (session.token == null) {
            state = State.Failed("Flare Todo 앱에서 먼저 로그인하세요.")
            return
        }
        viewModelScope.launch {
            state = try {
                val share = ExpenseShare(AppGraph.repo, session.timezone)
                val result = share.attach(uris) { done, total -> state = State.Working(done, total) }
                State.Done(result.todo.id, result.todo.title, result.added, result.failures)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                State.Failed(e.userMessage())
            }
        }
    }
}

@Composable
private fun ShareExpenseDialog(vm: ShareExpenseViewModel, onClose: () -> Unit, onOpen: (Long) -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("${ExpenseShare.CATEGORY}에 추가", style = MaterialTheme.typography.headlineSmall)
                when (val s = vm.state) {
                    is ShareExpenseViewModel.State.Working -> {
                        Text(if (s.total == 0) "준비 중…" else "사진 올리는 중 (${s.done}/${s.total})")
                        if (s.total == 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
                    }
                    is ShareExpenseViewModel.State.Done -> {
                        Text("'${s.title}'에 사진 ${s.added}장을 첨부했습니다.")
                        if (s.failures.isNotEmpty()) {
                            Text(
                                "${s.failures.size}장은 올리지 못했습니다: ${s.failures.first()}",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Buttons {
                            TextButton(onClick = { onOpen(s.todoId) }) { Text("열기") }
                            TextButton(onClick = onClose) { Text("닫기") }
                        }
                    }
                    is ShareExpenseViewModel.State.Failed -> {
                        Text(s.message, color = MaterialTheme.colorScheme.error)
                        Buttons { TextButton(onClick = onClose) { Text("닫기") } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { content() }
}
