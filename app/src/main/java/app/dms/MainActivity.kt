package app.dms

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import java.io.File

class MainActivity : BaseWebActivity() {

    private lateinit var progress: ProgressBar
    private lateinit var offlineView: View
    private lateinit var unreadBadge: TextView
    private lateinit var popupContainer: View
    private lateinit var popupHolder: FrameLayout
    private lateinit var prefs: SharedPreferences

    private var popupWebView: WebView? = null
    private var loadHadError = false
    private var unreadCount = -1
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val redirectTimes = ArrayDeque<Long>()

    // File chooser state
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoFile: File? = null
    private var cameraPhotoUri: Uri? = null
    private var cameraVideoFile: File? = null
    private var cameraVideoUri: Uri? = null
    private var cameraHintShown = false

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            deliverChosenFiles(it.resultCode, it.data)
        }

    private val hideNav get() = prefs.getBoolean(PREF_HIDE_NAV, true)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)

        progress = findViewById(R.id.progress)
        offlineView = findViewById(R.id.offline)
        unreadBadge = findViewById(R.id.unread_badge)
        popupContainer = findViewById(R.id.popup_container)
        popupHolder = findViewById(R.id.popup_holder)
        applySystemInsets(findViewById(R.id.content))

        initBase(findViewById(R.id.webview), findViewById(R.id.fullscreen))
        webView.webViewClient = MainClient()
        webView.webChromeClient = MainChrome()

        findViewById<ImageButton>(R.id.menu_button).setOnClickListener(::showMenu)
        findViewById<View>(R.id.retry).setOnClickListener { retry() }
        findViewById<View>(R.id.popup_close).setOnClickListener { closePopup() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })

        cleanupOldCaptures()

        val restored = savedInstanceState != null && webView.restoreState(savedInstanceState) != null
        if (!restored) webView.loadUrl(deepLinkUrl(intent) ?: UrlPolicy.INBOX_URL)

        registerNetworkCallback()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLinkUrl(intent)?.let {
            closePopup()
            webView.loadUrl(it)
        }
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        NotificationRelayService.clearAll(this)
        NotificationRelayService.rebind(this)
    }

    override fun onPause() {
        isInForeground = false
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        closePopup()
        networkCallback?.let {
            try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (e: Exception) {}
        }
        super.onDestroy()
    }

    private fun onBack() {
        val popup = popupWebView
        when {
            exitFullscreen() -> Unit
            popup != null -> if (popup.canGoBack()) popup.goBack() else closePopup()
            webView.canGoBack() -> webView.goBack()
            else -> moveTaskToBack(true) // minimize, keep the session alive
        }
    }

    /** ig.me links and instagram.com/direct/... links opened from other apps. */
    private fun deepLinkUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val data = intent.data ?: return null
        if (data.scheme != "https" && data.scheme != "http") return null
        val host = data.host?.lowercase() ?: return null
        val ok = host == "ig.me" || host == "www.ig.me" ||
            (UrlPolicy.isPathRuledInstagramHost(host) && data.path.orEmpty().startsWith("/direct"))
        return if (ok) data.buildUpon().scheme("https").build().toString() else null
    }

    // ---- Navigation ------------------------------------------------------------------------

    /** Returns true if the navigation was handled here (i.e. should NOT load in [target]). */
    private fun handleNavigation(target: WebView, uri: Uri): Boolean {
        handleSpecialScheme(uri) { fallback -> if (!handleNavigation(webView, fallback)) webView.loadUrl(fallback.toString()) }
            ?.let { return it }
        unwrapShim(uri)?.let { real ->
            if (!handleNavigation(target, real)) target.loadUrl(real.toString())
            return true
        }
        return when (UrlPolicy.decide(uri.host, uri.path)) {
            UrlPolicy.Action.ALLOW -> false
            UrlPolicy.Action.INBOX -> {
                if (target !== webView) closePopup()
                webView.loadUrl(UrlPolicy.INBOX_URL)
                true
            }
            UrlPolicy.Action.VIEWER -> { openViewer(uri.toString()); true }
            UrlPolicy.Action.EXTERNAL -> { openExternal(uri); true }
        }
    }

    override fun onBridgeOpen(uri: Uri) {
        if (!handleNavigation(webView, uri)) webView.loadUrl(uri.toString())
    }

    /** Stops runaway redirect loops if Instagram ever keeps bouncing us. */
    private fun allowRedirect(): Boolean {
        val now = System.currentTimeMillis()
        while (redirectTimes.isNotEmpty() && now - redirectTimes.first() > 10_000) redirectTimes.removeFirst()
        if (redirectTimes.size >= 4) return false
        redirectTimes.addLast(now)
        return true
    }

    private fun injectPageScripts(view: WebView) {
        val host = view.url?.let { Uri.parse(it).host }
        if (!UrlPolicy.isPathRuledInstagramHost(host)) return
        view.evaluateJavascript(Scripts.hideNav(hideNav), null)
        view.evaluateJavascript(Scripts.LINK_INTERCEPTOR, null)
    }

    private inner class MainClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            return handleNavigation(view, request.url)
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            loadHadError = false
            progress.isVisible = true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (!loadHadError) offlineView.isVisible = false
            injectPageScripts(view)
        }

        // Instagram navigates with pushState, which never hits shouldOverrideUrlLoading.
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            val uri = url?.let(Uri::parse) ?: return
            if (uri.scheme != "https" && uri.scheme != "http") return
            when (UrlPolicy.decide(uri.host, uri.path)) {
                UrlPolicy.Action.INBOX -> if (allowRedirect()) view.post { view.loadUrl(UrlPolicy.INBOX_URL) }
                UrlPolicy.Action.VIEWER -> if (allowRedirect()) {
                    openViewer(url)
                    view.post { if (view.canGoBack()) view.goBack() else view.loadUrl(UrlPolicy.INBOX_URL) }
                }
                else -> injectPageScripts(view)
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            when (error.errorCode) {
                ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_TIMEOUT, ERROR_IO, ERROR_PROXY_AUTHENTICATION,
                ERROR_FAILED_SSL_HANDSHAKE, ERROR_UNKNOWN -> {
                    loadHadError = true
                    offlineView.isVisible = true
                }
            }
        }
    }

    private inner class MainChrome : BaseChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
            progress.isVisible = newProgress < 100
        }

        override fun onReceivedTitle(view: WebView, title: String?) = updateUnread(title)

        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean = showFileChooser(callback, params)

        override fun onPermissionRequest(request: PermissionRequest) = handleWebPermission(request)

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean =
            openPopup(resultMsg)
    }

    // ---- Popups (window.open, e.g. calls) --------------------------------------------------

    private fun openPopup(resultMsg: Message): Boolean {
        closePopup()
        val popup = WebView(this)
        configureWebView(popup)
        popup.webViewClient = object : WebViewClient() {
            private var shown = false

            private fun route(view: WebView, uri: Uri): Boolean {
                if (handleNavigation(view, uri)) {
                    if (!shown) closePopup()
                    return true
                }
                if (!shown) {
                    shown = true
                    showPopup(view)
                }
                return false
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && route(view, request.url)

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!shown && url != null && url != "about:blank" && route(view, Uri.parse(url))) view.stopLoading()
            }
        }
        popup.webChromeClient = object : BaseChromeClient() {
            override fun onCloseWindow(window: WebView) = closePopup()
            override fun onPermissionRequest(request: PermissionRequest) = handleWebPermission(request)
        }
        popupWebView = popup
        (resultMsg.obj as WebView.WebViewTransport).webView = popup
        resultMsg.sendToTarget()
        return true
    }

    private fun showPopup(popup: WebView) {
        if (popup !== popupWebView || popup.parent != null) return
        popupHolder.addView(popup, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        popupContainer.isVisible = true
    }

    private fun closePopup() {
        val popup = popupWebView ?: return
        popupWebView = null
        popupHolder.removeView(popup)
        popupContainer.isVisible = false
        popup.post { popup.destroy() }
    }

    // ---- Permissions for voice messages & calls --------------------------------------------

    private fun handleWebPermission(request: PermissionRequest) {
        val origin = request.origin
        if (origin?.scheme != "https" || !UrlPolicy.isInstagramHost(origin.host)) {
            request.deny()
            return
        }
        val wanted = request.resources.filter {
            it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
        }
        if (wanted.isEmpty()) {
            request.deny()
            return
        }
        val perms = buildSet {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in wanted) {
                add(Manifest.permission.RECORD_AUDIO)
                add(Manifest.permission.MODIFY_AUDIO_SETTINGS)
            }
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in wanted) add(Manifest.permission.CAMERA)
        }
        withPermissions(perms) { granted ->
            val ok = wanted.filter {
                (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE && Manifest.permission.RECORD_AUDIO in granted) ||
                    (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE && Manifest.permission.CAMERA in granted)
            }
            try {
                if (ok.isEmpty()) request.deny() else request.grant(ok.toTypedArray())
            } catch (e: Exception) {
                // The page may have cancelled the request meanwhile.
            }
            if (ok.size < wanted.size) {
                toast(if (Manifest.permission.RECORD_AUDIO !in granted) R.string.mic_denied else R.string.camera_denied)
            }
        }
    }

    // ---- File chooser (send photos/videos) -------------------------------------------------

    private fun showFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = callback
        withPermissions(listOf(Manifest.permission.CAMERA)) { granted ->
            val camera = Manifest.permission.CAMERA in granted
            if (!camera && !cameraHintShown) {
                cameraHintShown = true
                toast(R.string.camera_off_gallery_only)
            }
            launchFileChooser(params, camera)
        }
        return true
    }

    private fun acceptedMimeTypes(params: WebChromeClient.FileChooserParams): List<String> {
        val mimes = params.acceptTypes.orEmpty()
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .mapNotNull { t ->
                if (t.startsWith(".")) MimeTypeMap.getSingleton().getMimeTypeFromExtension(t.drop(1)) else t
            }
            .distinct()
        return if (mimes.isEmpty() || "*/*" in mimes) listOf("image/*", "video/*") else mimes
    }

    private fun launchFileChooser(params: WebChromeClient.FileChooserParams, cameraAllowed: Boolean) {
        if (filePathCallback == null) return
        val mimes = acceptedMimeTypes(params)
        val gallery = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (mimes.size == 1) mimes[0] else "*/*"
            if (mimes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
        }

        cameraPhotoFile = null; cameraPhotoUri = null; cameraVideoFile = null; cameraVideoUri = null
        val cameraIntents = mutableListOf<Intent>()
        if (cameraAllowed) {
            if (mimes.any { it.startsWith("image/") }) {
                captureIntent(MediaStore.ACTION_IMAGE_CAPTURE, ".jpg")?.let { (intent, file, uri) ->
                    cameraPhotoFile = file; cameraPhotoUri = uri; cameraIntents += intent
                }
            }
            if (mimes.any { it.startsWith("video/") }) {
                captureIntent(MediaStore.ACTION_VIDEO_CAPTURE, ".mp4")?.let { (intent, file, uri) ->
                    cameraVideoFile = file; cameraVideoUri = uri; cameraIntents += intent
                }
            }
        }

        val chooser = Intent.createChooser(gallery, getString(R.string.choose_media)).apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, cameraIntents.toTypedArray())
        }
        try {
            fileChooserLauncher.launch(chooser)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.no_app_for_link)
            deliverChosenFiles(RESULT_CANCELED, null)
        }
    }

    private fun captureIntent(action: String, suffix: String): Triple<Intent, File, Uri>? = try {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val file = File.createTempFile("capture_", suffix, dir)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(action).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Triple(intent, file, uri)
    } catch (e: Exception) {
        null
    }

    /** Always answers the WebView callback (null on cancel) so the picker never gets stuck. */
    private fun deliverChosenFiles(resultCode: Int, data: Intent?) {
        val callback = filePathCallback ?: return
        filePathCallback = null
        var result: Array<Uri>? = null
        if (resultCode == RESULT_OK) {
            val uris = mutableListOf<Uri>()
            data?.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add)
            }
            if (uris.isEmpty()) data?.data?.let(uris::add)
            if (uris.isEmpty()) {
                when {
                    (cameraPhotoFile?.length() ?: 0L) > 0L -> cameraPhotoUri?.let(uris::add)
                    (cameraVideoFile?.length() ?: 0L) > 0L -> cameraVideoUri?.let(uris::add)
                }
            }
            if (uris.isNotEmpty()) result = uris.toTypedArray()
        }
        try {
            callback.onReceiveValue(result)
        } catch (e: Exception) {
            // WebView already gave up on this chooser.
        }
    }

    private fun cleanupOldCaptures() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        File(cacheDir, "camera").listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    // ---- Unread badge ----------------------------------------------------------------------

    private fun updateUnread(title: String?) {
        val count = TitleParser.unreadCount(title)
        if (count == unreadCount) return
        unreadCount = count
        unreadBadge.isVisible = count > 0
        unreadBadge.text = if (count > 99) "99+" else count.toString()
        unreadBadge.contentDescription = getString(R.string.unread_description, count)
        val label = if (count > 0) getString(R.string.app_name_with_count, count) else getString(R.string.app_name)
        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription(label))
    }

    // ---- Offline / retry -------------------------------------------------------------------

    private fun retry() {
        offlineView.isVisible = false
        if (webView.url.isNullOrEmpty()) webView.loadUrl(UrlPolicy.INBOX_URL) else webView.reload()
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { if (!isDestroyed && offlineView.isVisible) retry() }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Exception) {
            // Not critical: the Retry button still works.
        }
    }

    // ---- Menu ------------------------------------------------------------------------------

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, MENU_INBOX, 0, R.string.menu_inbox)
        menu.menu.add(0, MENU_RELOAD, 1, R.string.menu_reload)
        menu.menu.add(0, MENU_HIDE_NAV, 2, R.string.menu_hide_nav).apply {
            isCheckable = true
            isChecked = hideNav
        }
        menu.menu.add(0, MENU_NOTIFICATIONS, 3, R.string.menu_notifications)
        menu.menu.add(0, MENU_ABOUT, 4, R.string.menu_about)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_INBOX -> webView.loadUrl(UrlPolicy.INBOX_URL)
                MENU_RELOAD -> retry()
                MENU_HIDE_NAV -> {
                    prefs.edit().putBoolean(PREF_HIDE_NAV, !hideNav).apply()
                    injectPageScripts(webView)
                }
                MENU_NOTIFICATIONS -> showNotificationSetup()
                MENU_ABOUT -> AlertDialog.Builder(this)
                    .setTitle(R.string.app_name)
                    .setMessage(R.string.about_text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            true
        }
        menu.show()
    }

    // ---- Message notifications (notification-access relay) ---------------------------------

    private fun showNotificationSetup() {
        val access = NotificationRelayService.isAccessGranted(this)
        val post = NotificationRelayService.canPost(this)
        val running = NotificationRelayService.isRunning
        val diag = NotificationRelayService.diagnostics(this)
        val status = buildString {
            append(
                getString(
                    when {
                        !access -> R.string.notif_status_off
                        !post -> R.string.notif_status_blocked
                        !running -> R.string.notif_status_not_running
                        else -> R.string.notif_status_on
                    }
                )
            )
            if (access) {
                append("\n\n")
                append(getString(R.string.notif_diag_running, getString(if (running) R.string.yes else R.string.no)))
                append("\n")
                append(getString(R.string.notif_diag_seen, diag.seen))
                if (diag.lastSeenAt > 0L) {
                    append("\n")
                    append(
                        getString(
                            R.string.notif_diag_last,
                            android.text.format.DateUtils.getRelativeTimeSpanString(diag.lastSeenAt),
                            diag.lastOutcome.orEmpty(),
                        )
                    )
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_notifications)
            .setMessage(status + "\n\n" + getString(R.string.notif_setup_text))
            .setPositiveButton(R.string.notif_open_access) { _, _ -> openNotificationAccess() }
            .setNeutralButton(R.string.notif_app_info) { _, _ ->
                startSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openNotificationAccess() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }
        withPermissions(perms) {
            if (!NotificationRelayService.canPost(this)) toast(R.string.notif_permission_denied)
            NotificationRelayService.ensureChannel(this)
            if (!startSettings(NotificationRelayService.accessSettingsIntent(this))) {
                startSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
    }

    private fun startSettings(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    companion object {
        /** Read by the notification relay so it stays quiet while the inbox is on screen. */
        @Volatile
        var isInForeground = false
            private set

        private const val PREF_HIDE_NAV = "hide_nav"
        private const val MENU_INBOX = 1
        private const val MENU_RELOAD = 2
        private const val MENU_HIDE_NAV = 3
        private const val MENU_NOTIFICATIONS = 4
        private const val MENU_ABOUT = 5
    }
}
