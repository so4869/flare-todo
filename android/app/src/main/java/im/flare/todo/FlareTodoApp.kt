package im.flare.todo

import android.app.Application
import im.flare.todo.auth.BiometricLogin
import im.flare.todo.data.ApiClient
import im.flare.todo.data.SessionStore
import im.flare.todo.data.TodoRepository
import im.flare.todo.download.AttachmentDownloader
import im.flare.todo.notify.PinnedNotifier
import im.flare.todo.notify.PinnedSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 알림에서 앱을 열 때의 요청: 해당 카테고리의 미완료 목록을 보여주고, 필요하면 새 할 일 작성으로 간다.
 * todoId 가 있으면(비용 공유 후 "열기") 목록 조건은 그대로 두고 그 할 일을 연다.
 */
data class LaunchRequest(val categoryId: Long, val createNew: Boolean, val todoId: Long? = null)

/** 앱 전역 객체 모음 (수동 DI) */
object AppGraph {
    lateinit var app: Application
        private set
    lateinit var session: SessionStore
        private set
    lateinit var api: ApiClient
        private set
    lateinit var repo: TodoRepository
        private set
    lateinit var pinned: PinnedNotifier
        private set
    lateinit var biometric: BiometricLogin
        private set
    lateinit var downloader: AttachmentDownloader
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 이번 프로세스에서 로그인(지문 또는 비밀번호)을 통과했는지. 지문 로그인을 켜면 앱을 새로 열 때마다 인증한다. */
    var unlocked = false

    val launchRequest = MutableStateFlow<LaunchRequest?>(null)

    fun init(application: Application) {
        app = application
        session = SessionStore(application)
        api = ApiClient(session)
        pinned = PinnedNotifier(application, session) { repo }
        repo = TodoRepository(application, api, session, onChanged = { scope.launch { pinned.refresh() } })
        biometric = BiometricLogin(session)
        downloader = AttachmentDownloader(application) { repo }
    }

    /** 로그인 직후: 첨부 CDN 쿠키를 받고 상태표시줄 알림 동기화를 시작한다. */
    fun onLoggedIn() {
        unlocked = true
        scope.launch { runCatching { repo.ensureCdnSession(force = true) } }
        PinnedSync.apply(app)
    }
}

class FlareTodoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        AppGraph.pinned.createChannel()
        AppGraph.downloader.registerResultReceiver()
    }
}
