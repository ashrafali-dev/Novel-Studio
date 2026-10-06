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
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.net.URLEncoder

private data class BrowserTab(
    var label: String,
    var url: String,
    var state: Bundle? = null,
    @Transient var thumbnail: Bitmap? = null,
    var id: String = UUID.randomUUID().toString()
)

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
    private lateinit var tabStrip: LinearLayout
    private lateinit var shortcutStrip: LinearLayout
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
    // Next/Prev: the target page is reloaded once (fast) before extraction starts.
    private var refreshPending = false
    private var refreshToken = 0
    private var pendUrl = ""
    private var pendHash = 0

    // lightweight browser layer: one WebView, multiple saved navigation sessions
    private val browserTabs = mutableListOf<BrowserTab>()
    private var activeTabIndex = 0
    private var restoringTab = false
    private var searchShortcutsVisible = false

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
        Glossary.reload(this)
        SiteRules.sync(this)
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

        // ---- browser chrome: tabs + address/search bar + focused site shortcuts
        urlBar = EditText(this).apply {
            hint = "Search or enter address"
            setSingleLine()
            textSize = 14f
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(14), 0, dp(10), 0)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF8E8E98.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xFF303038.toInt())
            }
            setOnEditorActionListener { _, _, _ -> go(this.text.toString()); true }
            onFocusChangeListener = View.OnFocusChangeListener { _, has ->
                searchShortcutsVisible = has
                shortcutStrip.visibility = if (has) View.VISIBLE else View.GONE
            }
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
            text = "→"
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(0xFF4F7CFF.toInt())
            setOnClickListener { go(urlBar.text.toString()) }
        }

        tabStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tabScroll = HorizontalScrollView(this).apply {
            setBackgroundColor(0xFF18181C.toInt())
            isHorizontalScrollBarEnabled = false
            addView(tabStrip, ViewGroup.LayoutParams(WC, dp(38)))
        }

        shortcutStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), 0, dp(4), 0)
            visibility = View.GONE
        }
        val shortcutScroll = HorizontalScrollView(this).apply {
            setBackgroundColor(0xFF202024.toInt())
            isHorizontalScrollBarEnabled = false
            addView(shortcutStrip, ViewGroup.LayoutParams(WC, dp(44)))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            addView(urlBar, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(reloadBtn, LinearLayout.LayoutParams(dp(44), dp(42)))
            addView(goBtn, LinearLayout.LayoutParams(dp(42), dp(42)))
            setBackgroundColor(0xFF202024.toInt())
        }

        val browserChrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tabScroll, LinearLayout.LayoutParams(MP, dp(38)))
            addView(topBar, LinearLayout.LayoutParams(MP, dp(50)))
            addView(shortcutScroll, LinearLayout.LayoutParams(MP, dp(44)))
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
            addView(browserChrome, LinearLayout.LayoutParams(MP, WC))
            addView(content, LinearLayout.LayoutParams(MP, 0, 1f))
            addView(bar, LinearLayout.LayoutParams(MP, WC))
        }
        setContentView(root)

        buildStrip()
        buildSiteShortcuts()
        initBrowserTabs()
        setMode(Mode.SPLIT)
        refreshAutoBtn()
        applyDark()
        if (browserTabs.firstOrNull()?.url.isNullOrBlank()) {
            novelWv.loadUrl(Prefs.get(this, "lastNovelUrl", "https://duckduckgo.com/"))
        }
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
        s.databaseEnabled = true
        s.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        s.loadsImagesAutomatically = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.userAgentString = UA
        wv.setBackgroundColor(0xFF111114.toInt())
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
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

    private fun loginChatbot() {
        setMode(Mode.CHAT)
        val url = Prefs.get(this, "botUrl", bots().optJSONObject(0)?.optString("u", "https://chatgpt.com/") ?: "https://chatgpt.com/")
        chatWv.loadUrl(url)
        CookieManager.getInstance().flush()
        toast("🔐 এই WebView-তেই লগইন করো — session অ্যাপেই থাকবে")
    }

    // ================================================================== browser tabs / shortcuts / history
    private fun tabThumbFile(tab: BrowserTab): File =
        File(filesDir, "novelstudio_tab_${tab.id}.png")

    private fun deleteTabThumbnail(tab: BrowserTab) {
        try {
            tab.thumbnail?.recycle()
            tab.thumbnail = null
            tabThumbFile(tab).delete()
        } catch (_: Exception) {}
    }

    private fun loadTabThumbnail(tab: BrowserTab) {
        if (tab.thumbnail != null && !tab.thumbnail!!.isRecycled) return
        try {
            val f = tabThumbFile(tab)
            if (f.exists()) tab.thumbnail = BitmapFactory.decodeFile(f.absolutePath)
        } catch (_: Exception) {}
    }

    private fun initBrowserTabs() {
        browserTabs.clear()
        val raw = Prefs.get(this, "browserTabs", "")
        val saved = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        for (i in 0 until saved.length()) {
            val o = saved.optJSONObject(i) ?: continue
            val u = o.optString("url", "")
            if (u.isNotBlank()) {
                val id = o.optString("id", "").ifBlank { UUID.randomUUID().toString() }
                val tab = BrowserTab(o.optString("label", "New Tab"), u, id = id)
                loadTabThumbnail(tab)
                browserTabs.add(tab)
            }
        }
        if (browserTabs.isEmpty()) {
            browserTabs.add(BrowserTab("New Tab", Prefs.get(this, "lastNovelUrl", "https://duckduckgo.com/")))
        }
        activeTabIndex = Prefs.get(this, "browserActiveTab", "0").toIntOrNull()
            ?.coerceIn(0, browserTabs.lastIndex) ?: 0
        persistBrowserTabs()
        renderTabs()

        // Restore the active tab into the WebView after an app/process restart.
        // Tab metadata is persisted separately, so do not leave the fresh WebView
        // on a blank/new document when a real tab already exists.
        val active = browserTabs.getOrNull(activeTabIndex)
        if (active != null && active.url.isNotBlank()) {
            restoringTab = true
            try { novelWv.loadUrl(active.url) } catch (_: Exception) {}
            restoringTab = false
            urlBar.setText(active.url)
        }
    }

    private fun persistBrowserTabs() {
        val a = JSONArray()
        browserTabs.forEach { tab ->
            a.put(JSONObject().apply {
                put("id", tab.id)
                put("label", tab.label)
                put("url", tab.url)
            })
        }
        Prefs.put(this, "browserTabs", a.toString())
        Prefs.put(this, "browserActiveTab", activeTabIndex.toString())
    }

    private fun siteShortcuts(): List<Pair<String, String>> = listOf(
        "WTR-LAB" to "https://wtr-lab.com/",
        "WebNovel" to "https://www.webnovel.com/",
        "NovelBin" to "https://novelbin.com/",
        "BoxNovel" to "https://boxnovel.com/",
        "AsianNovel" to "https://www.asianovel.com/",
        "NovelFull" to "https://novelfull.net/",
        "LightNovelPub" to "https://lightnovelpub.com/",
        "NovelNext" to "https://novelnext.com/",
        "ReadNovelFull" to "https://readnovelfull.com/"
    )

    private fun buildSiteShortcuts() {
        shortcutStrip.removeAllViews()
        siteShortcuts().forEach { (name, url) ->
            shortcutStrip.addView(chip(name, {
                newBrowserTab(url)
                urlBar.clearFocus()
            }, null))
        }
    }

    private fun renderTabs() {
        if (!::tabStrip.isInitialized) return
        tabStrip.removeAllViews()

        val count = TextView(this).apply {
            text = "▦  ${browserTabs.size}"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFF303038.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(WC, dp(34)).also {
                it.setMargins(dp(5), dp(2), dp(6), dp(2))
            }
            setOnClickListener { showTabGrid() }
        }
        tabStrip.addView(count)

        val current = browserTabs.getOrNull(activeTabIndex)
        if (current != null) {
            val title = TextView(this).apply {
                text = current.label.ifBlank { Uri.parse(current.url).host ?: "New Tab" }
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(0xFFE8E8EC.toInt())
                setPadding(dp(10), 0, dp(10), 0)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(dp(180), dp(34))
            }
            tabStrip.addView(title)
        }

        tabStrip.addView(chip("＋", { newBrowserTab("https://duckduckgo.com/") }, null))
    }

    private fun saveCurrentTabState() {
        if (browserTabs.isEmpty() || restoringTab) return
        val tab = browserTabs[activeTabIndex]
        tab.url = novelWv.url ?: tab.url
        tab.label = (novelWv.title ?: "").ifBlank { Uri.parse(tab.url).host ?: "New Tab" }
        val state = Bundle()
        tab.state = state
        try { novelWv.saveState(state) } catch (_: Exception) {}
        captureCurrentTabThumbnail()
        Prefs.put(this, "lastNovelUrl", tab.url)
        persistBrowserTabs()
        renderTabs()
    }

    private fun captureCurrentTabThumbnail() {
        if (browserTabs.isEmpty() || novelWv.width <= 0 || novelWv.height <= 0) return
        try {
            val tab = browserTabs[activeTabIndex]
            val w = dp(320)
            val h = dp(210)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val sx = w.toFloat() / novelWv.width.toFloat()
            val sy = h.toFloat() / novelWv.height.toFloat()
            val scale = minOf(sx, sy)
            val dx = (w - novelWv.width * scale) / 2f
            val dy = (h - novelWv.height * scale) / 2f
            canvas.translate(dx, dy)
            canvas.scale(scale, scale)
            novelWv.draw(canvas)

            try {
                FileOutputStream(tabThumbFile(tab)).use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } catch (_: Exception) {}

            tab.thumbnail?.let { if (!it.isRecycled) it.recycle() }
            tab.thumbnail = bmp
        } catch (_: Exception) {}
    }

    private fun switchBrowserTab(index: Int) {
        if (index !in browserTabs.indices || index == activeTabIndex) return
        saveCurrentTabState()
        activeTabIndex = index
        val tab = browserTabs[index]
        lastChapter = null
        hasTr = false
        shownTranslated = false
        updatePill()
        restoringTab = true
        try {
            novelWv.stopLoading()
            if (tab.state != null) {
                novelWv.restoreState(tab.state!!)
            } else {
                novelWv.loadUrl(tab.url)
            }
        } catch (_: Exception) {
            novelWv.loadUrl(tab.url)
        }
        restoringTab = false
        renderTabs()
        urlBar.setText(tab.url)
        persistBrowserTabs()
    }

    private fun newBrowserTab(url: String) {
        saveCurrentTabState()
        browserTabs.add(BrowserTab("New Tab", url))
        activeTabIndex = browserTabs.lastIndex
        lastChapter = null
        hasTr = false
        shownTranslated = false
        updatePill()
        novelWv.stopLoading()
        novelWv.loadUrl(url)
        renderTabs()
        urlBar.setText(url)
        persistBrowserTabs()
    }

    private fun closeBrowserTab(index: Int) {
        if (index !in browserTabs.indices) return
        val removed = browserTabs[index]
        deleteTabThumbnail(removed)
        if (browserTabs.size <= 1) {
            browserTabs[0] = BrowserTab("New Tab", "https://duckduckgo.com/")
            activeTabIndex = 0
            novelWv.loadUrl(browserTabs[0].url)
        } else {
            browserTabs.removeAt(index)
            activeTabIndex = activeTabIndex.coerceAtMost(browserTabs.lastIndex)
            val tab = browserTabs[activeTabIndex]
            if (tab.state != null) {
                try { novelWv.restoreState(tab.state!!) } catch (_: Exception) { novelWv.loadUrl(tab.url) }
            } else novelWv.loadUrl(tab.url)
        }
        lastChapter = null
        hasTr = false
        shownTranslated = false
        updatePill()
        renderTabs()
        persistBrowserTabs()
    }

    private fun showTabGrid(filter: String = "") {
        saveCurrentTabState()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101012.toInt())
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(8), dp(10))
        }

        val title = TextView(this).apply {
            text = "Tabs (${browserTabs.size})"
            textSize = 22f
            setTextColor(0xFFF2F2F4.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        }

        val search = TextView(this).apply {
            text = "⌕"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(0xFFF2F2F4.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener {
                val input = EditText(this@MainActivity).apply {
                    hint = "Tab title বা URL"
                    setSingleLine()
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("🔍 Tabs খুঁজুন")
                    .setView(input)
                    .setPositiveButton("খুঁজুন") { _, _ ->
                        title.tag = input.text.toString().trim()
                        showTabGrid(title.tag as String)
                    }
                    .setNegativeButton("বাতিল", null)
                    .show()
            }
        }

        val more = TextView(this).apply {
            text = "⋮"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(0xFFF2F2F4.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setItems(arrayOf("✕ বর্তমান ছাড়া সব বন্ধ", "✕ সব Tab বন্ধ")) { _, which ->
                        when (which) {
                            0 -> {
                                val keep = browserTabs[activeTabIndex]
                                browserTabs.clear()
                                browserTabs.add(keep)
                                activeTabIndex = 0
                                persistBrowserTabs()
                                renderTabs()
                                showTabGrid()
                            }
                            1 -> {
                                browserTabs.forEach { deleteTabThumbnail(it) }
                                browserTabs.clear()
                                browserTabs.add(BrowserTab("New Tab", "https://duckduckgo.com/"))
                                activeTabIndex = 0
                                novelWv.loadUrl(browserTabs[0].url)
                                persistBrowserTabs()
                                renderTabs()
                                showTabGrid()
                            }
                        }
                    }
                    .setNegativeButton("বাতিল", null)
                    .show()
            }
        }

        val close = TextView(this).apply {
            text = "×"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(0xFFF2F2F4.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }

        header.addView(title)
        header.addView(search)
        header.addView(more)
        header.addView(close)
        root.addView(header)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val grid = GridLayout(this).apply {
            columnCount = 2
            setPadding(dp(10), dp(4), dp(10), dp(96))
            useDefaultMargins = false
        }
        scroll.addView(grid, ViewGroup.LayoutParams(MP, WC))
        root.addView(scroll, LinearLayout.LayoutParams(MP, 0, 1f))

        lateinit var dialog: AlertDialog
        val newTab = TextView(this).apply {
            text = "＋"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF1265D8.toInt())
            }
            elevation = dp(8).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).also {
                it.gravity = Gravity.END
                it.setMargins(0, 0, dp(18), dp(18))
            }
            setOnClickListener {
                dialog.dismiss()
                newBrowserTab("https://duckduckgo.com/")
            }
        }
        root.addView(newTab)

        dialog = AlertDialog.Builder(this)
            .setView(root)
            .create()

        fun refreshCards(query: String = filter) {
            grid.removeAllViews()
            val list = browserTabs.withIndex().filter { (_, t) ->
                query.isBlank() || t.label.contains(query, true) || t.url.contains(query, true)
            }
            title.text = if (query.isBlank()) "Tabs (${browserTabs.size})"
                else "Tabs (${list.size}/${browserTabs.size})"

            list.forEach { pair ->
                val i = pair.index
                val tab = pair.value
                val card = FrameLayout(this).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(if (i == activeTabIndex) 0xFF303038.toInt() else 0xFF202024.toInt())
                        setStroke(dp(1), if (i == activeTabIndex) 0xFF5B8CFF.toInt() else 0xFF38383F.toInt())
                    }
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        height = dp(285)
                        columnSpec = GridLayout.spec(i % 2, 1f)
                        setMargins(dp(6), dp(6), dp(6), dp(6))
                    }
                    setOnClickListener {
                        dialog.dismiss()
                        switchBrowserTab(i)
                    }
                }

                loadTabThumbnail(tab)
                val preview = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(0xFF18181C.toInt())
                    tab.thumbnail?.let { if (!it.isRecycled) setImageBitmap(it) }
                    if (tab.thumbnail == null) setImageResource(android.R.drawable.ic_menu_view)
                    layoutParams = FrameLayout.LayoutParams(MP, dp(220))
                }
                card.addView(preview)

                val label = TextView(this).apply {
                    text = tab.label.ifBlank { Uri.parse(tab.url).host ?: "New Tab" } +
                        "\n" + (Uri.parse(tab.url).host ?: "") +
                        "\n" + tab.url
                    textSize = 11f
                    setTextColor(0xFFF2F2F4.toInt())
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(10), dp(5), dp(40), dp(4))
                    layoutParams = FrameLayout.LayoutParams(MP, dp(70), Gravity.BOTTOM)
                }
                card.addView(label)

                val closeTab = TextView(this).apply {
                    text = "×"
                    textSize = 22f
                    gravity = Gravity.CENTER
                    setTextColor(0xFFE8E8EC.toInt())
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0x88303038.toInt())
                    }
                    layoutParams = FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END)
                    setOnClickListener {
                        closeBrowserTab(i)
                        refreshCards(query)
                    }
                }
                card.addView(closeTab)
                grid.addView(card)
            }
        }

        close.setOnClickListener { dialog.dismiss() }
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout(MP, MP)
            refreshCards()
        }
        dialog.show()
        dialog.window?.setLayout(MP, MP)
        refreshCards()
    }

    private fun browserHistoryDialog() {
        val h = Store.history(this)
        if (h.isEmpty()) return toast("🕘 হিস্ট্রি খালি")
        val labels = h.map {
            val host = Uri.parse(it.url).host ?: it.url
            "${it.title.ifBlank { host }}\n$host"
        }
        listDialog("🕘 ব্রাউজ হিস্ট্রি", labels, { i ->
            if (i in h.indices) {
                if (mode == Mode.CHAT) setMode(Mode.SPLIT)
                newBrowserTab(h[i].url)
            }
        }, null)
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
            val scheme = request?.url?.scheme ?: ""
            return scheme != "http" && scheme != "https"
        }
        override fun onPageFinished(view: WebView?, url: String?) {
            CookieManager.getInstance().flush()
        }
    }

    private inner class ChatChrome : WebChromeClient() {
        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            if (!isUserGesture || resultMsg == null) return false
            val popup = WebView(this@MainActivity)
            setup(popup)
            popup.webViewClient = ChatClient()
            val box = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(popup, LinearLayout.LayoutParams(MP, 0, 1f))
            }
            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("🔐 লগইন")
                .setView(box)
                .setNegativeButton("বন্ধ") { _, _ -> popup.stopLoading(); popup.destroy() }
                .create()
            dialog.setOnDismissListener { popup.stopLoading(); popup.destroy() }
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            transport.webView = popup
            resultMsg.sendToTarget()
            dialog.show()
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
        if (browserTabs.isEmpty()) initBrowserTabs()
        browserTabs[activeTabIndex].url = u
        browserTabs[activeTabIndex].label = Uri.parse(u).host ?: "New Tab"
        browserTabs[activeTabIndex].state = null
        persistBrowserTabs()
        renderTabs()
        novelWv.loadUrl(u)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
        urlBar.clearFocus()
    }

    private inner class NovelClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val r = request ?: return null
            if (r.url.host == "ns.local") {
                val name = (r.url.lastPathSegment ?: "").removeSuffix(".ttf")
                if (name in Prefs.FONT_FILES && name.isNotEmpty()) {
                    return try {
                        WebResourceResponse("font/ttf", null, 200, "OK",
                            mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=86400"),
                            assets.open("fonts/$name.ttf"))
                    } catch (e: Exception) { null }
                }
            }
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
            if (view === novelWv && url != null && !restoringTab) {
                browserTabs.getOrNull(activeTabIndex)?.url = url
                browserTabs.getOrNull(activeTabIndex)?.label =
                    (view.title ?: "").ifBlank { Uri.parse(url).host ?: "Novel" }
                // Save immediately so the URL survives an app/process restart.
                persistBrowserTabs()
                if (!urlBar.hasFocus()) urlBar.setText(url)
            }
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            if (darkMode() == 2) view?.evaluateJavascript(Js.DARK_ON, null)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (Prefs.adblock(this@MainActivity) && !AdBlock.exempt(Uri.parse(url ?: "").host)) {
                view?.evaluateJavascript(AdBlock.cosmeticJs(), null)
            }
            if (darkMode() == 2) view?.evaluateJavascript(Js.DARK_ON, null)
            if (url != null) {
                Prefs.put(this@MainActivity, "lastNovelUrl", url)
                if (view === novelWv && !restoringTab) {
                    browserTabs.getOrNull(activeTabIndex)?.url = url
                    browserTabs.getOrNull(activeTabIndex)?.label =
                        (view.title ?: "").ifBlank { Uri.parse(url).host ?: "Novel" }
                    Store.addHistory(this@MainActivity, url, view.title ?: "")
                    persistBrowserTabs()
                    renderTabs()
                    val finishedUrl = url
                    handler.postDelayed({
                        if (view === novelWv && !restoringTab &&
                            novelWv.url == finishedUrl &&
                            activeTabIndex in browserTabs.indices) {
                            captureCurrentTabThumbnail()
                            persistBrowserTabs()
                        }
                    }, 350L)
                }
            }

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
                        refreshPending = false
                        refreshToken = 0
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
            // Novel reader should stay in the same WebView. Popup/new-window
            // navigation is a common ad/redirect path; never open it here.
            return false
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
    // WebNovel uses a reader DOM that the fast direct-text extractor can miss.
    // Keep its proven commit-96 extraction path isolated from every other site.
    private fun extractWebNovelNow(cb: (Chapter?) -> Unit) {
        novelWv.evaluateJavascript("document.documentElement.outerHTML") { raw ->
            val url = novelWv.url ?: ""
            Thread {
                val ch: Chapter? = try {
                    val html = decode(raw)
                    if (html.isEmpty()) null else {
                        val doc = Jsoup.parse(html, url)
                        val host = Uri.parse(url).host?.lowercase().orEmpty()
                        val isNovel543 = host == "novel543.com" || host.endsWith(".novel543.com")
                        Extractor.extract(
                            doc, url,
                            if (isNovel543) ".chapter-content" else SiteProfiles.selector(this@MainActivity, url, "content"),
                            if (isNovel543) "h1" else SiteProfiles.selector(this@MainActivity, url, "title"),
                            if (isNovel543) ".warp:nth-child(2) > a:nth-child(5)" else SiteProfiles.selector(this@MainActivity, url, "next"),
                            if (isNovel543) ".warp:nth-child(2) > a:nth-child(1)" else SiteProfiles.selector(this@MainActivity, url, "prev")
                        )
                    }
                } catch (e: Exception) { null }
                runOnUiThread {
                    if (ch != null) SiteProfiles.remember(this@MainActivity, ch)
                    cb(ch)
                }
            }.start()
        }
    }

    private fun extractNow(cb: (Chapter?) -> Unit) {
        val url = novelWv.url ?: ""
        if (url.isBlank()) { cb(null); return }

        val host = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        val isNovel543 = host == "novel543.com" || host.endsWith(".novel543.com")

        // Novel543 is a BR-separated, multi-page reader. Keep its old reliable
        // WebView -> full DOM -> Jsoup extraction path; the generic fast walker
        // can mistake the site's browser-warning wrapper for chapter content.
        if (isNovel543) {
            extractWebNovelNow(cb)
            return
        }

        // Other sites use the generic fast extractor.
        val contentSel = SiteProfiles.selector(this, url, "content")
        val titleSel = SiteProfiles.selector(this, url, "title").ifBlank {
            if (isNovel543) "h1" else ""
        }
        val nextSel = SiteProfiles.selector(this, url, "next").ifBlank {
            if (isNovel543) ".warp:nth-child(2) > a:nth-child(5)" else ""
        }
        val prevSel = SiteProfiles.selector(this, url, "prev").ifBlank {
            if (isNovel543) ".warp:nth-child(2) > a:nth-child(1)" else ""
        }

        // Fast path: inspect only the already-rendered chapter DOM.
        // This is the same extraction strategy used by Novel Translator:
        // bounded chapter-root search + leaf text units, with no whole-page
        // outerHTML serialization. It also keeps short CJK lines.
        novelWv.evaluateJavascript(
            Js.fastExtract(contentSel, titleSel, nextSel, prevSel)
        ) { raw ->
            val payload = decode(raw)
            if (payload.isBlank()) { cb(null); return@evaluateJavascript }

            try {
                val o = JSONObject(payload)
                if (!o.optBoolean("ok", false)) {
                    // Keep the proven Jsoup fallback for JS-heavy/BR-based sites.
                    // Novel543 is explicitly included because its chapter body is
                    // stable at .chapter-content but can be missed while its DOM is
                    // still settling.
                    extractWebNovelNow(cb)
                    return@evaluateJavascript
                }

                val title = o.optString("title", "").trim()
                val body = o.optString("text", "").trim()
                val segmentCount = o.optInt("count", 0)
                // Do not reject short but valid chapters. The old 120-char
                // gate caused short CJK/mobile-reader chapters to be reported
                // as "no chapter text".
                if (body.length < 40 || segmentCount < 1) {
                    // A partially-rendered Novel543 DOM can return only the title
                    // or a short fragment on the first JS pass. Let the proven
                    // parser retry the same page instead of declaring failure.
                    extractWebNovelNow(cb)
                    return@evaluateJavascript
                }

                fun cleanText(s: String): String = s
                    .replace("\u00a0", " ")
                    .replace(Regex("[ \\t]+"), " ")
                    .replace(Regex(" ?\\n ?"), "\\n")
                    .replace(Regex("\\n{3,}"), "\\n\\n")
                    .trim()

                val cleanBody = cleanText(body)
                val cleanTitle = cleanText(title)
                val fullText = if (cleanTitle.isNotEmpty() && cleanBody.startsWith(cleanTitle)) {
                    cleanBody
                } else if (cleanTitle.isNotEmpty()) {
                    cleanTitle + "\\n\\n" + cleanBody
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

    private fun adjacentUrlIsSafe(current: String, target: String): Boolean {
        return try {
            val c = Uri.parse(current)
            val t = Uri.parse(target)
            val ch = c.host?.lowercase().orEmpty()
            val th = t.host?.lowercase().orEmpty()
            if (ch.isBlank() || th.isBlank()) return false
            if (ch != th && !th.endsWith(".$ch") && !ch.endsWith(".$th")) return false

            val tp = t.path.orEmpty().trimEnd('/')
            if (tp.isBlank() || tp == "/" ||
                tp.equals("/home", true) || tp.equals("/index", true) ||
                tp.endsWith("/home", true) || tp.endsWith("/index", true)) return false

            // Do not compare path depth: valid +1 sites often use a shallower
            // path for chapters (e.g. /chapter-2 vs /book/foo/chapter-1).
            if (cleanUrl(c.toString()) == cleanUrl(t.toString()) &&
                c.query == t.query) return false
            true
        } catch (_: Exception) { false }
    }


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
        // Navigation is a hard boundary: every async extraction/translation
        // started for the previous chapter becomes invalid immediately.
        navToken++
        runToken++
        queue.clear()
        running = null
        autoCopy = false
        polling = false
        refreshPending = false
        hasTr = false
        shownTranslated = false
        lastChapter = null
        updatePill()
        clearClipboard()

        // Never leave the previous chapter's prompt in the chatbot input.
        // This must happen in Auto mode too.
        chatWv.evaluateJavascript(Js.stop(), null)
        chatWv.evaluateJavascript(Js.clearBox(), null)

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

    // ● : Instant Extract. Never use stale DOM from the previous chapter.
    private fun extractCopy() {
        navToken++
        val token = navToken

        fun runFresh() {
            if (token != navToken) return
            val liveUrlBefore = cleanUrl(novelWv.url ?: "")
            extractNow { ch ->
                if (token != navToken) return@extractNow
                val liveUrl = cleanUrl(novelWv.url ?: "")

                // Never send a result from a different URL. This prevents the
                // previous chapter's DOM from reaching translation after Next.
                if (ch == null || liveUrl.isBlank() || cleanUrl(ch.url) != liveUrl) {
                    toast("⏳ নতুন chapter পুরোপুরি load হয়নি — একটু পরে ● চাপো")
                    return@extractNow
                }

                // If navigation changed while extraction was running, discard it.
                if (liveUrlBefore != liveUrl) {
                    toast("⏳ chapter বদলাচ্ছে — আবার ● চাপো")
                    return@extractNow
                }

                handleChapter(ch)
            }
        }

        if (novelWv.progress < 100) {
            handler.postDelayed({
                if (token != navToken) return@postDelayed
                if (novelWv.progress >= 100) runFresh()
                else handler.postDelayed({ if (token == navToken) runFresh() }, 500)
            }, 250)
        } else {
            runFresh()
        }
    }

    // ▶ / ◀ : go to next/prev chapter and handle it. Screen mode is never changed.
    private fun step(dir: String) {
        // Capture the current chapter BEFORE invalidating navigation state.
        // wipeStale() intentionally clears lastChapter so no old chapter can
        // be committed by a late async callback.
        val cached = lastChapter
        val currentUrl = cleanUrl(novelWv.url ?: "")

        // Do not re-extract the current chapter before clicking Next/Prev.
        // The cached chapter already contains its adjacent URL.
        wipeStale()
        showNavLoading()
        val token = navToken

        fun navigate(base: Chapter) {
            if (token != navToken) return
            val target = if (dir == "next") base.next else base.prev
            val webNovel = base.url.contains("webnovel.com/", ignoreCase = true)

            if (target != null && adjacentUrlIsSafe(currentUrl, target)) {
                // Extension-style fast path: use the adjacent chapter URL
                // already discovered from the rendered DOM. This also works
                // for WebNovel and avoids opening /catalog on every Next click.
                wipeStale(clearNovelDom = true)
                navToken = token
                loadAndWait(target, token, base)
            } else {
                // Only fall back to the site's own navigation when the
                // extractor could not discover an adjacent URL.
                clickAndWait(dir, base, token)
            }
        }

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
        // Load the target exactly once. The extension-style extractor reads
        // the rendered DOM as soon as WebView finishes; no second reload.
        polling = false
        refreshPending = false
        refreshToken = 0
        novelWv.loadUrl(url)
        handler.postDelayed({
            if (token == navToken && autoCopy) {
                autoCopy = false
                polling = false
                hideNavLoading()
                toast("❌ নতুন chapter লোড হয়নি")
            }
        }, 8000)
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
                // Never force a second reload. A real navigation will trigger
                // onPageFinished; an SPA navigation is handled by the same
                // fast extractor polling path below.
                refreshPending = false
                refreshToken = 0
                if (autoExtract) {
                    handler.postDelayed({
                        if (token == navToken) pollExtract(0, token)
                    }, 120)
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

    private fun handleChapter(ch: Chapter) {
        onChapter(ch)
        if (Prefs.auto(this)) autoFlow(ch) else copyChapter(ch)
    }

    // ================================================================== copy mode (⚡ off)
    // Internal paragraph markers: used only during AI translation to keep
    // source paragraph -> translated paragraph alignment stable. They are stripped
    // before saving/copying the final Bengali text.
    private fun addInternalMarkers(text: String): String {
        val blocks = text.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return blocks.mapIndexed { i, block ->
            "[${(i + 1).toString().padStart(3, '0')}] $block"
        }.joinToString("\\n\\n")
    }

    private fun stripInternalMarkers(text: String): String =
        text.replace(Regex("(?m)^\\s*\\[\\d{3,}\\]\\s*"), "").trim()

    private fun splitInternalMarkedTranslation(text: String): List<String> {
        val marker = Regex("(?m)^\\s*\\[\\d{3,}\\]\\s*")
        if (!marker.containsMatchIn(text)) {
            return text.split(Regex("\\n\\s*\\n"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
        return marker.split(text)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun copyChapter(ch: Chapter) {
        val p = Prefs.prompt(this)
        val full = if (Prefs.bool(this, "withPrompt") && p.isNotBlank()) promptWithGlossary(p, ch.text) + ch.text else ch.text
        val tag = (if (ch.number.isNotEmpty()) "Ch ${ch.number} — " else "") + ch.title
        deliver(full, tag)
    }

    // "<prompt>\n\n[glossary of terms found in this chapter]\n\n---\n\n" — chapter text is appended by the caller
    private fun promptWithGlossary(prompt: String, chapterText: String): String {
        // Some sites (e.g. fanqienovel) draw text with a scrambled font: the copied text is
        // full of private-use characters and cannot be translated.
        val pua = chapterText.count { it in '\uE000'..'\uF8FF' }
        if (pua > 20 && pua * 20 > chapterText.length) {
            toast("⚠ এই চ্যাপ্টারের লেখা এনক্রিপ্টেড ফন্টে — অনুবাদ ভুল হবে")
        }
        val g = if (Prefs.glossaryOn(this)) Glossary.block(this, chapterText) else ""
        return prompt + "\n\n" + (if (g.isNotEmpty()) g + "\n\n" else "") + "---\n\n"
    }

    private fun deliver(full: String, label: String) {
        copy(full)
        toast("📋 কপি হয়েছে: $label (${full.length} অক্ষর)")
        if (Prefs.bool(this, "autoPaste")) {
            val send = Prefs.bool(this, "autoSend")
            val token = navToken
            handler.postDelayed({
                if (token != navToken) return@postDelayed
                chatWv.evaluateJavascript(Js.send(full, send)) { r ->
                    if (decode(r).startsWith("nobox")) toast("⚠️ চ্যাট বক্স পাইনি — কপি হয়ে আছে, নিজে পেস্ট করো")
                }
            }, 400)
        }
    }

    // ================================================================== auto mode (⚡ on)
    private fun autoFlow(ch: Chapter) {
        val saved = Store.find(this, ch)
        if (saved != null) {
            applyTranslation(ch, Store.read(this, saved.id), true)
            toast("📖 সেভ করা অনুবাদ বসালাম")
        } else {
            enqueue(ch)
        }
    }

    private fun enqueue(ch: Chapter) {
        val k = keyOf(ch)
        val r = running
        if ((r != null && keyOf(r) == k) || queue.any { keyOf(it) == k }) { updateProgressUi(); return }
        queue.add(ch)
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
        waitIdle(tok, ch, 0)
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
        // Keep paragraph identity inside the AI request. These markers never reach
        // the novel page and are removed from the final saved/clipboard translation.
        val marked = addInternalMarkers(ch.text)
        val markerInstruction = """
[Internal paragraph markers]
Each paragraph starts with a marker such as [001], [002], [003].
Keep every marker exactly once, in the same order. Do not translate, remove,
merge, split, or reorder the markers. Return only the translation with the
markers preserved.
""".trimIndent()
        val full = promptWithGlossary(Prefs.prompt(this), marked) +
            markerInstruction + "\n\n" + marked
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
                }, 1800)
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
        }, 1000)
    }

    private fun cleanReply(t: String): String =
        t.replace(Regex("^\\s*(ChatGPT|Gemini|Claude|DeepSeek|Grok)\\s+said\\s*[:：]?\\s*", RegexOption.IGNORE_CASE), "").trim()

    private fun finishJob(tok: Int, ch: Chapter) {
        if (tok != runToken) return
        chatWv.evaluateJavascript(Js.readText()) { raw ->
            if (tok != runToken || lastChapter?.let { keyOf(it) } != keyOf(ch)) return@evaluateJavascript
            val rawTranslation = cleanReply(decode(raw))
            if (rawTranslation.length < 30) {
                failJob(tok, ch, "উত্তর পড়া গেল না")
                return@evaluateJavascript
            }
            // Markers are internal alignment data only. Never expose them in the
            // clipboard or saved chapter, but keep the raw marked response for DOM
            // insertion so paragraph alignment survives AI formatting changes.
            val t = stripInternalMarkers(rawTranslation)
            if (t.length < 30) {
                failJob(tok, ch, "অনুবাদটি পড়া গেল না")
                return@evaluateJavascript
            }
            copy(t)
            Store.save(this, ch, t)
            progress = 100
            updateProgressUi()
            val shown = lastChapter
            if (shown != null && keyOf(shown) == keyOf(ch)) {
                applyTranslation(shown, rawTranslation, true)
            }
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
        copy(promptWithGlossary(Prefs.prompt(this), ch.text) + ch.text)
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
        val paras = splitInternalMarkedTranslation(text)
        // A translation callback can arrive long after the user pressed Next.
        // Never write an old chapter's translation into the current page.
        if (lastChapter !== ch) return

        novelWv.evaluateJavascript(Js.apply(sel, JSONArray(paras).toString(), Prefs.fontFile(this), Prefs.sizePx(this))) { r ->
            if (lastChapter !== ch) return@evaluateJavascript
            if (r != null && r.contains("ok")) {
                hasTr = true
                shownTranslated = true
                updatePill()
                if (retry) {   // some sites re-render the content a moment later — put it back once
                    handler.postDelayed({
                        if (shownTranslated && lastChapter === ch) {
                            novelWv.evaluateJavascript(Js.stillApplied(sel)) { s ->
                                if (lastChapter === ch && s != null && s.contains("lost")) {
                                    applyTranslation(ch, text, false)
                                }
                            }
                        }
                    }, 2500)
                }
            } else {
                toast("⚠️ অনুবাদ বসানো গেল না — লাইব্রেরিতে সেভ আছে")
            }
        }
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
            "🕘 ব্রাউজ হিস্ট্রি",
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
            "🔄 চ্যাটবট রিলোড",
            menuGlossaryLabel(),
            menuFontLabel(),
            "🌐 সাইট সেটিং (যে সাইট চলে না)",
            "🔍 এই পেজ পরীক্ষা করো (রিপোর্ট কপি)",
            menuSizeLabel(),
            "🔐 চ্যাটবট লগইন"
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
                1 -> { dlg.dismiss(); browserHistoryDialog() }
                2 -> { dlg.dismiss(); exportAll(null) }
                3 -> { dlg.dismiss(); saveBookmark() }
                4 -> { dlg.dismiss(); bookmarkList() }
                5 -> { dlg.dismiss(); editPrompt() }
                6 -> {
                    toggleAuto()
                    labels[i] = menuAutoLabel()
                    adapter.notifyDataSetChanged()
                }
                7 -> {
                    val ch = lastChapter
                    if (ch == null) toast("❌ আগে একটা চ্যাপ্টার খোলো")
                    else {
                        enqueue(ch)
                        toast("⏳ আবার অনুবাদে দেওয়া হলো")
                    }
                    dlg.dismiss()
                }
                8 -> { cancelAll(); dlg.dismiss() }
                9 -> {
                    val d = darkMode()
                    Prefs.put(this, "dark", ((d + 1) % 3).toString())
                    applyDark()
                    labels[i] = menuDarkLabel()
                    adapter.notifyDataSetChanged()
                }
                10 -> {
                    val v = !Prefs.bool(this, "autoPaste")
                    Prefs.putBool(this, "autoPaste", v)
                    labels[i] = menuAutoPasteLabel()
                    adapter.notifyDataSetChanged()
                }
                11 -> {
                    val v = !Prefs.bool(this, "autoSend")
                    Prefs.putBool(this, "autoSend", v)
                    labels[i] = menuAutoSendLabel()
                    adapter.notifyDataSetChanged()
                }
                12 -> {
                    val v = !Prefs.bool(this, "withPrompt")
                    Prefs.putBool(this, "withPrompt", v)
                    labels[i] = menuPromptLabel()
                    adapter.notifyDataSetChanged()
                }
                13 -> {
                    val next = !Prefs.bool(this, "noSaveNext")
                    Prefs.putBool(this, "noSaveNext", !next)
                    labels[i] = menuSaveNextLabel()
                    adapter.notifyDataSetChanged()
                }
                14 -> {
                    val v = !Prefs.bool(this, "autoExtractNext", true)
                    Prefs.putBool(this, "autoExtractNext", v)
                    labels[i] = menuNextExtractLabel()
                    adapter.notifyDataSetChanged()
                }
                15 -> {
                    val v = !Prefs.adblock(this)
                    Prefs.putBool(this, "noAdblock", !v)
                    labels[i] = menuAdBlockLabel()
                    adapter.notifyDataSetChanged()
                }
                16 -> {
                    toast("⏳ লিস্ট নামাচ্ছি…")
                    AdBlock.update(this) { n ->
                        toast(if (n > 0) "✅ $n টা হোস্ট যোগ হয়েছে" else "❌ আপডেট হয়নি")
                    }
                }
                17 -> { dlg.dismiss(); novelWv.reload() }
                18 -> { dlg.dismiss(); chatWv.reload() }
                19 -> { dlg.dismiss(); glossaryDialog() }
                21 -> { dlg.dismiss(); siteRulesDialog() }
                22 -> { dlg.dismiss(); diagnosePage() }
                23 -> {
                    Prefs.put(this, "trSize", ((Prefs.sizeIdx(this) + 1) % Prefs.SIZES.size).toString())
                    labels[i] = menuSizeLabel()
                    adapter.notifyDataSetChanged()
                    val ch = lastChapter
                    if (ch != null && hasTr) {
                        Store.find(this, ch)?.let { applyTranslation(ch, Store.read(this, it.id), false) }
                    }
                }
                24 -> { dlg.dismiss(); loginChatbot() }
                20 -> {
                    Prefs.put(this, "trFont", ((Prefs.fontIdx(this) + 1) % Prefs.FONT_FILES.size).toString())
                    labels[i] = menuFontLabel()
                    adapter.notifyDataSetChanged()
                    val ch = lastChapter
                    if (ch != null && hasTr) {
                        Store.find(this, ch)?.let { applyTranslation(ch, Store.read(this, it.id), false) }
                    }
                }
            }
        }
        dlg.show()
    }

    // ---------------------------------------------------------------- page diagnosis
    private fun diagnosePage() {
        val js = """
            (function(){
              function vis(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}
              function sel(e){
                var id=(e.id||'').trim();
                if(id&&/^[A-Za-z_][A-Za-z0-9_-]*$/.test(id))return '#'+id;
                var c=Array.from(e.classList||[]).filter(function(x){return /^[A-Za-z_][A-Za-z0-9_-]*$/.test(x);}).slice(0,3);
                return e.tagName.toLowerCase()+(c.length?'.'+c.join('.'):'');
              }
              var out=[];
              out.push('URL: '+location.href);
              out.push('title: '+document.title);
              out.push('iframes: '+document.querySelectorAll('iframe').length+', canvas: '+document.querySelectorAll('canvas').length);
              var els=Array.from(document.querySelectorAll('div,article,section,main,p')),cand=[];
              for(var i=0;i<els.length;i++){
                var e=els[i]; var t=(e.innerText||'').trim();
                if(t.length<150)continue;
                var direct=0; for(var k=0;k<e.children.length;k++){ if(e.children[k].tagName==='P')direct++; }
                cand.push({s:sel(e),len:t.length,p:direct,v:vis(e),txt:t});
              }
              cand.sort(function(a,b){return (b.p*300+b.len/50)-(a.p*300+a.len/50);});
              out.push('--- text containers (top 5) ---');
              cand.slice(0,5).forEach(function(c){
                var pua=0; for(var j=0;j<c.txt.length;j++){var cc=c.txt.charCodeAt(j); if(cc>=0xE000&&cc<=0xF8FF)pua++;}
                out.push(c.s+' | len='+c.len+' | p='+c.p+' | visible='+c.v+' | privateUseChars='+pua+' | start: '+c.txt.slice(0,40).replace(/\s+/g,' '));
              });
              out.push('--- next/prev candidates ---');
              var re=/next|prev|下一|上一|다음|이전|次|前|›|»|‹|«/i, n=0;
              var all=Array.from(document.querySelectorAll('a,button,[role=button],div,span'));
              for(var m=0;m<all.length&&n<10;m++){
                var e=all[m]; if(e.children.length>2)continue;
                var t=((e.innerText||'')+' '+(e.getAttribute('aria-label')||'')+' '+(e.title||'')).trim();
                if(t.length>0&&t.length<20&&re.test(t)&&vis(e)){ n++; out.push(sel(e)+' | '+e.tagName+' | "'+t.replace(/\s+/g,' ')+'"'+(e.href?' | href='+e.href:'')); }
              }
              return out.join('\n');
            })()
        """.trimIndent()
        novelWv.evaluateJavascript(js) { r ->
            val page = try { JSONArray("[" + (r ?: "null") + "]").optString(0, "") } catch (e: Exception) { "" }
            val state = "app: polling=$polling autoCopy=$autoCopy refreshPending=$refreshPending hasTr=$hasTr " +
                "catalogPending=$webNovelCatalogPending navToken=$navToken\n" +
                "siteRule=" + (SiteRules.find(this, novelWv.url ?: "")?.let { it.host + " compat=" + it.compat } ?: "none")
            val report = state + "\n" + page
            copy(report)
            toast("🔍 রিপোর্ট কপি হয়েছে — আমাকে পেস্ট করো")
        }
    }

    // ---------------------------------------------------------------- site rules
    private fun siteRulesDialog() {
        val rules = SiteRules.all(this)
        val labels = ArrayList<String>()
        labels.add("➕ নতুন সাইট যোগ করো")
        rules.forEach {
            labels.add(it.host + "\n" + listOfNotNull(
                if (it.compat) "পুরো পেজ পড়ে" else null,
                if (it.noAds) "অ্যাডব্লক বন্ধ" else null,
                if (it.content.isNotBlank()) "content সেট" else null
            ).joinToString(" • "))
        }
        AlertDialog.Builder(this)
            .setTitle("🌐 সাইট সেটিং")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i == 0) siteRuleEditor(null) else siteRuleEditor(rules[i - 1])
            }
            .setNegativeButton("বন্ধ", null)
            .show()
    }

    private fun siteRuleEditor(old: SiteRules.Rule?) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val cur = SiteRules.hostOf(novelWv.url ?: "")
        val host = EditText(this).apply {
            hint = "ডোমেইন, যেমন example.com"
            setSingleLine(true)
            setText(old?.host ?: cur)
        }
        val compat = CheckBox(this).apply { text = "পুরো পেজ পড়ে লেখা বের করো (JS সাইটের জন্য)"; isChecked = old?.compat ?: true }
        val noAds = CheckBox(this).apply { text = "এই সাইটে অ্যাডব্লক বন্ধ রাখো"; isChecked = old?.noAds ?: true }
        fun field(h: String, v: String?) = EditText(this).apply { hint = h; setSingleLine(true); setText(v ?: "") }
        val content = field("content selector (ঐচ্ছিক) যেমন #chapter-content", old?.content)
        val next = field("next selector (ঐচ্ছিক)", old?.next)
        val prev = field("prev selector (ঐচ্ছিক)", old?.prev)
        listOf<View>(host, compat, noAds, content, next, prev).forEach { col.addView(it) }
        val sv = ScrollView(this).apply { addView(col) }

        val b = AlertDialog.Builder(this)
            .setTitle(if (old == null) "➕ সাইট যোগ" else "✏️ ${old.host}")
            .setView(sv)
            .setPositiveButton("সেভ") { _, _ ->
                val h = SiteRules.hostOf(host.text.toString())
                if (h.isEmpty() || !h.contains('.')) { toast("❌ ঠিক ডোমেইন লেখো"); return@setPositiveButton }
                if (old != null && old.host != h) SiteRules.remove(this, old.host)
                SiteRules.upsert(this, SiteRules.Rule(
                    h, compat.isChecked, noAds.isChecked,
                    content.text.toString().trim(), next.text.toString().trim(), prev.text.toString().trim()
                ))
                toast("✅ $h সেভ হয়েছে — পেজ রিলোড করে ● চাপো")
            }
            .setNegativeButton("বাতিল", null)
        if (old != null) b.setNeutralButton("🗑 মুছো") { _, _ ->
            SiteRules.remove(this, old.host)
            toast("🗑 মুছে ফেলা হয়েছে")
        }
        b.show()
    }

    private fun menuGlossaryLabel(): String =
        (if (Prefs.glossaryOn(this)) "✅" else "⬜") + " 📖 গ্লোসারি (" + Glossary.count + " টা) — এডিট/যোগ"

    private fun menuSizeLabel(): String =
        "🔠 অনুবাদের অক্ষরের সাইজ: " + Prefs.sizePx(this) + "px  (ট্যাপ করলে বদলায়)"

    private fun menuFontLabel(): String =
        "🔤 অনুবাদের ফন্ট: " + Prefs.FONT_NAMES[Prefs.fontIdx(this)] + "  (ট্যাপ করলে বদলায়)"

    private fun glossaryDialog() {
        val opts = arrayOf(
            "➕ একটা শব্দ যোগ (english = বাংলা)",
            "📥 অনেক শব্দ পেস্ট করো",
            (if (Prefs.glossaryOn(this)) "✅" else "⬜") + " প্রম্পটে গ্লোসারি জোড়া চালু",
            "📋 পুরো গ্লোসারি কপি করো",
            "🗑 সব মুছে ফেলো"
        )
        AlertDialog.Builder(this)
            .setTitle("📖 গ্লোসারি — ${Glossary.count} টা এন্ট্রি")
            .setItems(opts) { _, i ->
                when (i) {
                    0 -> glossaryInput(false)
                    1 -> glossaryInput(true)
                    2 -> {
                        Prefs.putBool(this, "noGlossary", Prefs.glossaryOn(this))
                        toast(if (Prefs.glossaryOn(this)) "✅ গ্লোসারি চালু" else "⬜ গ্লোসারি বন্ধ")
                    }
                    3 -> { copy(Glossary.read(this)); toast("📋 গ্লোসারি কপি হয়েছে") }
                    4 -> AlertDialog.Builder(this)
                        .setMessage("পুরো গ্লোসারি মুছে যাবে। নিশ্চিত?")
                        .setPositiveButton("মুছো") { _, _ -> Glossary.write(this, ""); toast("🗑 মুছে ফেলা হয়েছে") }
                        .setNegativeButton("বাতিল", null)
                        .show()
                }
            }
            .setNegativeButton("বন্ধ", null)
            .show()
    }

    private fun glossaryInput(bulk: Boolean) {
        val et = EditText(this).apply {
            hint = if (bulk) "dantian | dan tian | 丹田 => ডান্টিয়ান\n(প্রতি লাইনে একটা)" else "young master = ইয়াং মাস্টার"
            if (bulk) { minLines = 8; gravity = Gravity.TOP } else setSingleLine(false)
        }
        val b = AlertDialog.Builder(this)
            .setTitle(if (bulk) "📥 পেস্ট করো" else "➕ নতুন শব্দ")
            .setView(et)
            .setPositiveButton("যোগ করো") { _, _ ->
                Glossary.append(this, et.text.toString())
                toast("✅ যোগ হয়েছে")
            }
            .setNegativeButton("বাতিল", null)
        if (bulk) b.setNeutralButton("সব বদলে দাও") { _, _ ->
            Glossary.write(this, et.text.toString())
            toast("✅ গ্লোসারি বদলানো হয়েছে")
        }
        b.show()
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
        if (!isFinishing) saveCurrentTabState()
        persistBrowserTabs()
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
