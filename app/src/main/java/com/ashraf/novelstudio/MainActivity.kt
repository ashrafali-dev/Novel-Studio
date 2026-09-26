package com.ashraf.novelstudio

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.net.URLEncoder

class MainActivity : Activity() {
    private enum class Mode { NOVEL, CHAT, SPLIT }

    private val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    // views
    private lateinit var novelWv: WebView
    private lateinit var chatWv: WebView
    private lateinit var activeWv: WebView
    private lateinit var urlBar: EditText
    private lateinit var content: FrameLayout
    private lateinit var novelBox: FrameLayout
    private lateinit var chatBox: LinearLayout
    private lateinit var strip: LinearLayout
    private lateinit var modeBtn: TextView
    private lateinit var autoBtn: TextView
    private lateinit var progressBox: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var progressTv: TextView
    private lateinit var togglePill: TextView
    private var pulse: ValueAnimator? = null

    // state
    private var mode = Mode.SPLIT
    private var lastChapter: Chapter? = null
    private var lastCopied = ""
    private var exportNovel: String? = null
    private var hasTr = false                 // a translation is currently shown on the novel page
    private var shownTranslated = false
    private val handler = Handler(Looper.getMainLooper())

    // navigation (▶ ◀ ●) — every action gets a fresh token, old callbacks with an old token are ignored
    private var navToken = 0
    private var autoCopy = false
    private var polling = false
    private var pendUrl = ""
    private var pendHash = 0

    // WebNovel navigation: load the catalog in the same WebView, keep it
    // behind the loading overlay, extract the adjacent chapter URL, then
    // immediately load that chapter.
    private var webNovelCatalogPending = false
    private var webNovelCatalogDir = ""
    private var webNovelCatalogTitle = ""
    private var webNovelCatalogOldHash = 0
    private var webNovelCatalogToken = 0

    // background translation queue
    private val queue = mutableListOf<Chapter>()
    private val freshQueueKeys = mutableSetOf<String>()
    private var running: Chapter? = null
    private var runToken = 0
    private var progress = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    // ================================================================== UI
    private fun chip(label: String, onClick: () -> Unit, onLong: (() -> Unit)?): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(0xFFFFFFFF.toInt())
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(0xFF3A3A44.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(WC, WC).also { it.setMargins(dp(4), dp(4), dp(4), dp(4)) }
        setOnClickListener { onClick() }
        if (onLong != null) setOnLongClickListener { onLong(); true }
    }

    private fun barBtn(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdBlock.load(this)
        CookieManager.getInstance().setAcceptCookie(true)

        novelWv = WebView(this)
        chatWv = WebView(this)
        setup(novelWv)
        setup(chatWv)
        novelWv.settings.setSupportMultipleWindows(true)
        novelWv.settings.javaScriptCanOpenWindowsAutomatically = false
        novelWv.webViewClient = NovelClient()
        novelWv.webChromeClient = NovelChrome()
        chatWv.webViewClient = ChatClient()
        chatWv.settings.setSupportMultipleWindows(true)
        chatWv.settings.javaScriptCanOpenWindowsAutomatically = true
        chatWv.webChromeClient = ChatChrome()
        activeWv = novelWv
        novelWv.setOnTouchListener { _, _ -> activeWv = novelWv; false }
        chatWv.setOnTouchListener { _, _ -> activeWv = chatWv; false }

        // ---- top bar
        urlBar = EditText(this).apply {
            hint = "সার্চ করো বা লিংক দাও"
            setSingleLine()
            textSize = 14f
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, _, _ -> go(this.text.toString()); true }
        }
        val reloadBtn = TextView(this).apply {
            text = "⟳"
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { reloadPage() }
            setOnLongClickListener { novelWv.reload(); chatWv.reload(); toast("🔄 দুটোই রিলোড হচ্ছে"); true }
        }
        val goBtn = TextView(this).apply {
            text = "Go"
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(0xFF4F7CFF.toInt())
            setOnClickListener { go(urlBar.text.toString()) }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF202024.toInt())
            setPadding(dp(6), dp(2), dp(6), dp(2))
            addView(urlBar, LinearLayout.LayoutParams(0, WC, 1f))
            addView(reloadBtn, LinearLayout.LayoutParams(dp(44), dp(40)))
            addView(goBtn, LinearLayout.LayoutParams(dp(48), dp(40)))
        }

        // ---- panes (both always full-size and alive; the one on top is the one you see)
        novelBox = FrameLayout(this).apply { addView(novelWv, FrameLayout.LayoutParams(MP, MP)) }
        strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val stripScroll = HorizontalScrollView(this).apply {
            setBackgroundColor(0xFF202024.toInt())
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
        chatBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(stripScroll, LinearLayout.LayoutParams(MP, WC))
            addView(chatWv, LinearLayout.LayoutParams(MP, 0, 1f))
        }

        // ---- translation progress (thin bar + label, pulses while working)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
            progressTintList = ColorStateList.valueOf(0xFF4F7CFF.toInt())
            progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
        }
        progressTv = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), dp(3), dp(10), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xCC000000.toInt())
            }
            setOnClickListener { cancelAll() }
        }
        progressBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            addView(progressBar, LinearLayout.LayoutParams(MP, dp(4)))
            addView(progressTv, LinearLayout.LayoutParams(WC, WC).also { it.topMargin = dp(4) })
        }
        togglePill = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(12), dp(7), dp(12), dp(7))
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xCC4F7CFF.toInt())
            }
            setOnClickListener { toggleView() }
        }

        content = FrameLayout(this).apply {
            addView(novelBox, FrameLayout.LayoutParams(MP, MP))
            addView(chatBox, FrameLayout.LayoutParams(MP, MP))
            addView(progressBox, FrameLayout.LayoutParams(MP, WC, Gravity.TOP))
            addView(togglePill, FrameLayout.LayoutParams(WC, WC, Gravity.BOTTOM or Gravity.END).also { it.setMargins(0, 0, dp(10), dp(10)) })
            addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot) applyLayout() }
        }

        // ---- bottom bar
        modeBtn = barBtn("◫") { cycleMode() }
        autoBtn = barBtn("⚡") { toggleAuto() }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF202024.toInt())
            addView(modeBtn)
            addView(barBtn("📝") { deliver(Prefs.prompt(this@MainActivity), "প্রম্পট") })
            addView(barBtn("◀") { step("prev") })
            addView(barBtn("●") { extractCopy() })
            addView(barBtn("▶") { step("next") })
            addView(barBtn("💾") { saveAnswer() })
            addView(autoBtn)
            addView(barBtn("☰") { menu() })
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111114.toInt())
            addView(top, LinearLayout.LayoutParams(MP, WC))
            addView(content, LinearLayout.LayoutParams(MP, 0, 1f))
            addView(bar, LinearLayout.LayoutParams(MP, WC))
        }
        setContentView(root)

        buildStrip()
        setMode(Mode.SPLIT)
        refreshAutoBtn()
        applyDark()
        novelWv.loadUrl(Prefs.get(this, "lastNovelUrl", "https://duckduckgo.com/"))
        val firstBot = bots().getJSONObject(0).getString("u")
        chatWv.loadUrl(Prefs.get(this, "botUrl", firstBot))
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // Browser -> Share -> Novel Studio : open that link in the novel pane
    private fun handleIntent(i: Intent?) {
        val it = i ?: return
        if (it.action != Intent.ACTION_SEND) return
        val t = it.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val m = Regex("https?://\\S+").find(t) ?: return
        setMode(Mode.NOVEL)
        novelWv.loadUrl(m.value)
    }

    private fun setup(wv: WebView) {
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.userAgentString = UA
        wv.setBackgroundColor(0xFF111114.toInt())
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
        }
    }

    // ⟳ : reload the pane you are looking at (long press = reload both)
    private fun reloadPage() {
        val wv = if (mode == Mode.CHAT) chatWv else activeWv
        wv.reload()
        toast(if (wv === chatWv) "🔄 চ্যাটবট রিলোড হচ্ছে…" else "🔄 নোভেল পেজ রিলোড হচ্ছে…")
    }

    // ================================================================== dark mode (0 off, 1 auto, 2 force)
    private fun darkMode(): Int = Prefs.get(this, "dark", "0").toIntOrNull() ?: 0

    @Suppress("DEPRECATION")
    private fun applyDark() {
        val d = darkMode()
        for (wv in listOf(novelWv, chatWv)) {
            val on = d != 0
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, on)
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(wv.settings, if (on) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF)
            }
        }
        novelWv.evaluateJavascript(if (d == 2) Js.DARK_ON else Js.DARK_OFF, null)
    }

    // ================================================================== modes / layout
    private fun setMode(m: Mode) {
        mode = m
        modeBtn.text = when (m) {
            Mode.NOVEL -> "📖"
            Mode.CHAT -> "💬"
            Mode.SPLIT -> "◫"
        }
        activeWv = if (m == Mode.CHAT) chatWv else novelWv
        applyLayout()
        updatePill()
    }

    // The hidden pane stays full-size right underneath the visible one, so its page keeps running normally
    private fun applyLayout() {
        val h = content.height
        if (h <= 0) return
        val nl = FrameLayout.LayoutParams(MP, MP)
        val cl = FrameLayout.LayoutParams(MP, MP)
        when (mode) {
            Mode.NOVEL -> novelBox.bringToFront()
            Mode.CHAT -> chatBox.bringToFront()
            Mode.SPLIT -> {
                val half = h / 2
                nl.height = half - dp(1)
                nl.gravity = Gravity.TOP
                cl.height = h - half - dp(1)
                cl.gravity = Gravity.BOTTOM
            }
        }
        novelBox.layoutParams = nl
        chatBox.layoutParams = cl
        progressBox.bringToFront()
        togglePill.bringToFront()
    }

    private fun cycleMode() {
        setMode(when (mode) {
            Mode.SPLIT -> Mode.NOVEL
            Mode.NOVEL -> Mode.CHAT
            Mode.CHAT -> Mode.SPLIT
        })
    }

    // ================================================================== chatbots
    private fun bot(n: String, u: String) = JSONObject().put("n", n).put("u", u)

    private fun bots(): JSONArray {
        val s = Prefs.get(this, "bots")
        if (s.isNotEmpty()) {
            try { return JSONArray(s) } catch (e: Exception) {}
        }
        return JSONArray()
            .put(bot("ChatGPT", "https://chatgpt.com/"))
            .put(bot("Gemini", "https://gemini.google.com/app"))
            .put(bot("Claude", "https://claude.ai/"))
            .put(bot("DeepSeek", "https://chat.deepseek.com/"))
            .put(bot("Grok", "https://grok.com/"))
    }

    private fun buildStrip() {
        strip.removeAllViews()
        val a = bots()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            strip.addView(chip(o.getString("n"), { openBot(o.getString("u")) }, { removeBot(i) }))
        }
        strip.addView(chip("＋", { addBotDialog() }, null))
    }

    private fun openBot(u: String) {
        chatWv.loadUrl(u)
        Prefs.put(this, "botUrl", u)
    }

    private fun removeBot(i: Int) {
        AlertDialog.Builder(this)
            .setMessage("এই চ্যাটবট লিস্ট থেকে মুছবে?")
            .setPositiveButton("মুছো") { _, _ ->
                val a = bots()
                a.remove(i)
                Prefs.put(this, "bots", a.toString())
                buildStrip()
            }
            .setNegativeButton("না", null)
            .show()
    }

    private fun addBotDialog() {
        val n = EditText(this).apply { hint = "নাম" }
        val u = EditText(this).apply {
            hint = "https://…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(n)
            addView(u)
        }
        AlertDialog.Builder(this)
            .setTitle("চ্যাটবট যোগ করো")
            .setView(l)
            .setPositiveButton("যোগ") { _, _ ->
                var url = u.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                if (!url.startsWith("http")) url = "https://$url"
                val a = bots()
                a.put(bot(n.text.toString().ifBlank { url }, url))
                Prefs.put(this, "bots", a.toString())
                buildStrip()
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun openLoginInChrome(url: String) {
        val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
        if (packageManager.getLaunchIntentForPackage("com.android.chrome") != null) {
            tab.intent.setPackage("com.android.chrome")
        }
        try {
            tab.launchUrl(this, Uri.parse(url))
            toast("🔐 লগইন Chrome-এ খুলেছি — শেষ হলে Novel Studio-তে ফিরে আসো")
        } catch (_: Exception) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            catch (_: Exception) { toast("❌ লগইন পেজ খোলা গেল না") }
        }
    }

    private fun isChatLoginUrl(uri: Uri): Boolean {
        val host = (uri.host ?: "").lowercase()
        val path = (uri.path ?: "").lowercase()
        if (host == "accounts.google.com" || host.endsWith(".accounts.google.com")) return true
        val bot = host.contains("chatgpt.com") || host.contains("openai.com") ||
            host.contains("gemini.google.com") || host.contains("claude.ai") ||
            host.contains("anthropic.com") || host.contains("deepseek.com") ||
            host.contains("grok.com") || host == "x.com" || host.endsWith(".x.com")
        return bot && (path.contains("/login") || path.contains("/signin") ||
            path.contains("/sign-in") || path.contains("/auth") ||
            path.contains("/oauth") || path.contains("/authorize"))
    }

    private inner class ChatClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val r = request ?: return false
            val scheme = r.url.scheme ?: ""
            if (scheme != "http" && scheme != "https") return true
            if (isChatLoginUrl(r.url)) {
                openLoginInChrome(r.url.toString())
                return true
            }
            return false
        }
    }

    private inner class ChatChrome : WebChromeClient() {
        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            if (!isUserGesture || resultMsg == null) return false
            val popup = WebView(this@MainActivity)
            setup(popup)
            popup.webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                    if (url != null && isChatLoginUrl(Uri.parse(url))) {
                        openLoginInChrome(url)
                        v?.stopLoading()
                    }
                }
                override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                    val u = r?.url ?: return false
                    if (isChatLoginUrl(u)) {
                        openLoginInChrome(u.toString())
                        v?.stopLoading()
                        return true
                    }
                    return false
                }
            }
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            transport.webView = popup
            resultMsg.sendToTarget()
            return true
        }
    }

    // ================================================================== navigation / search
    private fun go(s0: String) {
        val s = s0.trim()
        if (s.isEmpty()) return
        val u = when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            !s.contains(' ') && s.contains('.') -> "https://$s"
            else -> "https://duckduckgo.com/?q=" + URLEncoder.encode(s, "UTF-8")
        }
        if (mode == Mode.CHAT) setMode(Mode.SPLIT)
        novelWv.loadUrl(u)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
        urlBar.clearFocus()
    }

    private inner class NovelClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val r = request ?: return null
            if (Prefs.adblock(this@MainActivity) && !r.isForMainFrame && AdBlock.blocked(r.url)) {
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val r = request ?: return false
            val scheme = r.url.scheme ?: ""
            if (scheme != "http" && scheme != "https") return true
            if (Prefs.adblock(this@MainActivity) && r.isForMainFrame && !r.hasGesture()) {
                if (AdBlock.blocked(r.url)) return true
                val cur = Uri.parse(view?.url ?: "").host
                val nh = r.url.host
                if (cur != null && nh != null && AdBlock.root(cur) != AdBlock.root(nh)) return true
            }
            return false
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (url != null && !urlBar.hasFocus()) urlBar.setText(url)
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            if (darkMode() == 2) view?.evaluateJavascript(Js.DARK_ON, null)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (Prefs.adblock(this@MainActivity)) view?.evaluateJavascript(AdBlock.cosmeticJs(), null)
            if (darkMode() == 2) view?.evaluateJavascript(Js.DARK_ON, null)
            if (url != null) Prefs.put(this@MainActivity, "lastNovelUrl", url)

            if (view === novelWv && webNovelCatalogPending) {
                val tk = webNovelCatalogToken
                val dir = webNovelCatalogDir
                val title = webNovelCatalogTitle
                val oldHash = webNovelCatalogOldHash
                webNovelCatalogPending = false
                view.evaluateJavascript(Js.webNovelPickCatalog(dir, title)) { raw ->
                    if (tk != navToken) return@evaluateJavascript
                    val target = decode(raw)
                    if (target.startsWith("http")) {
                        pendHash = oldHash
                        pendUrl = cleanUrl(target)
                        val autoExtract = Prefs.bool(this@MainActivity, "autoExtractNext", true)
                        autoCopy = autoExtract
                        polling = false
                        view.loadUrl(target)
                    } else {
                        hideNavLoading()
                        autoCopy = false
                        polling = false
                        toast(
                            if (target == "edge") "ℹ️ আর কোনো chapter নেই"
                            else "❌ WebNovel chapter link পাওয়া যায়নি"
                        )
                    }
                }
                return
            }

            if (view === novelWv && !autoCopy && !webNovelCatalogPending && navToken > 0) {
                hideNavLoading()
            }

            if (autoCopy && !polling) {
                polling = true
                val tk = navToken
                handler.postDelayed({ pollExtract(0, tk) }, 150)
            }
        }
    }

    private inner class NovelChrome : WebChromeClient() {
        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            if (!isUserGesture || resultMsg == null) return false
            val t = WebView(this@MainActivity)
            t.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                    val u = r?.url?.toString()
                    if (u != null && u.startsWith("http")) novelWv.loadUrl(u)
                    handler.post { v?.destroy() }
                    return true
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = t
            resultMsg.sendToTarget()
            return true
        }
    }

    // ================================================================== extraction
    private fun decode(raw: String?): String = try {
        if (raw == null || raw == "null") "" else JSONArray("[$raw]").getString(0)
    } catch (e: Exception) { "" }

    /**
     * Fast chapter extraction: serialize only the chapter node, not the whole page.
     * This avoids multi-MB outerHTML parsing on ad-heavy sites.
     */
    private fun extractNow(cb: (Chapter?) -> Unit) {
        val url = novelWv.url ?: ""
        if (url.isBlank()) { cb(null); return }

        val contentSel = SiteProfiles.selector(this, url, "content")
        val titleSel = SiteProfiles.selector(this, url, "title")
        val nextSel = SiteProfiles.selector(this, url, "next")
        val prevSel = SiteProfiles.selector(this, url, "prev")

        // IMPORTANT: do not return the whole chapter from one evaluateJavascript()
        // result. Large WebView -> Android results cross an IPC boundary; sending a
        // huge JSON string at once can truncate/fail on long chapters. Store the
        // rendered text in the page and pull it in small chunks instead.
        val initJs = """
            (function(){
              function visible(e){
                if(!e)return false;
                var r=e.getBoundingClientRect();
                return r.width>0&&r.height>0;
              }
              function txt(e){return ((e&&(e.innerText||e.textContent))||'').trim();}
              // A bare tag name ("div", "article") matches dozens of elements
              // on most pages, so document.querySelector(sel) later can grab
              // the wrong one silently (no error, just replaces the wrong
              // element). Prefer id, then tag+classnames; if neither gives a
              // reasonably specific selector, return '' so the caller falls
              // back to its own content-detection instead of trusting a
              // selector that matches almost anything.
              function stableSel(e){
                if(!e)return '';
                if(e.id&&/^[A-Za-z_][A-Za-z0-9_-]*$/.test(e.id))return '#'+e.id;
                var cls=(e.className&&typeof e.className==='string')
                  ? e.className.split(/\s+/).filter(function(c){return /^[A-Za-z_][A-Za-z0-9_-]*$/.test(c);}).slice(0,3)
                  : [];
                if(cls.length)return e.tagName.toLowerCase()+'.'+cls.join('.');
                return '';
              }
              function pick(sel){
                if(!sel)return null;
                try{var e=document.querySelector(sel);return e&&visible(e)?e:null;}catch(x){return null;}
              }
              var content=pick(__CONTENT__);

              var sels=['#chapter-content','.chapter-content','.chapter_content','#chr-content','.chr-c',
                '.reading-content','.text-left','#content','.entry-content','.cha-content','.cha-words',
                '.chapter-body','.novel_content','.j_readContent','.txt','#chaptercontent','.chapter-c',
                '#article','.article-content','.content','article','main'];

              function bestCandidate(){
                var best=null,score=0;
                for(var i=0;i<sels.length;i++){
                  var es=[];
                  try{es=[].slice.call(document.querySelectorAll(sels[i]));}catch(x){es=[];}
                  for(var j=0;j<es.length;j++){
                    var e=es[j];
                    if(!e||!visible(e))continue;
                    var t=txt(e);
                    if(t.length<300)continue;
                    var sc=t.length+(e.querySelectorAll('p').length*250);
                    if(sc>score){score=sc;best=e;}
                  }
                }
                return best;
              }

              // A learned selector is preferred, but if it only contains a
              // fraction of the actual chapter, choose the stronger chapter
              // candidate instead of blindly trusting the old profile.
              var learnedText=content?txt(content):'';
              var candidate=bestCandidate();
              var candidateText=candidate?txt(candidate):'';
              if(!content || (candidateText.length > learnedText.length*1.35 && candidateText.length-learnedText.length>500)){
                content=candidate;
              }
              if(!content)return JSON.stringify({ok:false});

              var body=txt(content);
              window.__nsExtractText=body;

              var titleEl=pick(__TITLE__);
              if(!titleEl){
                try{titleEl=document.querySelector('.chapter-title,.chr-title,#chapter-heading,h1,h2');}catch(x){}
              }
              var title=txt(titleEl);
              if(!title)title=document.title||'';

              function link(kind,sel){
                var e=pick(sel);
                if(!e){
                  var q=kind==='next'
                    ? 'link[rel="next"],a[rel="next"],a.next,.next a,[aria-label*="next" i],[title*="next" i]'
                    : 'link[rel="prev"],a[rel="prev"],a.prev,.prev a,[aria-label*="prev" i],[title*="prev" i]';
                  try{e=document.querySelector(q);}catch(x){e=null;}
                }
                if(e&&e.href)return {href:e.href,text:txt(e),selector:stableSel(e)};
                return null;
              }

              var n=link('next',__NEXT__),p=link('prev',__PREV__);
              var novel='';
              try{
                var m=document.querySelector('meta[property="og:novel:book_name"],meta[property="og:novel:novel_name"],meta[name="book_name"]');
                novel=m?(m.getAttribute('content')||'').trim():'';
              }catch(x){}
              if(!novel){
                try{
                  var aa=document.querySelector('a[href*="/novel/"],a[href*="/book/"],a[href*="/series/"]');
                  if(aa)novel=txt(aa);
                }catch(x){}
              }

              return JSON.stringify({
                ok:true,
                length:body.length,
                title:title,
                pageTitle:document.title||'',
                novel:novel,
                contentSel:stableSel(content),
                titleSel:titleEl?stableSel(titleEl):'',
                next:n,
                prev:p
              });
            })()
        """.trimIndent()
            .replace("__CONTENT__", JSONObject.quote(contentSel))
            .replace("__TITLE__", JSONObject.quote(titleSel))
            .replace("__NEXT__", JSONObject.quote(nextSel))
            .replace("__PREV__", JSONObject.quote(prevSel))

        novelWv.evaluateJavascript(initJs) { raw ->
            val payload = decode(raw)
            if (payload.isBlank()) { cb(null); return@evaluateJavascript }

            try {
                val o = JSONObject(payload)
                if (!o.optBoolean("ok", false)) {
                    cb(null)
                    return@evaluateJavascript
                }

                val expectedLength = o.optInt("length", 0)
                if (expectedLength < 300) {
                    cb(null)
                    return@evaluateJavascript
                }

                val chunkSize = 48_000
                val parts = ArrayList<String>()
                fun readChunk(start: Int) {
                    val chunkJs = "(function(){var s=window.__nsExtractText||'';return s.substring(" +
                        start + "," + (start + chunkSize) + ");})()"
                    novelWv.evaluateJavascript(chunkJs) { rr ->
                        try {
                            val piece = decode(rr)
                            parts.add(piece)
                            val nextStart = start + piece.length
                            if (nextStart < expectedLength && piece.isNotEmpty()) {
                                readChunk(nextStart)
                                return@evaluateJavascript
                            }

                            val body = parts.joinToString("")
                            if (body.length < 300) {
                                cb(null)
                                return@evaluateJavascript
                            }

                            fun cleanText(s: String): String = s
                                .replace("\u00a0", " ")
                                .replace(Regex("[ \\t]+"), " ")
                                .replace(Regex(" ?\\n ?"), "\\n")
                                .replace(Regex("\\n{3,}"), "\\n\\n")
                                .trim()

                            val cleanBody = cleanText(body)
                            val cleanTitle = cleanText(o.optString("title", "").trim())
                            val fullText = if (cleanTitle.isNotEmpty() && cleanBody.startsWith(cleanTitle)) {
                                cleanBody
                            } else if (cleanTitle.isNotEmpty()) {
                                cleanTitle + "\n\n" + cleanBody
                            } else {
                                cleanBody
                            }

                            val next = o.optJSONObject("next")?.optString("href", "")?.takeIf { it.isNotBlank() }
                            val prev = o.optJSONObject("prev")?.optString("href", "")?.takeIf { it.isNotBlank() }
                            val novel = o.optString("novel", "").trim().ifBlank {
                                url.substringAfter("://", "").substringBefore('/').removePrefix("www.")
                            }

                            val number = Regex(
                                "\\b(?:chapter|chap|ch|episode|ep)\\.?\\s*[-#:.]?\\s*(\\d+(?:\\.\\d+)?)",
                                RegexOption.IGNORE_CASE
                            ).find(cleanTitle)?.groupValues?.getOrNull(1)
                                ?: Regex("(\\d{1,5})").find(cleanTitle)?.value.orEmpty()

                            val ch = Chapter(
                                cleanTitle.ifBlank { o.optString("pageTitle", "").trim() },
                                fullText,
                                next,
                                prev,
                                url,
                                novel,
                                number,
                                o.optString("contentSel", ""),
                                o.optString("titleSel", ""),
                                o.optJSONObject("next")?.optString("selector", "").orEmpty(),
                                o.optJSONObject("prev")?.optString("selector", "").orEmpty()
                            )

                            SiteProfiles.remember(this@MainActivity, ch)
                            cb(ch)
                        } catch (_: Exception) {
                            cb(null)
                        }
                    }
                }
                readChunk(0)
            } catch (_: Exception) {
                cb(null)
            }
        }
    }

    private fun keyOf(ch: Chapter): String = if (ch.number.isNotEmpty()) ch.novel + "#" + ch.number else ch.url

    // Hash only the chapter body, not the heading. Some SPA readers update
    // the chapter title before replacing the actual chapter text.
    private fun bodyHash(ch: Chapter): Int {
        // Readers such as WTR-LAB can temporarily contain the old chapter
        // body while the new heading is already visible. Some pages also
        // duplicate the old heading inside the content during that swap.
        // Remove ALL leading copies of the heading before hashing.
        var body = ch.text.trim()
        val title = ch.title.trim()
        if (title.isNotEmpty()) {
            var guard = 0
            while (guard++ < 4 && body.startsWith(title, ignoreCase = false)) {
                body = body.removePrefix(title).trimStart()
            }
        }
        return body.hashCode()
    }

    private fun cleanUrl(u: String): String = u.substringBefore('#').trimEnd('/')

    private fun isNewPage(ch: Chapter, oldUrl: String, oldHash: Int): Boolean {
        // WebNovel can reuse the reader URL while replacing the chapter.
        // Accept either a changed body OR a changed chapter title.
        val oldTitle = lastChapter?.title?.trim()?.lowercase().orEmpty()
        val newTitle = ch.title.trim().lowercase()
        val titleChanged = oldTitle.isNotEmpty() && newTitle.isNotEmpty() && oldTitle != newTitle
        return ch.text.length > 300 && (bodyHash(ch) != oldHash || titleChanged)
    }

    private fun onChapter(ch: Chapter) {
        lastChapter = ch
        Store.touchBookmark(this, ch)
        updateProgressUi()
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("novel", text))
        lastCopied = text
    }

    private fun clearClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
        lastCopied = ""
    }

    // ================================================================== ● ▶ ◀
    // Same-URL/SPA readers must keep their document alive after a JS click.
    // We hide the old page immediately instead of destroying its DOM, because
    // destroying documentElement can cancel the site's own chapter transition.
    private fun showNavLoading() {
        novelWv.evaluateJavascript(
            """
            (function(){
                try {
                    var old=document.getElementById('__ns_nav_loading');
                    if(old) old.remove();
                    var x=document.createElement('div');
                    x.id='__ns_nav_loading';
                    x.style.cssText='position:fixed;inset:0;z-index:2147483647;background:#111;color:#aaa;display:flex;align-items:center;justify-content:center;font:16px sans-serif;';
                    x.textContent='Loading chapter…';
                    (document.body||document.documentElement).appendChild(x);
                } catch(e) {}
                return 'loading';
            })();
            """.trimIndent(), null
        )
    }

    private fun hideNavLoading() {
        novelWv.evaluateJavascript(
            "(function(){var x=document.getElementById('__ns_nav_loading');if(x)x.remove();return 'ok';})()",
            null
        )
    }

    // Throw away app state and, for direct URL navigation, the old page DOM.
    private fun wipeStale(clearNovelDom: Boolean = false) {
        navToken++
        autoCopy = false
        polling = false
        hasTr = false
        shownTranslated = false
        updatePill()
        clearClipboard()

        if (clearNovelDom) {
            novelWv.evaluateJavascript(
                """
                (function(){
                    try {
                        document.documentElement.innerHTML =
                            '<head><title>Loading…</title></head>' +
                            '<body style="background:#111;color:#aaa;font-family:sans-serif">' +
                            '<div style="padding:32px;text-align:center">Loading chapter…</div>' +
                            '</body>';
                    } catch(e) {}
                    return "cleared";
                })();
                """.trimIndent(), null
            )
        }

        if (!Prefs.auto(this)) chatWv.evaluateJavascript(Js.clearBox(), null)
        updateProgressUi()
    }

    // ● : auto mode + translation shown -> switch original/translation; otherwise handle the chapter on the page now
    private fun extractCopy() {
        navToken++
        val token = navToken

        fun extractFresh() {
            if (token != navToken) return
            extractNow { ch ->
                if (token != navToken) return@extractNow
                if (ch == null) {
                    toast("❌ এই পেজে চ্যাপ্টারের লেখা পাওয়া যায়নি")
                } else {
                    // Middle ● is an explicit fresh extraction/translation request.
                    // Never reuse a saved translation for this action.
                    handleChapter(ch, forceFresh = true)
                }
            }
        }

        // ● is a fresh request. Stop any previous chatbot generation immediately,
        // so the idle-wait loop cannot hold extraction for several seconds.
        if (Prefs.auto(this)) chatWv.evaluateJavascript(Js.stop(), null)

        // Same current chapter: the original source is already in lastChapter.
        // Do not restore/reparse the full translated DOM just to extract it again.
        val currentUrl = cleanUrl(novelWv.url ?: "")
        val cached = lastChapter
        if (Prefs.auto(this) && cached != null && currentUrl.isNotBlank() &&
            cleanUrl(cached.url) == currentUrl) {
            handleChapter(cached, forceFresh = true)
            return
        }

        // If the translated version is currently displayed, restore the original
        // chapter first. Otherwise extraction could read the old Bengali text and
        // feed that back into the translator.
        if (Prefs.auto(this) && hasTr && shownTranslated) {
            novelWv.evaluateJavascript(Js.TOGGLE) { r ->
                if (token != navToken) return@evaluateJavascript
                shownTranslated = false
                updatePill()
                extractFresh()
            }
        } else {
            extractFresh()
        }
    }

    // ▶ / ◀ : go to next/prev chapter and handle it. Screen mode is never changed.
    private fun step(dir: String) {
        // Do not re-extract the current chapter before clicking Next/Prev.
        // lastChapter already contains the current page, so waiting for a
        // second full DOM extraction here can make navigation feel 5–7s slow.
        wipeStale()
        showNavLoading()
        val token = navToken

        fun navigate(base: Chapter) {
            if (token != navToken) return
            val target = if (dir == "next") base.next else base.prev
            val webNovel = base.url.contains("webnovel.com/", ignoreCase = true)

            if (target != null && !webNovel) {
                // Normal sites: remove the old page immediately, then load target.
                wipeStale(clearNovelDom = true)
                navToken = token
                loadAndWait(target, token, base)
            } else {
                // SPA/same-URL readers (including WebNovel): keep the live DOM
                // only long enough for its own Next/Prev handler to run.
                clickAndWait(dir, base, token)
            }
        }

        val currentUrl = cleanUrl(novelWv.url ?: "")
        val cached = lastChapter
        if (cached != null && currentUrl.isNotEmpty() &&
            cleanUrl(cached.url) == currentUrl) {
            navigate(cached)
            return
        }

        // First page / stale state: only then do a fresh extraction.
        extractNow { cur ->
            if (token != navToken) return@extractNow
            val base = cur ?: lastChapter
            if (base == null) {
                hideNavLoading()
                toast("❌ আগে নোভেলের একটা চ্যাপ্টার পেজ খোলো")
                return@extractNow
            }
            navigate(base)
        }
    }

    private fun loadAndWait(url: String, token: Int, base: Chapter) {
        // For a direct next/prev URL, remember the TARGET URL, not the old
        // URL. SPA readers can change the heading/URL before replacing body.
        pendUrl = cleanUrl(url)
        pendHash = bodyHash(base)
        val autoExtract = Prefs.bool(this, "autoExtractNext", true)
        autoCopy = autoExtract
        // Let onPageFinished start the first fast extraction.
        polling = false
        novelWv.loadUrl(url)
        handler.postDelayed({
            if (token == navToken && autoCopy) {
                autoCopy = false
                polling = false
                hideNavLoading()
                toast("❌ পেজ লোড হয়নি — ⟳ চেপে আবার চেষ্টা করো")
            }
        }, 20000)
    }

    // No usable link in the page (JS "Next" button, e.g. webnovel.com): click the site's own button
    private fun clickAndWait(dir: String, base: Chapter, token: Int) {
        if (base.url.contains("webnovel.com/", ignoreCase = true)) {
            // WebNovel's catalog is used as a navigation source. It is loaded
            // in this WebView but kept completely covered by the loading
            // overlay; onPageFinished extracts the adjacent chapter URL and
            // immediately loads that chapter.
            val parsed = Uri.parse(base.url)
            val path = parsed.path ?: ""
            val match = Regex("^(/book/[^/]+)", RegexOption.IGNORE_CASE).find(path)
            if (match == null || parsed.scheme.isNullOrEmpty() || parsed.authority.isNullOrEmpty()) {
                hideNavLoading()
                toast("❌ WebNovel book URL বোঝা যায়নি")
                return
            }
            val catalogUrl = parsed.scheme + "://" + parsed.authority + match.value + "/catalog"
            webNovelCatalogPending = true
            webNovelCatalogDir = dir
            webNovelCatalogTitle = base.title
            webNovelCatalogOldHash = bodyHash(base)
            webNovelCatalogToken = token
            novelWv.loadUrl(catalogUrl)
            return
        }

        val script = Js.clickNext(dir)
        novelWv.evaluateJavascript(script) { res ->
            if (token != navToken) return@evaluateJavascript
            if (res != null && (res.contains("none") || res.contains("failed"))) {
                val g = Extractor.bump(base.url, if (dir == "next") 1 else -1)
                if (g != null) {
                    toast("⚠️ বাটন পাইনি — URL নম্বর দিয়ে অনুমান করছি")
                    loadAndWait(g, token, base)
                } else {
                    hideNavLoading()
                    toast("❌ নতুন chapter link পাওয়া যায়নি")
                }
            } else {
                val autoExtract = Prefs.bool(this@MainActivity, "autoExtractNext", true)
                pendHash = bodyHash(base)
                pendUrl = ""
                autoCopy = autoExtract
                polling = false
                if (autoExtract) {
                    waitChange(pendHash, 0, token, base.url)
                } else {
                    hideNavLoading()
                }
            }
        }
    }

    private fun waitChange(oldHash: Int, n: Int, token: Int, oldUrl: String = "") {
        handler.postDelayed({
            if (token != navToken) return@postDelayed
            extractNow { ch ->
                if (token != navToken) return@extractNow
                if (ch != null && isNewPage(ch, oldUrl, oldHash)) {
                    commit(ch, token)
                } else if (n < 40) {
                    waitChange(oldHash, n + 1, token, oldUrl)
                } else {
                    hideNavLoading()
                    toast("❌ নতুন চ্যাপ্টারের লেখা আসেনি — ● চেপে আবার চেষ্টা করো")
                }
            }
        }, if (n < 4) 150L else 220L)
    }

    // After a direct page load, accept a new body even when the URL is reused.
    private fun pollExtract(n: Int, token: Int, reloaded: Boolean = false) {
        if (token != navToken) return
        extractNow { ch ->
            if (token != navToken) return@extractNow
            if (ch != null && ch.text.length > 300 &&
                bodyHash(ch) != pendHash &&
                (pendUrl.isEmpty() || cleanUrl(ch.url) == pendUrl || cleanUrl(ch.url) == cleanUrl(novelWv.url ?: ""))) {
                autoCopy = false
                polling = false
                commit(ch, token)
            } else if (n < 8) {
                handler.postDelayed({ pollExtract(n + 1, token, reloaded) }, if (n < 4) 150L else 220L)
            } else {
                autoCopy = false
                polling = false
                hideNavLoading()
                toast("❌ নতুন চ্যাপ্টারের লেখা পাওয়া যায়নি — ● চেপে আবার চেষ্টা করো")
            }
        }
    }

    // let the page finish rendering, read it once more, then use the fuller version
    private fun commit(ch: Chapter, token: Int) {
        hideNavLoading()
        // The new chapter is already detected from its changed body hash.
        // Do not add another 900ms delay before extraction.
        extractNow { c2 ->
            if (token != navToken) return@extractNow
            val fin = if (c2 != null && cleanUrl(c2.url) == cleanUrl(ch.url) && c2.text.length >= ch.text.length) c2 else ch
            handleChapter(fin)
        }
    }

    private fun handleChapter(ch: Chapter, forceFresh: Boolean = false) {
        onChapter(ch)
        if (Prefs.auto(this)) autoFlow(ch, forceFresh) else copyChapter(ch)
    }

    // ================================================================== copy mode (⚡ off)
    private fun copyChapter(ch: Chapter) {
        val p = Prefs.prompt(this)
        val full = if (Prefs.bool(this, "withPrompt") && p.isNotBlank()) p + "\n\n---\n\n" + ch.text else ch.text
        val tag = (if (ch.number.isNotEmpty()) "Ch ${ch.number} — " else "") + ch.title
        deliver(full, tag)
    }

    private fun deliver(full: String, label: String) {
        copy(full)
        toast("📋 কপি হয়েছে: $label (${full.length} অক্ষর)")
        if (Prefs.bool(this, "autoPaste")) {
            val send = Prefs.bool(this, "autoSend")
            handler.postDelayed({
                chatWv.evaluateJavascript(Js.send(full, send)) { r ->
                    // Never leave the WebView holding IME focus after automatic paste.
                    // The keyboard must appear only after the user taps the chat box.
                    chatWv.clearFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(chatWv.windowToken, 0)
                    handler.postDelayed({
                        chatWv.clearFocus()
                        imm.hideSoftInputFromWindow(chatWv.windowToken, 0)
                    }, 120)
                    if (decode(r).startsWith("nobox")) toast("⚠️ চ্যাট বক্স পাইনি — কপি হয়ে আছে, নিজে পেস্ট করো")
                }
            }, 400)
        }
    }

    // ================================================================== auto mode (⚡ on)
    private fun autoFlow(ch: Chapter, forceFresh: Boolean = false) {
        // A manual ● press means "extract and translate again". Do not reuse
        // the previously saved translation for that explicit request.
        if (!forceFresh) {
            val saved = Store.find(this, ch)
            if (saved != null) {
                applyTranslation(ch, Store.read(this, saved.id), true)
                toast("📖 সেভ করা অনুবাদ বসালাম")
                return
            }
        }
        enqueue(ch, forceFresh)
    }

    private fun enqueue(ch: Chapter, forceFresh: Boolean = false) {
        val k = keyOf(ch)
        val r = running
        if ((r != null && keyOf(r) == k) || queue.any { keyOf(it) == k }) { updateProgressUi(); return }
        queue.add(ch)
        if (forceFresh) freshQueueKeys.add(k)
        if (running == null) startNext() else updateProgressUi()
    }

    private fun startNext() {
        if (queue.isEmpty()) {
            running = null
            updateProgressUi()
            return
        }
        val ch = queue.removeAt(0)
        running = ch
        progress = 0
        val tok = ++runToken
        updateProgressUi()
        if (freshQueueKeys.remove(keyOf(ch))) {
            // Manual ● = fresh request. Do not wait for the previous stream.
            sendJob(tok, ch)
        } else {
            waitIdle(tok, ch, 0)
        }
    }

    // make sure the chatbot is not still busy with something else before we send
    private fun waitIdle(tok: Int, ch: Chapter, n: Int) {
        chatWv.evaluateJavascript(Js.readLen()) { raw ->
            if (tok != runToken) return@evaluateJavascript
            val parts = decode(raw).split("|")
            val streaming = parts.getOrNull(1) == "1"
            if (streaming && n < 12) {
                if (n == 6) chatWv.evaluateJavascript(Js.stop(), null)
                handler.postDelayed({ if (tok == runToken) waitIdle(tok, ch, n + 1) }, 1000)
            } else {
                sendJob(tok, ch)
            }
        }
    }

    private fun sendJob(tok: Int, ch: Chapter) {
        val full = Prefs.prompt(this) + "\n\n---\n\n" + ch.text
        chatWv.evaluateJavascript(Js.send(full, true)) { raw ->
            if (tok != runToken) return@evaluateJavascript
            val r = decode(raw)
            if (r.startsWith("ok:")) {
                val parts = r.substring(3).split(":")
                val n0 = parts.getOrNull(0)?.toIntOrNull() ?: 0
                val baseLen = parts.getOrNull(1)?.toIntOrNull() ?: 0
                // Give the chatbot UI time to create the new assistant turn.
                handler.postDelayed({
                    if (tok == runToken) pollJob(tok, ch, n0, baseLen, System.currentTimeMillis(), 0, 0)
                }, 200)
            } else {
                failJob(tok, ch, "চ্যাট বক্স পাওয়া যায়নি — চ্যাটবটে লগইন আছে কি দেখো")
            }
        }
    }

    private fun expectedLen(ch: Chapter): Int {
        val t = ch.text
        var cjk = 0
        for (c in t) {
            val x = c.code
            if (x in 0x3040..0x30FF || x in 0x4E00..0x9FFF || x in 0xAC00..0xD7AF) cjk++
        }
        val ratio = if (cjk * 3 > t.length) 2.4 else 1.15
        return (t.length * ratio).toInt().coerceAtLeast(200)
    }

    private fun pollJob(tok: Int, ch: Chapter, n0: Int, baseLen: Int, started: Long, lastLen: Int, stable: Int) {
        handler.postDelayed({
            if (tok != runToken) return@postDelayed
            chatWv.evaluateJavascript(Js.readLen()) { raw ->
                if (tok != runToken) return@evaluateJavascript
                val parts = decode(raw).split("|")
                val n = parts.getOrNull(0)?.toIntOrNull() ?: 0
                val streaming = parts.getOrNull(1) == "1"
                val len = parts.getOrNull(2)?.toIntOrNull() ?: 0
                // Some chat UIs reuse the same assistant DOM node instead of
                // creating a new one. Count alone is therefore not enough.
                val got = n > n0 || (n == n0 && len > baseLen)
                val effLen = if (got) len else 0
                progress = if (!got) 3 else minOf(95, effLen * 100 / expectedLen(ch))
                val st = if (got && effLen == lastLen) stable + 1 else 0
                updateProgressUi()
                val elapsed = System.currentTimeMillis() - started
                val done = got && !streaming && st >= 3 && effLen >= ch.text.length * 0.3
                val doneSlow = got && st >= 15 && effLen > 50
                when {
                    done || doneSlow -> finishJob(tok, ch)
                    elapsed > 6 * 60_000 -> failJob(tok, ch, "সময় শেষ (৬ মিনিট)")
                    !got && elapsed > 60_000 -> failJob(tok, ch, "চ্যাটবট উত্তর শুরু করেনি — মেসেজ যায়নি?")
                    else -> pollJob(tok, ch, n0, baseLen, started, effLen, st)
                }
            }
        }, 300)
    }

    private fun cleanReply(t: String): String =
        t.replace(Regex("^\\s*(ChatGPT|Gemini|Claude|DeepSeek|Grok)\\s+said\\s*[:：]?\\s*", RegexOption.IGNORE_CASE), "").trim()

    private fun finishJob(tok: Int, ch: Chapter) {
        chatWv.evaluateJavascript(Js.readText()) { raw ->
            if (tok != runToken) return@evaluateJavascript
            val t = cleanReply(decode(raw))
            if (t.length < 30) {
                failJob(tok, ch, "উত্তর পড়া গেল না")
                return@evaluateJavascript
            }
            // The chatbot response is complete here, so copy the FINAL
            // response to Android clipboard automatically. This uses the
            // same clipboard path as manual Copy/Save.
            copy(t)
            Store.save(this, ch, t)
            progress = 100
            updateProgressUi()
            val shown = lastChapter
            if (shown != null && keyOf(shown) == keyOf(ch)) applyTranslation(shown, t, true)
            toast("✅ অনুবাদ সেভ হয়েছে" + (if (ch.number.isNotEmpty()) " (Ch ${ch.number})" else ""))
            running = null
            startNext()
        }
    }

    // on failure: stop the queue, keep the chapter on the clipboard so you can do it by hand
    private fun failJob(tok: Int, ch: Chapter, msg: String) {
        if (tok != runToken) return
        queue.clear()
        running = null
        copy(Prefs.prompt(this) + "\n\n---\n\n" + ch.text)
        toast("❌ $msg\n📋 চ্যাপ্টার কপি করে রাখলাম — নিজে চ্যাটে পেস্ট করে Copy → 💾 করো")
        updateProgressUi()
    }

    private fun cancelAll() {
        runToken++
        queue.clear()
        running = null
        chatWv.evaluateJavascript(Js.stop(), null)
        updateProgressUi()
        toast("⏹ অটো অনুবাদ বন্ধ করলাম")
    }

    // ---- put the translation into the novel page itself
    private fun applyTranslation(ch: Chapter, text: String, retry: Boolean) {
        val sel = ch.contentSel.ifBlank { SiteProfiles.selector(this, ch.url, "content") }
        val paras = text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val payload = JSONArray(paras).toString()

        fun attempt(selector: String, left: Int) {
            novelWv.evaluateJavascript(Js.apply(selector, payload)) { r ->
                if (r != null && r.contains("ok")) {
                    hasTr = true
                    shownTranslated = true
                    updatePill()
                    if (retry) {   // some sites re-render the content a moment later — put it back once
                        handler.postDelayed({
                            if (shownTranslated && lastChapter === ch) {
                                novelWv.evaluateJavascript(Js.stillApplied(selector)) { s ->
                                    if (s != null && s.contains("lost")) {
                                        attempt(selector, 2)
                                    }
                                }
                            }
                        }, 2500)
                    }
                } else if (left > 0) {
                    // The page can still be rebuilding its reader DOM when the
                    // chatbot answer finishes. Retry the same selector instead
                    // of losing the already completed translation.
                    handler.postDelayed({
                        if (lastChapter === ch && !isFinishing) attempt(selector, left - 1)
                    }, 350L)
                } else {
                    // Re-read the site's current learned selector once. Some
                    // readers replace their chapter container after rendering.
                    val fresh = SiteProfiles.selector(this, novelWv.url ?: ch.url, "content")
                    if (fresh.isNotBlank() && fresh != selector) {
                        attempt(fresh, 2)
                    } else {
                        toast("⚠️ অনুবাদ বসানো গেল না — লাইব্রেরিতে সেভ আছে")
                    }
                }
            }
        }

        attempt(sel, 3)
    }

    private fun toggleView() {
        novelWv.evaluateJavascript(Js.TOGGLE) { r ->
            if (r != null && r.contains("orig")) shownTranslated = false
            else if (r != null && r.contains("tr")) shownTranslated = true
            updatePill()
        }
    }

    private fun updatePill() {
        togglePill.visibility = if (hasTr && mode != Mode.CHAT) View.VISIBLE else View.GONE
        togglePill.text = if (shownTranslated) "🌐 মূল দেখো" else "🌐 অনুবাদ দেখো"
    }

    // ---- progress overlay
    private fun updateProgressUi() {
        val run = running
        if (run == null && queue.isEmpty()) {
            setProgressVisible(false)
            return
        }
        val shown = lastChapter
        val label: String
        var pct = 0
        if (run != null && shown != null && keyOf(run) == keyOf(shown)) {
            label = "🌐 অনুবাদ চলছে…  $progress%"
            pct = progress
        } else if (shown != null && queue.any { keyOf(it) == keyOf(shown) }) {
            val ahead = (if (run != null) 1 else 0) + queue.indexOfFirst { keyOf(it) == keyOf(shown) }
            label = "⏳ লাইনে আছে — আগে $ahead টা বাকি"
        } else {
            label = "🌐 পেছনে অন্য চ্যাপ্টারের অনুবাদ চলছে  $progress%"
            pct = progress
        }
        progressTv.text = label + "   (থামাতে ট্যাপ)"
        progressBar.progress = pct
        setProgressVisible(true)
    }

    private fun setProgressVisible(v: Boolean) {
        if (v && progressBox.visibility != View.VISIBLE) {
            progressBox.visibility = View.VISIBLE
            val a = ValueAnimator.ofFloat(0.45f, 1f)
            a.duration = 900
            a.repeatMode = ValueAnimator.REVERSE
            a.repeatCount = ValueAnimator.INFINITE
            a.addUpdateListener { progressBox.alpha = it.animatedValue as Float }
            a.start()
            pulse = a
        } else if (!v && progressBox.visibility == View.VISIBLE) {
            pulse?.cancel()
            pulse = null
            progressBox.alpha = 1f
            progressBox.visibility = View.GONE
        }
    }

    private fun toggleAuto() {
        val on = !Prefs.auto(this)
        Prefs.putBool(this, "noAuto", !on)
        refreshAutoBtn()
        toast(if (on) "⚡ অটো অনুবাদ চালু — ▶ চাপলেই ব্যাকগ্রাউন্ডে অনুবাদ হয়ে সাইটে বসবে" else "⚡ কপি মোড — ▶ চাপলে চ্যাপ্টার কপি হবে")
    }

    private fun refreshAutoBtn() {
        autoBtn.alpha = if (Prefs.auto(this)) 1f else 0.35f
    }

    // ================================================================== save answer by hand (copy mode)
    private fun saveAnswer() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: ""
        if (t.length < 30) return toast("❌ ক্লিপবোর্ডে অনুবাদ নেই — আগে চ্যাটবটের উত্তরের Copy বাটন চাপো")
        if (t == lastCopied.trim()) return toast("❌ এটা তো সোর্স টেক্সট — চ্যাটবটের উত্তরের Copy বাটন চাপো")
        val tr = Store.save(this, lastChapter, t)
        toast("💾 লাইব্রেরিতে সেভ: ${tr.novel} — ${tr.label()}")
        if (!Prefs.bool(this, "noSaveNext")) step("next")
    }

    // ================================================================== menu
    private fun menu() {
        val labels = arrayListOf(
            "📚 লাইব্রেরি (অফলাইনে পড়ো)",
            "⬇️ সব অনুবাদ txt এক্সপোর্ট",
            "🔖 এই পেজ বুকমার্ক করো",
            "🔖 বুকমার্ক লিস্ট",
            "📝 প্রম্পট এডিট",
            menuAutoLabel(),
            "🔁 এই চ্যাপ্টার আবার অনুবাদ করাও",
            "⏹ চলমান অটো অনুবাদ বন্ধ",
            menuDarkLabel(),
            menuAutoPasteLabel(),
            menuAutoSendLabel(),
            menuPromptLabel(),
            menuSaveNextLabel(),
            menuNextExtractLabel(),
            menuAdBlockLabel(),
            "🔄 Ad Block লিস্ট আপডেট",
            "🔄 নোভেল পেজ রিলোড",
            "🔄 চ্যাটবট রিলোড"
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        val lv = ListView(this)
        lv.adapter = adapter
        val dlg = AlertDialog.Builder(this)
            .setTitle("☰ মেনু")
            .setView(lv)
            .setNegativeButton("বন্ধ", null)
            .create()

        lv.setOnItemClickListener { _, _, i, _ ->
            when (i) {
                0 -> { dlg.dismiss(); libraryNovels() }
                1 -> { dlg.dismiss(); exportAll(null) }
                2 -> { dlg.dismiss(); saveBookmark() }
                3 -> { dlg.dismiss(); bookmarkList() }
                4 -> { dlg.dismiss(); editPrompt() }
                5 -> {
                    toggleAuto()
                    labels[i] = menuAutoLabel()
                    adapter.notifyDataSetChanged()
                }
                6 -> {
                    val ch = lastChapter
                    if (ch == null) toast("❌ আগে একটা চ্যাপ্টার খোলো")
                    else {
                        enqueue(ch)
                        toast("⏳ আবার অনুবাদে দেওয়া হলো")
                    }
                    dlg.dismiss()
                }
                7 -> { cancelAll(); dlg.dismiss() }
                8 -> {
                    val d = darkMode()
                    Prefs.put(this, "dark", ((d + 1) % 3).toString())
                    applyDark()
                    labels[i] = menuDarkLabel()
                    adapter.notifyDataSetChanged()
                }
                9 -> {
                    val v = !Prefs.bool(this, "autoPaste")
                    Prefs.putBool(this, "autoPaste", v)
                    labels[i] = menuAutoPasteLabel()
                    adapter.notifyDataSetChanged()
                }
                10 -> {
                    val v = !Prefs.bool(this, "autoSend")
                    Prefs.putBool(this, "autoSend", v)
                    labels[i] = menuAutoSendLabel()
                    adapter.notifyDataSetChanged()
                }
                11 -> {
                    val v = !Prefs.bool(this, "withPrompt")
                    Prefs.putBool(this, "withPrompt", v)
                    labels[i] = menuPromptLabel()
                    adapter.notifyDataSetChanged()
                }
                12 -> {
                    val next = !Prefs.bool(this, "noSaveNext")
                    Prefs.putBool(this, "noSaveNext", !next)
                    labels[i] = menuSaveNextLabel()
                    adapter.notifyDataSetChanged()
                }
                13 -> {
                    val v = !Prefs.bool(this, "autoExtractNext", true)
                    Prefs.putBool(this, "autoExtractNext", v)
                    labels[i] = menuNextExtractLabel()
                    adapter.notifyDataSetChanged()
                }
                14 -> {
                    val v = !Prefs.adblock(this)
                    Prefs.putBool(this, "noAdblock", !v)
                    labels[i] = menuAdBlockLabel()
                    adapter.notifyDataSetChanged()
                }
                15 -> {
                    toast("⏳ লিস্ট নামাচ্ছি…")
                    AdBlock.update(this) { n ->
                        toast(if (n > 0) "✅ $n টা হোস্ট যোগ হয়েছে" else "❌ আপডেট হয়নি")
                    }
                }
                16 -> { dlg.dismiss(); novelWv.reload() }
                17 -> { dlg.dismiss(); chatWv.reload() }
            }
        }
        dlg.show()
    }

    private fun menuAutoLabel(): String =
        (if (Prefs.auto(this)) "✅" else "⬜") + " ⚡ অটো অনুবাদ (ব্যাকগ্রাউন্ডে, সাইটে বসবে)"

    private fun menuDarkLabel(): String =
        "🌙 ডার্ক মোড: " + arrayOf("বন্ধ", "অটো", "ফোর্স")[darkMode()] + "  (ট্যাপ করলে বদলায়)"

    private fun menuAutoPasteLabel(): String =
        (if (Prefs.bool(this, "autoPaste")) "✅" else "⬜") + " কপি মোড: অটো পেস্ট"

    private fun menuAutoSendLabel(): String =
        (if (Prefs.bool(this, "autoSend")) "✅" else "⬜") + " কপি মোড: অটো সেন্ড"

    private fun menuPromptLabel(): String =
        (if (Prefs.bool(this, "withPrompt")) "✅" else "⬜") + " কপি মোড: কপির সাথে প্রম্পট"

    private fun menuNextExtractLabel(): String =
        (if (Prefs.bool(this, "autoExtractNext", true)) "✅" else "⬜") + " ▶ Next/Prev এর পর অটো Extract"

    private fun menuSaveNextLabel(): String =
        (if (!Prefs.bool(this, "noSaveNext")) "✅" else "⬜") + " কপি মোড: 💾 এর পর পরের চ্যাপ্টার"

    private fun menuAdBlockLabel(): String =
        (if (Prefs.adblock(this)) "✅" else "⬜") + " Ad Block"

    private fun listDialog(title: String, labels: List<String>, onClick: (Int) -> Unit, onLong: ((Int) -> Unit)?) {
        val lv = ListView(this)
        lv.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        val dlg = AlertDialog.Builder(this).setTitle(title).setView(lv).setNegativeButton("বন্ধ", null).create()
        lv.setOnItemClickListener { _, _, i, _ -> dlg.dismiss(); onClick(i) }
        if (onLong != null) lv.setOnItemLongClickListener { _, _, i, _ -> dlg.dismiss(); onLong(i); true }
        dlg.show()
    }

    // ---------- offline library ----------
    private fun libraryNovels() {
        val names = Store.novelNames(this)
        if (names.isEmpty()) return toast("লাইব্রেরি খালি — অনুবাদ হলে এখানে জমবে")
        val labels = names.map { it + "   (" + Store.chapters(this, it).size + " চ্যাপ্টার)" }
        listDialog("📚 লাইব্রেরি", labels, { i -> novelActions(names[i]) }, null)
    }

    private fun novelActions(name: String) {
        AlertDialog.Builder(this).setTitle(name)
            .setItems(arrayOf("📖 চ্যাপ্টার লিস্ট", "⬇️ এই নোভেল txt এক্সপোর্ট", "✏️ নাম বদলাও", "🗑 পুরো নোভেল মুছো")) { _, k ->
                when (k) {
                    0 -> chapterList(name)
                    1 -> exportAll(name)
                    2 -> renameNovel(name)
                    3 -> AlertDialog.Builder(this).setMessage("\"$name\" এর সব অনুবাদ মুছবে?")
                        .setPositiveButton("মুছো") { _, _ -> Store.deleteNovel(this, name) }
                        .setNegativeButton("না", null).show()
                    else -> {}
                }
            }.show()
    }

    private fun chapterList(name: String) {
        val l = Store.chapters(this, name)
        if (l.isEmpty()) return
        listDialog(name + " (লং প্রেসে মোছো)", l.map { it.label() },
            { i -> startActivity(Intent(this, ReaderActivity::class.java).putExtra("novel", name).putExtra("id", l[i].id)) },
            { i ->
                AlertDialog.Builder(this).setMessage("${l[i].label()} মুছবে?")
                    .setPositiveButton("মুছো") { _, _ -> Store.delete(this, l[i].id) }
                    .setNegativeButton("না", null).show()
            })
    }

    private fun renameNovel(old: String) {
        val et = EditText(this).apply { setText(old) }
        AlertDialog.Builder(this).setTitle("নোভেলের নাম")
            .setView(et)
            .setPositiveButton("সেভ") { _, _ -> Store.rename(this, old, et.text.toString().trim()) }
            .setNegativeButton("বাতিল", null).show()
    }

    private fun exportAll(novel: String?) {
        if (Store.list(this).isEmpty()) return toast("এক্সপোর্ট করার মতো অনুবাদ নেই")
        exportNovel = novel
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, (novel ?: "translations") + ".txt")
        }
        startActivityForResult(i, 42)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == RESULT_OK) {
            val u = data?.data ?: return
            try {
                contentResolver.openOutputStream(u)?.use { it.write(Store.export(this, exportNovel).toByteArray()) }
                toast("✅ এক্সপোর্ট হয়েছে")
            } catch (e: Exception) {
                toast("❌ " + (e.message ?: "ব্যর্থ"))
            }
        }
    }

    // ---------- bookmarks ----------
    private fun saveBookmark() {
        val u = novelWv.url ?: return
        val name = (novelWv.title ?: "").ifBlank { Uri.parse(u).host ?: u }
        Store.addBookmark(this, Bookmark(name, u, u, ""))
        toast("🔖 সেভ হয়েছে: $name")
    }

    private fun bookmarkList() {
        val l = Store.bookmarks(this)
        if (l.isEmpty()) return toast("বুকমার্ক খালি")
        val labels = l.map { it.name + (if (it.lastTitle.isNotEmpty()) "\n↳ " + it.lastTitle else "") }
        listDialog("🔖 বুকমার্ক (লং প্রেসে মোছো)", labels,
            { i ->
                if (mode == Mode.CHAT) setMode(Mode.SPLIT)
                novelWv.loadUrl(l[i].lastUrl.ifEmpty { l[i].url })
            },
            { i -> Store.removeBookmark(this, i) })
    }

    private fun editPrompt() {
        val et = EditText(this).apply {
            setText(Prefs.prompt(this@MainActivity))
            minLines = 6
            gravity = Gravity.TOP
        }
        AlertDialog.Builder(this)
            .setTitle("প্রম্পট")
            .setView(et)
            .setPositiveButton("সেভ") { _, _ -> Prefs.put(this, "prompt", et.text.toString()) }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    // ================================================================== lifecycle
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (activeWv.canGoBack()) activeWv.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        pulse?.cancel()
        novelWv.destroy()
        chatWv.destroy()
        super.onDestroy()
    }
}