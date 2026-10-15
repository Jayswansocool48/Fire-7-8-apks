package io.github.jayswansocool48.codeling

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
        web.webViewClient = AssetClient()
        web.webChromeClient = WebChromeClient()
        setContentView(web)

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(START_URL)
        }
        warnIfWebViewIsOld()
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
