package app.dms

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible

/**
 * Full-screen viewer for posts, reels, stories and profiles shared in a chat.
 * Uses its own WebView; cookies are shared app-wide so you stay logged in.
 * Closing it returns to the same chat underneath.
 */
class ViewerActivity : BaseWebActivity() {

    private lateinit var progress: ProgressBar
    private lateinit var titleView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)?.let(Uri::parse)
        if (url == null || (url.scheme != "https" && url.scheme != "http")) {
            finish()
            return
        }

        setContentView(R.layout.activity_viewer)
        progress = findViewById(R.id.progress)
        titleView = findViewById(R.id.viewer_title)
        applySystemInsets(findViewById(R.id.content))
        findViewById<View>(R.id.close_button).setOnClickListener { finish() }

        initBase(findViewById(R.id.webview), findViewById(R.id.fullscreen))
        webView.webViewClient = ViewerClient()
        webView.webChromeClient = object : BaseChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.isVisible = newProgress < 100
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                titleView.text = title?.takeUnless { it.startsWith("http") }.orEmpty()
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message) =
                captureNewWindow(resultMsg) { uri -> if (!handleNavigation(uri)) webView.loadUrl(uri.toString()) }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    exitFullscreen() -> Unit
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })

        val restored = savedInstanceState != null && webView.restoreState(savedInstanceState) != null
        if (!restored) {
            // CDN media opens fine directly; everything else goes through the policy first.
            if (!handleNavigation(url)) webView.loadUrl(url.toString())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (isWebViewReady) webView.saveState(outState)
    }

    /** Returns true if handled here (should NOT load in this viewer). */
    private fun handleNavigation(uri: Uri): Boolean {
        handleSpecialScheme(uri) { fallback -> if (!handleNavigation(fallback)) webView.loadUrl(fallback.toString()) }
            ?.let { return it }
        unwrapShim(uri)?.let { real ->
            if (!handleNavigation(real)) webView.loadUrl(real.toString())
            return true
        }
        return when (UrlPolicy.decideInViewer(uri.host, uri.path)) {
            UrlPolicy.ViewerAction.ALLOW -> false
            UrlPolicy.ViewerAction.OPEN_IN_MAIN -> { openInMain(uri); true }
            UrlPolicy.ViewerAction.CLOSE -> { finish(); true }
            UrlPolicy.ViewerAction.EXTERNAL -> { openExternal(uri); true }
        }
    }

    /** e.g. tapping "Message" on a profile: open that chat in the main window. */
    private fun openInMain(uri: Uri) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(uri)
        )
        finish()
    }

    private inner class ViewerClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            request.isForMainFrame && handleNavigation(request.url)

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            progress.isVisible = true
        }

        override fun onPageFinished(view: WebView, url: String?) = injectAppFeel(view)

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            val uri = url?.let(Uri::parse) ?: return
            if (uri.scheme != "https" && uri.scheme != "http") return
            when (UrlPolicy.decideInViewer(uri.host, uri.path)) {
                UrlPolicy.ViewerAction.OPEN_IN_MAIN -> view.post { openInMain(uri) }
                UrlPolicy.ViewerAction.CLOSE -> view.post { finish() }
                else -> Unit
            }
        }
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
