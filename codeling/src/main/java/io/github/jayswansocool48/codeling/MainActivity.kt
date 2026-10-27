package io.github.jayswansocool48.codeling

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Runs the Codeling web app from inside the APK, fully offline.
 *
 * Files are served from a private https address (appassets.androidplatform.net never reaches
 * the internet) so progress saved in localStorage stays put between launches.
 * Links to other sites open in the browser.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var ai: AiBridge
    private var backCallback: Any? = null // OnBackInvokedCallback on Android 13+
    private var backRegistered = false

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF5A38E0.toInt()

        web = WebView(this)
        web.setBackgroundColor(0xFFF4F6FF.toInt())
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            textZoom = 100
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        // The free AI tutor: Ollama on your computer over Wi-Fi, or a small model on the tablet.
        ai = AiBridge(this, web)
        web.addJavascriptInterface(ai, "CodelingAndroid")
        web.webViewClient = AssetClient()
        web.webChromeClient = WebChromeClient()
        setContentView(web)

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(START_URL)
        }
        warnIfWebViewIsOld()
        setUpPredictiveBack()
    }

    /**
     * Android 14 predictive back: while you drag the back gesture, the app slides its current screen
     * aside and shows where Back goes (window.__codelingBack in the web app). Releasing goes back;
     * letting go early springs the screen back. With nothing to go back to, Android's own
     * back-to-home preview takes over.
     */
    private fun setUpPredictiveBack() {
        if (Build.VERSION.SDK_INT >= 34) {
            backCallback = object : OnBackAnimationCallback {
                override fun onBackStarted(e: BackEvent) = backJs("start", 0f, e.swipeEdge)
                override fun onBackProgressed(e: BackEvent) = backJs("progress", e.progress, e.swipeEdge)
                override fun onBackCancelled() = backJs("cancel", 0f, 0)
                override fun onBackInvoked() {
                    backJs("commit", 1f, 0)
                    if (web.canGoBack()) web.goBack()
                }
            }
        } else if (Build.VERSION.SDK_INT >= 33) {
            backCallback = OnBackInvokedCallback { if (web.canGoBack()) web.goBack() }
        }
        // Android 12 and older use onBackPressed below
        syncBackCallback()
    }

    private fun backJs(phase: String, progress: Float, edge: Int) {
        web.evaluateJavascript("window.__codelingBack&&window.__codelingBack('$phase',$progress,$edge)", null)
    }

    private fun syncBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return
        val cb = backCallback as? OnBackInvokedCallback ?: return
        val want = web.canGoBack()
        if (want && !backRegistered) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        if (!want && backRegistered) onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
        backRegistered = want
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        web.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        ai.close()
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Still the simplest way to handle Back on Android 7")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    /** Older WebViews run the app but lack some layout features, so say so once per version. */
    private fun warnIfWebViewIsOld() {
        val ua = try { WebSettings.getDefaultUserAgent(this) } catch (e: Exception) { return }
        val version = Regex("Chrome/(\\d+)").find(ua)?.groupValues?.get(1)?.toIntOrNull() ?: return
        if (version >= 88) return
        val prefs = getSharedPreferences("codeling-app", MODE_PRIVATE)
        val key = "warned-$version"
        if (prefs.getBoolean(key, false)) return
        prefs.edit().putBoolean(key, true).apply()
        AlertDialog.Builder(this)
            .setTitle("Older web engine")
            .setMessage(
                "This tablet's Android System WebView is version $version. Codeling will run, " +
                    "but some screens may look a little cramped. Updating Android System WebView " +
                    "(version 88 or newer) makes everything look right."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private inner class AssetClient : WebViewClient() {
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            syncBackCallback()
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (url.host != HOST) return null
            var path = url.path ?: "/"
            if (path.isEmpty() || path.endsWith("/")) path += "index.html"
            // No service worker inside the app; the APK already works offline.
            if (path.endsWith("/sw.js")) return notFound()
            return try {
                val mime = mimeFor(path)
                val encoding = if (mime.startsWith("text/") || mime.endsWith("javascript") || mime.endsWith("json")) "utf-8" else null
                WebResourceResponse(mime, encoding, assets.open("web$path"))
            } catch (e: IOException) {
                notFound()
            }
        }

        @Deprecated("Needed for Android 7 and older")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            val uri = Uri.parse(url)
            if (uri.host == HOST) return false
            return when (uri.scheme) {
                "http", "https", "mailto" -> {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (e: ActivityNotFoundException) {
                        // No browser installed; ignore.
                    }
                    true
                }
                else -> false
            }
        }

        private fun notFound() =
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    private fun mimeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "application/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "webmanifest" -> "application/manifest+json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "woff2" -> "font/woff2"
        "wasm" -> "application/wasm"
        else -> "application/octet-stream"
    }

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val START_URL = "https://$HOST/index.html"
    }
}
