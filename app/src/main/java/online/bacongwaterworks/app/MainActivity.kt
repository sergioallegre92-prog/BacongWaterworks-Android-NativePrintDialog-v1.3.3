package online.bacongwaterworks.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.print.PrintJob
import android.print.PrintManager
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TRUSTED_HOST = "bacongwaterworks.online"
        private const val TRUSTED_BASE_URL = "https://bacongwaterworks.online/"
        private const val PRINT_CAPTURE_BRIDGE = "BacongPrintCapture"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var rootContainer: FrameLayout

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingCameraRequest: PermissionRequest? = null

    // Each print job gets its own real WebView. It stays alive through the system
    // print dialog and initial spool handoff, then is released so RAWBT/thermal
    // services can continue independently of the Bacong UI.
    private val activePrintViews = mutableSetOf<WebView>()

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileChooserCallback ?: return@registerForActivityResult
        val uris = if (result.resultCode == Activity.RESULT_OK) {
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        } else {
            null
        }
        callback.onReceiveValue(uris)
        fileChooserCallback = null
    }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
        pendingGeoOrigin = null
        pendingGeoCallback = null
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val request = pendingCameraRequest
        pendingCameraRequest = null
        if (request == null) return@registerForActivityResult

        if (granted) {
            val allowed = request.resources.filter {
                it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
            }.toTypedArray()
            if (allowed.isNotEmpty()) request.grant(allowed) else request.deny()
        } else {
            request.deny()
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Permission state is controlled by Android. Bacong continues normally
        // whether notifications are allowed or declined.
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Preserve the known-working Bacong app presentation.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = ContextCompat.getColor(this, R.color.bacong_blue)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.bacong_navy)
        setContentView(R.layout.activity_main)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        rootContainer = findViewById(R.id.rootContainer)

        installSystemAndKeyboardInsets()
        configurePersistentWebView()
        configureServiceWorker()
        requestOptionalNotificationPermissionOnce()

        if (savedInstanceState == null) {
            webView.loadUrl(getString(R.string.website_url))
        } else {
            webView.restoreState(savedInstanceState)
        }
    }


    private fun requestOptionalNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) return

        val prefs = getSharedPreferences("bacong_permissions", MODE_PRIVATE)
        if (prefs.getBoolean("notification_permission_prompted", false)) return
        prefs.edit().putBoolean("notification_permission_prompted", true).apply()

        // Delay until the main window is visible so Android's permission prompt
        // does not interfere with launch or the login screen rendering.
        webView.postDelayed({
            if (!isFinishing && !isDestroyed) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }, 1200)
    }

    private fun installSystemAndKeyboardInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { _, insets ->
            if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                webView.postDelayed({ ensureFocusedControlVisible() }, 120)
                webView.postDelayed({ ensureFocusedControlVisible() }, 320)
            }
            insets
        }
        ViewCompat.requestApplyInsets(rootContainer)
    }

    private fun updateStatusBarIconContrast() {
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false
    }

    private fun isTrustedUri(uri: Uri?): Boolean {
        if (uri == null || uri.scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return host == TRUSTED_HOST || host.endsWith(".$TRUSTED_HOST")
    }

    private fun openExternal(uri: Uri) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun configurePersistentWebView() {
        WebView.setWebContentsDebuggingEnabled(false)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // The bridge receives only an already-completed printable HTML document.
        // The website remains authoritative for bill data and 58mm print CSS.
        webView.addJavascriptInterface(PrintCaptureBridge(), PRINT_CAPTURE_BRIDGE)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                return when {
                    isTrustedUri(uri) -> false
                    uri.scheme == "http" || uri.scheme == "https" -> {
                        openExternal(uri)
                        true
                    }
                    else -> {
                        openExternal(uri)
                        true
                    }
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectKeyboardSafetyHelper()
                injectPrintCaptureHelper()
                updateStatusBarIconContrast()
            }
        }

        webView.webChromeClient = createMainWebChromeClient()

        webView.setDownloadListener { url, _, _, _, _ ->
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }

    private fun createMainWebChromeClient(): WebChromeClient {
        return object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                val fine = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                val coarse = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                if (fine || coarse) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    locationPermission.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val origin = runCatching { Uri.parse(request.origin.toString()) }.getOrNull()
                if (!isTrustedUri(origin)) {
                    request.deny()
                    return
                }

                // Grant only camera/video capture. Microphone and other WebView
                // resources remain denied unless Bacong explicitly needs them later.
                val wantsCamera = request.resources.any {
                    it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                }
                if (!wantsCamera) {
                    request.deny()
                    return
                }

                val granted = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED

                if (granted) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                } else {
                    pendingCameraRequest?.deny()
                    pendingCameraRequest = request
                    cameraPermission.launch(Manifest.permission.CAMERA)
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                return runCatching {
                    val intent = fileChooserParams?.createIntent()
                        ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                    filePicker.launch(intent)
                    true
                }.getOrElse {
                    fileChooserCallback = null
                    false
                }
            }

            // Normal non-print popups still work. Bacong's blank receipt popup is
            // captured synchronously by the injected window.open wrapper before
            // WebChromeClient is involved, eliminating the old popup timing race.
            @SuppressLint("SetJavaScriptEnabled")
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                if (resultMsg == null) return false

                val popup = WebView(this@MainActivity)
                with(popup.settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    loadsImagesAutomatically = true
                    javaScriptCanOpenWindowsAutomatically = true
                    setSupportMultipleWindows(false)
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }

                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val uri = request.url
                        if (uri.scheme == "about" || isTrustedUri(uri)) return false
                        openExternal(uri)
                        closeOrdinaryPopup(view)
                        return true
                    }
                }

                popup.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) {
                        window?.let { closeOrdinaryPopup(it) }
                    }
                }

                // Keep ordinary popups invisible inside the wrapper. They are only
                // a compatibility fallback; printing does not use this path.
                popup.visibility = View.GONE
                rootContainer.addView(
                    popup,
                    FrameLayout.LayoutParams(1, 1)
                )

                val transport = resultMsg.obj as? WebView.WebViewTransport ?: run {
                    closeOrdinaryPopup(popup)
                    return false
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                window?.let { closeOrdinaryPopup(it) }
            }
        }
    }

    private fun configureServiceWorker() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
            val controller = ServiceWorkerControllerCompat.getInstance()
            controller.setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest) = null
            })
        }
    }

    /**
     * Reproduces the website's successful browser print flow without asking the
     * site to change:
     *
     *   window.open('', '_blank')
     *   -> document.open/write/close
     *   -> Android standard print dialog
     *
     * The full generated HTML (including @page { size: 58mm ... }) is kept intact.
     * We do not rasterize it, convert it to ESC/POS, or force any printer settings.
     */
    private fun injectPrintCaptureHelper() {
        val script = """
            (function () {
              try {
                var bridge = window.$PRINT_CAPTURE_BRIDGE;
                if (!bridge) return;

                // React navigation can keep the same Window alive, so reinstall
                // only if this exact helper is not active yet.
                if (window.__bacongPrintCaptureV132) return;
                window.__bacongPrintCaptureV132 = true;

                var nativeOpen = (typeof window.open === 'function')
                  ? window.open.bind(window) : null;
                var nativePrint = (typeof window.print === 'function')
                  ? window.print.bind(window) : null;

                function safeTitleFromHtml(html) {
                  try {
                    var match = String(html || '').match(/<title[^>]*>([\s\S]*?)<\/title>/i);
                    if (!match || !match[1]) return 'Bacong Waterworks Bill';
                    return match[1].replace(/<[^>]+>/g, '').replace(/&amp;/g, '&').trim() ||
                      'Bacong Waterworks Bill';
                  } catch (_) {
                    return 'Bacong Waterworks Bill';
                  }
                }

                function sendHtml(html, title) {
                  try {
                    html = String(html || '');
                    if (html.length < 40) return false;

                    // Send in small chunks. Bacong receipts embed the municipal
                    // logo as base64, so a complete receipt can be large. Chunking
                    // avoids Android JS-bridge/Binder transaction-size failures.
                    bridge.beginPrint(String(title || 'Bacong Waterworks Bill'));
                    var chunkSize = 32768;
                    for (var i = 0; i < html.length; i += chunkSize) {
                      bridge.appendPrintChunk(html.substring(i, i + chunkSize));
                    }
                    bridge.finishPrint();
                    return true;
                  } catch (_) {
                    try { bridge.cancelPrint(); } catch (_) {}
                    return false;
                  }
                }

                // Support a direct window.print() if Bacong ever prints the current
                // page instead of using a receipt popup.
                window.print = function () {
                  try {
                    var html = '<!doctype html>' + document.documentElement.outerHTML;
                    if (sendHtml(html, document.title)) return;
                  } catch (_) {}
                  if (nativePrint) return nativePrint();
                };

                if (!nativeOpen) return;

                window.open = function (url, targetName, features) {
                  var empty = (url === '' || url === null || typeof url === 'undefined');
                  var target = String(targetName || '_blank').toLowerCase();

                  // Bacong billing receipts use a blank popup then document.write().
                  // Capture only that pattern; real URLs such as Google Maps still
                  // use the browser/WebView popup path normally.
                  if (empty && (target === '_blank' || target === '')) {
                    var chunks = [];
                    var sent = false;
                    var fakeWindow;

                    function flush() {
                      if (sent) return;
                      var html = chunks.join('');
                      if (!html || html.length < 40) return;
                      sent = sendHtml(html, safeTitleFromHtml(html));
                    }

                    var fakeDocument = {
                      open: function () {
                        chunks = [];
                        sent = false;
                        return fakeDocument;
                      },
                      write: function (value) {
                        chunks.push(String(value == null ? '' : value));
                      },
                      writeln: function (value) {
                        chunks.push(String(value == null ? '' : value) + '\n');
                      },
                      close: function () {
                        // This is the deterministic handoff. In Chrome the newly
                        // written document later calls window.print(); in the APK we
                        // already have the exact completed document here.
                        flush();
                      },
                      title: 'Bacong Waterworks Bill'
                    };

                    fakeWindow = {
                      document: fakeDocument,
                      closed: false,
                      focus: function () {},
                      print: function () { flush(); },
                      close: function () { fakeWindow.closed = true; },
                      addEventListener: function () {},
                      removeEventListener: function () {},
                      setTimeout: window.setTimeout.bind(window),
                      clearTimeout: window.clearTimeout.bind(window)
                    };

                    return fakeWindow;
                  }

                  return nativeOpen.apply(window, arguments);
                };
              } catch (_) {}
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    private inner class PrintCaptureBridge {
        private val lock = Any()
        private var pendingTitle = "Bacong Waterworks Bill"
        private var pendingHtml = StringBuilder()
        private var accepting = false

        @JavascriptInterface
        fun beginPrint(title: String?) {
            synchronized(lock) {
                pendingTitle = safePrintTitle(title)
                pendingHtml = StringBuilder()
                accepting = true
            }
        }

        @JavascriptInterface
        fun appendPrintChunk(chunk: String?) {
            if (chunk.isNullOrEmpty()) return
            synchronized(lock) {
                if (!accepting) return
                // Defensive cap: normal Bacong receipts are far below this.
                if (pendingHtml.length + chunk.length > 12_000_000) {
                    accepting = false
                    pendingHtml = StringBuilder()
                    return
                }
                pendingHtml.append(chunk)
            }
        }

        @JavascriptInterface
        fun cancelPrint() {
            synchronized(lock) {
                accepting = false
                pendingHtml = StringBuilder()
            }
        }

        @JavascriptInterface
        fun finishPrint() {
            val payload: Pair<String, String>? = synchronized(lock) {
                if (!accepting || pendingHtml.length < 40) {
                    accepting = false
                    pendingHtml = StringBuilder()
                    null
                } else {
                    val result = pendingHtml.toString() to pendingTitle
                    accepting = false
                    pendingHtml = StringBuilder()
                    result
                }
            }

            val (html, title) = payload ?: return
            runOnUiThread {
                // Only accept print requests while the main app is on Bacong.
                val current = runCatching { Uri.parse(webView.url ?: "") }.getOrNull()
                if (!isTrustedUri(current)) return@runOnUiThread

                createRenderedPrintJob(html = html, title = title)
            }
        }
    }

    /**
     * Lightweight print optimization for RAWBT/thermal services.
     *
     * The Bacong receipt is already fully rendered HTML. Its municipal seal is
     * embedded as a large base64 image even though it is displayed at only 23 mm.
     * Android PrintManager turns that into PDF data and RAWBT rasterizes the PDF
     * again for the thermal printer. Keeping a multi-megapixel logo there adds
     * needless CPU, spool and Bluetooth work and can cause timeout/pause behavior.
     *
     * Only the receipt logo is downscaled. QR/barcodes or any future non-logo
     * images are intentionally left untouched. Script tags are removed because
     * JavaScript is disabled in the hidden print WebView and the original delayed
     * window.print() is not needed there.
     */
    private fun optimizeReceiptForThermalPrint(html: String): String {
        var result = html.replace(
            Regex("(?is)<script\\b[^>]*>.*?</script>"),
            ""
        )

        val imgTagRegex = Regex("(?is)<img\\b[^>]*>")
        result = imgTagRegex.replace(result) { tagMatch ->
            val tag = tagMatch.value
            val isReceiptLogo = Regex("(?is)class\\s*=\\s*['\"][^'\"]*\\blogo\\b[^'\"]*['\"]")
                .containsMatchIn(tag) ||
                Regex("(?is)alt\\s*=\\s*['\"][^'\"]*(municipality|bacong)[^'\"]*['\"]")
                    .containsMatchIn(tag)

            if (!isReceiptLogo) return@replace tag

            val srcRegex = Regex("(?is)src\\s*=\\s*(['\"])(data:image/(?:png|jpe?g);base64,)([^'\"]+)\\1")
            val srcMatch = srcRegex.find(tag) ?: return@replace tag
            val encoded = srcMatch.groupValues[3].replace(Regex("\\s+"), "")

            // Small logos are already cheap. Avoid needless recompression.
            if (encoded.length < 60_000) return@replace tag

            val optimizedUri = runCatching {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Invalid logo image")

                var sample = 1
                while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) {
                    sample *= 2
                }

                val bitmap = BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                ) ?: error("Unable to decode receipt logo")

                val maxDimension = 256
                val scale = minOf(
                    1.0,
                    maxDimension.toDouble() / maxOf(bitmap.width, bitmap.height).toDouble()
                )
                val targetWidth = maxOf(1, (bitmap.width * scale).toInt())
                val targetHeight = maxOf(1, (bitmap.height * scale).toInt())
                val scaled = if (targetWidth != bitmap.width || targetHeight != bitmap.height) {
                    Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
                } else {
                    bitmap
                }

                val output = ByteArrayOutputStream()
                // JPEG is appropriate for the existing municipal seal and gives a
                // much smaller print payload than retaining the original source.
                scaled.compress(Bitmap.CompressFormat.JPEG, 72, output)
                val compact = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)

                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()

                "data:image/jpeg;base64,$compact"
            }.getOrElse {
                return@replace tag
            }

            tag.replaceRange(
                srcMatch.range,
                "src=${srcMatch.groupValues[1]}$optimizedUri${srcMatch.groupValues[1]}"
            )
        }

        return result
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createRenderedPrintJob(html: String, title: String) {
        Toast.makeText(this, "Opening print dialog…", Toast.LENGTH_SHORT).show()

        // Keep the exact receipt layout, but reduce payload before Android/RAWBT
        // rasterizes it. Bacong's website embeds a much larger source logo than a
        // 58 mm thermal printer can physically resolve. Compressing only that logo
        // materially reduces PDF/raster work and Bluetooth transfer time without
        // changing the bill's text, dimensions, totals, or print-dialog behavior.
        val printHtml = optimizeReceiptForThermalPrint(html)

        val printView = WebView(this)
        with(printView.settings) {
            // The receipt is already fully generated HTML. Disable JavaScript so
            // its embedded delayed window.print() cannot create a duplicate job.
            javaScriptEnabled = false
            domStorageEnabled = false
            databaseEnabled = false
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        printView.isClickable = false
        printView.isFocusable = false
        printView.setBackgroundColor(android.graphics.Color.WHITE)
        activePrintViews.add(printView)

        // Put it behind the live application WebView. It remains fully attached and
        // renderable for PrintDocumentAdapter, but the user never sees a second page.
        rootContainer.addView(
            printView,
            0,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        var handedOff = false
        printView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (handedOff) return
                handedOff = true

                // Give base64 images/CSS one short render frame after onPageFinished,
                // then hand the actual WebView document to Android's print framework.
                view.postDelayed({
                    if (!activePrintViews.contains(view)) return@postDelayed
                    launchAndroidPrintDialog(view, title)
                }, 250)
            }
        }

        // baseURL keeps relative assets/CSS behavior consistent with the live site.
        printView.loadDataWithBaseURL(
            TRUSTED_BASE_URL,
            printHtml,
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun safePrintTitle(title: String?): String {
        return title?.trim()?.take(80)?.ifBlank { "Bacong Waterworks Bill" }
            ?: "Bacong Waterworks Bill"
    }

    private fun launchAndroidPrintDialog(printView: WebView, title: String) {
        if (!activePrintViews.contains(printView)) return

        val manager = getSystemService(PRINT_SERVICE) as PrintManager
        val adapter = printView.createPrintDocumentAdapter(title)

        // Null attributes are deliberate. Android's normal print UI/printer service
        // owns printer, paper size, copies, orientation, color, scaling, etc.
        val job = manager.print(title, adapter, null)
        releasePrintViewWhenSpoolAccepted(printView, job)
    }

    /**
     * Android's print service owns the document once the job has moved out of the
     * print-dialog CREATED state and into QUEUED/STARTED/BLOCKED. Keeping the hidden
     * WebView alive until the physical printer reports COMPLETED can make some
     * thermal print services hold the spool job open. The symptom is exactly what
     * Bacong v1.3.0 showed: the printer only starts after the user cancels the
     * Android Print Spooler status.
     *
     * Release the hidden WebView shortly after Android has accepted the spool job,
     * not after the physical printer finishes. The PrintDocumentAdapter has already
     * handed the rendered document to the system at that point. A short grace delay
     * avoids racing the final spool write on slower phones.
     */
    private fun releasePrintViewWhenSpoolAccepted(printView: WebView, printJob: PrintJob) {
        var releaseScheduled = false

        fun scheduleRelease(delayMs: Long) {
            if (releaseScheduled || !activePrintViews.contains(printView)) return
            releaseScheduled = true
            printView.postDelayed({
                if (activePrintViews.contains(printView)) {
                    destroyPrintView(printView)
                }
            }, delayMs)
        }

        fun check() {
            if (!activePrintViews.contains(printView)) return

            when {
                printJob.isCancelled || printJob.isFailed || printJob.isCompleted -> {
                    scheduleRelease(100)
                }
                printJob.isQueued || printJob.isStarted || printJob.isBlocked -> {
                    // Android has accepted/spooled the document. Do not keep the
                    // receipt WebView tied to the lifetime of the physical printer.
                    scheduleRelease(850)
                }
                else -> {
                    // User is still in the system print dialog. Keep the WebView
                    // alive so paper-size/orientation changes can relayout safely.
                    printView.postDelayed({ check() }, 250)
                }
            }
        }

        printView.postDelayed({ check() }, 250)
    }

    private fun destroyPrintView(target: WebView) {
        if (!activePrintViews.remove(target)) return
        (target.parent as? ViewGroup)?.removeView(target)
        target.stopLoading()
        target.webChromeClient = null
        target.webViewClient = WebViewClient()
        target.destroy()
    }

    private fun closeOrdinaryPopup(target: WebView) {
        (target.parent as? ViewGroup)?.removeView(target)
        target.stopLoading()
        target.webChromeClient = null
        target.webViewClient = WebViewClient()
        target.destroy()
    }

    private fun injectKeyboardSafetyHelper() {
        val script = """
            (function () {
              if (window.__bacongKeyboardHelperInstalled) return;
              window.__bacongKeyboardHelperInstalled = true;

              function focusedIntoView() {
                var el = document.activeElement;
                if (!el) return;
                var tag = (el.tagName || '').toLowerCase();
                if (tag !== 'input' && tag !== 'textarea' && tag !== 'select') return;
                setTimeout(function () {
                  try { el.scrollIntoView({behavior:'smooth', block:'center', inline:'nearest'}); }
                  catch (e) { try { el.scrollIntoView(false); } catch (_) {} }
                }, 80);
              }

              document.addEventListener('focusin', focusedIntoView, true);
              if (window.visualViewport) {
                window.visualViewport.addEventListener('resize', focusedIntoView);
                window.visualViewport.addEventListener('scroll', focusedIntoView);
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun ensureFocusedControlVisible() {
        val script = """
            (function(){
              var el=document.activeElement;
              if(!el) return;
              var tag=(el.tagName||'').toLowerCase();
              if(tag==='input'||tag==='textarea'||tag==='select') {
                try { el.scrollIntoView({behavior:'smooth',block:'center',inline:'nearest'}); }
                catch(e) { try { el.scrollIntoView(false); } catch(_){} }
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (isFinishing) {
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = null
            pendingCameraRequest?.deny()
            pendingCameraRequest = null

            activePrintViews.toList().forEach { destroyPrintView(it) }

            webView.stopLoading()
            webView.removeJavascriptInterface(PRINT_CAPTURE_BRIDGE)
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }
        super.onDestroy()
    }
}
