package im.flare.todo.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import im.flare.todo.AppGraph
import im.flare.todo.MainActivity
import im.flare.todo.R
import im.flare.todo.data.ApiException
import im.flare.todo.data.ListFilter
import im.flare.todo.data.SessionState
import im.flare.todo.data.SessionStore
import im.flare.todo.data.TodoRepository
import im.flare.todo.data.TodoSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * 카테고리별 "상태표시줄에 띄우기".
 *
 * 고정한 카테고리마다 미완료 할 일 목록을 담은 진행 중(ongoing) 알림을 하나씩 띄운다.
 * Android 16 에서는 Live Update 로 승격을 요청해 상태표시줄 칩(One UI 는 Now Bar)에 건수가 보인다.
 * 미완료 할 일이 없는 카테고리는 알림을 내린다.
 */
class PinnedNotifier(
    private val context: Context,
    private val session: SessionStore,
    private val repo: () -> TodoRepository,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val mutex = Mutex()

    fun createChannel() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "상태표시줄 고정", NotificationManager.IMPORTANCE_LOW).apply {
                description = "카테고리별 미완료 할 일을 상태표시줄에 계속 띄워 둡니다."
                setShowBadge(false)
            }
        )
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Live Update 로 띄울 수 있는지 (Android 16+, 사용자가 앱 설정에서 끌 수 있다). */
    fun canPromote(): Boolean = Build.VERSION.SDK_INT >= 36 && manager.canPostPromotedNotifications()

    /** @return 서버에서 목록을 받아 알림을 맞췄으면 true. 일시적 실패(네트워크 등)면 false. */
    suspend fun refresh(): Boolean = mutex.withLock {
        val state = session.state.value
        if (state.token == null || state.pinnedIds.isEmpty() || !hasPermission()) {
            cancelAll()
            return true
        }
        val todos = try {
            repo().todos(ListFilter(status = "active"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // 토큰 만료 등: 다시 로그인할 때까지는 마지막으로 띄운 내용을 그대로 둔다.
            return e.status == 401
        } catch (e: Exception) {
            return false
        }

        val posted = mutableSetOf<Int>()
        for (id in state.pinnedIds) {
            val items = if (id == SessionState.PIN_ALL) todos else todos.filter { t -> t.categories.any { it.id == id } }
            if (items.isEmpty()) continue
            val name = if (id == SessionState.PIN_ALL) "전체" else items.first().categories.first { it.id == id }.name
            manager.notify(TAG, id.toInt(), build(id, name, items, state.promoted))
            posted += id.toInt()
        }
        manager.activeNotifications
            .filter { it.tag == TAG && it.id !in posted }
            .forEach { manager.cancel(TAG, it.id) }
        true
    }

    private fun cancelAll() {
        manager.activeNotifications.filter { it.tag == TAG }.forEach { manager.cancel(TAG, it.id) }
    }

    private fun build(categoryId: Long, name: String, todos: List<TodoSummary>, promoted: Boolean) =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_todo)
            .setColor(context.getColor(R.color.brand))
            .setContentTitle("$name · 미완료 ${todos.size}건")
            .setContentText(todos.joinToString(" · ") { it.title })
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(buildString {
                    todos.take(MAX_LINES).forEach { appendLine("• ${it.title}") }
                    if (todos.size > MAX_LINES) append("… 외 ${todos.size - MAX_LINES}건")
                }.trimEnd())
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setSortKey("%010d".format(categoryId))
            .setContentIntent(activityIntent("category", categoryId))
            .addAction(0, "할 일 추가", activityIntent("new", categoryId))
            .addAction(
                0, "새로고침",
                PendingIntent.getBroadcast(
                    context, 0,
                    Intent(context, PinnedReceiver::class.java).setAction(PinnedReceiver.ACTION_REFRESH),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .apply {
                if (promoted) {
                    // 칩에는 아주 짧은 글만 들어간다: "업무 3"
                    setRequestPromotedOngoing(true)
                    setShortCriticalText("${name.take(4)} ${todos.size}")
                }
            }
            .build()

    // data URI 로 카테고리별 PendingIntent 를 구분한다.
    private fun activityIntent(kind: String, categoryId: Long): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("$SCHEME://$kind/$categoryId")),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val SCHEME = "flaretodo"
        private const val CHANNEL = "pinned"
        private const val TAG = "pinned"
        private const val MAX_LINES = 8
    }
}

/** 웹 등 다른 곳에서 바뀐 내용을 주기적으로 반영한다. */
class PinnedSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (AppGraph.pinned.refresh() || runAttemptCount >= 3) Result.success() else Result.retry()
}

object PinnedSync {
    private const val PERIODIC = "pinned-sync"
    private const val ONCE = "pinned-sync-once"
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** 고정 설정·로그인 상태에 맞춰 주기 동기화를 켜거나 끄고, 지금 한 번 갱신한다. */
    fun apply(context: Context) {
        val state = AppGraph.session.state.value
        val wm = WorkManager.getInstance(context)
        if (state.token != null && state.pinnedIds.isNotEmpty()) {
            wm.enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PinnedSyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).build(),
            )
        } else {
            wm.cancelUniqueWork(PERIODIC)
        }
        AppGraph.scope.launch { AppGraph.pinned.refresh() }
    }

    /** 네트워크가 연결되면 한 번 갱신 (부팅 직후 등) */
    fun kick(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONCE, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PinnedSyncWorker>().setConstraints(online).build(),
        )
    }
}

class PinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH -> {
                val pending = goAsync()
                AppGraph.scope.launch {
                    try {
                        AppGraph.pinned.refresh()
                    } finally {
                        pending.finish()
                    }
                }
            }
            // 재부팅·앱 업데이트 뒤에는 알림이 사라지므로 다시 띄운다.
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> PinnedSync.kick(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "im.flare.todo.action.REFRESH_PINNED"
    }
}
