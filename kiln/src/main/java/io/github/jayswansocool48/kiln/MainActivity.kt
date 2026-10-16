package io.github.jayswansocool48.kiln

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File

/**
 * Kiln for Android tablets (built and sized for the Fire HD 8, 8th gen, on LineageOS 21).
 *
 * The same web app as the desktop version runs in a WebView. A small server inside the app
 * (KilnServer) answers the same /api calls the desktop server.py does, so boards, the file library,
 * the 3D viewer and the assistant all work offline. Your data lives in the app's private storage.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var server: KilnServer
    private var pickFiles: ValueCallback<Array<Uri>>? = null

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF1B1B1F.toInt()
        window.navigationBarColor = 0xFF1B1B1F.toInt()

        server = KilnServer(assets, File(filesDir, "Kiln").apply { mkdirs() }) { runOnUiThread { askUninstall() } }
        server.start(NanoHTTPDTimeout, false)

        // Debug builds can be inspected from a computer (chrome://inspect); used by the emulator test in CI.
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
        web = WebView(this)
        web.setBackgroundColor(0xFF1B1B1F.toInt())
        web.overScrollMode = View.OVER_SCROLL_NEVER
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            textZoom = 100
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            userAgentString = "$userAgentString KilnAndroid/${BuildConfig.VERSION_NAME}"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        setContentView(web)

        web.loadUrl("http://127.0.0.1:${server.listeningPort}/?k=${server.token}")
        warnIfWebViewIsOld()
    }

    override fun onResume() { super.onResume(); web.onResume() }

    override fun onPause() {
        web.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        server.stop()
        super.onDestroy()
    }

    @Deprecated("Still the simplest way to handle Back on Android 7")
    override fun onBackPressed() {
        // Back closes the 3D viewer, a dialog or a menu first (the page's Escape key), then leaves the app.
        web.evaluateJavascript("window.kilnBack ? window.kilnBack() : false") { handled ->
            if (handled != "true") leave()
        }
    }

    @Suppress("DEPRECATION")
    private fun leave() = super.onBackPressed()

    @Deprecated("Activity result API needs AndroidX; this keeps the app dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == PICK) {
            val cb = pickFiles; pickFiles = null
            if (cb == null) return
            if (resultCode != RESULT_OK || data == null) { cb.onReceiveValue(null); return }
            val uris = data.clipData?.let { c -> Array(c.itemCount) { c.getItemAt(it).uri } } ?: data.data?.let { arrayOf(it) }
            cb.onReceiveValue(uris)
            return
        }
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
    }

    private fun askUninstall() {
        try {
            @Suppress("DEPRECATION")
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    /** Kiln's 3D viewer needs WebGL 2 and modern JavaScript, so very old WebViews get a warning. */
    private fun warnIfWebViewIsOld() {
        val ua = try { WebSettings.getDefaultUserAgent(this) } catch (e: Exception) { return }
        val version = Regex("Chrome/(\\d+)").find(ua)?.groupValues?.get(1)?.toIntOrNull() ?: return
        if (version >= 89) return
        val prefs = getSharedPreferences("kiln-app", MODE_PRIVATE)
        val key = "warned-$version"
        if (prefs.getBoolean(key, false)) return
        prefs.edit().putBoolean(key, true).apply()
        AlertDialog.Builder(this)
            .setTitle("Older web engine")
            .setMessage(
                "This tablet's Android System WebView is version $version. Kiln needs version 89 or newer " +
                    "for the 3D viewer. Update Android System WebView (on LineageOS it updates with the system, " +
                    "or install it from F-Droid/Aurora Store)."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = open(request.url)

        @Deprecated("Needed for older WebViews")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = open(Uri.parse(url))

        /** Kiln's own pages stay inside; links to websites (like ollama.com) open in the browser. */
        private fun open(uri: Uri): Boolean {
            if (uri.host == "127.0.0.1") return false
            if (uri.scheme in setOf("http", "https", "mailto")) {
                try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (e: ActivityNotFoundException) { /* no browser */ }
                return true
            }
            return false
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            pickFiles?.onReceiveValue(null)
            pickFiles = callback
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*" // .glb and .blend files have no Android mime type, so allow everything
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
            }
            return try {
                @Suppress("DEPRECATION") startActivityForResult(intent, PICK); true
            } catch (e: ActivityNotFoundException) {
                pickFiles = null; false
            }
        }
    }

    companion object {
        private const val PICK = 41
        private const val NanoHTTPDTimeout = 60_000
    }
}
