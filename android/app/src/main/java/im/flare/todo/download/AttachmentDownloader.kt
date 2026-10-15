package im.flare.todo.download

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.widget.Toast
import im.flare.todo.data.ApiException
import im.flare.todo.data.Attachment
import im.flare.todo.data.TodoRepository

/**
 * 첨부파일을 공용 다운로드 폴더의 `Download/flare-todo/` 에 저장한다.
 *
 * 시스템 DownloadManager 에 맡기므로 큰 파일도 앱이 꺼진 뒤까지 이어 받고, 진행 상황과 완료 알림이
 * 시스템 알림으로 표시된다. 공용 Download 폴더는 저장소 권한 없이 쓸 수 있다.
 */
class AttachmentDownloader(private val context: Context, private val repo: () -> TodoRepository) {

    private val manager = context.getSystemService(DownloadManager::class.java)

    /** @return 저장될 상대 경로 (예: Download/flare-todo/보고서.pdf) */
    suspend fun download(attachment: Attachment): String {
        // 첨부 CDN 은 CloudFront 서명 쿠키로 인증한다.
        val enabled = repo().ensureCdnSession()
        val cookie = CookieManager.getInstance().getCookie(attachment.url)
        if (!enabled || cookie.isNullOrBlank()) {
            throw ApiException(0, "첨부 저장소 인증 정보를 받지 못했습니다. 서버의 CDN 설정을 확인하세요.")
        }
        val name = attachment.name.replace(UNSAFE_CHARS, "_").trim().ifEmpty { "file" }
        val request = DownloadManager.Request(Uri.parse(attachment.url))
            .addRequestHeader("Cookie", cookie)
            .setTitle(name)
            .setDescription("Flare Todo 첨부파일")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // 같은 이름이 있으면 DownloadManager 가 "이름-1.ext" 처럼 새 이름을 붙인다.
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "$FOLDER/$name")
        attachment.contentType?.takeIf { it.isNotBlank() }?.let(request::setMimeType)
        manager.enqueue(request)
        return "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$name"
    }

    /** 다운로드 결과를 토스트로 알린다 (실패 원인은 시스템 알림만으로는 알기 어렵다). */
    fun registerResultReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                manager.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                    if (!c.moveToFirst()) return
                    val title = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val message = if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER 에 저장했습니다: $title"
                    } else {
                        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        "다운로드 실패: $title (${describe(reason)})"
                    }
                    Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
                }
            }
        }
        // 브로드캐스트를 보내는 쪽이 시스템 다운로드 제공자(다른 앱)라 EXPORTED 여야 받는다.
        context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
    }

    private fun describe(reason: Int) = when (reason) {
        401, 403 -> "접근 권한 없음"
        404 -> "파일 없음"
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "저장 공간 부족"
        DownloadManager.ERROR_CANNOT_RESUME, DownloadManager.ERROR_HTTP_DATA_ERROR -> "네트워크 오류"
        else -> "오류 $reason"
    }

    companion object {
        const val FOLDER = "flare-todo"
        private val UNSAFE_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    }
}
