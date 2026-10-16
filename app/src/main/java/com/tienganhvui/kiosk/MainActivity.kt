package com.tienganhvui.kiosk

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

class MainActivity : ComponentActivity() {

    companion object {
        private const val HOME_URL = "https://tienganhvui.com/"

        // Danh sách tên miền cho phép: Trang học chính + Hệ thống đăng nhập Google/Firebase/Auth0
        private val ALLOWED_DOMAINS = listOf(
            "tienganhvui.com",
            "google.com",
            "accounts.google.com",
            "googleapis.com",
            "gstatic.com",
            "googleusercontent.com",
            "firebaseapp.com",
            "auth0.com"
        )

        // MÃ PIN MẶC ĐỊNH CỦA BA MẸ: 1993 (Không cần làm phép toán)
        private const val DEFAULT_PARENT_PIN = "1993"

        // Chạm 5 lần liên tiếp vào góc trên bên trái trong 2.5 giây để mở bảng nhập PIN
        private const val SECRET_TAP_COUNT_REQUIRED = 5
        private const val SECRET_TAP_WINDOW_MS = 2500L

        private const val PREFS_NAME = "TiengAnhVuiKioskPrefs"
        private const val KEY_PIN = "saved_parent_pin"

        // User-Agent Chrome chuẩn trên Tablet để Google không chặn đăng nhập OAuth
        private const val CHROME_TABLET_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel Tablet) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences
    private var tapCount = 0
    private var firstTapTimestamp = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Giữ màn hình luôn sáng khi bé học
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.WHITE)
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Vùng chạm bí mật 80dp x 80dp ở GÓC TRÊN BÊN TRÁI dành cho Ba Mẹ
        val density = resources.displayMetrics.density
        val triggerSizePx = (80 * density).toInt()
        val secretParentTrigger = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(triggerSizePx, triggerSizePx, Gravity.TOP or Gravity.START)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { handleSecretCornerTap() }
        }

        rootLayout.addView(webView)
        rootLayout.addView(secretParentTrigger)
        setContentView(rootLayout)

        configureWebView(webView)
        setupBackButtonInterceptor()
        setupEdgeGestureExclusion(rootLayout)

        enableImmersiveFullscreen()
        activateKioskLockTask()

        if (savedInstanceState == null) {
            webView.loadUrl(HOME_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(targetWebView: WebView) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(targetWebView, true)

        targetWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // Lưu token đăng nhập lâu dài vào LocalStorage
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true) // Hỗ trợ mở cửa sổ đăng nhập Google (OAuth Popup)
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            userAgentString = CHROME_TABLET_USER_AGENT
        }

        targetWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false
                val host = url.host?.lowercase() ?: ""

                val isAllowed = ALLOWED_DOMAINS.any { domain ->
                    host == domain || host.endsWith(".$domain")
                }

                return if (isAllowed) {
                    false
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Đã chặn liên kết ngoài ($host) để bảo vệ bé!",
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
            }
        }

        targetWebView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val popupWebView = WebView(this@MainActivity)
                configureWebView(popupWebView)

                val dialog = Dialog(this@MainActivity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                dialog.setContentView(popupWebView)
                dialog.setOnDismissListener {
                    CookieManager.getInstance().flush()
                    enableImmersiveFullscreen()
                }
                dialog.show()

                popupWebView.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) {
                        CookieManager.getInstance().flush()
                        dialog.dismiss()
                        enableImmersiveFullscreen()
                    }
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = popupWebView
                resultMsg?.sendToTarget()
                return true
            }
        }
    }

    /**
     * Ký hiệu riêng cho Ba Mẹ: Chạm 5 lần liên tiếp vào góc trên bên trái trong 2.5 giây
     */
    private fun handleSecretCornerTap() {
        val now = SystemClock.elapsedRealtime()
        if (now - firstTapTimestamp > SECRET_TAP_WINDOW_MS) {
            tapCount = 1
            firstTapTimestamp = now
        } else {
            tapCount++
        }

        if (tapCount >= SECRET_TAP_COUNT_REQUIRED) {
            tapCount = 0
            firstTapTimestamp = 0L
            showParentalGateDialog()
        }
    }

    /**
     * Hộp thoại xác thực Ba Mẹ: Chỉ cần nhập đúng Mã PIN (1993), đã bỏ phép tính
     */
    private fun showParentalGateDialog() {
        val currentPin = prefs.getString(KEY_PIN, DEFAULT_PARENT_PIN) ?: DEFAULT_PARENT_PIN

        val paddingPx = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }

        val infoText = TextView(this).apply {
            text = "Nhập Mã PIN của Ba Mẹ để thoát ứng dụng:"
            textSize = 15f
        }

        val pinInput = EditText(this).apply {
            hint = "Nhập Mã PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        container.addView(infoText)
        container.addView(pinInput)

        AlertDialog.Builder(this)
            .setTitle("Khóa Phụ Huynh")
            .setView(container)
            .setCancelable(true)
            .setPositiveButton("Thoát App") { _, _ ->
                val enteredPin = pinInput.text.toString().trim()
                if (enteredPin == currentPin) {
                    exitKioskAndCloseApp()
                } else {
                    Toast.makeText(this, "Sai Mã PIN!", Toast.LENGTH_SHORT).show()
                    enableImmersiveFullscreen()
                }
            }
            .setNeutralButton("Đổi Mã PIN") { _, _ ->
                val enteredPin = pinInput.text.toString().trim()
                if (enteredPin == currentPin) {
                    showChangePinDialog()
                } else {
                    Toast.makeText(this, "Vui lòng nhập đúng PIN hiện tại trước!", Toast.LENGTH_SHORT).show()
                    enableImmersiveFullscreen()
                }
            }
            .setNegativeButton("Về Trang Chủ") { _, _ ->
                webView.loadUrl(HOME_URL)
                enableImmersiveFullscreen()
            }
            .setOnDismissListener {
                enableImmersiveFullscreen()
            }
            .show()
    }

    private fun showChangePinDialog() {
        val pinEdit = EditText(this).apply {
            hint = "Nhập Mã PIN mới"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getString(KEY_PIN, DEFAULT_PARENT_PIN))
        }

        AlertDialog.Builder(this)
            .setTitle("Đổi Mã PIN Phụ Huynh")
            .setView(pinEdit)
            .setPositiveButton("Lưu PIN Mới") { _, _ ->
                val newPin = pinEdit.text.toString().trim().ifEmpty { DEFAULT_PARENT_PIN }
                prefs.edit().putString(KEY_PIN, newPin).apply()
                Toast.makeText(this, "Đã lưu mã PIN mới!", Toast.LENGTH_SHORT).show()
                enableImmersiveFullscreen()
            }
            .setNegativeButton("Hủy") { _, _ -> enableImmersiveFullscreen() }
            .show()
    }

    private fun setupBackButtonInterceptor() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                }
                enableImmersiveFullscreen()
            }
        })
    }

    private fun setupEdgeGestureExclusion(rootView: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            rootView.addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                val edgeWidth = (48 * resources.displayMetrics.density).toInt()
                val leftEdge = Rect(0, top, edgeWidth, bottom)
                val rightEdge = Rect(right - edgeWidth, top, right, bottom)
                v.systemGestureExclusionRects = listOf(leftEdge, rightEdge)
            }
        }
    }

    private fun enableImmersiveFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    private fun activateKioskLockTask() {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (activityManager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
                startLockTask()
            }
        } catch (_: Exception) {
        }
    }

    private fun exitKioskAndCloseApp() {
        CookieManager.getInstance().flush()
        try {
            stopLockTask()
        } catch (_: Exception) {
        }
        finishAndRemoveTask()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveFullscreen()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_APP_SWITCH || keyCode == KeyEvent.KEYCODE_HOME) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
