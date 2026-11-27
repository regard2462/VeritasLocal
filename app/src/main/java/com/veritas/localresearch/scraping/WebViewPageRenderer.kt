package com.veritas.localresearch.scraping

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Android: unsichtbare WebView als JavaScript-Ausweichweg. */
fun createJsPageRenderer(context: Context, userAgent: String): JsPageRenderer? = WebViewPageRenderer(context, userAgent)

private class WebViewPageRenderer(private val context: Context, private val userAgent: String) : JsPageRenderer {
    private val webViewMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isDestroyed = AtomicBoolean(false)
    private var webView: WebView? = null

    private val blockedExtensions = setOf(
        ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico",
        ".woff", ".woff2", ".ttf", ".mp4", ".webm", ".mp3", ".css"
    )

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebViewOnMain(): WebView? {
        if (isDestroyed.get()) return null
        webView?.let { return it }
        return WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = false
            settings.blockNetworkImage = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.userAgentString = userAgent
            webView = this
        }
    }

    override suspend fun loadAndEval(url: String, jsScript: String, timeoutMs: Long): String? {
        if (isDestroyed.get()) return null
        return webViewMutex.withLock {
            if (isDestroyed.get()) return@withLock null
            withContext(Dispatchers.Main) {
                val wv = ensureWebViewOnMain() ?: return@withContext null
                try {
                    withTimeoutOrNull(timeoutMs) {
                        val loaded = suspendCancellableCoroutine<Boolean> { cont ->
                            var resumed = false
                            fun finish(ok: Boolean) {
                                if (!resumed && cont.isActive) {
                                    resumed = true
                                    cont.resume(ok)
                                }
                            }

                            wv.webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val path = request?.url?.path?.lowercase().orEmpty()
                                    val host = request?.url?.host?.lowercase().orEmpty()
                                    if (blockedExtensions.any { path.endsWith(it) } ||
                                        host.contains("doubleclick") ||
                                        host.contains("googlesyndication") ||
                                        host.contains("analytics")
                                    ) {
                                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                                    }
                                    return super.shouldInterceptRequest(view, request)
                                }

                                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                                    finish(true)
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    if (request?.isForMainFrame == true) finish(false)
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    errorResponse: WebResourceResponse?
                                ) {
                                    if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 200) >= 400) {
                                        finish(false)
                                    }
                                }

                                override fun onReceivedSslError(
                                    view: WebView?,
                                    handler: SslErrorHandler?,
                                    error: SslError?
                                ) {
                                    handler?.cancel()
                                    finish(false)
                                }

                                override fun onRenderProcessGone(
                                    view: WebView?,
                                    detail: RenderProcessGoneDetail?
                                ): Boolean {
                                    // Verhindert App-Crash wenn Android den WebView-Renderer wegen RAM-Druck killt
                                    if (webView === view) {
                                        webView = null
                                    }
                                    runCatching { view?.destroy() }
                                    finish(false)
                                    return true
                                }
                            }

                            cont.invokeOnCancellation {
                                mainHandler.post {
                                    if (webView === wv) runCatching { wv.stopLoading() }
                                }
                            }
                            wv.loadUrl(url)
                        }
                        if (!loaded || webView !== wv) return@withTimeoutOrNull null
                        delay(300) // Kurzes Zeitfenster fuer leichtes DOM-Rendering
                        suspendCancellableCoroutine<String?> { evalCont ->
                            wv.evaluateJavascript(jsScript) { result ->
                                if (evalCont.isActive) evalCont.resume(result)
                            }
                        }
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.Main) {
                        if (webView === wv) runCatching { wv.stopLoading() }
                    }
                }
            }
        }
    }

    override fun destroy() {
        if (!isDestroyed.compareAndSet(false, true)) return
        mainHandler.post {
            webView?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.destroy()
                }
            }
            webView = null
        }
    }
}
