package im.flare.todo.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

/** 본문 HTML 을 그리는 데 쓰는 테마 색 (CSS 값) */
data class HtmlColors(val fg: String, val muted: String, val link: String, val border: String, val code: String)

@Composable
fun htmlColors(): HtmlColors {
    val c = MaterialTheme.colorScheme
    return remember(c) {
        HtmlColors(
            fg = c.onSurface.toCss(),
            muted = c.onSurfaceVariant.toCss(),
            link = c.primary.toCss(),
            border = c.outlineVariant.toCss(),
            code = c.surfaceContainerHigh.toCss(),
        )
    }
}

private fun contentCss(c: HtmlColors) = """
    html { -webkit-text-size-adjust: 100%; }
    body { margin: 0; padding: 12px 16px 24px; color: ${c.fg}; background: transparent;
           font: 16px/1.6 sans-serif; overflow-wrap: anywhere; }
    img { max-width: 100%; height: auto; }
    table { border-collapse: collapse; display: block; max-width: 100%; overflow-x: auto; }
    td, th { border: 1px solid ${c.border}; padding: 4px 8px; min-width: 2em; }
    a { color: ${c.link}; }
    pre, code { background: ${c.code}; border-radius: 4px; }
    pre { padding: 8px; white-space: pre-wrap; }
    blockquote { margin-left: 0; padding-left: 12px; border-left: 3px solid ${c.border}; color: ${c.muted}; }
    h1 { font-size: 1.5em; } h2 { font-size: 1.3em; } h3 { font-size: 1.15em; }
    p { margin: 0 0 .6em; }
""".trimIndent()

private fun viewerPage(body: String, c: HtmlColors) = """
    <!doctype html><html><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>${contentCss(c)}</style></head><body>$body</body></html>
""".trimIndent()

private fun openExternally(context: Context, request: WebResourceRequest): Boolean {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
    return true
}

/**
 * 할 일 본문(HTML) 보기. 스크립트는 실행하지 않는다.
 * baseUrl 을 서버 주소로 두어야 첨부 CDN(같은 상위 도메인)의 SameSite 쿠키가 이미지 요청에 실린다.
 */
@Composable
fun HtmlView(html: String, baseUrl: String, modifier: Modifier = Modifier) {
    val colors = htmlColors()
    val page = remember(html, colors) { viewerPage(html, colors) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                        openExternally(view.context, request)
                }
            }
        },
        update = { view ->
            if (view.tag != page) {
                view.tag = page
                view.loadDataWithBaseURL(baseUrl, page, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}

/**
 * 본문 편집기 (assets/editor.html 의 contenteditable).
 * 웹(TinyMCE)에서 쓴 표·이미지 등 HTML 을 그대로 보존하면서 고칠 수 있다.
 */
@SuppressLint("SetJavaScriptEnabled")
fun createEditorWebView(context: Context, baseUrl: String, colors: HtmlColors, initialHtml: String, onReady: () -> Unit): WebView =
    WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(AndroidColor.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        isFocusable = true
        isFocusableInTouchMode = true
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                openExternally(view.context, request)

            override fun onPageFinished(view: WebView, url: String?) {
                // 본문은 스크립트 문자열로 넘겨 innerHTML 로 넣는다 (본문 안의 <script> 는 실행되지 않는다).
                view.evaluateJavascript("setHtml(${Json.encodeToString(initialHtml)})") { onReady() }
            }
        }
        val template = context.assets.open("editor.html").bufferedReader().use { it.readText() }
        loadDataWithBaseURL(baseUrl, template.replace("/*CONTENT_CSS*/", contentCss(colors)), "text/html", "utf-8", null)
    }

/** 편집기의 JS 함수를 호출하고 문자열 결과를 받는다. */
suspend fun WebView.evalString(script: String): String? = suspendCancellableCoroutine { cont ->
    evaluateJavascript(script) { result ->
        val value = runCatching { Json.decodeFromString<String?>(result ?: "null") }.getOrNull()
        if (cont.isActive) cont.resume(value)
    }
}

fun WebView.editorCommand(name: String, value: String? = null) {
    val arg = if (value == null) "null" else Json.encodeToString(value)
    evaluateJavascript("cmd(${Json.encodeToString(name)}, $arg)", null)
}
