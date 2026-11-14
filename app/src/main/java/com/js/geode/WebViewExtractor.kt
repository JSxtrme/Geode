package com.js.geode

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Hidden-WebView video extractor for Instagram / Facebook reels.
 *
 * Why this exists: Instagram and Facebook block Geode's raw HTTP fetch
 * (an anonymous request from the phone, no session), so most reels fail
 * with "platform blocked the fetch". A WebView is a real browser engine —
 * real JavaScript, the WebView's own default mobile User-Agent — so the
 * platform serves it the page the way it serves a browser, and the video
 * URL is read out of the loaded page.
 *
 * NO LOGIN, NO CREDENTIALS, NO SESSION — hard rule from JS ("even i
 * wouldn't use any app that's asking for my insta account credentials,
 * instant delete"). Nothing is ever typed into the WebView, no account
 * is created, and no cookies or browsing data are kept: cookies and
 * Web Storage are wiped and the WebView is destroyed after every
 * attempt, successful or not. This only ever reads a PUBLIC page.
 *
 * It is a FALLBACK, not the first try: the raw fetchHtml path in
 * GeminiClient is fast when it works and runs first; the WebView only
 * runs when that returns nothing.
 */
sealed interface WebViewOutcome {
    data class Found(val videoUrl: String) : WebViewOutcome
    /** Confirmed login wall / private reel — not a generic failure. */
    object Private : WebViewOutcome
    object NotFound : WebViewOutcome
}

object WebViewExtractor {
    private const val TIMEOUT_MS = 30_000L
    private const val SETTLE_MS = 2500L

    /** JS run in the loaded page: video URL, "PRIVATE", or "" (not found). */
    private const val JS_EXTRACT = """
        (function(){
          var path = location.pathname || "";
          if (path.indexOf("/accounts/login") >= 0) return "PRIVATE";
          var t = document.title || "";
          if (t.indexOf("Login") === 0) return "PRIVATE";
          var m = document.querySelector('meta[property="og:video:secure_url"]');
          if (m && m.content) return m.content;
          var m2 = document.querySelector('meta[property="og:video"]');
          if (m2 && m2.content) return m2.content;
          var v = document.querySelector("video");
          if (v && v.src) return v.src;
          var s = document.querySelector("video source");
          if (s && s.src) return s.src;
          var h = (document.documentElement && document.documentElement.outerHTML) || "";
          var mm = h.match(/"video_url"\s*:\s*"([^"]+)"/);
          if (mm) return mm[1];
          var mm2 = h.match(/"playable_url"\s*:\s*"([^"]+)"/);
          if (mm2) return mm2[1];
          var body = (document.body && document.body.innerText) || "";
          if (body.indexOf("Log in to continue") >= 0 || body.indexOf("Log in to see") >= 0) return "PRIVATE";
          return "";
        })()
    """

    suspend fun extractVideoUrl(context: Context, pageUrl: String): WebViewOutcome {
        return withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    var webView: WebView? = null
                    var finished = false

                    fun cleanup() {
                        val w = webView
                        webView = null
                        if (w != null) {
                            runCatching { w.stopLoading() }
                            runCatching { w.destroy() }
                        }
                        // Nothing is kept between fetches: cookies and Web
                        // Storage are wiped. The next fetch starts clean.
                        runCatching { CookieManager.getInstance().removeAllCookies(null) }
                        runCatching { CookieManager.getInstance().removeSessionCookies(null) }
                        runCatching { WebStorage.getInstance().deleteAllData() }
                    }

                    fun complete(outcome: WebViewOutcome) {
                        if (finished) return
                        finished = true
                        cleanup()
                        cont.resume(outcome)
                    }

                    cont.invokeOnCancellation { cleanup() }

                    try {
                        val w = WebView(context.applicationContext)
                        webView = w
                        w.settings.javaScriptEnabled = true
                        w.settings.domStorageEnabled = true
                        // Deliberately NO custom User-Agent and NO login of
                        // any kind: the WebView's own default mobile
                        // User-Agent is what makes the platform serve the
                        // page like a browser.
                        w.webViewClient = object : WebViewClient() {
                            var extracted = false

                            override fun onPageFinished(view: WebView, url: String) {
                                if (extracted) return
                                // Login redirect = private reel, no need to wait.
                                if (url.contains("/accounts/login/")) {
                                    extracted = true
                                    complete(WebViewOutcome.Private)
                                    return
                                }
                                extracted = true
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (finished) return@postDelayed
                                    view.evaluateJavascript(JS_EXTRACT) { raw ->
                                        complete(parseJsResult(raw))
                                    }
                                }, SETTLE_MS)
                            }
                        }
                        w.loadUrl(pageUrl)
                    } catch (e: Exception) {
                        complete(WebViewOutcome.NotFound)
                    }
                }
            }
        } ?: WebViewOutcome.NotFound
    }

    /**
     * evaluateJavascript returns a JS string as a JSON-encoded string
     * (quoted, slashes escaped) — or null. Unwraps it to the raw value.
     */
    private fun parseJsResult(raw: String?): WebViewOutcome {
        if (raw.isNullOrBlank() || raw == "null") return WebViewOutcome.NotFound
        val value = raw
            .trim()
            .removeSurrounding("\"")
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .trim()
        return when {
            value == "PRIVATE" -> WebViewOutcome.Private
            value.startsWith("http") && (value.contains(".mp4") || value.contains("video")) ->
                WebViewOutcome.Found(value)
            value.isBlank() -> WebViewOutcome.NotFound
            else -> WebViewOutcome.NotFound
        }
    }
}
