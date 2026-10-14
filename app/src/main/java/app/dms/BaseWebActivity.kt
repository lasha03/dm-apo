package app.dms

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Message
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.max

/**
 * Shared WebView plumbing for the inbox and the viewer: settings, cookies,
 * runtime permissions, full-screen video, long-press "Save" and downloads.
 */
abstract class BaseWebActivity : ComponentActivity() {

    protected lateinit var webView: WebView
    protected lateinit var fullscreenContainer: FrameLayout
    protected lateinit var mediaSaver: MediaSaver

    protected val isWebViewReady get() = ::webView.isInitialized

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private var pendingPermissionCallback: (() -> Unit)? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val cb = pendingPermissionCallback
            pendingPermissionCallback = null
            cb?.invoke()
        }

    protected fun initBase(webView: WebView, fullscreenContainer: FrameLayout) {
        this.webView = webView
        this.fullscreenContainer = fullscreenContainer
        mediaSaver = MediaSaver(this)
        configureWebView(webView)
    }

    // ---- WebView setup -------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    protected fun configureWebView(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
            userAgentString = cleanUserAgent(userAgentString)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            // Only kicks in when the app theme is dark (values-night), i.e. it follows the system.
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, true)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        // Look like an app, not a browser.
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        addDocumentStartScript(wv, Scripts.APP_FEEL)

        wv.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                lastTouchX = ev.x
                lastTouchY = ev.y
            }
            false
        }
        wv.setOnLongClickListener { handleLongPress(wv) }
        wv.setDownloadListener { url, userAgent, _, mimeType, _ ->
            saveMedia(wv, url, mimeType, userAgent)
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // Only Instagram pages can talk to the app.
            WebViewCompat.addWebMessageListener(wv, "DMsBridge", INSTAGRAM_ORIGINS) { _, message, _, isMainFrame, _ ->
                if (isMainFrame) message.data?.let { onBridgeMessage(it) }
            }
        }
    }

    /**
     * Runs [script] on Instagram pages before they render (no flash of unstyled UI).
     * Returns null where the WebView is too old; callers also inject after load.
     */
    protected fun addDocumentStartScript(wv: WebView, script: String): ScriptHandler? {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return null
        return try {
            WebViewCompat.addDocumentStartJavaScript(wv, script, INSTAGRAM_ORIGINS)
        } catch (e: Exception) {
            null
        }
    }

    /** Fallback for WebViews without document-start scripts; harmless to repeat. */
    protected fun injectAppFeel(wv: WebView) {
        val host = wv.url?.let { Uri.parse(it).host }
        if (UrlPolicy.isPathRuledInstagramHost(host)) wv.evaluateJavascript(Scripts.APP_FEEL, null)
    }

    private fun cleanUserAgent(ua: String): String =
        ua.replace("; wv", "").replace(Regex("""Version/[\d.]+\s"""), "")

    // ---- JS bridge -------------------------------------------------------------------------

    private fun onBridgeMessage(raw: String) {
        val msg = try { JSONObject(raw) } catch (e: Exception) { return }
        when (msg.optString("t")) {
            "data" -> msg.optString("d").takeIf { it.startsWith("data:") }?.let { saveWithPermission { mediaSaver.saveDataUrl(it) } }
            "saveError" -> toast(R.string.save_streamed)
            "open" -> msg.optString("u").takeIf { it.startsWith("https://") }?.let { onBridgeOpen(Uri.parse(it)) }
        }
    }

    /** A link tap inside Instagram that the page script handed to us. */
    protected open fun onBridgeOpen(uri: Uri) {}

    // ---- Navigation helpers ----------------------------------------------------------------

    /** Common handling for non-http(s) schemes. Returns null if the URL is http(s). */
    protected fun handleSpecialScheme(uri: Uri, onFallback: (Uri) -> Unit): Boolean? {
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> null
            null, "about", "blob", "data", "javascript" -> false
            "intent" -> { openIntentUri(uri.toString(), onFallback); true }
            else -> {
                if (!UrlPolicy.isMetaAppLink(uri.scheme, null)) openExternal(uri)
                true
            }
        }
    }

    /** Unwraps l.instagram.com/?u=... link shims. */
    protected fun unwrapShim(uri: Uri): Uri? {
        if (!UrlPolicy.isLinkShim(uri.host)) return null
        val target = try { uri.getQueryParameter("u") } catch (e: Exception) { null } ?: return null
        val parsed = Uri.parse(target)
        return parsed.takeIf { it.scheme == "https" || it.scheme == "http" }
    }

    protected fun openExternal(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.no_app_for_link)
        } catch (e: SecurityException) {
            toast(R.string.no_app_for_link)
        }
    }

    private fun openIntentUri(raw: String, onFallback: (Uri) -> Unit) {
        val intent = try { Intent.parseUri(raw, Intent.URI_INTENT_SCHEME) } catch (e: Exception) { return }
        val fallback = intent.getStringExtra("browser_fallback_url")
        val fb = fallback?.let(Uri::parse)?.takeIf { it.scheme == "https" || it.scheme == "http" }
        val targetsMetaApp = UrlPolicy.isMetaAppLink(intent.scheme, intent.`package`) ||
            UrlPolicy.isMetaAppLink(intent.selector?.scheme, intent.selector?.`package`) ||
            UrlPolicy.isInstagramHost(intent.data?.host)
        if (targetsMetaApp) {
            // Stay in DMs: use the web version if the page offered one, else ignore.
            fb?.let(onFallback)
            return
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        try {
            startActivity(intent)
        } catch (e: Exception) {
            fb?.let(onFallback)
        }
    }

    protected fun openViewer(url: String) {
        val now = System.currentTimeMillis()
        if (url == lastViewerUrl && now - lastViewerAt < 1500) return
        lastViewerUrl = url
        lastViewerAt = now
        startActivity(Intent(this, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_URL, url))
    }

    private var lastViewerUrl: String? = null
    private var lastViewerAt = 0L

    /**
     * For a WebView without its own popup UI: capture the URL a window.open /
     * target=_blank wanted to load and hand it to [onUrl].
     */
    protected fun captureNewWindow(resultMsg: Message, onUrl: (Uri) -> Unit): Boolean {
        val temp = WebView(this)
        temp.webViewClient = object : WebViewClient() {
            private var done = false
            private fun take(view: WebView, url: Uri) {
                if (done) return
                done = true
                onUrl(url)
                view.post { view.destroy() }
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                take(view, request.url)
                return true
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (url != null && url != "about:blank") {
                    view.stopLoading()
                    take(view, Uri.parse(url))
                }
            }
        }
        (resultMsg.obj as WebView.WebViewTransport).webView = temp
        resultMsg.sendToTarget()
        return true
    }

    // ---- Insets ----------------------------------------------------------------------------

    /** Pads [view] for status/nav bars and the keyboard (edge-to-edge + adjustResize). */
    protected fun applySystemInsets(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
    }

    // ---- Permissions -----------------------------------------------------------------------

    protected fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** Requests what's missing, then calls [onResult] with the permissions now granted. */
    protected fun withPermissions(perms: Collection<String>, onResult: (Set<String>) -> Unit) {
        val granted = { perms.filter(::hasPermission).toSet() }
        val missing = perms.filterNot(::hasPermission)
        if (missing.isEmpty()) {
            onResult(granted())
            return
        }
        if (pendingPermissionCallback != null) {
            // Another request is already on screen; don't stack dialogs.
            onResult(granted())
            return
        }
        pendingPermissionCallback = { onResult(granted()) }
        try {
            permissionLauncher.launch(missing.toTypedArray())
        } catch (e: Exception) {
            pendingPermissionCallback = null
            onResult(granted())
        }
    }

    // ---- Full-screen video -----------------------------------------------------------------

    open inner class BaseChromeClient : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            fullscreenContainer.addView(
                view,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            fullscreenContainer.visibility = View.VISIBLE
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        override fun onHideCustomView() {
            exitFullscreen()
        }
    }

    /** Returns true if full-screen video was showing and has been closed. */
    protected fun exitFullscreen(): Boolean {
        val view = customView ?: return false
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        return true
    }

    // ---- Long-press save -------------------------------------------------------------------

    private fun handleLongPress(wv: WebView): Boolean {
        val hit = wv.hitTestResult
        val extra = hit.extra
        if ((hit.type == WebView.HitTestResult.IMAGE_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) &&
            extra != null && isSavable(extra)
        ) {
            showSaveDialog(wv, extra, isVideo = false)
            return true
        }
        // Instagram often covers media with overlay elements; ask the page what's under the finger.
        val density = resources.displayMetrics.density
        wv.evaluateJavascript(Scripts.mediaAtPoint(lastTouchX / density, lastTouchY / density)) { result ->
            val json = try { JSONTokener(result).nextValue() as? String } catch (e: Exception) { null }
            if (json.isNullOrEmpty()) return@evaluateJavascript
            val obj = try { JSONObject(json) } catch (e: Exception) { return@evaluateJavascript }
            val url = obj.optString("u")
            if (isSavable(url)) showSaveDialog(wv, url, isVideo = obj.optString("k") == "video")
        }
        return false
    }

    private fun isSavable(url: String) =
        url.startsWith("https://") || url.startsWith("http://") || url.startsWith("blob:") || url.startsWith("data:")

    private fun showSaveDialog(wv: WebView, url: String, isVideo: Boolean) {
        if (isFinishing) return
        val label = getString(if (isVideo) R.string.save_video else R.string.save_image)
        AlertDialog.Builder(this)
            .setItems(arrayOf(label)) { _, _ ->
                saveMedia(wv, url, if (isVideo) "video/mp4" else "image/jpeg", wv.settings.userAgentString)
            }
            .show()
    }

    protected fun saveMedia(wv: WebView, url: String, mimeHint: String?, userAgent: String?) {
        when {
            url.startsWith("blob:") -> {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    wv.evaluateJavascript(Scripts.fetchBlob(url), null)
                } else {
                    toast(R.string.save_failed)
                }
            }
            url.startsWith("data:") -> saveWithPermission { mediaSaver.saveDataUrl(url) }
            URLUtil.isHttpsUrl(url) || URLUtil.isHttpUrl(url) ->
                saveWithPermission { mediaSaver.download(url, mimeHint, userAgent ?: wv.settings.userAgentString) }
            else -> toast(R.string.save_failed)
        }
    }

    private fun saveWithPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            action()
            return
        }
        withPermissions(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) { granted ->
            if (Manifest.permission.WRITE_EXTERNAL_STORAGE in granted) action() else toast(R.string.storage_denied)
        }
    }

    // ---- Lifecycle -------------------------------------------------------------------------

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        if (::webView.isInitialized) webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    protected fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    companion object {
        private val INSTAGRAM_ORIGINS =
            setOf("https://www.instagram.com", "https://instagram.com", "https://m.instagram.com")
    }
}
