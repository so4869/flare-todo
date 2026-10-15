package im.flare.todo.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.source
import java.io.IOException

class TodoRepository(
    private val context: Context,
    private val api: ApiClient,
    private val session: SessionStore,
    /** 할 일·카테고리가 바뀐 뒤 호출된다 (상태표시줄 알림 갱신용). */
    private val onChanged: () -> Unit,
) {
    /** 데이터가 바뀔 때마다 증가. 화면은 이 값을 구독해 다시 불러온다. */
    val revision = MutableStateFlow(0)

    private var cdnValidUntil = 0L

    private fun changed() {
        revision.update { it + 1 }
        onChanged()
    }

    // ── 인증 ────────────────────────────────────────────────
    suspend fun login(username: String, password: String): User {
        val result: LoginResult = api.send("POST", "api/auth/login", LoginRequest(username, password), auth = false)
        session.setLogin(result.accessToken, result.user)
        cdnValidUntil = 0
        return result.user
    }

    suspend fun register(username: String, email: String, password: String): User =
        api.send("POST", "api/auth/register", RegisterRequest(username, email, password), auth = false)

    suspend fun logout() {
        runCatching { api.exec("POST", "api/auth/logout") }
        session.clearToken()
        cdnValidUntil = 0
        onChanged()
    }

    suspend fun updateTimezone(tz: String) {
        val user: User = api.send("PUT", "api/users/timezone", TimezoneRequest(tz))
        session.setTimezone(user.timezone ?: tz)
    }

    // ── 카테고리 ─────────────────────────────────────────────
    suspend fun categories(): List<Category> = api.get("api/categories")

    suspend fun addCategory(name: String) {
        api.send<CategoryRequest, Category>("POST", "api/categories", CategoryRequest(name))
        changed()
    }

    suspend fun deleteCategory(id: Long) {
        api.exec("DELETE", "api/categories/$id")
        changed()
    }

    suspend fun reorderCategories(ids: List<Long>) {
        api.exec("PUT", "api/categories/order", api.jsonBody(CategoryOrderRequest.serializer(), CategoryOrderRequest(ids)))
        changed()
    }

    // ── 할 일 ───────────────────────────────────────────────
    suspend fun todos(f: ListFilter): List<TodoSummary> {
        val (sortBy, sortDir) = f.sort.split("_").let { it[0] to it.getOrElse(1) { "desc" } }
        val query = buildList {
            add("filter" to f.status)
            add("sortBy" to sortBy)
            add("sortDir" to sortDir)
            f.categoryIds.forEach { add("categoryIds" to it.toString()) }
            f.createdFrom?.let { add("createdFrom" to it) }
            f.createdTo?.let { add("createdTo" to it) }
            f.completedFrom?.let { add("completedFrom" to it) }
            f.completedTo?.let { add("completedTo" to it) }
        }
        return api.get("api/todos", query)
    }

    suspend fun todo(id: Long): Todo = api.get("api/todos/$id")

    suspend fun createTodo(request: TodoRequest): Todo =
        api.send<TodoRequest, Todo>("POST", "api/todos", request).also { changed() }

    suspend fun updateTodo(id: Long, request: TodoRequest): Todo =
        api.send<TodoRequest, Todo>("PUT", "api/todos/$id", request).also { changed() }

    suspend fun deleteTodo(id: Long) {
        api.exec("DELETE", "api/todos/$id")
        changed()
    }

    suspend fun toggle(id: Long): Todo =
        (api.call("PATCH", "api/todos/$id/toggle", Todo.serializer())
            ?: throw ApiException(0, "서버 응답이 비어 있습니다.")).also { changed() }

    // ── 첨부 ────────────────────────────────────────────────
    suspend fun attachmentConfig(): AttachmentConfig = api.get("api/attachments/config")

    /** 할 일 첨부 파일 업로드. 할 일 저장 시 attachmentIds 로 연결해야 유지된다. */
    suspend fun uploadFile(uri: Uri, onProgress: (Float) -> Unit = {}): Attachment = upload("api/attachments", uri, onProgress)

    /** 본문 이미지 업로드 (PNG/JPEG/GIF/WebP) */
    suspend fun uploadImage(uri: Uri): Attachment = upload("api/attachments/images", uri) {}

    private suspend fun upload(path: String, uri: Uri, onProgress: (Float) -> Unit): Attachment {
        val resolver = context.contentResolver
        var name = "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size > 0) {
            val config = attachmentConfig()
            if (size > config.maxFileSize) throw ApiException(0, "첨부파일은 ${config.maxFileSizeLabel}까지 올릴 수 있습니다.")
        }
        val type = resolver.getType(uri)?.toMediaTypeOrNull()
        val part = object : RequestBody() {
            override fun contentType(): MediaType? = type
            override fun contentLength(): Long = size
            override fun writeTo(sink: BufferedSink) {
                val input = resolver.openInputStream(uri) ?: throw IOException("파일을 열 수 없습니다.")
                input.source().use { source ->
                    val buffer = Buffer()
                    var sent = 0L
                    while (true) {
                        val n = source.read(buffer, 64 * 1024L)
                        if (n == -1L) break
                        sink.write(buffer, n)
                        sent += n
                        if (size > 0) onProgress((sent.toFloat() / size).coerceAtMost(1f))
                    }
                }
            }
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", name, part).build()
        return api.call("POST", path, Attachment.serializer(), body = body)
            ?: throw ApiException(0, "서버 응답이 비어 있습니다.")
    }

    /** 외부 공유 링크. direct=true 면 앱을 거치지 않는 S3 서명 URL(최대 7일, days 생략 시 서버 설정값). */
    suspend fun share(attachmentId: Long, days: Double?, direct: Boolean = false): ShareCreated =
        api.send("POST", "api/attachments/$attachmentId/share", ShareRequest(days, direct))

    suspend fun shareLinks(): List<ShareLink> = api.get("api/share-links")

    suspend fun revokeShare(id: Long) = api.exec("DELETE", "api/share-links/$id")

    /**
     * 첨부 CDN(CloudFront) 서명 쿠키를 받아 WebView 쿠키 저장소에 넣는다.
     * 본문 이미지(WebView)와 파일 다운로드가 같은 쿠키를 쓴다.
     * @return 서버가 서명 쿠키를 발급하는지 여부
     */
    suspend fun ensureCdnSession(force: Boolean = false): Boolean {
        if (!force && System.currentTimeMillis() < cdnValidUntil) return true
        val result = api.call("POST", "api/attachments/cdn-session", CdnSession.serializer(), onResponse = { resp ->
            val cookies = CookieManager.getInstance()
            val url = resp.request.url.toString()
            resp.headers("Set-Cookie").forEach { cookies.setCookie(url, it) }
            cookies.flush()
        })
        val enabled = result?.enabled == true
        // 쿠키 유효시간(12시간)보다 충분히 짧게 잡아 만료 전에 다시 받는다.
        if (enabled) cdnValidUntil = System.currentTimeMillis() + CDN_REFRESH_MS
        return enabled
    }

    private companion object {
        const val CDN_REFRESH_MS = 60 * 60 * 1000L
    }
}
