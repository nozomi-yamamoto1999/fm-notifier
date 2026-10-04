package jp.fmnotifier

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import android.view.MotionEvent
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.jsoup.Jsoup
import kotlin.concurrent.thread

data class ThreadCandidate(
    val title: String,
    val url: String,
    val ikioi: Int
)

/** YouTubeの通常WebView/全画面CustomViewの両方で、アプリ側のOFFジェスチャーを監視する。 */
class YoutubeGestureFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    var gestureHandler: ((MotionEvent) -> Boolean)? = null

    // 左上のYouTube UI（Shortsの「←」など）をタップで操作できるよう、
    // ACTION_DOWNではイベントを奪わず、実際に専用ジェスチャーの距離へ到達した
    // ときだけ親がタッチをインターセプトする。
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (gestureHandler?.invoke(event) == true) {
            return true
        }
        return super.onInterceptTouchEvent(event)
    }
}

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "JantamaPrefs"
        private const val PREF_YOUTUBE_ENABLED = "enable_youtube"
        private const val PREF_SOUND_ENABLED = "enable_sound"
        private const val PREF_VIBRATE_ENABLED = "enable_vibrate"
        private const val PREF_HISTORY = "history"
        private const val PREF_ERROR_LOG = "error_log"
        private const val PREF_SELECTED_THREAD_URL = "selected_thread_url"
        private const val PREF_LATEST_THREAD_TITLE = "latest_thread_title"
        private const val PREF_HISTORY_HEADER_V1 = "history_header_v1"
        private const val YOUTUBE_HOME_URL = "https://www.youtube.com/"
        private const val YOUTUBE_MOBILE_URL = "https://m.youtube.com/"
        private const val YOUTUBE_GESTURE_EDGE_DP = 100f
        private const val YOUTUBE_GESTURE_DISTANCE_DP = 180f
        private const val MAX_HISTORY_ENTRIES = 15
        private const val MAX_ERROR_ENTRIES = 30
        private const val COUNTDOWN_TICK_MS = 1000L
    }

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private lateinit var tvThreadTitle: TextView
    private lateinit var tvHistory: TextView
    private lateinit var spinnerInterval: Spinner
    private lateinit var btnSearchJantama: Button
    private lateinit var btnAllThreads: Button
    private lateinit var btnCheckNow: Button
    private lateinit var btnClearHistory: Button
    private lateinit var btnYoutubeToggle: Button
    private lateinit var btnErrorLog: Button
    private lateinit var switchSound: SwitchCompat
    private lateinit var switchVibrate: SwitchCompat
    private lateinit var tvCountdown: TextView
    private lateinit var webViewYoutube: WebView
    private lateinit var youtubeSection: View
    private lateinit var youtubeContainer: FrameLayout
    private lateinit var historySection: View
    private lateinit var mainLayout: LinearLayout

    // YouTube ON中の専用OFFジェスチャー（左上→右下）
    private var youtubeGestureStartX = 0f
    private var youtubeGestureStartY = 0f
    private var youtubeGestureTracking = false

    // YouTubeの全画面要求を受けたときに表示するCustomView。
    // 通常時と同じ大きさのYouTube領域に表示するため、レイアウト変更は行わない。
    private var youtubeCustomView: View? = null
    private var youtubeCustomViewCallback: WebChromeClient.CustomViewCallback? = null

    private var youtubePageLoaded = false

    // 画面回転時にWebViewを再生成せず、動画再生状態を維持するための補助状態。
    private var restoringYoutubeState = false

    private val handler = Handler(Looper.getMainLooper())
    private var currentIntervalMs: Long = 60 * 1000L

    private var countdownSec: Int = 60
    private var countdownRunnable: Runnable? = null

    // Activityが画面上に表示されている間だけtrue。
    // バックグラウンドでは自動巡回・保存・通知を行わない。
    @Volatile
    private var isActivityStarted = false

    // 巡回処理が二重起動しないようにする。
    @Volatile
    private var checkInProgress = false

    private var previousUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null
    private var installedUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mainLayout = findViewById(R.id.mainLayout)

        val mainView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(mainView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        tvThreadTitle = findViewById(R.id.tvThreadTitle)
        tvHistory = findViewById(R.id.tvHistory)
        spinnerInterval = findViewById(R.id.spinnerInterval)
        btnSearchJantama = findViewById(R.id.btnSearchJantama)
        btnAllThreads = findViewById(R.id.btnAllThreads)
        btnCheckNow = findViewById(R.id.btnCheckNow)
        btnClearHistory = findViewById(R.id.btnClearHistory)
        btnYoutubeToggle = findViewById(R.id.btnYoutubeToggle)
        btnErrorLog = findViewById(R.id.btnErrorLog)
        switchSound = findViewById(R.id.switchSound)
        switchVibrate = findViewById(R.id.switchVibrate)
        tvCountdown = findViewById(R.id.tvCountdown)
        webViewYoutube = findViewById(R.id.webViewYoutube)
        youtubeSection = findViewById(R.id.youtubeSection)
        youtubeContainer = findViewById(R.id.youtubeContainer)
        historySection = findViewById(R.id.historySection)

        setupYoutubeWebView()

        // YouTube ON中のAndroid本体の「戻る」は、通常YouTube画面から募集画面へ戻る操作として使用する。
        // ただしYouTubeプレイヤーが全画面中は、まず全画面だけを解除する。
        // 募集画面へ戻る条件は、専用ジェスチャー・新着募集検出・1000レス超え検出・
        // 通常YouTube画面でのAndroid本体の「戻る」の4つ。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!prefsYoutubeEnabled()) {
                    finish()
                    return
                }

                if (youtubeCustomView != null) {
                    // 全画面中の戻るは、YouTubeの全画面表示だけ解除する。
                    hideYoutubeCustomView()
                    return
                }

                // Shorts視聴中のAndroid戻るは、まずYouTube内の前画面へ戻す。
                // これにより「Shorts → 通常YouTube画面」をYouTubeの標準操作として行える。
                // 通常のYouTubeトップ等では、従来どおりYouTube OFFにする。
                val currentYoutubeUrl = webViewYoutube.url.orEmpty()
                if (currentYoutubeUrl.contains("/shorts/") && webViewYoutube.canGoBack()) {
                    webViewYoutube.goBack()
                    return
                }

                // 通常のYouTube画面で戻るを押した場合だけYouTubeをOFFにして募集画面へ戻る。
                disableYoutubeAndShowHistory()
            }
        })

        tvHistory.movementMethod = LinkMovementMethod.getInstance()

        val prefs = prefs
        switchSound.isChecked = prefs.getBoolean(PREF_SOUND_ENABLED, true)
        switchVibrate.isChecked = prefs.getBoolean(PREF_VIBRATE_ENABLED, true)

        val youtubeEnabled = prefs.getBoolean(PREF_YOUTUBE_ENABLED, false)
        val restoredYoutube = savedInstanceState?.getBoolean("youtube_enabled", false) == true
        if (restoredYoutube && youtubeEnabled) {
            restoringYoutubeState = true
            youtubePageLoaded = false
            setYoutubeEnabled(true)
            val webState = savedInstanceState.getBundle("youtube_webview_state")
            if (webState != null) {
                try {
                    webViewYoutube.restoreState(webState)
                    youtubePageLoaded = webViewYoutube.url != null
                } catch (e: Exception) {
                    recordError("YouTube画面状態の復元", e)
                }
            }
            restoringYoutubeState = false
        } else {
            setYoutubeEnabled(youtubeEnabled)
        }

        previousUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        installedUncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, throwable ->
            recordError("未捕捉例外（${thread.name}）", throwable)
            previousUncaughtExceptionHandler?.uncaughtException(thread, throwable)
        }
        Thread.setDefaultUncaughtExceptionHandler(installedUncaughtExceptionHandler)

        switchSound.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_SOUND_ENABLED, isChecked).apply()
        }

        switchVibrate.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_VIBRATE_ENABLED, isChecked).apply()
        }

        btnYoutubeToggle.setOnClickListener {
            val enabled = !prefs.getBoolean(PREF_YOUTUBE_ENABLED, false)
            prefs.edit().putBoolean(PREF_YOUTUBE_ENABLED, enabled).apply()
            setYoutubeEnabled(enabled)
        }

        btnErrorLog.setOnClickListener {
            showErrorLogDialog()
        }

        // 履歴クリアボタンの処理
        btnClearHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("履歴のクリア")
                .setMessage("募集履歴をすべて消去しますか？")
                .setPositiveButton("消去") { _, _ ->
                    prefs.edit().remove(PREF_HISTORY).apply()
                    updateUI()
                    Toast.makeText(this, "履歴をクリアしました", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }

        val intervals = arrayOf("30秒", "60秒", "3分", "5分")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, intervals)
        spinnerInterval.adapter = adapter
        // 起動時の初期値は60秒。
        spinnerInterval.setSelection(1)

        spinnerInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentIntervalMs = when (position) {
                    0 -> 30 * 1000L
                    1 -> 60 * 1000L
                    2 -> 3 * 60 * 1000L
                    3 -> 5 * 60 * 1000L
                    else -> 30 * 1000L
                }
                resetForegroundTimer()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnSearchJantama.setOnClickListener {
            fetchAndShowThreads(filterJantama = true)
        }

        btnAllThreads.setOnClickListener {
            fetchAndShowThreads(filterJantama = false)
        }

        btnCheckNow.setOnClickListener {
            resetForegroundTimer()
            runCheck(manual = true)
        }

        updateUI()
    }

    private fun setupYoutubeWebView() {
        // YouTubeはWebView上でJavaScript・Cookie・HTML5動画を使用するため、
        // 通常のWebページより少し強めにWebViewを設定する。
        webViewYoutube.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(true)
        }

        // YouTubeはCookieを利用するため、Cookieを明示的に有効化する。
        // YouTubeのログイン状態をWebViewのCookieとして保持する。
        // WebViewのCookieはアプリのデータ領域に保存され、アプリを終了して
        // 再起動しても利用できる。第三者CookieもYouTube用に許可する。
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webViewYoutube, true)
        }

        webViewYoutube.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    recordError(
                        "YouTube読み込みエラー: ${error.errorCode} ${error.description}",
                        RuntimeException("URL=${request.url}")
                    )

                    // www.youtube.com の読み込みに失敗した場合は、モバイル版へ切り替える。
                    if (request.url.toString() == YOUTUBE_HOME_URL) {
                        view.loadUrl("https://m.youtube.com/")
                    }
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                youtubePageLoaded = true

                // YouTube Shortsなどの見切れを防ぐため、ShadowDOM内部も含めてvideo要素に直接スタイルを適用し中央に配置する
                val injectionScript = """
                    (function() {
                        function adjustShortsVideo() {
                            if (window.location.href.includes('/shorts/')) {
                                // ページ内のすべての video 要素に直接インラインスタイルを適用し中央配置
                                const videos = document.querySelectorAll('video');
                                videos.forEach(video => {
                                    video.style.setProperty('object-fit', 'contain', 'important');
                                    video.style.setProperty('background-color', 'black', 'important');
                                    video.style.setProperty('position', 'absolute', 'important');
                                    video.style.setProperty('top', '50%', 'important');
                                    video.style.setProperty('left', '50%', 'important');
                                    video.style.setProperty('transform', 'translate(-50%, -50%)', 'important');
                                    video.style.setProperty('width', '100%', 'important');
                                    video.style.setProperty('height', '100%', 'important');
                                });

                                // シャドウDOM（Shadow DOM）内部も含めて video を探して中央配置スタイルを適用
                                function traverseShadowDOM(root) {
                                    const allElements = root.querySelectorAll('*');
                                    allElements.forEach(el => {
                                        if (el.shadowRoot) {
                                            const shadowVideos = el.shadowRoot.querySelectorAll('video');
                                            shadowVideos.forEach(v => {
                                                v.style.setProperty('object-fit', 'contain', 'important');
                                                v.style.setProperty('background-color', 'black', 'important');
                                                v.style.setProperty('position', 'absolute', 'important');
                                                v.style.setProperty('top', '50%', 'important');
                                                v.style.setProperty('left', '50%', 'important');
                                                v.style.setProperty('transform', 'translate(-50%, -50%)', 'important');
                                                v.style.setProperty('width', '100%', 'important');
                                                v.style.setProperty('height', '100%', 'important');
                                            });
                                            traverseShadowDOM(el.shadowRoot);
                                        }
                                    });
                                }
                                traverseShadowDOM(document.body);
                            }
                        }

                        // 初回実行および定期実行・MutationObserverで動画プレイヤーの動的生成に対応
                        adjustShortsVideo();
                        if (!window.shortsIntervalInitialized) {
                            window.shortsIntervalInitialized = true;
                            setInterval(adjustShortsVideo, 1000);
                        }

                        if (!window.shortsObserverInitialized) {
                            window.shortsObserverInitialized = true;
                            const observer = new MutationObserver(() => {
                                adjustShortsVideo();
                            });
                            observer.observe(document.body, { childList: true, subtree: true });
                        }
                    })();
                """.trimIndent()

                view.evaluateJavascript(injectionScript, null)

                // YouTube側のページ・プレイヤー状態をそのまま維持する。
                // CookieはonStop()/onDestroy()で保存するため、ページ遷移ごとのflushは行わない。

            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request.isForMainFrame) {
                    recordError(
                        "YouTube HTTPエラー: ${errorResponse.statusCode}",
                        RuntimeException("URL=${request.url}")
                    )
                }
            }
        }
        // WebViewだけでなく、YouTubeの全画面CustomView中も同じジェスチャーを検出する。
        (youtubeContainer as? YoutubeGestureFrameLayout)?.gestureHandler = { event ->
            if (!prefsYoutubeEnabled()) {
                youtubeGestureTracking = false
                false
            } else {
                val density = resources.displayMetrics.density
                val startEdge = YOUTUBE_GESTURE_EDGE_DP * density
                val requiredDistance = YOUTUBE_GESTURE_DISTANCE_DP * density
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        youtubeGestureStartX = event.x
                        youtubeGestureStartY = event.y
                        youtubeGestureTracking =
                            event.x <= startEdge && event.y <= startEdge
                        // DOWNは絶対に奪わない。Shortsの左上「←」などのタップを
                        // YouTube WebViewへそのまま渡すため。
                        false
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (!youtubeGestureTracking) {
                            false
                        } else {
                            val dx = event.x - youtubeGestureStartX
                            val dy = event.y - youtubeGestureStartY
                            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                            if (dx < 0f || dy < 0f) {
                                youtubeGestureTracking = false
                                false
                            } else if (distance >= requiredDistance) {
                                youtubeGestureTracking = false
                                setYoutubeEnabled(false)
                                true
                            } else {
                                // 距離に達するまではYouTubeへ渡す。
                                false
                            }
                        }
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        youtubeGestureTracking = false
                        false
                    }

                    else -> false
                }
            }
        }

        // YouTubeの動画・検索・スクロール操作をWebView内で扱う。
        // 通常表示のYouTube領域自体を大きくしているため、YouTubeの全画面要求が
        // 来ても別サイズには変更せず、同じ表示領域へCustomViewを載せ替える。
        webViewYoutube.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (youtubeCustomView != null) {
                    callback.onCustomViewHidden()
                    return
                }

                youtubeCustomView = view
                youtubeCustomViewCallback = callback
                webViewYoutube.visibility = View.GONE

                // YouTubeプレイヤーの全画面再生に入ったら端末を横画面にする。
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

                val params = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                youtubeContainer.addView(view, params)
            }

            override fun onHideCustomView() {
                hideYoutubeCustomView()
            }
        }

        // 起動時はYouTube OFFなので、ここでは読み込まない。
        // ONにしたタイミングでsetYoutubeEnabled(true)から読み込む。
    }

    private fun loadYoutubeHome() {
        try {
            webViewYoutube.stopLoading()
            webViewYoutube.loadUrl(YOUTUBE_HOME_URL)
        } catch (e: Exception) {
            recordError("YouTube起動", e)
            try {
                webViewYoutube.loadUrl(YOUTUBE_MOBILE_URL)
            } catch (fallback: Exception) {
                recordError("YouTubeモバイル版起動", fallback)
            }
        }
    }

    /** YouTube内のHTML5動画をすべて一時停止する。 */
    private fun pauseYoutubeVideo() {
        try {
            webViewYoutube.evaluateJavascript(
                "(function(){document.querySelectorAll('video').forEach(function(v){v.pause();});})()",
                null
            )
        } catch (e: Exception) {
            recordError("YouTube一時停止", e)
        }
    }

    /**
     * YouTubeのON/OFFを切り替える。
     * ONに戻したときは自動再生せず、必ず一時停止状態にする。
     */
    private fun setYoutubeEnabled(enabled: Boolean) {
        btnYoutubeToggle.text = if (enabled) "YouTube ON" else "YouTube OFF"

        if (!enabled) {
            if (youtubeCustomView != null) {
                hideYoutubeCustomView()
            } else {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            pauseYoutubeVideo()

            // 通常画面へ復帰。
            mainLayout.setPadding(
                (12 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt()
            )

            for (i in 0 until mainLayout.childCount) {
                mainLayout.getChildAt(i).visibility = View.VISIBLE
            }

            youtubeSection.visibility = View.GONE
            historySection.visibility = View.VISIBLE
            (historySection.layoutParams as LinearLayout.LayoutParams).apply {
                weight = 1f
                height = 0
            }.also { historySection.layoutParams = it }

        } else {
            // YouTube ON時は友人戦募集画面を完全に隠し、
            // YouTube WebViewだけを画面いっぱいに表示する。
            mainLayout.setPadding(0, 0, 0, 0)

            for (i in 0 until mainLayout.childCount) {
                val child = mainLayout.getChildAt(i)
                child.visibility = if (child === youtubeSection) View.VISIBLE else View.GONE
            }

            youtubeSection.visibility = View.VISIBLE
            youtubeSection.layoutParams = (youtubeSection.layoutParams as LinearLayout.LayoutParams).apply {
                weight = 1f
                height = 0
            }

            youtubeContainer.layoutParams =
                (youtubeContainer.layoutParams as LinearLayout.LayoutParams).apply {
                    weight = 1f
                    height = 0
                }

            if (restoringYoutubeState) {
                // onCreate()でWebView状態をrestoreState()するため、ここでは再読み込みしない。
            } else if (youtubePageLoaded && webViewYoutube.url != null && webViewYoutube.url != "about:blank") {
                // ONに戻した場合も自動再生しない。
                webViewYoutube.post { pauseYoutubeVideo() }
            } else {
                loadYoutubeHome()
            }
        }

        mainLayout.requestLayout()
        youtubeSection.requestLayout()
        historySection.requestLayout()
    }

    private fun showErrorLogDialog() {
        val prefs = prefs
        val log = prefs.getString(PREF_ERROR_LOG, "") ?: ""

        val scrollView = android.widget.ScrollView(this)
        val textView = TextView(this).apply {
            text = if (log.isBlank()) "エラーログはありません。" else log
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(24, 8, 24, 8)
        }
        scrollView.addView(textView)

        AlertDialog.Builder(this)
            .setTitle("ErrorLog")
            .setView(scrollView)
            .setNeutralButton("コピー") { _, _ ->
                if (log.isNotBlank()) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ErrorLog", log))
                    Toast.makeText(this, "エラーログをコピーしました", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "コピーするエラーログがありません", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("ログクリア") { _, _ ->
                prefs.edit().remove(PREF_ERROR_LOG).apply()
                Toast.makeText(this, "エラーログをクリアしました", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("閉じる", null)
            .show()
    }

    private fun recordError(location: String, throwable: Throwable) {
        try {
            val timestamp = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date())
            val stackWriter = StringWriter()
            throwable.printStackTrace(PrintWriter(stackWriter))
            val stackTrace = stackWriter.toString().take(12000)
            val entry = "[$timestamp] $location\n${throwable::class.java.name}: ${throwable.message ?: "(メッセージなし)"}\n$stackTrace"

            val prefs = prefs
            val old = prefs.getString(PREF_ERROR_LOG, "") ?: ""
            val entries = old.split("\n\n===== ERROR =====\n\n")
                .filter { it.isNotBlank() }
                .toMutableList()
            entries.add(entry)
            val saved = entries.takeLast(MAX_ERROR_ENTRIES).joinToString("\n\n===== ERROR =====\n\n")
            prefs.edit().putString(PREF_ERROR_LOG, saved).apply()
        } catch (_: Exception) {
            // ErrorLogへの記録自体が原因でアプリを停止させない。
        }
    }

    /** YouTubeのCustomViewを通常のWebViewへ戻す。 */
    private fun hideYoutubeCustomView() {
        val customView = youtubeCustomView ?: return

        youtubeContainer.removeView(customView)
        youtubeCustomView = null

        val callback = youtubeCustomViewCallback
        youtubeCustomViewCallback = null

        webViewYoutube.visibility = View.VISIBLE
        callback?.onCustomViewHidden()

        // 全画面再生を終了したら、アプリを縦画面へ戻す。
        if (!isFinishing && !isDestroyed) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    /** YouTubeのCookie（ログイン状態）を端末へ明示的に保存する。 */
    private fun flushYoutubeCookies() {
        try {
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            recordError("YouTubeログイン情報の保存", e)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // AndroidManifestでorientation|screenSize|keyboardHiddenをconfigChangesに指定すると、
        // 画面回転時にActivity/WebViewを再生成せず、再生中の動画と位置を維持できる。
        webViewYoutube.requestLayout()
    }

    override fun onStart() {
        super.onStart()

        isActivityStarted = true
        updateUI()
        btnCheckNow.isEnabled = !checkInProgress
        startForegroundTimer()
    }

    override fun onStop() {
        // 画面から離れた瞬間に自動巡回タイマーを停止する。
        // 以降、進行中の自動巡回も結果を保存・通知しない。
        isActivityStarted = false
        stopForegroundTimer()

        // アプリを閉じる／別画面へ移る前にYouTubeのログインCookieを保存する。
        flushYoutubeCookies()

        super.onStop()
    }

    private fun startForegroundTimer() {
        // Activityが表示されていない状態ではタイマーを開始しない。
        if (!isActivityStarted) return

        stopForegroundTimer()
        countdownSec = (currentIntervalMs / 1000).toInt()

        countdownRunnable = object : Runnable {
            override fun run() {
                // バックグラウンドへ移ったら、このRunnableを再登録しない。
                if (!isActivityStarted || isFinishing || isDestroyed) {
                    stopForegroundTimer()
                    return
                }

                if (countdownSec <= 0) {
                    tvCountdown?.text = "自動巡回中..."
                    runCheck(manual = false)
                    countdownSec = (currentIntervalMs / 1000).toInt()
                } else {
                    tvCountdown?.text = "自動巡回まで: ${countdownSec}秒"
                    countdownSec--
                }

                // runCheck()の通信中にバックグラウンドへ移る可能性があるため、
                // 再登録前にも表示状態を確認する。
                if (isActivityStarted && !isFinishing && !isDestroyed) {
                    handler.postDelayed(this, COUNTDOWN_TICK_MS)
                }
            }
        }

        handler.postDelayed(countdownRunnable!!, 0)
    }

    private fun stopForegroundTimer() {
        countdownRunnable?.let {
            handler.removeCallbacks(it)
        }
        countdownRunnable = null
        tvCountdown?.text = "自動巡回停止中"
    }

    private fun resetForegroundTimer() {
        stopForegroundTimer()
        if (isActivityStarted && !isFinishing && !isDestroyed) {
            startForegroundTimer()
        }
    }

    private fun fetchAndShowThreads(filterJantama: Boolean) {
        btnSearchJantama.isEnabled = false
        btnAllThreads.isEnabled = false

        val msg = if (filterJantama) "雀魂スレッドを勢い順で検索中..." else "麻雀板全スレッドを勢い順で取得中..."
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

        thread {
            val candidates = fetchThreadCandidates(filterJantama)
            runOnUiThread {
                if (!isActivityStarted || isFinishing || isDestroyed) return@runOnUiThread

                btnSearchJantama.isEnabled = true
                btnAllThreads.isEnabled = true

                if (candidates.isNotEmpty()) {
                    showThreadSelectDialog(candidates)
                } else {
                    Toast.makeText(this, "スレッドが見つかりませんでした", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun runCheck(manual: Boolean) {
        // 今回のアプリは「画面を開いている間だけ巡回」が仕様。
        // 自動巡回はActivityが表示されていなければ開始しない。
        if (!isActivityStarted || isFinishing || isDestroyed) {
            return
        }

        // 手動・自動を問わず、同時に2本の巡回を走らせない。
        if (checkInProgress) {
            return
        }

        val prefs = prefs
        val selectedUrl = prefs.getString(PREF_SELECTED_THREAD_URL, null)

        if (selectedUrl.isNullOrEmpty()) {
            if (manual && isActivityStarted) {
                Toast.makeText(this, "先に「雀魂を検索」または「麻雀板一覧」でスレッドを選択してください", Toast.LENGTH_LONG).show()
            }
            return
        }

        checkInProgress = true

        if (manual) {
            btnCheckNow.isEnabled = false
            Toast.makeText(this, "手動で巡回中...", Toast.LENGTH_SHORT).show()
        }

        thread {
            val title = prefs.getString(PREF_LATEST_THREAD_TITLE, "選択中のスレッド") ?: ""

            // parseThreadAndSave() の内部でも画面表示状態を随時確認する。
            // バックグラウンドへ移った場合は保存せず中止する。
            val addedCount = parseThreadAndSave(
                title = title,
                url = selectedUrl,
                shouldContinue = { isActivityStarted && !isFinishing && !isDestroyed }
            )

            runOnUiThread {
                checkInProgress = false

                // バックグラウンド中に完了した処理については、
                // UI更新・通知・Toastを行わない。
                if (!isActivityStarted || isFinishing || isDestroyed) {
                    return@runOnUiThread
                }

                btnCheckNow.isEnabled = true

                if (addedCount > 0) {
                    // 新着募集を検出したら、YouTubeをOFFにして募集画面へ戻す。
                    disableYoutubeAndShowHistory()
                    playNotificationSoundAndVibrate()
                    Toast.makeText(this@MainActivity, "新着募集を $addedCount 件取得しました！", Toast.LENGTH_SHORT).show()
                } else if (addedCount == -2) {
                    // 1000レス超えを検出したら、YouTubeをOFFにして募集画面へ戻す。
                    disableYoutubeAndShowHistory()
                    Toast.makeText(this@MainActivity, "このスレッドは１０００を超えました", Toast.LENGTH_SHORT).show()
                } else if (addedCount == 0) {
                    // YouTubeプレイヤーの全画面表示中は、巡回完了のToastを表示しない。
                    // 全画面解除・YouTube OFF・新着/1000レス超え時の復帰は従来どおり。
                    if (youtubeCustomView == null) {
                        val mode = if (manual) "手動" else "自動"
                        Toast.makeText(this@MainActivity, "${mode}巡回完了（新しい募集はありませんでした）", Toast.LENGTH_SHORT).show()
                    }
                }
                // -1 は通信エラー等。既存動作を変えず、不要な「新着0件」通知を出さない。
                updateUI()
            }
        }
    }

    private fun disableYoutubeAndShowHistory() {
        if (!prefsYoutubeEnabled()) return
        prefs.edit().putBoolean(PREF_YOUTUBE_ENABLED, false).apply()
        setYoutubeEnabled(false)
    }

    private fun prefsYoutubeEnabled(): Boolean {
        return prefs
            .getBoolean(PREF_YOUTUBE_ENABLED, false)
    }

    private fun playNotificationSoundAndVibrate() {
        val prefs = prefs
        val isSoundEnabled = prefs.getBoolean(PREF_SOUND_ENABLED, true)
        val isVibrateEnabled = prefs.getBoolean(PREF_VIBRATE_ENABLED, true)

        if (isSoundEnabled) {
            try {
                val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val ringtone = RingtoneManager.getRingtone(applicationContext, notificationUri)
                ringtone.play()
            } catch (e: Exception) {
                recordError("通知音の再生", e)
            }
        }

        if (isVibrateEnabled) {
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vibratorManager.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }

                if (vibrator.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val timings = longArrayOf(0, 200, 100, 200)
                        val effect = VibrationEffect.createWaveform(timings, -1)
                        vibrator.vibrate(effect)
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(500)
                    }
                }
            } catch (e: Exception) {
                recordError("バイブレーション", e)
            }
        }
    }

    private fun fetchThreadCandidates(filterJantama: Boolean): List<ThreadCandidate> {
        val list = mutableListOf<ThreadCandidate>()
        try {
            val boardUrl = "https://egg.5ch.io/mj/subback.html"
            val boardDoc = Jsoup.connect(boardUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(15000)
                .get()

            val currentTimeSec = System.currentTimeMillis() / 1000
            val links = boardDoc.select("a[href]")

            for (link in links) {
                val fullText = link.text()

                val isMatch = if (filterJantama) {
                    fullText.contains("雀魂") || fullText.contains("じゃんたま") || fullText.contains("ジャンタマ")
                } else {
                    true
                }

                if (isMatch) {
                    val href = link.attr("href")
                    val threadIdMatch = Regex("""\d{9,11}""").find(href)

                    if (threadIdMatch != null) {
                        val threadIdStr = threadIdMatch.value
                        val threadCreatedTimeSec = threadIdStr.toLongOrNull() ?: currentTimeSec

                        val resCountMatch = Regex("""\((\d+)\)""").find(fullText)
                        val resCount = resCountMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

                        val diffTimeSec = (currentTimeSec - threadCreatedTimeSec).coerceAtLeast(1)
                        val ikioi = ((resCount.toDouble() / diffTimeSec) * 86400).toInt()

                        val url = "https://egg.5ch.io/test/read.cgi/mj/$threadIdStr/"
                        list.add(ThreadCandidate(fullText, url, ikioi))
                    }
                }
            }
        } catch (e: Exception) {
            recordError("スレッド一覧取得", e)
        }

        return list.sortedByDescending { it.ikioi }
    }

    private fun showThreadSelectDialog(candidates: List<ThreadCandidate>) {
        val displayTitles = candidates.map { "[勢い: ${it.ikioi}] ${it.title}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("対象スレッドを選択（勢い順）")
            .setItems(displayTitles) { _, which ->
                val selected = candidates[which]
                val prefs = prefs
                prefs.edit()
                    .putString(PREF_LATEST_THREAD_TITLE, selected.title)
                    .putString(PREF_SELECTED_THREAD_URL, selected.url)
                    .apply()

                if (isActivityStarted && !isFinishing && !isDestroyed) {
                    Toast.makeText(this, "『${selected.title}』を選択しました", Toast.LENGTH_SHORT).show()
                    updateUI()
                    runCheck(manual = false)
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /**
     * 友人戦募集の検知条件。
     *
     * word1 は必須、word2 が空でなければ word1 と word2 の両方が
     * 同じ投稿本文に含まれていることを条件とする。5桁のルームIDは
     * parseThreadAndSave() 側で別途同じ投稿本文から確認する。
     *
     * 例:
     *   三麻 + 東 + 12345 -> （三人東）
     *   秋刀魚 + 半荘 + 12345 -> （三人南）
     */
    private data class RecruitmentRule(
        val word1: String,
        val word2: String,
        val historyLabel: String
    )

    private val recruitmentRules = listOf(
        // 特殊ルールのキーワードを最優先で判定する。
        // 本文に対局種別（半荘・南・東など）が同時に含まれていても、
        // これらの特殊ルールがあれば特殊ルール側を友人戦募集として採用する。
        RecruitmentRule("万象", "", "万象修羅"),
        RecruitmentRule("修羅", "", "修羅の戦"),
        RecruitmentRule("闇夜", "", "闇夜の戦"),
        RecruitmentRule("赤血", "", "赤血の戦"),
        RecruitmentRule("明鏡", "", "明鏡の戦"),
        RecruitmentRule("占星", "", "占星の戦"),
        RecruitmentRule("天命", "", "天命の戦"),
        RecruitmentRule("詠唱", "", "詠唱の戦"),
        RecruitmentRule("魂の一撃", "", "魂の一撃"),

        // 「半荘」を「南」「東」より優先するため、半荘を含む複合条件を先に判定する。
        // 半荘は南戦扱いとして履歴表示する。
        RecruitmentRule("三麻", "半荘", "三人南"),
        RecruitmentRule("3麻", "半荘", "三人南"),
        RecruitmentRule("３麻", "半荘", "三人南"),
        RecruitmentRule("秋刀魚", "半荘", "三人南"),
        RecruitmentRule("サンマ", "半荘", "三人南"),
        RecruitmentRule("3人", "半荘", "三人南"),
        RecruitmentRule("３人", "半荘", "三人南"),
        RecruitmentRule("三人", "半荘", "三人南"),
        RecruitmentRule("四麻", "半荘", "四人南"),
        RecruitmentRule("4麻", "半荘", "四人南"),
        RecruitmentRule("４麻", "半荘", "四人南"),
        RecruitmentRule("4人", "半荘", "四人南"),
        RecruitmentRule("４人", "半荘", "四人南"),
        RecruitmentRule("四人", "半荘", "四人南"),

        // 南を含む複合条件。
        RecruitmentRule("三麻", "南", "三人南"),
        RecruitmentRule("3麻", "南", "三人南"),
        RecruitmentRule("３麻", "南", "三人南"),
        RecruitmentRule("秋刀魚", "南", "三人南"),
        RecruitmentRule("サンマ", "南", "三人南"),
        RecruitmentRule("四麻", "南", "四人南"),
        RecruitmentRule("4麻", "南", "四人南"),
        RecruitmentRule("４麻", "南", "四人南"),

        // 「4半」「４半」「四半」は四人南戦として扱う（「東」よりも優先度を高くする）
        RecruitmentRule("4半", "", "四人南"),
        RecruitmentRule("４半", "", "四人南"),
        RecruitmentRule("四半", "", "四人南"),

        // 「東」を含む複合条件は半荘条件より後に判定する。
        RecruitmentRule("三麻", "東", "三人東"),
        RecruitmentRule("3麻", "東", "三人東"),
        RecruitmentRule("３麻", "東", "三人東"),
        RecruitmentRule("秋刀魚", "東", "三人東"),
        RecruitmentRule("サンマ", "東", "三人東"),
        RecruitmentRule("四麻", "東", "四人東"),
        RecruitmentRule("4麻", "東", "四人東"),
        RecruitmentRule("４麻", "東", "四人東"),

        // 単独キーワード + 5桁。
        // 「友人戦」は具体的な対局種別より後に判定する。
        RecruitmentRule("三麻", "", "三麻"),
        RecruitmentRule("3麻", "", "三麻"),
        RecruitmentRule("３麻", "", "三麻"),
        RecruitmentRule("四麻", "", "四麻"),
        RecruitmentRule("4麻", "", "四麻"),
        RecruitmentRule("４麻", "", "四麻"),
        RecruitmentRule("秋刀魚", "", "三麻"),
        RecruitmentRule("サンマ", "", "三麻"),
        RecruitmentRule("3東", "", "三人東"),
        RecruitmentRule("３東", "", "三人東"),
        RecruitmentRule("三東", "", "三人東"),
        RecruitmentRule("3人東", "", "三人東"),
        RecruitmentRule("３人東", "", "三人東"),
        RecruitmentRule("三人東", "", "三人東"),
        RecruitmentRule("3南", "", "三人南"),
        RecruitmentRule("３南", "", "三人南"),
        RecruitmentRule("三南", "", "三人南"),
        RecruitmentRule("3人南", "", "三人南"),
        RecruitmentRule("３人南", "", "三人南"),
        RecruitmentRule("三人南", "", "三人南"),
        RecruitmentRule("4東", "", "四人東"),
        RecruitmentRule("４東", "", "四人東"),
        RecruitmentRule("四東", "", "四人東"),
        RecruitmentRule("4人東", "", "四人東"),
        RecruitmentRule("４人東", "", "四人東"),
        RecruitmentRule("四人東", "", "四人東"),
        RecruitmentRule("4南", "", "四人南"),
        RecruitmentRule("４南", "", "四人南"),
        RecruitmentRule("四南", "", "四人南"),
        RecruitmentRule("4人南", "", "四人南"),
        RecruitmentRule("４人南", "", "四人南"),
        RecruitmentRule("四人南", "", "四人南"),
        RecruitmentRule("友人戦", "", "友人戦")
    )

    private fun findRecruitmentRule(text: String): RecruitmentRule? {
        // より具体的な対局種別を優先し、最後に汎用の「友人戦」を判定する。
        return recruitmentRules.firstOrNull { rule ->
            text.contains(rule.word1) &&
                    (rule.word2.isEmpty() || text.contains(rule.word2))
        }
    }

    private fun detectRuleTags(text: String): String {
        val recruitmentRule = findRecruitmentRule(text)
        if (recruitmentRule != null) {
            return when (recruitmentRule.historyLabel) {
                "三人東" -> "（三人東）"
                "三人南" -> "（三人南）"
                "四人東" -> "（四人東）"
                "四人南" -> "（四人南）"
                else -> {
                    // 三麻・四麻・特殊ルールなどは従来の表示形式を維持する。
                    val tags = mutableListOf<String>()
                    if (recruitmentRule.historyLabel == "三麻") tags.add("三麻")
                    else if (recruitmentRule.historyLabel == "四麻") tags.add("四麻")
                    else if (recruitmentRule.historyLabel != "友人戦") tags.add(recruitmentRule.historyLabel)
                    if (tags.isNotEmpty()) "【${tags.joinToString("・")}" + "】" else ""
                }
            }
        }

        // 友人戦募集以外の表示については、従来のルール判定を維持する。
        val tags = mutableListOf<String>()

        if (text.contains("三麻") || text.contains("サンマ") || text.contains("3麻") || text.contains("三人")) {
            tags.add("三麻")
        } else if (text.contains("四麻") || text.contains("ヨンマ") || text.contains("4麻") || text.contains("四人")) {
            tags.add("四麻")
        }

        if (text.contains("東風")) {
            tags.add("東風")
        } else if (text.contains("半荘") || text.contains("南戦") || text.contains("南局") || text.contains("東南")) {
            tags.add("半荘")
        } else if (text.contains("一局")) {
            tags.add("一局戦")
        }

        if (text.contains("修羅")) {
            tags.add("修羅")
        } else if (text.contains("赤3") || text.contains("赤三")) {
            tags.add("赤3")
        }

        return if (tags.isNotEmpty()) {
            "【${tags.joinToString("・")}" + "】"
        } else {
            ""
        }
    }

    private data class DatFetchResult(
        val bytes: ByteArray,
        val totalSize: Long,
        val lastModified: String?,
        val isFullFetch: Boolean,
        val notModified: Boolean
    )

    private data class DatPostData(
        val number: Int,
        val text: String,
        val time: String,
        val fullDateTimeKey: String,
        val id: String,
        val author: String,
        val dateAndId: String
    )

    /**
     * 5chのDATを差分取得する。
     * 初回だけ全文、2回目以降は Range + If-Modified-Since を使用する。
     * 5chの公式開発資料でも、差分取得では Range と If-Modified-Since の併用が推奨されている。
     */
    private fun fetchDatDifferential(
        datUrl: String,
        previousSize: Long,
        previousLastModified: String?
    ): DatFetchResult {
        val connection = (URL(datUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Monazilla/1.00 (jp.fmnotifier/1.0)")
            // 差分取得時はgzip圧縮を使わない。Rangeのバイト位置をそのまま扱うため。
            setRequestProperty("Accept-Encoding", "identity")

            if (previousSize >= 0L) {
                // 前回受信済みバイトの「次」から取得する。
                // 末尾1バイトを重ねないことで、差分データの重複解析を防ぐ。
                val rangeStart = previousSize
                setRequestProperty("Range", "bytes=$rangeStart-")
                if (!previousLastModified.isNullOrBlank()) {
                    setRequestProperty("If-Modified-Since", previousLastModified)
                }
            }
        }

        try {
            val status = connection.responseCode
            val lastModified = connection.getHeaderField("Last-Modified")
            val contentLength = connection.getHeaderFieldLong("Content-Length", -1L)

            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return DatFetchResult(
                    bytes = ByteArray(0),
                    totalSize = previousSize,
                    lastModified = lastModified ?: previousLastModified,
                    isFullFetch = false,
                    notModified = true
                )
            }

            if (status == 416) {
                // Range の開始位置が現在のDAT末尾と一致する場合、
                // 「追加バイトなし」で416になるサーバーがある。
                // Content-Range が bytes */N なら現在サイズNを確認できる。
                val contentRange = connection.getHeaderField("Content-Range")
                val totalFromRange = Regex("""\*/(\d+)$""")
                    .find(contentRange ?: "")
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toLongOrNull()

                if (totalFromRange != null && totalFromRange == previousSize) {
                    return DatFetchResult(
                        bytes = ByteArray(0),
                        totalSize = previousSize,
                        lastModified = lastModified ?: previousLastModified,
                        isFullFetch = false,
                        notModified = true
                    )
                }

                // DATが前回保存時より短くなった場合などは全文を取り直す。
                connection.disconnect()
                return fetchDatFull(datUrl)
            }

            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                throw java.io.IOException("DAT取得HTTPエラー: $status URL=$datUrl")
            }

            // 206の場合はContent-Rangeの開始位置を確認する。
            // 前回サイズ以外から始まっていたら、重複や欠落を避けるため全文取得へ戻す。
            if (status == HttpURLConnection.HTTP_PARTIAL && previousSize >= 0L) {
                val contentRange = connection.getHeaderField("Content-Range")
                val rangeMatch = Regex("""bytes (\d+)-(\d+)/(\d+|\*)$""")
                    .find(contentRange ?: "")
                val returnedStart = rangeMatch?.groupValues?.getOrNull(1)?.toLongOrNull()
                if (returnedStart != previousSize) {
                    connection.disconnect()
                    return fetchDatFull(datUrl)
                }
            }

            val bytes = connection.inputStream.use { it.readBytes() }
            val isFull = status == HttpURLConnection.HTTP_OK || previousSize < 0L
            val totalSize = if (status == HttpURLConnection.HTTP_PARTIAL && previousSize >= 0L) {
                val contentRange = connection.getHeaderField("Content-Range")
                Regex("""/([0-9]+)$""").find(contentRange ?: "")?.groupValues?.getOrNull(1)?.toLongOrNull()
                    ?: (previousSize + bytes.size)
            } else {
                if (contentLength >= 0L) contentLength else bytes.size.toLong()
            }

            return DatFetchResult(
                bytes = bytes,
                totalSize = totalSize,
                lastModified = lastModified ?: previousLastModified,
                isFullFetch = isFull,
                notModified = false
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchDatFull(datUrl: String): DatFetchResult {
        val connection = (URL(datUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Monazilla/1.00 (jp.fmnotifier/1.0)")
            setRequestProperty("Accept-Encoding", "identity")
        }

        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw java.io.IOException("DAT全文取得HTTPエラー: $status URL=$datUrl")
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            val contentLength = connection.getHeaderFieldLong("Content-Length", -1L)
            return DatFetchResult(
                bytes = bytes,
                totalSize = if (contentLength >= 0L) contentLength else bytes.size.toLong(),
                lastModified = connection.getHeaderField("Last-Modified"),
                isFullFetch = true,
                notModified = false
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Range差分の先頭が万一レス途中から始まっていた場合、
     * 最初の不完全な行だけを捨てて新規レスの重複・欠落を防ぐ。
     */
    private fun normalizeDatDifferential(datText: String): String {
        if (datText.isEmpty()) return datText
        val firstNewline = datText.indexOf('\n')
        if (firstNewline < 0) return ""
        val firstLine = datText.substring(0, firstNewline)
        return if (firstLine.split("<>").size >= 4) {
            datText
        } else {
            datText.substring(firstNewline + 1)
        }
    }

    private fun parseDatPosts(datText: String, shouldContinue: () -> Boolean): List<DatPostData> {
        val posts = mutableListOf<DatPostData>()
        val postTimeRegex = Regex("""\d{2}:\d{2}""")
        val fullDateTimeRegex = Regex("""(\d{4}/\d{2}/\d{2}\([^)]+\)\s*\d{2}:\d{2}:\d{2})""")
        val idRegex = Regex("""ID:([a-zA-Z0-9+/]+)""")

        var number = 1
        for (line in datText.split('\n')) {
            if (!shouldContinue()) return emptyList()
            if (line.isBlank()) continue

            val fields = line.split("<>", limit = 5)
            if (fields.size < 4) continue

            // DATの本文はHTMLエンコード済み。Jsoupで表示用テキストへ戻す。
            val bodyHtml = fields[3]
            val text = Jsoup.parse(bodyHtml).text()
            val dateAndId = fields[2]
            val fullDateTimeMatch = fullDateTimeRegex.find(dateAndId)
            val timeMatch = postTimeRegex.find(dateAndId)
            val idMatch = idRegex.find(dateAndId)

            posts.add(
                DatPostData(
                    number = number,
                    text = text,
                    time = timeMatch?.value ?: "時間不詳",
                    fullDateTimeKey = fullDateTimeMatch?.groupValues?.get(1) ?: "0000/00/00 ${timeMatch?.value ?: "00:00"}:00",
                    id = idMatch?.groupValues?.get(1) ?: "",
                    author = fields[0].trim(),
                    dateAndId = dateAndId.trim()
                )
            )
            number++
        }
        return posts
    }

    private fun threadIdFromUrl(url: String): String? {
        return Regex("""/test/read\.cgi/[^/]+/(\d{9,11})/?""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun datUrlFromThreadUrl(url: String): String? {
        val uri = Uri.parse(url)
        val threadId = threadIdFromUrl(url) ?: return null
        val board = uri.pathSegments.firstOrNull { it != "test" && it != "read.cgi" }
            ?: return null
        return "${uri.scheme ?: "https"}://${uri.host}/$board/dat/$threadId.dat"
    }

    /**
     * 既存履歴を「募集情報 → 5ch元レスヘッダー → 本文」の順に正規化する。
     * 旧版ではヘッダーが募集情報より前に保存されていたため、表示順を移行する。
     */
    private fun normalizeHistoryEntryOrder(item: String): String {
        val match = Regex("(?s)^(<!--DATE:.*?--><!--POST:.*?-->\\n)?(\\[[^\\n]+\\])\\n(.*)$").find(item)
            ?: return item

        val metadata = match.groupValues[1]
        val headerLine = match.groupValues[2]
        val content = match.groupValues[3]
        val lines = content.split('\n', limit = 3)
        if (lines.size < 2) return item

        // 新しい順序ならそのまま。
        if (lines[0].startsWith("(") && lines[0].contains("game.mahjongsoul.com")) {
            return item
        }

        val timeLine = lines[0]
        val linkLine = lines[1]
        val body = if (lines.size == 3) "\n${lines[2]}" else ""
        return "$metadata$timeLine\n$linkLine\n$headerLine$body"
    }

    private fun cleanReplyBody(text: String): String {
        var cleanedBody = text
        cleanedBody = cleanedBody.replace(Regex("""\d{4}/\d{2}/\d{2}\([^)]+\)\s*\d{2}:\d{2}:\d{2}(\.\d+)?(ID:[a-zA-Z0-9+/]+)?"""), "")
        cleanedBody = cleanedBody.replace(Regex("""^\d+[\s:]*"""), "")
        cleanedBody = cleanedBody.replace(Regex("""\(.*?\)"""), "")
        cleanedBody = cleanedBody.replace(Regex("""(名無しさん|名無しにかわりまして|焼き鳥|垢版|大砲|[:\s]+)+"""), " ")
        cleanedBody = cleanedBody.replace(Regex("""&gt;&gt;\d+|>>\d+"""), "")
        cleanedBody = cleanedBody.replace(Regex("""ID:[a-zA-Z0-9+/]+"""), "").trim()
        return cleanedBody
    }

    private fun parseThreadAndSave(
        title: String,
        url: String,
        shouldContinue: () -> Boolean
    ): Int {
        return try {
            if (!shouldContinue()) return 0

            val prefs = prefs
            val datUrl = datUrlFromThreadUrl(url)
                ?: throw IllegalArgumentException("DAT URLを生成できません: $url")
            val threadId = threadIdFromUrl(url)
                ?: throw IllegalArgumentException("スレッドIDを取得できません: $url")

            val sizeKey = "dat_size_$threadId"
            val modifiedKey = "dat_modified_$threadId"
            val countKey = "dat_post_count_$threadId"
            val previousSize = prefs.getLong(sizeKey, -1L)
            val previousModified = prefs.getString(modifiedKey, null)
            val previousPostCount = prefs.getInt(countKey, 0)

            // 既存履歴には投稿ヘッダー情報が保存されていないため、
            // このバージョンへの初回移行時だけ全文を再取得して履歴を更新する。
            val needsHeaderMigration = !prefs.getBoolean(PREF_HISTORY_HEADER_V1, false)
            val fetchPreviousSize = if (needsHeaderMigration) -1L else previousSize
            val fetchPreviousModified = if (needsHeaderMigration) null else previousModified

            val fetch = fetchDatDifferential(datUrl, fetchPreviousSize, fetchPreviousModified)
            if (!shouldContinue()) return 0

            if (fetch.notModified) {
                return 0
            }

            val sjis = Charset.forName("Shift_JIS")
            val receivedText = String(fetch.bytes, sjis)
            val datText: String
            val basePostNumber: Int
            val effectiveTotalSize = fetch.totalSize
            val effectiveLastModified = fetch.lastModified

            if (fetch.isFullFetch || fetchPreviousSize < 0L) {
                datText = receivedText
                basePostNumber = 1
            } else {
                // 差分は前回サイズの「次のバイト」から取得しているため、
                // 先頭バイトを削除せず、そのまま新規レスとして解析する。
                datText = receivedText
                basePostNumber = previousPostCount + 1
            }

            val normalizedDatText = if (!fetch.isFullFetch && fetchPreviousSize >= 0L) {
                normalizeDatDifferential(datText)
            } else {
                datText
            }
            val parsed = parseDatPosts(normalizedDatText, shouldContinue)
            if (!shouldContinue()) return 0

            // 差分側のレス番号は「前回までのレス数」を基準にする。
            val allPosts = if (fetch.isFullFetch || fetchPreviousSize < 0L || basePostNumber == 1) {
                parsed.mapIndexed { index, post -> post.copy(number = index + 1) }
            } else {
                parsed.mapIndexed { index, post -> post.copy(number = basePostNumber + index) }
            }

            val totalPostCount = if (fetch.isFullFetch || fetchPreviousSize < 0L || basePostNumber == 1) {
                allPosts.size
            } else {
                previousPostCount + allPosts.size
            }
            val isThreadFull = totalPostCount >= 1000

            data class HistoryEntry(val fullDateTimeKey: String, val roomCode: String, val textContent: String)
            val roomCodeRegex = Regex("""(?<!\d)\d{5}(?!\d)""")
            val idRegex = Regex("""ID:([a-zA-Z0-9+/]+)""")
            val fullDateTimeRegex = Regex("""(\d{4}/\d{2}/\d{2})\([^)]+\)\s*(\d{2}:\d{2}:\d{2})""")

            val existingHistory = prefs.getString(PREF_HISTORY, "") ?: ""
            val existingList = existingHistory.split("-------------------")
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.contains("このスレッドは１０００を超えました") && !it.contains("このスレッドは１００0を超えました") }

            val historyEntries = mutableListOf<HistoryEntry>()
            for (item in existingList) {
                val dateTagMatch = Regex("""<!--DATE:(.*?)-->""").find(item)
                val dateKey = if (dateTagMatch != null) {
                    dateTagMatch.groupValues[1]
                } else {
                    val fallbackMatch = fullDateTimeRegex.find(item)
                    if (fallbackMatch != null) "${fallbackMatch.groupValues[1]} ${fallbackMatch.groupValues[2]}" else "0000/00/00 00:00:00"
                }
                val roomMatch = Regex("""部屋番号:\s*(\d{5})""").find(item) ?: roomCodeRegex.find(item)
                val roomCode = roomMatch?.groupValues?.getOrNull(1) ?: roomMatch?.value ?: ""
                val normalizedItem = normalizeHistoryEntryOrder(item)
                historyEntries.add(HistoryEntry(dateKey, roomCode, normalizedItem))
            }

            var newlyAddedCount = 0

            for (post in allPosts) {
                if (!shouldContinue()) return 0

                // 全角数字を半角数字に正規化したテキストを作成（全角の部屋番号等に対応）
                val normalizedPostText = zenkakuToHankakuDigits(post.text)

                val recruitmentRule = findRecruitmentRule(normalizedPostText)
                if (recruitmentRule == null) continue

                val roomMatch = roomCodeRegex.find(normalizedPostText) ?: continue
                val roomCode = roomMatch.value
                val roomUrl = "https://game.mahjongsoul.com/?room=$roomCode"
                val ruleTag = detectRuleTags(normalizedPostText)
                val targetAnchorRegex = Regex("""(>>|&gt;&gt;)\s*${post.number}\b""")

                val selfReplies = if (post.id.isNotEmpty()) {
                    allPosts.filter { reply ->
                        reply.number > post.number && reply.id == post.id && targetAnchorRegex.containsMatchIn(zenkakuToHankakuDigits(reply.text))
                    }.map { reply ->
                        "  └ [本人返信 ${reply.time}] ${cleanReplyBody(zenkakuToHankakuDigits(reply.text))}"
                    }
                } else emptyList()

                val formattedText = idRegex.replace(normalizedPostText) { matchResult ->
                    "${matchResult.value}\n"
                }
                val replyBlock = if (selfReplies.isNotEmpty()) "\n${selfReplies.joinToString("\n")}" else ""
                val recruitmentKeyword = recruitmentRule.historyLabel
                val rulePrefix = if (ruleTag.isNotBlank()) "$ruleTag " else ""
                val postHeader = "[${post.number}: ${post.author}  ${post.dateAndId}]"
                // 表示順は「募集時刻・部屋番号・リンク → 元レスヘッダー → 本文」。
                // ヘッダーは本文の前に置き、5chの元レス情報を必ず表示する。
                val entryText = "<!--DATE:${post.fullDateTimeKey}--><!--POST:${post.number}|ID:${post.id}-->[${post.time}] ${rulePrefix}部屋番号: $roomCode\n($recruitmentKeyword $roomCode: <a href=\"$roomUrl\">$roomUrl</a>)\n$postHeader\n$formattedText$replyBlock"

                val existingIndex = historyEntries.indexOfFirst { it.roomCode == roomCode }
                if (existingIndex == -1) {
                    historyEntries.add(HistoryEntry(post.fullDateTimeKey, roomCode, entryText))
                    newlyAddedCount++
                } else {
                    historyEntries[existingIndex] = HistoryEntry(post.fullDateTimeKey, roomCode, entryText)
                }
            }

            // 新しく届いたレスが、以前の募集投稿への本人返信だった場合は履歴へ追記する。
            for (reply in allPosts) {
                if (!shouldContinue()) return 0
                if (reply.id.isBlank()) continue
                val targetMatch = Regex("""(>>|&gt;&gt;)\s*(\d+)\b""").find(reply.text) ?: continue
                val targetNumber = targetMatch.groupValues[2].toIntOrNull() ?: continue
                val targetIndex = historyEntries.indexOfFirst { entry ->
                    val postMatch = Regex("""<!--POST:(\d+)\|ID:([^>]*)-->""").find(entry.textContent)
                    postMatch != null && postMatch.groupValues[1].toIntOrNull() == targetNumber && postMatch.groupValues[2] == reply.id
                }
                if (targetIndex >= 0) {
                    val entry = historyEntries[targetIndex]
                    if (!entry.textContent.contains("本人返信 ${reply.time}")) {
                        val updated = entry.textContent + "\n  └ [本人返信 ${reply.time}] ${cleanReplyBody(reply.text)}"
                        historyEntries[targetIndex] = entry.copy(textContent = updated)
                    }
                }
            }

            if (!shouldContinue()) return 0

            val sortedEntries = historyEntries.sortedByDescending { it.fullDateTimeKey }
                .distinctBy { it.roomCode }
                .take(MAX_HISTORY_ENTRIES)
                .map { it.textContent }
                .toMutableList()

            if (isThreadFull) {
                sortedEntries.add(0, "<font color='#FF0000'><b>【このスレッドは１０００を超えました】</b></font>")
            }

            if (sortedEntries.isNotEmpty() || isThreadFull) {
                val updatedHistory = sortedEntries.joinToString("\n-------------------\n") + "\n-------------------"
                prefs.edit().putString(PREF_HISTORY, updatedHistory).apply()
            }

            // 差分取得状態を保存する。次回はこのサイズ位置から取得する。
            prefs.edit()
                .putLong(sizeKey, effectiveTotalSize)
                .putString(modifiedKey, effectiveLastModified)
                .putInt(countKey, totalPostCount)
                .putBoolean(PREF_HISTORY_HEADER_V1, true)
                .apply()

            if (!shouldContinue()) return 0
            if (isThreadFull) -2 else newlyAddedCount
        } catch (e: Exception) {
            recordError("5ch DAT差分巡回・募集解析", e)
            -1
        }
    }

    private fun openUrlOrApp(url: String) {
        // 雀魂の友人戦リンクは、Android版アプリがインストールされていれば
        // アプリを優先して起動し、未インストールならWeb版を開く。
        if (!url.contains("game.mahjongsoul.com", ignoreCase = true)) {
            openInBrowser(url)
            return
        }

        // 日本版「雀魂 -じゃんたま-」のAndroidパッケージ名
        // Google Play: com.YoStarJP.MajSoul
        val packageName = "com.YoStarJP.MajSoul"
        val uri = Uri.parse(url)
        val roomCode = uri.getQueryParameter("room")

        try {
            // ルーム番号がURLに含まれている場合はクリップボードへコピーしておく。
            // Web版へ進む場合にも、ルームIDをすぐ使えるようにしておく。
            if (!roomCode.isNullOrEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("雀魂ルームID", roomCode)
                )
            }

            // 先に雀魂アプリがインストールされているか確認する。
            // 未インストールの場合はPlayストアへ送らず、元の友人戦URLをWebブラウザで開く。
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)

            if (launchIntent == null) {
                val webUrl = if (!roomCode.isNullOrEmpty()) {
                    // 友人戦のルームIDを保持した元URLをそのまま開く。
                    // Web版でログイン後、対応していればこのroomパラメータが利用される。
                    "https://game.mahjongsoul.com/?room=${Uri.encode(roomCode)}"
                } else {
                    "https://game.mahjongsoul.com/"
                }

                Toast.makeText(
                    this,
                    if (!roomCode.isNullOrEmpty()) {
                        "雀魂アプリがないため、Web版の友人戦を開きます（ルームID $roomCode をコピー済み）"
                    } else {
                        "雀魂アプリがないため、Web版の雀魂を開きます"
                    },
                    Toast.LENGTH_LONG
                ).show()

                openInBrowser(webUrl)
                return
            }

            // アプリがインストール済みなら、まず友人戦URLを雀魂アプリへ直接渡す。
            // 雀魂側がディープリンクを受け付けない端末・バージョンでは、
            // 通常のアプリ起動へ進む。
            val deepLinkIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                startActivity(deepLinkIntent)
                Toast.makeText(
                    this,
                    if (!roomCode.isNullOrEmpty()) {
                        "雀魂を起動しました（ルームID $roomCode をコピー済み）"
                    } else {
                        "雀魂を起動しました"
                    },
                    Toast.LENGTH_SHORT
                ).show()
                return
            } catch (_: Exception) {
                // URLを受け付けない端末・バージョンでは通常のアプリ起動へ進む。
            }

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)

            Toast.makeText(
                this,
                if (!roomCode.isNullOrEmpty()) {
                    "雀魂を起動しました。ルームID $roomCode をコピーしています"
                } else {
                    "雀魂を起動しました"
                },
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {
            recordError("雀魂アプリ起動", e)
            Toast.makeText(this, "雀魂を起動できませんでした", Toast.LENGTH_LONG).show()
        }
    }

    private fun openInBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            recordError("外部リンク起動", e)
            Toast.makeText(this, "リンクを開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("youtube_enabled", prefsYoutubeEnabled())
        if (prefsYoutubeEnabled()) {
            try {
                val webState = Bundle()
                webViewYoutube.saveState(webState)
                outState.putBundle("youtube_webview_state", webState)
            } catch (e: Exception) {
                recordError("YouTube画面状態の保存", e)
            }
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        flushYoutubeCookies()
        if (!isChangingConfigurations) {
            hideYoutubeCustomView()
            webViewYoutube.stopLoading()
            webViewYoutube.webChromeClient = null
            webViewYoutube.destroy()
        }
        if (Thread.getDefaultUncaughtExceptionHandler() === installedUncaughtExceptionHandler) {
            Thread.setDefaultUncaughtExceptionHandler(previousUncaughtExceptionHandler)
        }
        super.onDestroy()
    }

    private fun updateUI() {
        val prefs = prefs
        val title = prefs.getString(PREF_LATEST_THREAD_TITLE, "未選択（ボタンを押してスレッドを選択してください）") ?: "未選択"
        val history = prefs.getString(PREF_HISTORY, "まだ募集履歴はありません。") ?: "まだ募集履歴はありません。"

        tvThreadTitle.text = "【選択中のスレ】\n$title"

        val htmlContent = history.replace("\n", "<br>")
        val charSequence = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(htmlContent, Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(htmlContent)
        }

        val builder = SpannableStringBuilder(charSequence)
        val urls = builder.getSpans(0, builder.length, URLSpan::class.java)

        for (span in urls) {
            val start = builder.getSpanStart(span)
            val end = builder.getSpanEnd(span)
            val flags = builder.getSpanFlags(span)
            val url = span.url

            val clickableSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    openUrlOrApp(url)
                }

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.color = Color.parseColor("#448AFF")
                    ds.isUnderlineText = true
                }
            }

            builder.removeSpan(span)
            builder.setSpan(clickableSpan, start, end, flags)
        }

        tvHistory.text = builder
    }

    private fun zenkakuToHankakuDigits(input: String): String {
        val zenkaku = "０１２３４５６７８９"
        val hankaku = "0123456789"
        return input.map { char ->
            val index = zenkaku.indexOf(char)
            if (index != -1) hankaku[index] else char
        }.joinToString("")
    }
}