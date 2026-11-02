package im.flare.todo

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import im.flare.todo.data.SessionState
import im.flare.todo.notify.PinnedNotifier
import im.flare.todo.notify.PinnedSync
import im.flare.todo.ui.CategoriesScreen
import im.flare.todo.ui.FlareTheme
import im.flare.todo.ui.LoginScreen
import im.flare.todo.ui.RegisterScreen
import im.flare.todo.ui.SettingsScreen
import im.flare.todo.ui.ShareLinksScreen
import im.flare.todo.ui.TodoDetailScreen
import im.flare.todo.ui.TodoEditScreen
import im.flare.todo.ui.TodoListScreen

// BiometricPrompt(androidx.biometric)가 FragmentActivity 를 요구한다.
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { FlareTheme { AppNav() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // 웹 등 다른 곳에서 바뀐 내용이 앱으로 돌아올 때 상태표시줄에 반영되게 한다.
        if (AppGraph.unlocked) PinnedSync.apply(this)
    }

    /** 상태표시줄 알림에서 온 flaretodo://category/{id} 또는 flaretodo://new/{id}, 비용 공유에서 온 flaretodo://todo/{id} */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != PinnedNotifier.SCHEME) return
        if (data.host == "todo") {
            val todoId = data.lastPathSegment?.toLongOrNull() ?: return
            AppGraph.launchRequest.value = LaunchRequest(SessionState.PIN_ALL, createNew = false, todoId = todoId)
            return
        }
        val categoryId = data.lastPathSegment?.toLongOrNull() ?: return
        AppGraph.launchRequest.value = LaunchRequest(categoryId, createNew = data.host == "new")
    }
}

private object Routes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val LIST = "list"
    const val CATEGORIES = "categories"
    const val SHARES = "shares"
    const val SETTINGS = "settings"
    fun detail(id: Long) = "detail/$id"
    fun edit(id: Long?, categoryIds: List<Long> = emptyList()) = "edit?id=${id ?: -1}&cats=${categoryIds.joinToString(",")}"
}

private fun NavHostController.resetTo(route: String) {
    navigate(route) { popUpTo(graph.id) { inclusive = true } }
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val start = remember {
        val state = AppGraph.session.state.value
        // 지문 로그인을 켰으면 앱을 새로 열 때마다 로그인 화면(지문 인증)을 거친다.
        if (state.token != null && (!state.bioEnabled || AppGraph.unlocked)) {
            AppGraph.onLoggedIn()
            Routes.LIST
        } else {
            Routes.LOGIN
        }
    }

    // 토큰 만료(401) → 로그인 화면으로
    LaunchedEffect(Unit) {
        AppGraph.api.sessionExpired.collect {
            AppGraph.unlocked = false
            if (nav.currentDestination?.route != Routes.LOGIN) nav.resetTo(Routes.LOGIN)
        }
    }

    // 다른 화면을 보던 중 알림을 누르면 목록으로 돌아가 요청을 처리한다.
    val request by AppGraph.launchRequest.collectAsState()
    LaunchedEffect(request) {
        if (request != null && AppGraph.unlocked) nav.popBackStack(Routes.LIST, inclusive = false)
    }

    NavHost(nav, startDestination = start) {
        composable(Routes.LOGIN) {
            LoginScreen(onLoggedIn = { nav.resetTo(Routes.LIST) }, onRegister = { nav.navigate(Routes.REGISTER) })
        }
        composable(Routes.REGISTER) { RegisterScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.LIST) {
            TodoListScreen(
                onOpen = { nav.navigate(Routes.detail(it)) },
                onNew = { nav.navigate(Routes.edit(null, it)) },
                onCategories = { nav.navigate(Routes.CATEGORIES) },
                onShares = { nav.navigate(Routes.SHARES) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onLoggedOut = {
                    AppGraph.unlocked = false
                    nav.resetTo(Routes.LOGIN)
                },
            )
        }
        composable("detail/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments!!.getLong("id")
            TodoDetailScreen(id, onBack = { nav.popBackStack() }, onEdit = { nav.navigate(Routes.edit(id)) })
        }
        composable(
            "edit?id={id}&cats={cats}",
            arguments = listOf(
                navArgument("id") { type = NavType.LongType; defaultValue = -1L },
                navArgument("cats") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val id = entry.arguments!!.getLong("id").takeIf { it > 0 }
            val categoryIds = entry.arguments!!.getString("cats").orEmpty().split(",").mapNotNull(String::toLongOrNull)
            TodoEditScreen(id, categoryIds, onDone = { nav.popBackStack() })
        }
        composable(Routes.CATEGORIES) { CategoriesScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SHARES) {
            ShareLinksScreen(onBack = { nav.popBackStack() }, onOpenTodo = { nav.navigate(Routes.detail(it)) })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() }, onCategories = { nav.navigate(Routes.CATEGORIES) })
        }
    }
}
