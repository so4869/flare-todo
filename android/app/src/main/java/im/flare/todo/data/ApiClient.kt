package im.flare.todo.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val status: Int, message: String) : IOException(message)

fun Throwable.userMessage(): String = when (this) {
    is ApiException -> message ?: "요청에 실패했습니다."
    is UnknownHostException, is ConnectException, is SocketTimeoutException -> "서버에 연결할 수 없습니다. 네트워크를 확인하세요."
    is SSLException -> "보안 연결(HTTPS)에 실패했습니다."
    is IOException -> "네트워크 오류: ${message ?: javaClass.simpleName}"
    else -> message ?: "알 수 없는 오류가 발생했습니다."
}

/** flare-todo REST API 호출. 모든 응답은 {success, message, data} 봉투로 온다. */
class ApiClient(private val session: SessionStore) {

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 인증이 필요한 요청이 401 을 받으면 발행된다 (토큰 만료). */
    val sessionExpired: SharedFlow<Unit> = _sessionExpired

    fun <T> jsonBody(serializer: KSerializer<T>, value: T): RequestBody =
        json.encodeToString(serializer, value).toRequestBody(JSON)

    /** @return 봉투의 data. 성공이 아니면 서버 메시지를 담은 [ApiException]. */
    suspend fun <T> call(
        method: String,
        path: String,
        serializer: KSerializer<T>,
        query: List<Pair<String, String>> = emptyList(),
        body: RequestBody? = null,
        auth: Boolean = true,
        onResponse: (Response) -> Unit = {},
    ): T? {
        val state = session.state.value
        val base = state.serverUrl.toHttpUrlOrNull() ?: throw ApiException(0, "서버 주소가 올바르지 않습니다.")
        val url = base.newBuilder().addPathSegments(path)
            .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        val request = Request.Builder().url(url)
            .method(method, body ?: if (method == "GET" || method == "DELETE") null else EMPTY)
            .apply { if (auth) state.token?.let { header("Authorization", "Bearer $it") } }
            .build()

        val (code, text) = http.newCall(request).await { resp ->
            onResponse(resp)
            resp.code to resp.body.string()
        }

        if (code == 401 && auth) {
            session.clearToken()
            _sessionExpired.tryEmit(Unit)
            throw ApiException(401, "로그인이 만료되었습니다. 다시 로그인하세요.")
        }
        // 프록시/로드밸런서가 돌려준 HTML 오류 페이지 등은 봉투 형식이 아니다.
        val envelope = try {
            json.decodeFromString(ApiEnvelope.serializer(serializer), text)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } ?: throw ApiException(code, if (code == 413) "파일이 너무 큽니다." else "서버 오류가 발생했습니다. (HTTP $code)")

        if (code !in 200..299 || !envelope.success) {
            throw ApiException(code, envelope.message ?: "요청에 실패했습니다. (HTTP $code)")
        }
        return envelope.data
    }

    suspend inline fun <reified T> get(path: String, query: List<Pair<String, String>> = emptyList()): T =
        call("GET", path, serializer<T>(), query) ?: throw ApiException(0, "서버 응답이 비어 있습니다.")

    suspend inline fun <reified B, reified T> send(method: String, path: String, body: B, auth: Boolean = true): T =
        call(method, path, serializer<T>(), body = jsonBody(serializer<B>(), body), auth = auth)
            ?: throw ApiException(0, "서버 응답이 비어 있습니다.")

    /** data 가 없는(Void) 요청 */
    suspend fun exec(method: String, path: String, body: RequestBody? = null) {
        call(method, path, JsonElement.serializer(), body = body)
    }

    private suspend fun <R> Call.await(block: (Response) -> R): R = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use(block)
                    if (cont.isActive) cont.resume(result)
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        })
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val EMPTY = ByteArray(0).toRequestBody()
    }
}
