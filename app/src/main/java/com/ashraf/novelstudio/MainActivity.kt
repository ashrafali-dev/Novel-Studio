package com.ashraf.novelstudio

import android.animation.ValueAnimator
import android.app.Dialog
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewOutlineProvider
import android.view.GestureDetector
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
import android.view.MotionEvent
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
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import android.widget.AbsListView
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

    private val MAX_RETAINED_TAB_STATES = 2
    private val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    // views
    private lateinit var novelWv: WebView
    private lateinit var chatWv: WebView
    private lateinit var activeWv: WebView
    // Isolated, off-screen reader used only for optional next-chapter prefetch.
    private lateinit var prefetchWv: WebView
    private var prefetchTarget = ""
    private var prefetchToken = 0
    private var prefetchLoading = false
    private var prefetchCatalogPending = false
    private var prefetchCatalogTitle = ""
    private var prefetchSourceUrl = ""
    private val prefetchKeys = mutableSetOf<String>()
    private val prefetchGeneratedKeys = mutableSetOf<String>()
    private val prefetchedTranslationIds = mutableMapOf<String, Long>()
    private var activePrefetchedTranslationKey: String? = null
    private val botWebViews = mutableMapOf<String, WebView>()
    private var currentBotKey = ""
    private lateinit var urlBar: EditText
    private lateinit var tabCountBtn: TextView
    private lateinit var content: FrameLayout
    private lateinit var modeTransitionCover: ImageView
    private lateinit var novelBox: FrameLayout
    private lateinit var chatBox: LinearLayout
    private lateinit var strip: LinearLayout
    private lateinit var tabStrip: LinearLayout
    private lateinit var shortcutStrip: LinearLayout
    private lateinit var modeBtn: ImageView
    private lateinit var extractBtn: ExtractButton
    private lateinit var progressBox: LinearLayout
    private lateinit var progressBar: GlowBar
    private lateinit var progressTv: TextView
    private lateinit var togglePill: TextView
    private lateinit var tabRail: HorizontalScrollView
    private lateinit var shortcutScroll: HorizontalScrollView
    private lateinit var addressBox: LinearLayout
    private lateinit var progressPct: TextView
    private lateinit var snackView: TextView
    private var snackHide: Runnable? = null
    private var progressShown = false
    private var pulse: ValueAnimator? = null

    // state
    private var mode = Mode.SPLIT
    private var lastChapter: Chapter? = null
    private var lastCopied = ""
    private var exportNovel: String? = null
    private var hasTr = false                 // a translation is currently shown on the novel page
    private var shownTranslated = false
    private var jobMarkers = 0                 // number of [NNN] paragraph markers sent in the running job
    private var lastFastWhy = ""               // why the fast extractor failed (shown in the failure toast)
    private var shownTrRaw: String? = null     // translation text currently on the page (for font/size re-apply)
    private val handler = Handler(Looper.getMainLooper())
    // Freeze the current panes briefly while live WebViews resize during mode changes.
    private var modeTransitionBitmap: Bitmap? = null
    private var modeTransitionHide: Runnable? = null
    private var modeTransitionPreDraw: android.view.ViewTreeObserver.OnPreDrawListener? = null
    private var modeTransitionFrames = 0

    // navigation (▶ ◀ ●) — every action gets a fresh token, old callbacks with an old token are ignored
    private var navToken = 0
    private var autoCopy = false
    private var polling = false
    // Next/Prev: the target page is reloaded once (fast) before extraction starts.
    private var refreshPending = false
    private var refreshToken = 0
    private var pendUrl = ""
    private var pendHash = 0
    private var pendingNavHash: Int? = null
    private var pendingNavUrl = ""

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
    private var webNovelCatalogSourceUrl = ""
    private var webNovelCatalogOldHash = 0
    private var webNovelCatalogToken = 0

    // background translation queue
    private val queue = mutableListOf<Chapter>()
    private var running: Chapter? = null
    private var runToken = 0
    private var progress = 0
    private var progressTicker: Runnable? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ================================================================== theme ("Aurora Glass", see Ui.kt)
    private val cBg = UiTheme.bg
    private val cSurface = 0xFF0C0E15.toInt()
    private val cPill = 0xFF171A24.toInt()
    private val cChip = 0xFF1D2130.toInt()
    private val cField = 0xFF141722.toInt()
    private val cFieldStroke = 0xFF2A2F42.toInt()
    private val cStroke = 0xFF262B3C.toInt()
    private val cCard = 0xFF141722.toInt()
    private val cCardActive = 0xFF1C2238.toInt()
    private val cTxt = UiTheme.text
    private val cDim = UiTheme.dim
    private val cAccent = UiTheme.accent
    private val cSheet = UiTheme.sheet

    private fun pillBg(color: Int, radiusDp: Int, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
            if (stroke > 0) setStroke(dp(stroke), strokeColor)
        }

    private fun rippleWrap(v: View, color: Int, radiusDp: Int) {
        if (Build.VERSION.SDK_INT >= 21) {
            v.background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), pillBg(color, radiusDp), null
            )
        } else v.background = pillBg(color, radiusDp)
    }

    private fun toast(m: String) {
        var text = m
        val translations = listOf(
            "🔄 Reloading both pages" to "🔄 Reloading both pages",
            "🔄 Reloading chatbot…" to "🔄 Reloading chatbot…",
            "🔄 Reloading novel page…" to "🔄 Reloading novel page…",
            "🔐 Log in here; your session stays in the app" to "🔐 Log in here; your session stays in the app",
            "🕘 History is empty" to "🕘 History is empty",
            "আর কোনো chapter নেই" to "No more chapters",
            "WebNovel chapter link পাওয়া যায়নি" to "WebNovel chapter link not found",
            "এই পেজে চ্যাপ্টারের লেখা পাওয়া যায়নি" to "Chapter text was not found on this page",
            "নতুন chapter এখনো আসেনি — আবার ● চাপো" to "The next chapter has not loaded yet. Tap ● again.",
            "আগে নোভেলের একটা চ্যাপ্টার পেজ খোলো" to "Open a novel chapter page first",
            "নতুন chapter লোড হয়নি" to "The next chapter did not load",
            "WebNovel book URL বোঝা যায়নি" to "Could not identify the WebNovel book URL",
            "বাটন পাইনি — URL নম্বর দিয়ে অনুমান করছি" to "Navigation button not found; guessing from the URL",
            "নতুন chapter link পাওয়া যায়নি" to "Next chapter link not found",
            "নতুন চ্যাপ্টারের লেখা আসেনি — ● চেপে আবার চেষ্টা করো" to "Next chapter text has not appeared. Tap ● to retry.",
            "নতুন চ্যাপ্টারের লেখা পাওয়া যায়নি — ● চেপে আবার চেষ্টা করো" to "Next chapter text was not found. Tap ● to retry.",
            "এই চ্যাপ্টারের লেখা এনক্রিপ্টেড ফন্টে — অনুবাদ ভুল হবে" to "This chapter uses an encoded font; translation may be inaccurate",
            "গ্লোসারি: ${Glossary.lastMatchCount} টা টার্ম প্রম্পটে গেল" to "Glossary: ${Glossary.lastMatchCount} terms added to the prompt",
            "কপি হয়েছে: " to "Copied: ",
            "অক্ষর" to "characters",
            "চ্যাপ্টার কপি করে রাখলাম — নিজে চ্যাটে পেস্ট করে Copy → 💾 করো" to "Chapter copied. Paste it into the chatbot and tap Copy → 💾.",
            "চ্যাট বক্স পাইনি — কপি হয়ে আছে, নিজে পেস্ট করো" to "Chat input not found. Text is copied; paste it manually.",
            "অনুবাদ সেভ হয়েছে" to "Translation saved",
            "অটো অনুবাদ বন্ধ করলাম" to "Background translation stopped",
            "অনুবাদ বসানো গেল না — লাইব্রেরিতে সেভ আছে" to "Could not insert the translation; it is saved in the library",
            "অটো অনুবাদ চালু — ▶ চাপলেই ব্যাকগ্রাউন্ডে অনুবাদ হয়ে সাইটে বসবে" to "Background translation enabled — tap ▶ to translate and insert into the site",
            "কপি মোড — ▶ চাপলে চ্যাপ্টার কপি হবে" to "Copy mode — tap ▶ to copy the chapter",
            "ক্লিপবোর্ডে অনুবাদ নেই — আগে চ্যাটবটের উত্তরের Copy বাটন চাপো" to "No translation in clipboard. Copy the chatbot's answer first.",
            "এটা তো সোর্স টেক্সট — চ্যাটবটের উত্তরের Copy বাটন চাপো" to "This is source text. Copy the chatbot's answer instead.",
            "লাইব্রেরিতে সেভ:" to "Saved to library:",
            "আগে একটা চ্যাপ্টার খোলো" to "Open a chapter first",
            "আবার অনুবাদে দেওয়া হলো" to "Chapter added to the translation queue",
            "লিস্ট নামাচ্ছি…" to "Updating the block list…",
            "টা হোস্ট যোগ হয়েছে" to " hosts added",
            "আপডেট হয়নি" to "Update failed",
            "চ্যাটবট রিপোর্ট কপি হয়েছে — আমাকে পেস্ট করো" to "Chatbot report copied — paste it here",
            "রিপোর্ট কপি হয়েছে — আমাকে পেস্ট করো" to "Report copied — paste it here",
            "ঠিক ডোমেইন লেখো" to "Enter a valid domain",
            "সেভ হয়েছে — পেজ রিলোড করে ● চাপো" to "Saved — reload the page and tap ●",
            "মুছে ফেলা হয়েছে" to "Deleted",
            "গ্লোসারি চালু" to "Glossary enabled",
            "গ্লোসারি বন্ধ" to "Glossary disabled",
            "গ্লোসারি কপি হয়েছে" to "Glossary copied",
            "Delete" to "Delete",
            "যোগ হয়েছে" to "Added",
            "গ্লোসারি বদলানো হয়েছে" to "Glossary updated",
            "লাইব্রেরি খালি — অনুবাদ হলে এখানে জমবে" to "Library is empty. Translations will appear here when saved.",
            "এক্সপোর্ট করার মতো অনুবাদ নেই" to "There are no translations to export",
            "এক্সপোর্ট হয়েছে" to "Export completed",
            "ব্যর্থ" to "Failed",
            "সেভ হয়েছে:" to "Saved:",
            "বুকমার্ক খালি" to "Bookmarks are empty"
        )
        translations.forEach { (from, to) -> text = text.replace(from, to) }
        if (Regex("[\\u0980-\\u09FF]").containsMatchIn(text)) {
            text = "Action completed or failed. Please check the page and try again."
        }
        showSnack(text)
    }

    // ================================================================== UI building blocks
    private val medium: Typeface get() = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val boldMedium: Typeface get() = Typeface.create("sans-serif-medium", Typeface.BOLD)

    /** Small glass pill (quick-site shortcuts). */
    private fun chip(label: String, onClick: () -> Unit, onLong: (() -> Unit)?): TextView = TextView(this).apply {
        text = label
        textSize = 12.5f
        typeface = medium
        setTextColor(UiTheme.iconIdle)
        gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(14), 0)
        background = Glass.ripple(this@MainActivity, Glass.solid(this@MainActivity, 0x14FFFFFF, 17, UiTheme.strokeSoft), 17)
        layoutParams = LinearLayout.LayoutParams(WC, dp(34)).also { it.setMargins(dp(3), dp(4), dp(3), dp(4)) }
        setOnClickListener { onClick() }
        if (onLong != null) setOnLongClickListener { onLong(); true }
    }

    /** Segment of the chatbot selector; the active one is filled with the accent gradient. */
    private fun botChip(label: String, active: Boolean, onClick: () -> Unit, onLong: (() -> Unit)?): TextView = TextView(this).apply {
        text = label
        textSize = 12.5f
        typeface = if (active) boldMedium else medium
        setTextColor(if (active) Color.WHITE else UiTheme.dim)
        gravity = Gravity.CENTER
        setPadding(dp(15), 0, dp(15), 0)
        background = if (active) Glass.accent(this@MainActivity, 17) else Glass.ripple(this@MainActivity, null, 17)
        layoutParams = LinearLayout.LayoutParams(WC, dp(34)).also { it.setMargins(dp(3), 0, dp(3), 0) }
        setOnClickListener { onClick() }
        if (onLong != null) setOnLongClickListener { onLong(); true }
    }

    /** Icon slot of the bottom dock. */
    private fun dockBtn(ic: Ic, desc: String, onClick: () -> Unit): ImageView =
        iconButton(ic, desc, 22, 46, UiTheme.iconIdle, onClick).also {
            it.layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
        }

    private fun appBackground(): Drawable {
        fun glow(cx: Float, cy: Float, color: Int, radiusDp: Int) = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(radiusDp).toFloat()
            setGradientCenter(cx, cy)
            colors = intArrayOf(color, color and 0x00FFFFFF)
        }
        return LayerDrawable(arrayOf(
            ColorDrawable(UiTheme.bg),
            glow(0.05f, 0.0f, 0x387B8CFF, 340),
            glow(1.0f, 1.0f, 0x2EB27CFF, 380)
        ))
    }

    // ---- floating message (replaces the system Toast, which cannot be styled)
    private fun showSnack(msg: String) {
        runOnUiThread {
            if (!::snackView.isInitialized || !hasWindowFocus()) {
                // a dialog/sheet is on top of the app window: the system toast is still visible there
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }
            snackHide?.let { handler.removeCallbacks(it) }
            snackView.animate().cancel()
            snackView.text = msg
            snackView.visibility = View.VISIBLE
            snackView.alpha = 0f
            snackView.translationY = dp(16).toFloat()
            snackView.animate().alpha(1f).translationY(0f).setDuration(190).start()
            val r = Runnable { hideSnack() }
            snackHide = r
            handler.postDelayed(r, (2200L + msg.length * 28L).coerceAtMost(6500L))
        }
    }

    private fun hideSnack() {
        if (!::snackView.isInitialized) return
        snackView.animate().alpha(0f).translationY(dp(10).toFloat()).setDuration(170)
            .withEndAction { snackView.visibility = View.GONE }.start()
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
        prefetchWv = WebView(this).apply {
            setup(this)
            alpha = 0.01f
            isClickable = false
            isFocusable = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!prefetchLoading || url.isNullOrBlank()) return
                    val token = prefetchToken
                    if (prefetchCatalogPending) {
                        prefetchCatalogPending = false
                        val title = prefetchCatalogTitle
                        val sourceUrl = prefetchSourceUrl
                        // The catalogue list is drawn by JS AFTER onPageFinished, so retry for a while.
                        fun pickAttempt(attempt: Int) {
                            if (!prefetchLoading || token != prefetchToken) return
                            prefetchWv.evaluateJavascript(Js.webNovelPickCatalog("next", title, sourceUrl)) { raw ->
                                if (!prefetchLoading || token != prefetchToken) return@evaluateJavascript
                                val target = decode(raw)
                                if (target.startsWith("http") && adjacentUrlIsSafe(prefetchSourceUrl, target)) {
                                    prefetchTarget = target
                                    prefetchWv.loadUrl(target)
                                } else if (attempt < 10) {
                                    handler.postDelayed({ pickAttempt(attempt + 1) }, 800L)
                                } else {
                                    prefetchLoading = false
                                    prefetchTarget = ""
                                }
                            }
                        }
                        handler.postDelayed({ pickAttempt(0) }, 600L)
                        return
                    }
                    fun readAttempt(attempt: Int) {
                        if (!prefetchLoading || token != prefetchToken) return
                        prefetchExtractNow { next ->
                            if (!prefetchLoading || token != prefetchToken) return@prefetchExtractNow
                            if (next == null || next.text.length < 80) {
                                if (attempt < 14) {
                                    // SPA readers fetch the chapter text after load; scroll a little to wake lazy loaders.
                                    prefetchWv.evaluateJavascript(
                                        "try{window.scrollTo(0,Math.min(document.body.scrollHeight,900))}catch(e){}", null)
                                    handler.postDelayed({ readAttempt(attempt + 1) }, 900L)
                                } else {
                                    prefetchLoading = false
                                    prefetchTarget = ""
                                }
                                return@prefetchExtractNow
                            }
                            prefetchLoading = false
                            prefetchTarget = ""
                            val current = lastChapter
                            if (current != null && keyOf(next) == keyOf(current)) return@prefetchExtractNow
                            if (Store.find(this@MainActivity, next) != null) return@prefetchExtractNow
                            val nextKey = keyOf(next)
                            if (prefetchKeys.add(nextKey)) {
                                prefetchGeneratedKeys.add(nextKey)
                                enqueue(next)
                            }
                        }
                    }
                    handler.postDelayed({ readAttempt(0) }, 650L)
                }
            }
        }
        var pullDownStartY = 0f
        var pullDownStartX = 0f
        novelWv.setOnTouchListener { view, event ->
            activeWv = novelWv
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pullDownStartY = event.y
                    pullDownStartX = event.x
                }
                MotionEvent.ACTION_UP -> {
                    val dy = event.y - pullDownStartY
                    val dx = kotlin.math.abs(event.x - pullDownStartX)
                    if (dy > dp(110) && dx < dp(80) && novelWv.scrollY <= 0) {
                        novelWv.reload()
                        toast("Refreshing novel page…")
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    pullDownStartY = 0f
                    pullDownStartX = 0f
                }
            }
            false
        }
        chatWv.setOnTouchListener { _, _ -> activeWv = chatWv; false }

        window.statusBarColor = UiTheme.bg
        window.navigationBarColor = UiTheme.bg

        // ================= address bar (glass capsule): [tabs] [ address ......... ] [menu]
        val addrIcon = ImageView(this).apply {
            setImageDrawable(IconDrawable(Ic.GLOBE, UiTheme.dim))
            layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).also { it.marginEnd = dp(8) }
        }

        urlBar = EditText(this).apply {
            hint = "Search or enter address"
            setSingleLine()
            textSize = 14f
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(0, 0, 0, 0)
            setTextColor(UiTheme.text)
            setHintTextColor(UiTheme.faint)
            background = ColorDrawable(Color.TRANSPARENT)
            setSelectAllOnFocus(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnEditorActionListener { _, _, _ -> go(this.text.toString()); true }
            onFocusChangeListener = View.OnFocusChangeListener { _, has ->
                searchShortcutsVisible = has
                shortcutScroll.visibility = if (has) View.VISIBLE else View.GONE
                addressBox.background = Glass.field(this@MainActivity, 19, has)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val secure = s?.startsWith("https://") == true
                    addrIcon.setImageDrawable(IconDrawable(if (secure) Ic.LOCK else Ic.GLOBE, if (secure) UiTheme.ok else UiTheme.dim))
                }
            })
            setOnLongClickListener { showTabGrid(); true }
        }

        val reloadBtn = iconButton(Ic.RELOAD, "Reload", 19, 36, UiTheme.iconIdle) { reloadPage() }.apply {
            setOnLongClickListener { novelWv.reload(); chatWv.reload(); toast("🔄 Reloading both pages"); true }
        }

        addressBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), 0, dp(2), 0)
            background = Glass.field(this@MainActivity, 19, false)
            addView(addrIcon)
            addView(urlBar, LinearLayout.LayoutParams(0, MP, 1f))
            addView(reloadBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).also {
                it.marginStart = dp(8)
                it.marginEnd = dp(4)
            }
        }

        tabCountBtn = TextView(this).apply {
            text = browserTabs.size.toString()
            gravity = Gravity.CENTER
            textSize = 13.5f
            typeface = boldMedium
            setTextColor(UiTheme.text)
            background = Glass.ripple(this@MainActivity, Glass.solid(this@MainActivity, 0x00000000, 11, UiTheme.accent, 2), 11)
            contentDescription = "Open tabs"
            setOnClickListener { showTabGrid() }
            setOnLongClickListener { newBrowserTab("https://www.google.com/"); true }
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), 0, dp(6), 0)
            background = Glass.panel(this@MainActivity, 26)
            elevation = dp(8).toFloat()
            addView(tabCountBtn, LinearLayout.LayoutParams(dp(34), dp(34)))
            addView(addressBox)
        }
        val topWrap = FrameLayout(this).apply {
            setPadding(dp(10), dp(8), dp(10), dp(4))
            addView(topBar, FrameLayout.LayoutParams(MP, dp(52)))
        }

        // tab rail (only visible with 2+ tabs) and quick-site shortcuts (only while typing an address)
        tabStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
        }
        tabRail = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.GONE
            addView(tabStrip, ViewGroup.LayoutParams(WC, dp(40)))
        }
        shortcutStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
        }
        shortcutScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.GONE
            addView(shortcutStrip, ViewGroup.LayoutParams(WC, dp(42)))
        }

        val browserChrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(topWrap, LinearLayout.LayoutParams(MP, WC))
            addView(shortcutScroll, LinearLayout.LayoutParams(MP, WC))
        }

        // ================= panes (both always full-size and alive; the one on top is the one you see)
        novelBox = FrameLayout(this).apply { addView(novelWv, FrameLayout.LayoutParams(MP, MP)) }
        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
        }
        val stripScroll = HorizontalScrollView(this).apply {
            setBackgroundColor(cSurface)
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(strip, ViewGroup.LayoutParams(WC, dp(46)))
        }
        val stripDivider = View(this).apply { setBackgroundColor(UiTheme.strokeSoft) }
        chatBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(stripScroll, LinearLayout.LayoutParams(MP, WC))
            addView(stripDivider, LinearLayout.LayoutParams(MP, dp(1)))
            addView(chatWv, LinearLayout.LayoutParams(MP, 0, 1f))
        }

        // ================= translation status card (floats over the page)
        progressBar = GlowBar(this)
        progressTv = TextView(this).apply {
            textSize = 13.5f
            typeface = medium
            setTextColor(UiTheme.text)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        progressPct = TextView(this).apply {
            textSize = 13.5f
            typeface = boldMedium
            setTextColor(0xFFCFD5FF.toInt())
        }
        val progStop = iconButton(Ic.CLOSE, "Stop translation", 13, 28, UiTheme.iconIdle) { cancelAll() }.apply {
            background = Glass.ripple(this@MainActivity, Glass.oval(0x1FFFFFFF))
        }
        val progIcon = ImageView(this).apply { setImageDrawable(IconDrawable(Ic.GLOBE, UiTheme.accent)) }
        val progRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(progIcon, LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(progressTv, LinearLayout.LayoutParams(0, WC, 1f).also { it.marginStart = dp(9) })
            addView(progressPct, LinearLayout.LayoutParams(WC, WC).also { it.marginStart = dp(8) })
            addView(progStop, LinearLayout.LayoutParams(dp(28), dp(28)).also { it.marginStart = dp(8) })
        }
        progressBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(10), dp(10), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xF2282C3E.toInt(), 0xF2181B28.toInt())
            ).apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0x668C9CFF)
            }
            elevation = dp(10).toFloat()
            addView(progRow, LinearLayout.LayoutParams(MP, WC))
            addView(progressBar, LinearLayout.LayoutParams(MP, dp(5)).also { it.topMargin = dp(9) })
        }

        togglePill = TextView(this).apply {
            textSize = 12.5f
            typeface = boldMedium
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(17), 0)
            compoundDrawablePadding = dp(8)
            val gi = IconDrawable(Ic.GLOBE, Color.WHITE)
            gi.setBounds(0, 0, dp(16), dp(16))
            setCompoundDrawablesRelative(gi, null, null, null)
            visibility = View.GONE
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xEB7B8CFF.toInt(), 0xEBB27CFF.toInt())
            ).apply { cornerRadius = dp(19).toFloat() }
            elevation = dp(8).toFloat()
            setOnClickListener { toggleView() }
        }

        modeTransitionCover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            visibility = View.GONE
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        content = FrameLayout(this).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(20).toFloat())
                }
            }
            // Off-screen reader for prefetch: a real-size view UNDER the visible pages. A 1x1
            // WebView never triggers the lazy/IntersectionObserver rendering that WTR-LAB
            // and WebNovel use, so their chapter text never appeared.
            addView(prefetchWv, 0, FrameLayout.LayoutParams(MP, MP))
            addView(novelBox, FrameLayout.LayoutParams(MP, MP))
            addView(chatBox, FrameLayout.LayoutParams(MP, MP))
            addView(modeTransitionCover, FrameLayout.LayoutParams(MP, MP))
            addView(progressBox, FrameLayout.LayoutParams(MP, WC, Gravity.TOP).also { it.setMargins(dp(10), dp(10), dp(10), 0) })
            addView(togglePill, FrameLayout.LayoutParams(WC, dp(38), Gravity.BOTTOM or Gravity.END).also { it.setMargins(0, 0, dp(12), dp(12)) })
            addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot) applyLayout() }
        }
        val contentHolder = FrameLayout(this).apply {
            setPadding(dp(8), dp(4), dp(8), dp(2))
            addView(content, FrameLayout.LayoutParams(MP, MP))
        }

        // ================= floating dock: [view mode] [prev] [EXTRACT] [next] [save]
        modeBtn = dockBtn(Ic.SPLIT, "View mode") { cycleMode() }
        extractBtn = ExtractButton(this).apply {
            val tapDetector = GestureDetector(this@MainActivity,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean = true
                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        extractCopy()
                        return true
                    }
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        insertSavedTranslation()
                        return true
                    }
                    override fun onLongPress(e: MotionEvent) {
                        toggleAuto()
                    }
                })
            setOnTouchListener { _, event -> tapDetector.onTouchEvent(event) }
            isClickable = true
            isLongClickable = true
        }
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            background = Glass.panel(this@MainActivity, 34, strong = true)
            elevation = dp(12).toFloat()
            addView(modeBtn)
            addView(dockBtn(Ic.BACK, "Previous chapter") { step("prev") })
            addView(extractBtn, LinearLayout.LayoutParams(dp(66), dp(66)).also { it.setMargins(dp(2), 0, dp(2), 0) })
            addView(dockBtn(Ic.FORWARD, "Next chapter") { step("next") })
            addView(dockBtn(Ic.GRID, "Menu") { menu() })
        }
        val dockWrap = FrameLayout(this).apply {
            setPadding(dp(14), dp(6), dp(14), dp(12))
            addView(dock, FrameLayout.LayoutParams(MP, dp(68)))
        }

        snackView = TextView(this).apply {
            textSize = 13f
            typeface = medium
            setTextColor(UiTheme.text)
            maxWidth = dp(330)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xF2292D3F.toInt(), 0xF2181B28.toInt())
            ).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), 0x38FFFFFF)
            }
            elevation = dp(16).toFloat()
            visibility = View.GONE
            setOnClickListener { hideSnack() }
        }

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(browserChrome, LinearLayout.LayoutParams(MP, WC))
            addView(contentHolder, LinearLayout.LayoutParams(MP, 0, 1f))
            addView(dockWrap, LinearLayout.LayoutParams(MP, WC))
        }
        val root = FrameLayout(this).apply {
            background = appBackground()
            addView(main, FrameLayout.LayoutParams(MP, MP))
            addView(snackView, FrameLayout.LayoutParams(WC, WC, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).also { it.bottomMargin = dp(106) })
        }
        setContentView(root)

        buildStrip()
        buildSiteShortcuts()
        initBrowserTabs()
        setMode(Mode.SPLIT)
        refreshAutoBtn()
        applyDark()
        if (browserTabs.firstOrNull()?.url.isNullOrBlank()) {
            novelWv.loadUrl(Prefs.get(this, "lastNovelUrl", "https://www.google.com/"))
        }
        val firstBot = bots().getJSONObject(0).getString("u")
        val savedBot = Prefs.get(this, "botUrl", firstBot)
        currentBotKey = botKey(savedBot)
        botWebViews[currentBotKey] = chatWv
        chatWv.loadUrl(savedBot)
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
        wv.setBackgroundColor(0xFF0B0D14.toInt())
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
        }
    }

    // ⟳ : reload the pane you are looking at (long press = reload both)
    private fun reloadPage() {
        val wv = if (mode == Mode.CHAT) chatWv else activeWv
        wv.reload()
        toast(if (wv === chatWv) "🔄 Reloading chatbot…" else "🔄 Reloading novel page…")
    }

    private fun loginChatbot() {
        setMode(Mode.CHAT)
        val url = Prefs.get(this, "botUrl", bots().optJSONObject(0)?.optString("u", "https://chatgpt.com/") ?: "https://chatgpt.com/")
        openBot(url)
        CookieManager.getInstance().flush()
        toast("🔐 Log in here; your session stays in the app")
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
            if (!f.exists()) return
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, opts)
            var sample = 1
            while (opts.outWidth / sample > dp(640) || opts.outHeight / sample > dp(420)) sample *= 2
            val decode = BitmapFactory.Options().apply {
                inSampleSize = sample.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            tab.thumbnail = BitmapFactory.decodeFile(f.absolutePath, decode)
        } catch (_: Exception) {}
    }

    private fun releaseTabThumbnailsExcept(keepIndex: Int = -1) {
        browserTabs.forEachIndexed { i, tab ->
            if (i == keepIndex) return@forEachIndexed
            try { tab.thumbnail?.let { if (!it.isRecycled) it.recycle() } } catch (_: Exception) {}
            tab.thumbnail = null
        }
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
                // Thumbnail PNGs are loaded lazily only when the tab grid is opened.
                browserTabs.add(tab)
            }
        }
        if (browserTabs.isEmpty()) {
            browserTabs.add(BrowserTab("New Tab", Prefs.get(this, "lastNovelUrl", "https://www.google.com/")))
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

    // ---- tab rail: a slim row of chips (visible with 2+ tabs). The active chip is filled with the
    // accent tint and shows a close button; the others switch instantly (state is saved/restored).
    private val tabHues = listOf(
        0xFF27324F.toInt(), 0xFF3A2A52.toInt(), 0xFF1F3D3A.toInt(),
        0xFF3D2F27.toInt(), 0xFF283A52.toInt(), 0xFF42283A.toInt()
    )

    private fun tabHost(tab: BrowserTab): String = Uri.parse(tab.url).host?.removePrefix("www.") ?: ""

    private fun tabTitle(tab: BrowserTab): String = tab.label.ifBlank { tabHost(tab).ifBlank { "New Tab" } }

    private fun tabHue(tab: BrowserTab): Int = tabHues[(tabHost(tab).hashCode() and 0x7FFFFFFF) % tabHues.size]

    private fun tabInitial(tab: BrowserTab): String =
        tabHost(tab).firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"

    private fun renderTabs() {
        if (!::tabStrip.isInitialized) return
        tabStrip.removeAllViews()
        if (::tabCountBtn.isInitialized) tabCountBtn.text = browserTabs.size.toString()
        if (::tabRail.isInitialized) tabRail.visibility = if (browserTabs.size > 1) View.VISIBLE else View.GONE

        var activeChip: View? = null
        browserTabs.forEachIndexed { i, tab ->
            val active = i == activeTabIndex
            val chipView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), 0, dp(if (active) 4 else 12), 0)
                background = Glass.ripple(
                    this@MainActivity,
                    if (active) Glass.solid(this@MainActivity, 0x2E7B8CFF, 16, UiTheme.accent, 1.5f)
                    else Glass.solid(this@MainActivity, 0x12FFFFFF, 16, UiTheme.strokeSoft),
                    16
                )
                layoutParams = LinearLayout.LayoutParams(WC, dp(32)).also { it.setMargins(dp(3), dp(4), dp(3), dp(4)) }
                isClickable = true
                setOnClickListener { switchBrowserTab(i) }
                setOnLongClickListener { showTabGrid(); true }
            }
            val badge = TextView(this).apply {
                text = tabInitial(tab)
                textSize = 10f
                typeface = boldMedium
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = Glass.oval(tabHue(tab))
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).also { it.marginEnd = dp(7) }
            }
            chipView.addView(badge)
            chipView.addView(TextView(this).apply {
                text = tabTitle(tab)
                textSize = 12.5f
                typeface = if (active) boldMedium else medium
                setTextColor(if (active) UiTheme.text else UiTheme.dim)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = dp(if (active) 150 else 110)
                layoutParams = LinearLayout.LayoutParams(WC, WC)
            })
            if (active && browserTabs.size > 1) {
                chipView.addView(iconButton(Ic.CLOSE, "Close tab", 11, 24, UiTheme.iconIdle) { closeBrowserTab(i) }.also {
                    it.layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).also { lp -> lp.marginStart = dp(4) }
                })
                activeChip = chipView
            }
            tabStrip.addView(chipView)
        }

        tabStrip.addView(iconButton(Ic.PLUS, "New tab", 17, 32, UiTheme.accent) { newBrowserTab("https://www.google.com/") }.also {
            it.background = Glass.ripple(this, Glass.solid(this, 0x12FFFFFF, 16, UiTheme.strokeSoft), 16)
            it.layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).also { lp -> lp.setMargins(dp(3), dp(4), dp(6), dp(4)) }
        })
        val target = activeChip
        if (target != null && ::tabRail.isInitialized) tabRail.post { tabRail.smoothScrollTo(maxOf(0, target.left - dp(60)), 0) }
    }

    private fun saveCurrentTabState() {
        if (browserTabs.isEmpty() || restoringTab) return
        val tab = browserTabs[activeTabIndex]
        tab.url = novelWv.url ?: tab.url
        tab.label = (novelWv.title ?: "").ifBlank { Uri.parse(tab.url).host ?: "New Tab" }
        val state = Bundle()
        tab.state = state
        try { novelWv.saveState(state) } catch (_: Exception) {}
        // saveState() can retain a surprisingly large WebView history bundle. Keep only a couple of inactive tab snapshots.
        val inactive = browserTabs.indices.filter { it != activeTabIndex && browserTabs[it].state != null }
        if (inactive.size > MAX_RETAINED_TAB_STATES) {
            inactive.dropLast(MAX_RETAINED_TAB_STATES).forEach { browserTabs[it].state = null }
        }
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
            browserTabs[0] = BrowserTab("New Tab", "https://www.google.com/")
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
        val pad = dp(14)
        val gap = dp(10)
        val colW = (resources.displayMetrics.widthPixels - pad * 2 - gap) / 2
        val cardH = (colW * 1.34f).toInt()

        lateinit var dialog: Dialog
        val root = FrameLayout(this).apply { setBackgroundColor(0xEB07080D.toInt()) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ---- header: "Tabs" + count, options, close
        val title = TextView(this).apply {
            text = "Tabs"
            textSize = 25f
            setTextColor(UiTheme.text)
            typeface = boldMedium
        }
        val countChip = TextView(this).apply {
            textSize = 12.5f
            typeface = boldMedium
            gravity = Gravity.CENTER
            setTextColor(0xFFCFD5FF.toInt())
            setPadding(dp(10), 0, dp(10), 0)
            background = Glass.solid(this@MainActivity, 0x387B8CFF, 11)
            layoutParams = LinearLayout.LayoutParams(WC, dp(22)).also { it.marginStart = dp(10) }
        }
        val optionsBtn = iconButton(Ic.DOTS, "Tab options", 22, 44) { showTabOptions(dialog) }
        val closeBtn = iconButton(Ic.CLOSE, "Close", 22, 44) { dialog.dismiss() }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, dp(12), dp(6), dp(4))
            addView(title)
            addView(countChip)
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(optionsBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(closeBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        column.addView(header, LinearLayout.LayoutParams(MP, WC))

        // ---- search
        val searchIcon = ImageView(this).apply { setImageDrawable(IconDrawable(Ic.SEARCH, UiTheme.dim)) }
        val searchInput = EditText(this).apply {
            hint = "Search tabs"
            setSingleLine()
            textSize = 14.5f
            setTextColor(UiTheme.text)
            setHintTextColor(UiTheme.faint)
            background = ColorDrawable(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(filter)
        }
        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = Glass.field(this@MainActivity, 21, false)
            addView(searchIcon, LinearLayout.LayoutParams(dp(18), dp(18)).also { it.marginEnd = dp(10) })
            addView(searchInput, LinearLayout.LayoutParams(0, MP, 1f))
        }
        column.addView(searchBox, LinearLayout.LayoutParams(MP, dp(42)).also { it.setMargins(pad, dp(6), pad, dp(8)) })

        // ---- grid
        val emptyView = TextView(this).apply {
            text = "No tabs match your search"
            textSize = 14f
            setTextColor(UiTheme.faint)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(48), 0, 0)
        }
        val grid = GridLayout(this).apply {
            columnCount = 2
            setPadding(pad - gap / 2, dp(2), pad - gap / 2, dp(110))
        }
        val gridHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(grid, LinearLayout.LayoutParams(MP, WC))
            addView(emptyView, LinearLayout.LayoutParams(MP, WC))
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(gridHolder, ViewGroup.LayoutParams(MP, WC))
        }
        column.addView(scroll, LinearLayout.LayoutParams(MP, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MP, MP))

        // ---- "New tab" pill
        val newTab = TextView(this).apply {
            text = "New tab"
            textSize = 15f
            typeface = boldMedium
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(20), 0, dp(26), 0)
            compoundDrawablePadding = dp(8)
            val pi = IconDrawable(Ic.PLUS, Color.WHITE, 2.2f)
            pi.setBounds(0, 0, dp(20), dp(20))
            setCompoundDrawablesRelative(pi, null, null, null)
            background = Glass.accent(this@MainActivity, 27)
            elevation = dp(12).toFloat()
            setOnClickListener {
                dialog.dismiss()
                newBrowserTab("https://www.google.com/")
            }
        }
        root.addView(newTab, FrameLayout.LayoutParams(WC, dp(54), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).also { it.bottomMargin = dp(26) })

        fun refreshCards(query: String) {
            grid.removeAllViews()
            val list = browserTabs.withIndex().filter { (_, t) ->
                query.isBlank() || t.label.contains(query, true) || t.url.contains(query, true)
            }
            countChip.text = if (query.isBlank()) "${browserTabs.size}" else "${list.size}/${browserTabs.size}"
            emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

            list.forEachIndexed { pos, pair ->
                val i = pair.index
                val tab = pair.value
                val active = i == activeTabIndex
                loadTabThumbnail(tab)

                val card = FrameLayout(this).apply {
                    background = Glass.solid(this@MainActivity, 0xFF12141D.toInt(), 22)
                    clipToOutline = true
                    elevation = dp(if (active) 10 else 3).toFloat()
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = colW
                        height = cardH
                        setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
                    }
                    setOnClickListener {
                        dialog.dismiss()
                        switchBrowserTab(i)
                    }
                }

                // preview: the live thumbnail, or a tinted tile with the site's initial
                val thumb = tab.thumbnail
                if (thumb != null && !thumb.isRecycled) {
                    card.addView(ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(thumb)
                    }, FrameLayout.LayoutParams(MP, MP))
                } else {
                    card.addView(FrameLayout(this).apply {
                        background = GradientDrawable(
                            GradientDrawable.Orientation.TL_BR,
                            intArrayOf(tabHue(tab), 0xFF0E1018.toInt())
                        )
                        addView(TextView(this@MainActivity).apply {
                            text = tabInitial(tab)
                            textSize = 26f
                            typeface = boldMedium
                            gravity = Gravity.CENTER
                            setTextColor(Color.WHITE)
                            background = Glass.oval(0x2EFFFFFF)
                        }, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER).also { it.bottomMargin = dp(26) })
                    }, FrameLayout.LayoutParams(MP, MP))
                }

                card.addView(View(this).apply {
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(0x0007080D, 0xF207080D.toInt())
                    )
                }, FrameLayout.LayoutParams(MP, dp(84), Gravity.BOTTOM))

                val host = tabHost(tab)
                card.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, dp(12), dp(10))
                    addView(TextView(this@MainActivity).apply {
                        text = tabTitle(tab)
                        textSize = 12.5f
                        typeface = medium
                        setTextColor(UiTheme.text)
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    if (host.isNotBlank()) addView(TextView(this@MainActivity).apply {
                        text = host
                        textSize = 11f
                        setTextColor(UiTheme.dim)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }, FrameLayout.LayoutParams(MP, WC, Gravity.BOTTOM))

                card.addView(iconButton(Ic.CLOSE, "Close tab", 12, 30, Color.WHITE) {
                    closeBrowserTab(i)
                    refreshCards(searchInput.text.toString().trim())
                }.also {
                    it.background = Glass.ripple(this, Glass.oval(0x8C0A0C12.toInt(), 0x33FFFFFF, dp(1)))
                }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.END).also { it.setMargins(0, dp(8), dp(8), 0) })

                // border drawn above the content so the thumbnail cannot cover it
                card.addView(View(this).apply {
                    background = Glass.solid(
                        this@MainActivity, 0x00000000, 22,
                        if (active) UiTheme.accent else 0x24FFFFFF, if (active) 2.5f else 1f
                    )
                    isClickable = false
                }, FrameLayout.LayoutParams(MP, MP))

                grid.addView(card)
                card.alpha = 0f
                card.scaleX = 0.93f
                card.scaleY = 0.93f
                card.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay((pos * 22L).coerceAtMost(180L)).setDuration(220).start()
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { refreshCards(s?.toString()?.trim().orEmpty()) }
        })

        dialog = Sheets.create(this, root, fullScreen = true, bottom = false, dim = 0f)
        dialog.setOnDismissListener { releaseTabThumbnailsExcept(activeTabIndex) }
        dialog.setOnShowListener {
            root.alpha = 0f
            root.animate().alpha(1f).setDuration(180).start()
            refreshCards(filter)
        }
        dialog.show()
    }

    /** Bulk actions for the tab switcher. */
    private fun showTabOptions(parentDialog: Dialog) {
        lateinit var sheet: Dialog
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(18))
            background = Sheets.sheetBackground(this@MainActivity)
            addView(sheetHandle())
            val group = sheetGroup()
            group.addView(sheetRow(Ic.CLOSE, "Close other tabs", false) {
                sheet.dismiss()
                val keep = browserTabs[activeTabIndex]
                browserTabs.filter { it !== keep }.forEach { deleteTabThumbnail(it) }
                browserTabs.clear()
                browserTabs.add(keep)
                activeTabIndex = 0
                persistBrowserTabs()
                renderTabs()
                parentDialog.dismiss()
                showTabGrid()
            }.view)
            group.addView(sheetRow(Ic.CLOSE, "Close all tabs", true) {
                sheet.dismiss()
                browserTabs.forEach { deleteTabThumbnail(it) }
                browserTabs.clear()
                browserTabs.add(BrowserTab("New Tab", "https://www.google.com/"))
                activeTabIndex = 0
                novelWv.loadUrl(browserTabs[0].url)
                persistBrowserTabs()
                renderTabs()
                parentDialog.dismiss()
                showTabGrid()
            }.view)
            addView(group, LinearLayout.LayoutParams(MP, WC))
        }
        sheet = Sheets.create(this, col)
        sheet.show()
    }

    private fun browserHistoryDialog() {
        val h = Store.history(this)
        if (h.isEmpty()) return toast("🕘 History is empty")
        val labels = h.map {
            val host = Uri.parse(it.url).host ?: it.url
            "${it.title.ifBlank { host }}\n$host"
        }
        listDialog("🕘 Browser History", labels, { i ->
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
    // WebView may briefly expose a white surface frame when its viewport changes.
    // Keep a bitmap of the currently visible panes above them until the new layout settles.
    private fun beginModeTransition(): Boolean {
        if (!::content.isInitialized || !::modeTransitionCover.isInitialized ||
            content.width <= 0 || content.height <= 0) return false

        // If the user switches again before the current transition ends, keep the
        // same stable frame rather than synchronously drawing another large bitmap.
        if (modeTransitionCover.visibility == View.VISIBLE && modeTransitionBitmap != null) return true

        val snapshot = try {
            Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            return false
        }
        val progressVisibility = progressBox.visibility
        val pillVisibility = togglePill.visibility
        return try {
            // These controls are redrawn above the cover immediately after the mode switch.
            progressBox.visibility = View.GONE
            togglePill.visibility = View.GONE
            content.draw(Canvas(snapshot))
            modeTransitionBitmap?.let { if (!it.isRecycled) it.recycle() }
            modeTransitionBitmap = snapshot
            modeTransitionCover.setImageBitmap(snapshot)
            modeTransitionCover.visibility = View.VISIBLE
            true
        } catch (_: Exception) {
            if (!snapshot.isRecycled) snapshot.recycle()
            false
        } finally {
            progressBox.visibility = progressVisibility
            togglePill.visibility = pillVisibility
        }
    }

    private fun scheduleModeTransitionRelease() {
        modeTransitionHide?.let { handler.removeCallbacks(it) }
        modeTransitionPreDraw?.let { listener ->
            try {
                if (content.viewTreeObserver.isAlive) {
                    content.viewTreeObserver.removeOnPreDrawListener(listener)
                }
            } catch (_: Exception) {}
        }
        modeTransitionPreDraw = null
        modeTransitionFrames = 0

        val release = Runnable {
            modeTransitionCover.animate().cancel()
            modeTransitionCover.setImageDrawable(null)
            modeTransitionCover.visibility = View.GONE
            modeTransitionCover.alpha = 1f
            modeTransitionBitmap?.let { if (!it.isRecycled) it.recycle() }
            modeTransitionBitmap = null
            modeTransitionHide = null
            modeTransitionPreDraw = null
        }

        val startFade = Runnable {
            if (modeTransitionHide !== release || modeTransitionCover.visibility != View.VISIBLE) return@Runnable
            modeTransitionPreDraw?.let { listener ->
                try {
                    if (content.viewTreeObserver.isAlive) {
                        content.viewTreeObserver.removeOnPreDrawListener(listener)
                    }
                } catch (_: Exception) {}
            }
            modeTransitionPreDraw = null
            modeTransitionCover.animate().cancel()
            modeTransitionCover.alpha = 1f
            modeTransitionCover.animate()
                .alpha(0f)
                .setDuration(150L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction { release.run() }
                .start()
        }

        modeTransitionHide = release
        modeTransitionCover.animate().cancel()
        modeTransitionCover.alpha = 1f

        // Do not fade immediately. The old snapshot stays on top while the
        // new layout gets at least two real draw frames. This makes the
        // transition visible even when the WebViews switch in a few ms.
        val preDraw = object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (modeTransitionHide !== release || modeTransitionCover.visibility != View.VISIBLE) {
                    return true
                }
                modeTransitionFrames++
                if (modeTransitionFrames >= 2) {
                    handler.post(startFade)
                }
                return true
            }
        }
        modeTransitionPreDraw = preDraw
        content.viewTreeObserver.addOnPreDrawListener(preDraw)

        // Safety fallback in case a WebView does not trigger the expected
        // pre-draw cycle promptly.
        handler.postDelayed({
            if (modeTransitionHide === release && modeTransitionCover.visibility == View.VISIBLE) {
                startFade.run()
            }
        }, 300L)
    }

    private fun setMode(m: Mode) {
        val keepFrame = mode != m && beginModeTransition()
        mode = m
        modeBtn.setImageDrawable(IconDrawable(when (m) {
            Mode.NOVEL -> Ic.BOOK
            Mode.CHAT -> Ic.CHAT
            Mode.SPLIT -> Ic.SPLIT
        }, UiTheme.iconIdle))
        modeBtn.contentDescription = when (m) {
            Mode.NOVEL -> "Novel only"
            Mode.CHAT -> "Chatbot only"
            Mode.SPLIT -> "Split view"
        }
        activeWv = if (m == Mode.CHAT) chatWv else novelWv
        applyLayout()
        if (m != Mode.NOVEL) refreshChatViewport()
        updatePill()
        if (keepFrame) {
            modeTransitionCover.bringToFront()
            progressBox.bringToFront()
            togglePill.bringToFront()
            scheduleModeTransitionRelease()
        }
    }

    // Keep both WebView containers full-size and reveal only the needed region.
    // Resizing a live WebView during split -> full-screen can cause a one-frame
    // black surface flash on Android. Clipping keeps the WebView surface stable.
    private fun applyLayout() {
        val w = content.width
        val h = content.height
        if (w <= 0 || h <= 0) return

        when (mode) {
            Mode.NOVEL -> {
                // Full novel WebView.
                novelBox.translationY = 0f
                chatBox.translationY = 0f
                novelBox.clipBounds = null
                chatBox.clipBounds = null
                novelBox.layoutParams = FrameLayout.LayoutParams(MP, MP)
                chatBox.layoutParams = FrameLayout.LayoutParams(MP, 0)
                chatBox.bringToFront()
                novelBox.bringToFront()
            }

            Mode.CHAT -> {
                // Full chatbot WebView.
                novelBox.translationY = 0f
                chatBox.translationY = 0f
                novelBox.clipBounds = null
                chatBox.clipBounds = null
                novelBox.layoutParams = FrameLayout.LayoutParams(MP, 0)
                chatBox.layoutParams = FrameLayout.LayoutParams(MP, MP)
                novelBox.bringToFront()
                chatBox.bringToFront()
            }

            Mode.SPLIT -> {
                val half = h / 2

                // Each pane gets its own real half-height viewport. This is
                // important: clipping a full-height WebView only shows a slice
                // of its page. Giving chatBox a real half-height layout lets
                // chatWv fill the entire lower pane normally.
                novelBox.translationY = 0f
                chatBox.translationY = 0f
                novelBox.clipBounds = null
                chatBox.clipBounds = null

                novelBox.layoutParams = FrameLayout.LayoutParams(
                    MP, half
                )

                chatBox.layoutParams = FrameLayout.LayoutParams(
                    MP, half
                ).apply {
                    topMargin = half
                }

                // Ensure the lower chatbot pane wins hit-testing as well as drawing.
                // WebViews can retain stale sibling Z ordering after their bounds change.
                novelBox.elevation = 0f
                chatBox.elevation = dp(2).toFloat()
                content.bringChildToFront(novelBox)
                content.bringChildToFront(chatBox)
                chatWv.bringToFront()
            }
        }

        // Keep overlays above both panes, without covering the chatbot's touch area.
        progressBox.bringToFront()
        togglePill.bringToFront()
    }

    // Repaint a chatbot WebView after it was collapsed to zero height. Keep the
    // same WebView/session; do not reload and lose the logged-in chatbot state.
    private fun refreshChatViewport() {
        if (!::chatWv.isInitialized || !::chatBox.isInitialized) return
        chatBox.post {
            if (mode == Mode.NOVEL) return@post
            chatBox.requestLayout()
            chatWv.requestLayout()
            chatWv.onResume()
            chatWv.invalidate()
            chatWv.evaluateJavascript(
                "(function(){try{window.dispatchEvent(new Event('resize'));if(window.visualViewport)window.visualViewport.dispatchEvent(new Event('resize'));}catch(e){}})();",
                null
            )
            chatWv.postDelayed({
                if (mode != Mode.NOVEL) {
                    chatWv.requestLayout()
                    chatWv.invalidate()
                }
            }, 120L)
        }
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
        val cur = Prefs.get(this, "botUrl", a.optJSONObject(0)?.optString("u", "") ?: "")
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val u = o.getString("u")
            val active = Uri.parse(u).host != null && Uri.parse(u).host == Uri.parse(cur).host
            strip.addView(botChip(o.getString("n"), active, { openBot(u) }, { removeBot(i) }))
        }
        strip.addView(botChip("+", false, { addBotDialog() }, null))
    }

    private fun botKey(u: String): String = Uri.parse(u).host?.lowercase() ?: u.trim().lowercase()

    private fun configureBotWebView(wv: WebView) {
        setup(wv)
        wv.webViewClient = ChatClient()
        wv.settings.setSupportMultipleWindows(true)
        wv.settings.javaScriptCanOpenWindowsAutomatically = true
        wv.webChromeClient = ChatChrome()
        wv.setOnTouchListener { _, _ -> activeWv = wv; false }
    }

    private fun newBotWebView(): WebView = WebView(this).also { configureBotWebView(it) }

    private fun openBot(u: String) {
        val key = botKey(u)
        if (key == currentBotKey) return
        val target = botWebViews[key] ?: newBotWebView().also { wv ->
            botWebViews[key] = wv
            wv.loadUrl(u)
        }
        if (chatWv.parent === chatBox) chatBox.removeView(chatWv)
        chatBox.addView(target, 2, LinearLayout.LayoutParams(MP, 0, 1f))
        chatWv = target
        currentBotKey = key
        if (mode == Mode.CHAT) activeWv = chatWv
        Prefs.put(this, "botUrl", u)
        buildStrip()
    }

    private fun removeBot(i: Int) {
        AlertDialog.Builder(this)
            .setMessage("Remove this chatbot from the list?")
            .setPositiveButton("Delete") { _, _ ->
                val a = bots()
                a.remove(i)
                Prefs.put(this, "bots", a.toString())
                buildStrip()
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun addBotDialog() {
        val n = EditText(this).apply { hint = "Name" }
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
            .setTitle("Add Chatbot")
            .setView(l)
            .setPositiveButton("Add") { _, _ ->
                var url = u.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                if (!url.startsWith("http")) url = "https://$url"
                val a = bots()
                a.put(bot(n.text.toString().ifBlank { url }, url))
                Prefs.put(this, "bots", a.toString())
                buildStrip()
            }
            .setNegativeButton("Cancel", null)
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
                .setTitle("🔐 Login")
                .setView(box)
                .setNegativeButton("Close") { _, _ -> popup.stopLoading(); popup.destroy() }
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
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(s, "UTF-8")
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
            // Serve bundled Bengali fonts from a same-origin-looking path as well as the
            // legacy ns.local host. WebNovel's CSP can block cross-origin font URLs.
            val localFontPath = r.url.path?.startsWith("/__ns_font/") == true
            if (r.url.host == "ns.local" || localFontPath) {
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
                val sourceUrl = webNovelCatalogSourceUrl
                val oldHash = webNovelCatalogOldHash
                webNovelCatalogPending = false
                view.evaluateJavascript(Js.webNovelPickCatalog(dir, title, sourceUrl)) { raw ->
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
                            if (target == "edge") "ℹ️ No more chapters"
                            else "❌ WebNovel chapter link not found"
                        )
                    }
                }
                return
            }

            if (view === novelWv && !autoCopy && !webNovelCatalogPending && navToken > 0) {
                hideNavLoading()
            }

            if (view === novelWv && !url.isNullOrBlank() && !webNovelCatalogPending) {
                scheduleSavedTranslationCheck(url)
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

    private fun extractNow(attempt: Int = 0, cb: (Chapter?) -> Unit) {
        val url = novelWv.url ?: ""
        if (url.isBlank()) { cb(null); return }

        val host = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        val isNovel543 = host == "novel543.com" || host.endsWith(".novel543.com")

        // Use the fast rendered-DOM extractor for every site, including Novel543.
        // Novel543 has a dedicated fast path inside Js.fastExtract(). The old
        // full-document Jsoup parser remains only as a fallback when the fast
        // DOM read genuinely fails.
        val contentSel = if (isNovel543) ".chapter-content" else SiteProfiles.selector(this, url, "content")
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
            if (payload.isBlank()) {
                // The script itself failed to run/parse (e.g. very old WebView). Use the
                // slower full-page parser instead of giving up.
                lastFastWhy = "js-null"
                extractWebNovelNow(cb)
                return@evaluateJavascript
            }

            try {
                val o = JSONObject(payload)
                if (!o.optBoolean("ok", false)) {
                    lastFastWhy = o.optString("why", "no-units") + " roots=" + o.optInt("roots", -1)
                    // Keep the proven Jsoup fallback for JS-heavy/BR-based sites.
                    // Novel543 is explicitly included because its chapter body is
                    // stable at .chapter-content but can be missed while its DOM is
                    // still settling.
                    extractWebNovelNow(cb)
                    return@evaluateJavascript
                }

                lastFastWhy = ""
                val title = o.optString("title", "").trim()
                val body = o.optString("text", "").trim()
                val segmentCount = o.optInt("count", 0)
                // Do not reject short but valid chapters. The old 120-char
                // gate caused short CJK/mobile-reader chapters to be reported
                // as "no chapter text".
                val titleOnly = isNovel543 && title.isNotBlank() &&
                    body.trim().equals(title.trim(), ignoreCase = false)
                if (body.length < 40 || segmentCount < 1 || titleOnly) {
                    // Novel543 can briefly expose the heading before the actual
                    // chapter body. Retry the FAST rendered DOM a few times first;
                    // do not jump immediately to the slow full-document parser.
                    if (isNovel543 && attempt < 8) {
                        handler.postDelayed({
                            extractNow(attempt + 1, cb)
                        }, 120L)
                    } else if (isNovel543) {
                        extractWebNovelNow(cb)
                    } else {
                        cb(null)
                    }
                    return@evaluateJavascript
                }

                fun cleanText(s: String): String = s
                    .replace("\u00a0", " ")
                    .replace(Regex("[ \\t]+"), " ")
                    .replace(Regex(" ?\\n ?"), "\n")
                    .replace(Regex("\\n{3,}"), "\n\n")
                    .trim()

                val cleanBody = cleanText(body)
                val cleanTitle = cleanText(title)
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

                val number0 = Regex(
                    "\\b(?:chapter|chap|ch|episode|ep)\\.?\\s*[-#:.]?\\s*(\\d+(?:\\.\\d+)?)",
                    RegexOption.IGNORE_CASE
                ).find(cleanTitle)?.groupValues?.getOrNull(1)
                    ?: Regex("(\\d{1,5})").find(cleanTitle)?.value.orEmpty()
                // Novel543: /<book>/<vol>_<ch>_<part>.html is page <part> of the same chapter.
                val partNo = if (isNovel543)
                    Regex("/\\d+_\\d+_(\\d+)\\.html").find(url)?.groupValues?.get(1)?.toIntOrNull() else null
                val number = if (partNo != null && partNo >= 2 && number0.isNotEmpty() && !number0.contains('.'))
                    number0 + "." + partNo.toString().padStart(2, '0') else number0

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

    // Stable identity of the exact source page.
    // Novel543 uses multiple URLs for one logical chapter:
    // /<book>/<volume>_<chapter>.html, /..._<part>.html, /..._<part2>.html
    // Each page gets its own translation key while remaining in one chapter group.
    private fun pageKey(url: String): String {
        return try {
            val u = Uri.parse(url)
            val host = (u.host ?: "").lowercase().removePrefix("www.").removePrefix("m.")
            if (host == "novel543.com" || host.endsWith(".novel543.com")) {
                val book = u.pathSegments.firstOrNull().orEmpty()
                val file = u.pathSegments.getOrNull(1).orEmpty()
                val m = Regex("^(\\d+)_(\\d+)(?:_(\\d+))?\\.html?$").find(file)
                if (book.matches(Regex("\\d{7,}")) && m != null) {
                    val group = m.groupValues[1] + "_" + m.groupValues[2]
                    val part = m.groupValues.getOrNull(3).orEmpty().ifBlank { "1" }
                    return host + "/" + book + "/" + group + ":part" + part
                }
            }
            cleanUrl(url)
        } catch (_: Exception) { cleanUrl(url) }
    }

    private fun chapterGroupKey(url: String): String {
        return try {
            val u = Uri.parse(url)
            val host = (u.host ?: "").lowercase().removePrefix("www.").removePrefix("m.")
            if (host == "novel543.com" || host.endsWith(".novel543.com")) {
                val book = u.pathSegments.firstOrNull().orEmpty()
                val file = u.pathSegments.getOrNull(1).orEmpty()
                val m = Regex("^(\\d+)_(\\d+)(?:_\\d+)?\\.html?$").find(file)
                if (book.matches(Regex("\\d{7,}")) && m != null)
                    host + "/" + book + "/" + m.groupValues[1] + "_" + m.groupValues[2]
                else ""
            } else ""
        } catch (_: Exception) { "" }
    }

    private fun keyOf(ch: Chapter): String {
        val source = sourceKey(ch.url).ifBlank { cleanUrl(ch.url) }
        // Novel543 page identity wins over detected title/chapter number.
        if (chapterGroupKey(ch.url).isNotBlank()) return pageKey(ch.url)
        val number = ch.number.trim()
        if (number.isNotEmpty()) return source + "#" + number
        val title = ch.title.trim().lowercase().replace(Regex("\\s+"), " ")
        if (title.isNotEmpty()) return source + "|" + title
        return pageKey(ch.url)
    }

    // Stable identity of the BOOK, not the chapter number or detected title.
    // If a site has no recognizable book ID, use the exact URL as a safe fallback.
    private fun sourceKey(url: String): String = try {
        val u = Uri.parse(url)
        val host = (u.host ?: "").lowercase().removePrefix("www.").removePrefix("m.")
        val parts = u.pathSegments
        when {
            host.endsWith("webnovel.com") -> {
                val i = parts.indexOfFirst { it.equals("book", true) }
                if (i >= 0 && parts.size > i + 1) host + "/book/" + parts[i + 1].lowercase() else ""
            }
            host == "novel543.com" || host.endsWith(".novel543.com") -> {
                val id = parts.firstOrNull().orEmpty()
                if (id.matches(Regex("\\d{7,}"))) host + "/" + id else ""
            }
            else -> {
                val markers = setOf("novel", "book", "series", "story", "fiction")
                val i = parts.indexOfFirst { it.lowercase() in markers }
                if (i >= 0 && parts.size > i + 1 && parts[i + 1].isNotBlank())
                    host + "/" + parts[i].lowercase() + "/" + parts[i + 1].lowercase() else ""
            }
        }
    } catch (_: Exception) { "" }

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

            // If the site exposes a stable book ID, the next link MUST point to
            // that same book. Chapter numbers/titles alone are never enough.
            val currentBook = sourceKey(current)
            val targetBook = sourceKey(target)
            if (currentBook.isNotBlank() && targetBook != currentBook) return false

            if (th == "novel543.com" || th.endsWith(".novel543.com")) {
                // Accept the site's observed /<bookId>/<chapter> and
                // /<bookId>/<volume>_<chapter>[_<part>].html forms, same book only.
                val chapterPath = Regex("^/\\d{7,}/\\d+(?:_\\d+){0,2}(?:\\.html)?$")
                if (!chapterPath.matches(tp)) return false
            }

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
        return ch.text.length > 80 && (bodyHash(ch) != oldHash || titleChanged)
    }

    private fun onChapter(ch: Chapter) {
        val nextKey = keyOf(ch)
        val activePrefetchKey = activePrefetchedTranslationKey
        if (activePrefetchKey != null && activePrefetchKey != nextKey) {
            // A prefetched chapter has now been read and the reader moved on.
            // Remove only the transient prefetch copy, never a user-saved translation.
            prefetchedTranslationIds.remove(activePrefetchKey)?.let { Store.delete(this, it) }
            prefetchGeneratedKeys.remove(activePrefetchKey)
            prefetchKeys.remove(activePrefetchKey)
            activePrefetchedTranslationKey = null
        }
        lastChapter = ch
        Store.touchBookmark(this, ch)
        updateProgressUi()
    }

    private fun markPrefetchedTranslationActive(ch: Chapter, saved: Tr) {
        val key = keyOf(ch)
        if (prefetchedTranslationIds[key] == saved.id) activePrefetchedTranslationKey = key
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
                    x.style.cssText='position:fixed;inset:0;z-index:2147483647;background:linear-gradient(160deg,#0B0D14,#131826);color:#8B93A7;display:flex;align-items:center;justify-content:center;font:15px sans-serif;letter-spacing:.4px;';
                    x.textContent='⏳ Loading chapter…';
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
        // Navigation is a hard boundary for the visible chapter, but a
        // translation explicitly generated by Prefetch must continue in the
        // background. This is important for split chapters: while Part 1 is
        // being read, Part 2 may still be translating.
        navToken++
        val keepRunning = running?.let { prefetchGeneratedKeys.contains(keyOf(it)) } == true
        if (!keepRunning) {
            runToken++
            running = null
        }
        queue.removeAll { !prefetchGeneratedKeys.contains(keyOf(it)) }
        autoCopy = false
        polling = false
        refreshPending = false
        hasTr = false
        shownTranslated = false
        lastChapter = null
        updatePill()
        clearClipboard()

        // Never leave the previous chapter's prompt in the chatbot input.
        // Keep it intact when a prefetch translation is currently using the
        // chatbot; otherwise cancel the old visible-chapter job.
        if (!keepRunning) {
            chatWv.evaluateJavascript(Js.stop(), null)
            chatWv.evaluateJavascript(Js.clearBox(), null)
            // If Prefetch had already prepared another page, resume it after
            // cancelling the visible chapter's work.
            if (running == null && queue.isNotEmpty()) startNext()
        }

        if (clearNovelDom) {
            novelWv.evaluateJavascript(
                """
                (function(){
                    try {
                        document.documentElement.innerHTML =
                            '<head><title>Loading…</title></head>' +
                            '<body style="background:linear-gradient(160deg,#0B0D14,#131826);color:#8B93A7;font-family:sans-serif">' +
                            '<div style="padding:32px;text-align:center;letter-spacing:.4px">⏳ Loading chapter…</div>' +
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
    private fun webViewVer(): String = try {
        if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage()?.versionName ?: "?" else "old"
    } catch (_: Exception) { "?" }

    private fun extractCopy() {
        // ● is always a real extraction. Never wait for WebView.progress and
        // never turn it into the original/translation toggle.
        navToken++
        val token = navToken
        val cached = lastChapter
        // A displayed translation is not the source; keep using cached chapter text.

        fun attempt(n: Int) {
            extractNow { ch ->
                if (token != navToken) return@extractNow
                if (ch == null) {
                    // Page may still be rendering: a few quick retries (~1s total).
                    if (n < 4) {
                        handler.postDelayed({ if (token == navToken) attempt(n + 1) }, 250L)
                    } else {
                        toast("❌ Chapter text was not found on this page\n(fast: " +
                            lastFastWhy.ifBlank { "short/empty" } + " · WebView " + webViewVer() + ")")
                    }
                    return@extractNow
                }
                val pendingHash = pendingNavHash
                if (pendingHash != null && bodyHash(ch) == pendingHash) {
                    toast("⏳ The next chapter has not loaded yet. Tap ● again.")
                    return@extractNow
                }
                pendingNavHash = null
                pendingNavUrl = ""
                // Manual ●/Instant is always a forced re-translation.
                handleChapter(ch, useSavedTranslation = false)
            }
        }

        // If this exact page already has our Bengali insertion, reuse the cached
        // ORIGINAL chapter object. Never scrape the translated DOM for retranslation.
        if (cached != null &&
            cleanUrl(cached.url) == cleanUrl(novelWv.url ?: "") &&
            hasTr && shownTranslated && cached.text.isNotBlank()) {
            handleChapter(cached, useSavedTranslation = false)
        } else {
            attempt(0)
        }
    }

    // ▶ / ◀ : go to next/prev chapter and handle it. Screen mode is never changed.
    private fun step(dir: String) {
        // Capture the current chapter BEFORE invalidating navigation state.
        // wipeStale() intentionally clears lastChapter so no old chapter can
        // be committed by a late async callback.
        val cached = lastChapter
        val currentUrl = cleanUrl(novelWv.url ?: "")
        pendingNavHash = cached?.let { bodyHash(it) }
        pendingNavUrl = currentUrl

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
                toast("❌ Open a novel chapter page first")
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
                toast("❌ The next chapter did not load")
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
                toast("❌ Could not identify the WebNovel book URL")
                return
            }
            val catalogUrl = parsed.scheme + "://" + parsed.authority + match.value + "/catalog"
            webNovelCatalogPending = true
            webNovelCatalogDir = dir
            webNovelCatalogTitle = base.title
            webNovelCatalogSourceUrl = base.url
            webNovelCatalogOldHash = bodyHash(base)
            webNovelCatalogToken = token
            novelWv.loadUrl(catalogUrl)
            return
        }

        val script = Js.clickNext(dir)
        novelWv.evaluateJavascript(script) { res ->
            if (token != navToken) return@evaluateJavascript
            if (res != null && (res.contains("none") || res.contains("failed"))) {
                val h543 = Uri.parse(base.url).host.orEmpty().lowercase()
                val g = if (h543 == "novel543.com" || h543.endsWith(".novel543.com")) null
                        else Extractor.bump(base.url, if (dir == "next") 1 else -1)
                if (g != null) {
                    toast("⚠️ Navigation button not found; guessing from the URL")
                    loadAndWait(g, token, base)
                } else {
                    hideNavLoading()
                    toast("❌ Next chapter link not found")
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
                    toast("❌ Next chapter text has not appeared. Tap ● to retry.")
                }
            }
        }, if (n < 4) 150L else 220L)
    }

    // After a direct page load, accept a new body even when the URL is reused.
    private fun pollExtract(n: Int, token: Int, reloaded: Boolean = false) {
        if (token != navToken) return
        extractNow { ch ->
            if (token != navToken) return@extractNow
            if (ch != null && ch.text.length > 80 &&
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
                toast("❌ Next chapter text was not found. Tap ● to retry.")
            }
        }
    }

    // let the page finish rendering, read it once more, then use the fuller version
    private fun commit(ch: Chapter, token: Int) {
        if (token != navToken) return
        hideNavLoading()
        pendingNavHash = null
        pendingNavUrl = ""
        handleChapter(ch)
    }

    private fun handleChapter(ch: Chapter, useSavedTranslation: Boolean = true) {
        onChapter(ch)
        if (useSavedTranslation) {
            val saved = Store.find(this, ch)
            if (saved != null) {
                val translated = Store.readRaw(this, saved.id)
                if (translated.length >= 30) {
                    markPrefetchedTranslationActive(ch, saved)
                    applyTranslation(ch, translated, true)
                    schedulePrefetch(ch)
                    return
                }
            }
        }
        if (Prefs.auto(this)) autoFlow(ch) else copyChapter(ch)
        schedulePrefetch(ch)
    }

    // On page load, check for a saved translation even when Next auto-extraction is off.
    private fun scheduleSavedTranslationCheck(pageUrl: String) {
        if (!Prefs.bool(this, "prefetchNextChapter")) return
        val token = navToken
        handler.postDelayed({
            if (token != navToken || cleanUrl(novelWv.url ?: "") != cleanUrl(pageUrl)) return@postDelayed
            if (hasTr && lastChapter?.let { cleanUrl(it.url) == cleanUrl(pageUrl) } == true) return@postDelayed
            extractNow { ch ->
                if (token != navToken || ch == null || cleanUrl(novelWv.url ?: "") != cleanUrl(pageUrl)) return@extractNow
                val saved = Store.find(this, ch) ?: return@extractNow
                val translated = Store.readRaw(this, saved.id)
                if (translated.length < 30) return@extractNow
                autoCopy = false
                polling = false
                onChapter(ch)
                markPrefetchedTranslationActive(ch, saved)
                hideNavLoading()
                applyTranslation(ch, translated, true)
                schedulePrefetch(ch)
            }
        }, 500L)
    }

    // Backup: double-tap ⚡ to insert a saved translation without translating again.
    private fun insertSavedTranslation() {
        val token = navToken
        val cached = lastChapter
        fun useChapter(ch: Chapter) {
            if (token != navToken) return
            onChapter(ch)
            val saved = Store.find(this, ch)
            val translated = saved?.let { Store.readRaw(this, it.id) }.orEmpty()
            if (translated.length < 30) {
                toast("ℹ️ No saved translation found for this chapter")
                return
            }
            saved?.let { markPrefetchedTranslationActive(ch, it) }
            autoCopy = false
            polling = false
            applyTranslation(ch, translated, true)
            schedulePrefetch(ch)
        }
        if (cached != null && cleanUrl(cached.url) == cleanUrl(novelWv.url ?: "")) {
            useChapter(cached)
        } else {
            extractNow { ch ->
                if (token != navToken) return@extractNow
                if (ch == null) toast("❌ Chapter text was not found on this page") else useChapter(ch)
            }
        }
    }
    private fun schedulePrefetch(ch: Chapter) {
        if (!Prefs.bool(this, "prefetchNextChapter")) return
        val parsedCurrent = Uri.parse(ch.url)
        val hostCurrent = parsedCurrent.host?.lowercase().orEmpty()
        val inferredWtrNext = if (hostCurrent == "wtr-lab.com" || hostCurrent.endsWith(".wtr-lab.com")) {
            try {
                val path = parsedCurrent.path.orEmpty()
                val m = Regex("^(.*?/chapter-)(\\d+)/?$", RegexOption.IGNORE_CASE).find(path)
                if (m != null) {
                    val nextPath = m.groupValues[1] + (m.groupValues[2].toLong() + 1L)
                    parsedCurrent.buildUpon().path(nextPath).build().toString()
                } else null
            } catch (_: Exception) { null }
        } else null
        // WebNovel's reader can expose a non-adjacent/opaque chapter URL as "Next".
        // Always resolve its next chapter from the catalog, just like the visible Next button.
        val isWebNovel = ch.url.contains("webnovel.com/", ignoreCase = true)
        val directTarget = if (isWebNovel) null else
            (ch.next?.takeIf { it.startsWith("http") && adjacentUrlIsSafe(ch.url, it) }
                ?: inferredWtrNext?.takeIf { adjacentUrlIsSafe(ch.url, it) })
        val target: String
        val useCatalog: Boolean
        if (directTarget != null) {
            target = directTarget
            useCatalog = false
        } else {
            // WebNovel often exposes Next only through its catalogue rather than
            // as a direct adjacent URL in the chapter DOM.
            val parsed = Uri.parse(ch.url)
            val path = parsed.path.orEmpty()
            val bookPath = Regex("^(/book/[^/]+)", RegexOption.IGNORE_CASE).find(path)?.value
            if (!ch.url.contains("webnovel.com/", ignoreCase = true) ||
                bookPath == null || parsed.scheme.isNullOrBlank() || parsed.authority.isNullOrBlank()) return
            target = parsed.scheme + "://" + parsed.authority + bookPath + "/catalog"
            useCatalog = true
        }
        if (prefetchLoading && cleanUrl(prefetchTarget) == cleanUrl(target)) return
        prefetchToken++
        val token = prefetchToken
        prefetchSourceUrl = ch.url
        prefetchCatalogTitle = ch.title
        prefetchCatalogPending = useCatalog
        prefetchTarget = target
        prefetchLoading = true
        prefetchWv.stopLoading()
        prefetchWv.loadUrl(target)
        handler.postDelayed({
            if (prefetchLoading && token == prefetchToken) {
                prefetchLoading = false
                prefetchCatalogPending = false
                prefetchTarget = ""
                try { prefetchWv.stopLoading() } catch (_: Exception) {}
            }
        }, 30000L)
    }

    private fun menuPrefetchLabel(): String =
        (if (Prefs.bool(this, "prefetchNextChapter")) "✅" else "⬜") +
            " ⚡ Prefetch next chapter in background"

    /**
     * Dedicated prefetch extractor. Intentionally reads ONLY prefetchWv.
     * Do not route this through extractNow(), whose source is the visible novelWv.
     */
    private fun prefetchExtractNow(cb: (Chapter?) -> Unit) {
        val url = prefetchWv.url ?: prefetchTarget
        if (url.isBlank()) { cb(null); return }
        val host = Uri.parse(url).host?.lowercase().orEmpty()
        val isNovel543 = host == "novel543.com" || host.endsWith(".novel543.com")
        val contentSel = if (isNovel543) ".chapter-content" else SiteProfiles.selector(this, url, "content")
        val titleSel = SiteProfiles.selector(this, url, "title").ifBlank { if (isNovel543) "h1" else "" }
        val nextSel = SiteProfiles.selector(this, url, "next").ifBlank {
            if (isNovel543) ".warp:nth-child(2) > a:nth-child(5)" else ""
        }
        val prevSel = SiteProfiles.selector(this, url, "prev").ifBlank {
            if (isNovel543) ".warp:nth-child(2) > a:nth-child(1)" else ""
        }

        // IMPORTANT: background prefetch must use the same rendered-DOM extractor
        // as the visible reader. The old Jsoup-only path picked WTR-LAB's shared
        // reader shell and accidentally included TOC/progress/navigation text.
        prefetchWv.evaluateJavascript(Js.fastExtract(contentSel, titleSel, nextSel, prevSel)) { raw ->
            val payload = decode(raw)
            try {
                val o = JSONObject(payload)
                val title = o.optString("title", "").trim()
                val body = o.optString("text", "").trim()
                val count = o.optInt("count", 0)
                if (o.optBoolean("ok", false) && body.length >= 80 && count > 0 &&
                    !(title.isNotBlank() && body == title)) {
                    val full = if (title.isNotBlank() && !body.startsWith(title)) "$title\n\n$body" else body
                    val number = Regex(
                        "\\b(?:chapter|chap|ch|episode|ep)\\.?\\s*[-#:.]?\\s*(\\d+(?:\\.\\d+)?)",
                        RegexOption.IGNORE_CASE
                    ).find(title)?.groupValues?.getOrNull(1)
                        ?: Regex("(\\d{1,5})").find(title)?.value.orEmpty()
                    val chapter = Chapter(
                        title.ifBlank { o.optString("pageTitle", "").trim() },
                        full,
                        o.optJSONObject("next")?.optString("href", "")?.takeIf { it.isNotBlank() },
                        o.optJSONObject("prev")?.optString("href", "")?.takeIf { it.isNotBlank() },
                        url,
                        o.optString("novel", "").trim().ifBlank { host.removePrefix("www.") },
                        number,
                        o.optString("contentSel", ""),
                        o.optString("titleSel", ""),
                        o.optJSONObject("next")?.optString("selector", "").orEmpty(),
                        o.optJSONObject("prev")?.optString("selector", "").orEmpty()
                    )
                    SiteProfiles.remember(this@MainActivity, chapter)
                    cb(chapter)
                    return@evaluateJavascript
                }
            } catch (_: Exception) { /* use bounded HTML fallback below */ }

            // Fallback only when rendered-DOM extraction genuinely fails.
            prefetchWv.evaluateJavascript("document.documentElement.outerHTML") { htmlRaw ->
                val html = decode(htmlRaw)
                if (html.isBlank()) { cb(null); return@evaluateJavascript }
                Thread {
                    val chapter = try {
                        val doc = Jsoup.parse(html, url)
                        Extractor.extract(doc, url, contentSel, titleSel, nextSel, prevSel)
                    } catch (_: Exception) { null }
                    runOnUiThread {
                        if (chapter != null) SiteProfiles.remember(this@MainActivity, chapter)
                        cb(chapter)
                    }
                }.start()
            }
        }
    }

    // ================================================================== copy mode (⚡ off)
    // Internal paragraph markers: used only during AI translation to keep
    // source paragraph -> translated paragraph alignment stable. They are stripped
    // before saving/copying the final Bengali text.
    private fun addInternalMarkers(text: String, splitSingleLines: Boolean = false): String {
        var blocks = text.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        // Normal case: the extractor joined the real text blocks with blank lines.
        // Some readers (WTR-LAB in particular) can hand over the whole chapter as ONE
        // block whose paragraphs are separated by single newlines only. Then every
        // line is a paragraph; otherwise the model gets a single [001] marker and the
        // whole translation lands as one wall of text.
        if (splitSingleLines && blocks.size <= 2) {
            val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size >= 6) blocks = lines
        }
        // Last resort: one huge line with no newlines at all. Cut it at sentence ends
        // so the translation still comes back as readable paragraphs.
        if (blocks.size == 1 && blocks[0].length > 800) blocks = chunkBySentences(blocks[0])
        return blocks.mapIndexed { i, block ->
            "[${(i + 1).toString().padStart(3, '0')}] $block"
        }.joinToString("\n\n")
    }

    private fun chunkBySentences(t: String, target: Int = 280): List<String> {
        val ends = "。！？!?…」』”"
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in t) {
            sb.append(c)
            if (sb.length >= target && ends.indexOf(c) >= 0) {
                out.add(sb.toString().trim())
                sb.setLength(0)
            }
        }
        if (sb.isNotBlank()) out.add(sb.toString().trim())
        return out.filter { it.isNotEmpty() }
    }

    private fun stripInternalMarkers(text: String): String =
        text.replace(Regex("(?m)^\\s*\\[\\d{3,}\\]\\s*"), "").trim()

    private fun splitInternalMarkedTranslation(text: String): List<String> {
        // Models sometimes keep [001][002] markers on the same physical line.
        // Split on the marker itself, not only on a line-start marker.
        val marker = Regex("\\[\\d{3,}\\]\\s*")
        val parts = if (marker.containsMatchIn(text)) {
            marker.split(text).map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        }
        if (parts.size > 1) return parts
        // Only ONE block came back (markers lost / flattened by the chatbot): rebuild paragraphs
        // from its single newlines, or from sentence ends, instead of one wall of text.
        val one = parts.firstOrNull() ?: return parts
        val lines = one.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size >= 3) return lines
        if (one.length > 500) {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            for (c in one) {
                sb.append(c)
                if (sb.length >= 220 && "।!?”\u2026".indexOf(c) >= 0) { out.add(sb.toString().trim()); sb.setLength(0) }
            }
            if (sb.isNotBlank()) out.add(sb.toString().trim())
            if (out.size > 1) return out
        }
        return parts
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
            toast("⚠ This chapter uses an encoded font; translation may be inaccurate")
        }
        val g = if (Prefs.glossaryOn(this)) Glossary.block(this, chapterText) else ""
        if (Prefs.glossaryOn(this) && Glossary.count > 0)
            toast("📖 Glossary: ${Glossary.lastMatchCount} terms added to the prompt")
        return prompt + "\n\n" + (if (g.isNotEmpty()) g + "\n\n" else "") + "---\n\n"
    }

    private fun deliver(full: String, label: String) {
        copy(full)
        toast("📋 Copied: $label (${full.length} characters)")
        if (Prefs.bool(this, "autoPaste")) {
            val send = Prefs.bool(this, "autoSend")
            val token = navToken
            handler.postDelayed({
                if (token != navToken) return@postDelayed
                chatWv.evaluateJavascript(Js.send(full, send)) { r ->
                    if (decode(r).startsWith("nobox")) toast("⚠️ Chat input not found. Text is copied; paste it manually.")
                }
            }, 400)
        }
    }

    // ================================================================== auto mode (⚡ on)
    private fun autoFlow(ch: Chapter) {
        // Every extraction is translated from scratch. Saved translations are
        // never reused. If the same chapter is already being translated
        // (● pressed again), restart it cleanly instead of ignoring the press.
        val r = running
        if (r != null && keyOf(r) == keyOf(ch)) {
            runToken++
            queue.clear()
            running = null
            chatWv.evaluateJavascript(Js.stop(), null)
        }
        enqueue(ch)
    }

    private fun enqueue(ch: Chapter) {
        val k = keyOf(ch)
        val r = running
        if ((r != null && keyOf(r) == k) || queue.any { keyOf(it) == k }) { updateProgressUi(); return }
        queue.add(ch)
        if (running == null) startNext() else updateProgressUi()
    }

    private fun startNext() {
        stopProgressTicker()
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
        startProgressTicker(tok)
        waitIdle(tok, ch, 0)
    }

    // WebView JavaScript timers are throttled when the chatbot pane is hidden.
    // Keep the visible progress UI moving on the Android main-thread clock, but
    // never let the estimate claim completion before the reply is verified.
    private fun startProgressTicker(tok: Int) {
        stopProgressTicker()
        val ticker = object : Runnable {
            override fun run() {
                if (tok != runToken || running == null) {
                    if (progressTicker === this) progressTicker = null
                    return
                }
                if (progress < 88) {
                    progress += 1
                    updateProgressUi()
                }
                handler.postDelayed(this, 1400L)
            }
        }
        progressTicker = ticker
        handler.postDelayed(ticker, 1400L)
    }

    private fun stopProgressTicker() {
        progressTicker?.let { handler.removeCallbacks(it) }
        progressTicker = null
    }

    // make sure the chatbot is not still busy with something else before we send
    private fun waitIdle(tok: Int, ch: Chapter, n: Int) {
        chatWv.evaluateJavascript(Js.readLen()) { raw ->
            if (tok != runToken) return@evaluateJavascript
            val parts = decode(raw).split("|")
            val streaming = parts.getOrNull(1) == "1"
            if (streaming) {
                chatWv.evaluateJavascript(Js.stop()) {
                    if (tok == runToken) {
                        handler.postDelayed({ if (tok == runToken) sendJob(tok, ch) }, 120)
                    }
                }
            } else {
                sendJob(tok, ch)
            }
        }
    }

    private fun sendJob(tok: Int, ch: Chapter) {
        // Keep paragraph identity inside the AI request. These markers never reach
        // the novel page and are removed from the final saved/clipboard translation.
        val host = Uri.parse(ch.url).host?.lowercase().orEmpty()
        val isWtrLab = host == "wtr-lab.com" || host.endsWith(".wtr-lab.com")
        val marked = addInternalMarkers(ch.text, splitSingleLines = isWtrLab)
        jobMarkers = Regex("(?m)^\\[\\d{3,}\\] ").findAll(marked).count()
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
                // Give the chatbot UI a short moment to create the new turn.
                handler.postDelayed({
                    if (tok == runToken) pollJob(tok, ch, n0, baseLen, System.currentTimeMillis(), 0, 0)
                }, 300)
            } else {
                failJob(tok, ch, "Chat input not found — check that you are logged in to the chatbot")
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

    private fun pollJob(
        tok: Int, ch: Chapter, n0: Int, baseLen: Int, started: Long,
        lastLen: Int, stable: Int, sawStream: Boolean = false, firstLen: Int = -1
    ) {
        handler.postDelayed({
            if (tok != runToken) return@postDelayed
            chatWv.evaluateJavascript(Js.readLen()) { raw ->
                if (tok != runToken) return@evaluateJavascript
                val parts = decode(raw).split("|")
                val n = parts.getOrNull(0)?.toIntOrNull() ?: 0
                val streaming = parts.getOrNull(1) == "1"
                val len = parts.getOrNull(2)?.toIntOrNull() ?: 0
                val found = parts.getOrNull(3)?.toIntOrNull() ?: 0
                val sendFailed = parts.getOrNull(5) == "failed"
                val uiDone = parts.getOrNull(6) == "1"
                val got = parts.getOrNull(4) == "1" || n > n0
                val effLen = if (got) len else 0
                // Some chat pages (Claude) already report the echoed prompt as "reply text"
                // the moment the message is sent. Judge progress by how much the reply has
                // GROWN since the first reading, never by its absolute length.
                val baseLen0 = if (firstLen >= 0) firstLen else if (got) effLen else -1
                val grown = if (baseLen0 >= 0) (effLen - baseLen0).coerceAtLeast(0) else 0
                val total = jobMarkers
                val allMarkers = got && total > 0 && found >= total
                // Marker progress is linear: each translated paragraph advances the bar.
                // If the model doesn't preserve markers, fall back to output length.
                val pct = when {
                    !got -> 3
                    total > 0 -> if (found > 0) (found * 100 / total).coerceIn(3, 94)
                        else minOf(90, grown * 100 / expectedLen(ch)).coerceAtLeast(3)
                    else -> minOf(95, grown * 100 / expectedLen(ch)).coerceAtLeast(3)
                }
                // A stream that grows by 1–2 characters is still changing, not stable.
                // Require an exactly unchanged reply length before calling it complete.
                val st = if (got && effLen == lastLen) stable + 1 else 0
                val elapsed = System.currentTimeMillis() - started

                // The stop-button detector can miss a model's streaming state or the
                // UI can briefly expose Send during a pause. Never treat that alone as
                // completion: require the reply to stay unchanged across multiple polls.
                val streamEnded = sawStream && !streaming
                val expectedFloor = maxOf(
                    120,
                    minOf((expectedLen(ch) * 0.55).toInt(), (ch.text.length * 0.85).toInt())
                )
                val completedByMarkers = got && effLen > 50 && !streaming && allMarkers && st >= 4
                val completedByUi = got && effLen > 50 && uiDone && !streaming && st >= 8
                val completedAfterStop = got && effLen > 50 && streamEnded && st >= 8
                val completedWithoutStopSignal = got && effLen > 50 && !sawStream && !streaming &&
                    st >= 20 && (allMarkers || effLen >= expectedFloor)
                val completed = completedByMarkers || completedByUi ||
                    completedAfterStop || completedWithoutStopSignal

                // Marker counts can appear all at once (or be read from a prompt echo).
                // Rate-limit increases so the bar cannot jump from 3% to ~98% in one poll.
                progress = if (completed) 100
                    else if (!got) maxOf(progress, 3)
                    else maxOf(progress, minOf(90, pct, progress + 1))
                updateProgressUi()

                when {
                    completed -> finishJob(tok, ch)
                    sendFailed && !got -> failJob(tok, ch, "Message could not be sent — all methods failed (check chatbot login/CAPTCHA)")
                    elapsed > 6 * 60_000 -> failJob(tok, ch, "Timed out (6 minutes)")
                    !got && elapsed > 60_000 -> failJob(tok, ch, "The chatbot did not start responding — was the message sent?")
                    else -> pollJob(tok, ch, n0, baseLen, started, effLen, st, sawStream || streaming, baseLen0)
                }
            }
        }, 500)
    }

    private fun cleanReply(t: String): String =
        t.replace(Regex("^\\s*(ChatGPT|Gemini|Claude|DeepSeek|Grok)\\s+said\\s*[:：]?\\s*", RegexOption.IGNORE_CASE), "").trim()

    private fun finishJob(tok: Int, ch: Chapter) {
        if (tok != runToken) return
        chatWv.evaluateJavascript(Js.readText()) { raw ->
            if (tok != runToken) return@evaluateJavascript
            val rawTranslation = cleanReply(decode(raw))
            if (rawTranslation.length < 30) {
                failJob(tok, ch, "Could not read the answer")
                return@evaluateJavascript
            }
            // Markers are internal alignment data only. Never expose them in the
            // clipboard or saved chapter, but keep the raw marked response for DOM
            // insertion so paragraph alignment survives AI formatting changes.
            val t = stripInternalMarkers(rawTranslation)
            if (t.length < 30) {
                failJob(tok, ch, "Could not read the translation")
                return@evaluateJavascript
            }
            copy(t)
            val savedTranslation = Store.save(this, ch, rawTranslation)
            val savedKey = keyOf(ch)
            if (prefetchGeneratedKeys.contains(savedKey)) {
                prefetchedTranslationIds[savedKey] = savedTranslation.id
            }
            progress = 100
            updateProgressUi()
            stopProgressTicker()
            val shown = lastChapter
            if (shown != null && keyOf(shown) == keyOf(ch)) {
                applyTranslation(shown, rawTranslation, true)
            }
            toast("✅ Translation saved" + (if (ch.number.isNotEmpty()) " (Ch ${ch.number})" else ""))
            running = null
            startNext()
        }
    }

    // on failure: stop the queue, keep the chapter on the clipboard so you can do it by hand
    private fun failJob(tok: Int, ch: Chapter, msg: String) {
        if (tok != runToken) return
        stopProgressTicker()
        queue.clear()
        running = null
        copy(promptWithGlossary(Prefs.prompt(this), ch.text) + ch.text)
        toast("❌ $msg\n📋 Chapter copied. Paste it into the chatbot and tap Copy → 💾.")
        updateProgressUi()
    }

    private fun cancelAll() {
        runToken++
        stopProgressTicker()
        queue.clear()
        running = null
        chatWv.evaluateJavascript(Js.stop(), null)
        updateProgressUi()
        toast("⏹ Background translation stopped")
    }

    // ---- put the translation into the novel page itself
    private fun applyTranslation(ch: Chapter, text: String, retry: Boolean) {
        val sel = ch.contentSel.ifBlank { SiteProfiles.selector(this, ch.url, "content") }
        val paras = splitInternalMarkedTranslation(text)
        shownTrRaw = text
        // A translation callback can arrive long after the user pressed Next.
        // Never write an old chapter's translation into the current page.
        if (lastChapter !== ch) return

        injectBengaliFont()
        novelWv.evaluateJavascript(
            Js.apply(sel, JSONArray(paras).toString(), Prefs.fontFile(this), Prefs.sizePx(this), pageKey(ch.url))
        ) { r ->
            if (lastChapter !== ch) return@evaluateJavascript
            if (r != null && r.contains("ok")) {
                hasTr = true
                shownTranslated = true
                updatePill()
                if (retry) {   // some sites re-render the content a moment later — put it back once
                    handler.postDelayed({
                        if (shownTranslated && lastChapter === ch) {
                            novelWv.evaluateJavascript(Js.stillApplied(sel, pageKey(ch.url))) { s ->
                                if (lastChapter === ch && s != null && s.contains("lost")) {
                                    applyTranslation(ch, text, false)
                                }
                            }
                        }
                    }, 2500)
                }
            } else {
                toast("⚠️ Could not insert the translation; it is saved in the library")
            }
        }
    }

    // WebNovel's CSP / service worker can block the @font-face url (same reason the font
    // "does not apply" there). A FontFace built from raw bytes is not fetched, so neither applies.
    private val fontB64 = mutableMapOf<String, String>()
    private fun injectBengaliFont() {
        val name = Prefs.fontFile(this)
        if (name.isEmpty()) return
        val b64 = fontB64.getOrPut(name) {
            try {
                android.util.Base64.encodeToString(
                    assets.open("fonts/$name.ttf").use { it.readBytes() }, android.util.Base64.NO_WRAP)
            } catch (_: Exception) { "" }
        }
        if (b64.isEmpty()) return
        val js = "(function(n,b){try{if(window.__nsFontName===n)return 'have';var s=atob(b),l=s.length,u=new Uint8Array(l);" +
            "for(var i=0;i<l;i++)u[i]=s.charCodeAt(i);var f=new FontFace('NSBn',u.buffer);" +
            "f.load().then(function(){document.fonts.add(f);window.__nsFontName=n;}).catch(function(){});return 'ok';}" +
            "catch(e){return 'err';}})('" + name + "','" + b64 + "')"
        novelWv.evaluateJavascript("(window.__nsFontName||'')") { cur ->
            if (cur == null || !cur.contains(name)) novelWv.evaluateJavascript(js, null)
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
        togglePill.text = if (shownTranslated) "View original" else "View translation"
    }

    // ---- translation status card + the ring around the extract button
    private fun updateProgressUi() {
        val run = running
        if (run == null && queue.isEmpty()) {
            setProgressVisible(false)
            extractBtn.ringProgress = -1
            return
        }
        val shown = lastChapter
        val title: String
        var pct = 0
        if (run != null && shown != null && keyOf(run) == keyOf(shown)) {
            title = "Translating" + (if (run.number.isNotBlank()) " · Chapter ${run.number}" else "")
            pct = progress
        } else if (shown != null && queue.any { keyOf(it) == keyOf(shown) }) {
            val ahead = (if (run != null) 1 else 0) + queue.indexOfFirst { keyOf(it) == keyOf(shown) }
            title = "Queued · $ahead ahead"
        } else {
            title = "Translating in the background"
            pct = progress
        }
        progressTv.text = title
        progressPct.text = if (pct > 0) "$pct%" else ""
        progressBar.progress = pct
        extractBtn.ringProgress = if (pct > 0) pct else 0
        setProgressVisible(true)
    }

    private fun setProgressVisible(v: Boolean) {
        if (v == progressShown) return
        progressShown = v
        progressBox.animate().cancel()
        if (v) {
            progressBox.alpha = 0f
            progressBox.translationY = -dp(14).toFloat()
            progressBox.visibility = View.VISIBLE
            progressBox.animate().alpha(1f).translationY(0f).setDuration(230).start()
        } else {
            progressBox.animate().alpha(0f).translationY(-dp(10).toFloat()).setDuration(190)
                .withEndAction { if (!progressShown) progressBox.visibility = View.GONE }.start()
        }
    }

    private fun toggleAuto() {
        val on = !Prefs.auto(this)
        Prefs.putBool(this, "noAuto", !on)
        refreshAutoBtn()
        toast(if (on) "⚡ Background translation enabled — tap ▶ to translate and insert into the site" else "⚡ Copy mode — tap ▶ to copy the chapter")
    }

    private fun refreshAutoBtn() {
        extractBtn.autoOn = Prefs.auto(this)
    }

    // ================================================================== save answer by hand (copy mode)
    private fun saveAnswer() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: ""
        if (t.length < 30) return toast("❌ No translation in clipboard. Copy the chatbot answer first.")
        if (t == lastCopied.trim()) return toast("❌ This is source text. Copy the chatbot answer instead.")
        val tr = Store.save(this, lastChapter, t)
        toast("💾 Saved to library: ${tr.novel} — ${tr.label()}")
        if (!Prefs.bool(this, "noSaveNext")) step("next")
    }

    // ================================================================== menu

    // ---------------------------------------------------------------- bottom-sheet building blocks
    private fun sheetHandle(): View = View(this).apply {
        background = Glass.solid(this@MainActivity, 0x47FFFFFF, 2)
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).also {
            it.gravity = Gravity.CENTER_HORIZONTAL
            it.bottomMargin = dp(12)
        }
    }

    private fun sectionLabel(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 11.5f
        typeface = boldMedium
        letterSpacing = 0.12f
        setTextColor(UiTheme.faint)
        setPadding(dp(6), dp(16), dp(6), dp(7))
    }

    private fun sheetGroup(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Glass.solid(this@MainActivity, 0x0FFFFFFF, 20, UiTheme.strokeSoft)
    }

    /** "✅ 🌙 Dark Mode: Auto" -> title "Dark Mode", badge "Auto"; toggles become ON/OFF badges. */
    private fun splitLabel(raw: String): Triple<String, String?, Boolean?> {
        var s = raw.trim()
        var toggle: Boolean? = null
        if (s.startsWith("✅")) { toggle = true; s = s.removePrefix("✅") }
        else if (s.startsWith("⬜")) { toggle = false; s = s.removePrefix("⬜") }
        s = s.replace(Regex("^[^A-Za-z0-9]+"), "").trim()
        if (toggle != null) return Triple(s, if (toggle) "ON" else "OFF", toggle)
        val k = s.lastIndexOf(": ")
        return if (k > 0) Triple(s.substring(0, k), s.substring(k + 2), null) else Triple(s, null, null)
    }

    private inner class SheetRow(val view: LinearLayout, val title: TextView, val state: TextView) {
        fun set(raw: String) {
            val (t, s, on) = splitLabel(raw)
            title.text = t
            if (s == null) { state.visibility = View.GONE; return }
            state.visibility = View.VISIBLE
            state.text = s
            when (on) {
                true -> { state.setTextColor(0xFF06210F.toInt()); state.background = Glass.solid(this@MainActivity, UiTheme.ok, 12) }
                false -> { state.setTextColor(UiTheme.faint); state.background = Glass.solid(this@MainActivity, 0x14FFFFFF, 12) }
                null -> { state.setTextColor(UiTheme.dim); state.background = Glass.solid(this@MainActivity, 0x14FFFFFF, 12) }
            }
        }
    }

    private fun sheetRow(ic: Ic?, rawLabel: String, last: Boolean, onClick: () -> Unit): SheetRow {
        val title = TextView(this).apply {
            textSize = 14.5f
            typeface = medium
            setTextColor(UiTheme.text)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val state = TextView(this).apply {
            textSize = 11.5f
            typeface = boldMedium
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            visibility = View.GONE
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(14), 0)
            minimumHeight = dp(52)
            background = Glass.ripple(this@MainActivity, null, 0)
            isClickable = true
            setOnClickListener { onClick() }
            if (ic != null) {
                val box = FrameLayout(this@MainActivity).apply { background = Glass.oval(0x14FFFFFF) }
                box.addView(ImageView(this@MainActivity).apply { setImageDrawable(IconDrawable(ic, UiTheme.iconIdle)) },
                    FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                addView(box, LinearLayout.LayoutParams(dp(34), dp(34)).also { it.marginEnd = dp(12) })
            }
            addView(title, LinearLayout.LayoutParams(0, WC, 1f))
            addView(state, LinearLayout.LayoutParams(WC, dp(24)).also { it.marginStart = dp(8) })
        }
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(inner, LinearLayout.LayoutParams(MP, WC))
            if (!last) addView(View(this@MainActivity).apply { setBackgroundColor(0x10FFFFFF) },
                LinearLayout.LayoutParams(MP, dp(1)).also { it.marginStart = dp(58) })
        }
        return SheetRow(view, title, state).also { it.set(rawLabel) }
    }

    /** Square quick-action tile (Home, Share, New tab, Prompt, Auto). */
    private fun tile(ic: Ic, label: String, on: Boolean, onClick: () -> Unit): Pair<LinearLayout, (Boolean) -> Unit> {
        val iconView = ImageView(this)
        val labelView = TextView(this).apply {
            text = label
            textSize = 11f
            typeface = medium
            gravity = Gravity.CENTER
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { onClick() }
            addView(iconView, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(labelView, LinearLayout.LayoutParams(WC, WC).also { it.topMargin = dp(7) })
        }
        fun style(active: Boolean) {
            iconView.setImageDrawable(IconDrawable(ic, if (active) Color.WHITE else UiTheme.iconIdle))
            labelView.setTextColor(if (active) Color.WHITE else UiTheme.iconIdle)
            box.background = Glass.ripple(
                this,
                if (active) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0x597B8CFF, 0x47B27CFF)).apply {
                    cornerRadius = dp(20).toFloat(); setStroke(dp(1), UiTheme.accent)
                } else Glass.solid(this, 0x12FFFFFF, 20, UiTheme.strokeSoft),
                20
            )
        }
        style(on)
        return Pair(box, ::style)
    }

    // ---------------------------------------------------------------- main menu (bottom sheet)
    private fun menu() {
        val labels = arrayListOf(
            "📚 Library", "🕘 Browser History", "⬇️ Export All Translations (.txt)",
            "🔖 Bookmark This Page", "🔖 Bookmarks", "📝 Edit Prompt",
            menuAutoLabel(), "🔁 Re-translate Current Chapter", "⏹ Stop Background Translation",
            menuDarkLabel(), menuAutoPasteLabel(), menuAutoSendLabel(), menuPromptLabel(),
            menuSaveNextLabel(), menuNextExtractLabel(), menuAdBlockLabel(),
            "🔄 Update Ad Block Lists", "🔄 Reload Novel Page", "🔄 Reload Chatbot",
            menuGlossaryLabel(), menuFontLabel(), "🌐 Site Settings",
            "🔍 Inspect Page (Copy Report)", menuSizeLabel(), "🔐 Chatbot Login",
            "🔍 Inspect Chatbot (Copy Report)", menuPrefetchLabel()
        )
        // Rows that are not offered here. Their underlying features and code remain unchanged.
        val hidden = setOf(2, 6, 8, 10, 11, 13, 17, 18, 22, 25)
        val icons = mapOf(
            0 to Ic.BOOK, 1 to Ic.CLOCK, 3 to Ic.BOOKMARK, 4 to Ic.BOOKMARK, 5 to Ic.SPARK,
            7 to Ic.RELOAD, 9 to Ic.MOON, 12 to Ic.SPARK, 14 to Ic.BOLT, 15 to Ic.SHIELD,
            16 to Ic.RELOAD, 19 to Ic.BOOK, 20 to Ic.TEXT, 21 to Ic.SLIDERS, 23 to Ic.TEXT, 24 to Ic.LOCK
        )
        val sections = listOf(
            "LIBRARY" to listOf(0, 1, 3, 4),
            "TRANSLATION" to listOf(5, 7, 12, 14, 19, 26),
            "READER" to listOf(20, 23, 9),
            "BROWSER" to listOf(15, 16, 21, 24)
        )
        lateinit var dlg: Dialog
        val rows = HashMap<Int, SheetRow>()
        fun refresh(i: Int, label: String) { labels[i] = label; rows[i]?.set(label) }

        fun onItem(i: Int) {
            when (i) {
                0 -> { dlg.dismiss(); libraryNovels() }
                1 -> { dlg.dismiss(); browserHistoryDialog() }
                3 -> { dlg.dismiss(); saveBookmark() }
                4 -> { dlg.dismiss(); bookmarkList() }
                5 -> { dlg.dismiss(); editPrompt() }
                7 -> {
                    val ch = lastChapter
                    if (ch == null) toast("❌ Open a chapter first")
                    else { enqueue(ch); toast("⏳ Chapter added to the translation queue") }
                    dlg.dismiss()
                }
                9 -> {
                    val d = darkMode()
                    Prefs.put(this, "dark", ((d + 1) % 3).toString())
                    applyDark()
                    refresh(i, menuDarkLabel())
                }
                12 -> {
                    Prefs.putBool(this, "withPrompt", !Prefs.bool(this, "withPrompt"))
                    refresh(i, menuPromptLabel())
                }
                14 -> {
                    Prefs.putBool(this, "autoExtractNext", !Prefs.bool(this, "autoExtractNext", true))
                    refresh(i, menuNextExtractLabel())
                }
                15 -> {
                    val v = !Prefs.adblock(this)
                    Prefs.putBool(this, "noAdblock", !v)
                    refresh(i, menuAdBlockLabel())
                }
                16 -> {
                    toast("⏳ Updating the block list…")
                    AdBlock.update(this) { n ->
                        toast(if (n > 0) "✅ Added $n hosts" else "❌ Update failed")
                    }
                }
                19 -> { dlg.dismiss(); glossaryDialog() }
                20 -> {
                    Prefs.put(this, "trFont", ((Prefs.fontIdx(this) + 1) % Prefs.FONT_FILES.size).toString())
                    refresh(i, menuFontLabel())
                    val ch = lastChapter
                    if (ch != null && hasTr) shownTrRaw?.let { applyTranslation(ch, it, false) }
                }
                21 -> { dlg.dismiss(); siteRulesDialog() }
                23 -> {
                    Prefs.put(this, "trSize", ((Prefs.sizeIdx(this) + 1) % Prefs.SIZES.size).toString())
                    refresh(i, menuSizeLabel())
                    val ch = lastChapter
                    if (ch != null && hasTr) shownTrRaw?.let { applyTranslation(ch, it, false) }
                }
                24 -> { dlg.dismiss(); loginChatbot() }
                26 -> {
                    val enabled = !Prefs.bool(this, "prefetchNextChapter")
                    Prefs.putBool(this, "prefetchNextChapter", enabled)
                    if (enabled) {
                        lastChapter?.let { schedulePrefetch(it) }
                    } else {
                        prefetchToken++
                        prefetchLoading = false
                        prefetchCatalogPending = false
                        prefetchTarget = ""
                        prefetchKeys.clear()
                        try { prefetchWv.stopLoading() } catch (_: Exception) {}
                    }
                    refresh(i, menuPrefetchLabel())
                }
            }
        }

        // ---- quick actions
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun addTile(t: Pair<LinearLayout, (Boolean) -> Unit>) {
            tiles.addView(t.first, LinearLayout.LayoutParams(0, dp(76), 1f).also { it.setMargins(dp(4), 0, dp(4), 0) })
        }
        addTile(tile(Ic.HOME, "Home", false) {
            dlg.dismiss()
            val home = "https://www.google.com/"
            urlBar.setText(home)
            go(home)
        })
        addTile(tile(Ic.SHARE, "Share", false) {
            dlg.dismiss()
            val currentUrl = novelWv.url ?: browserTabs.getOrNull(activeTabIndex)?.url.orEmpty()
            if (currentUrl.isNotBlank()) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, currentUrl)
                }
                startActivity(Intent.createChooser(send, "Share page"))
            } else toast("No page to share")
        })
        addTile(tile(Ic.PLUS, "New tab", false) { dlg.dismiss(); newBrowserTab("https://www.google.com/") })
        addTile(tile(Ic.SPARK, "Prompt", false) { dlg.dismiss(); deliver(Prefs.prompt(this), "Prompt") })
        lateinit var styleAuto: (Boolean) -> Unit
        val autoTile = tile(Ic.BOLT, "Auto", Prefs.auto(this)) {
            toggleAuto()
            styleAuto(Prefs.auto(this))
        }
        styleAuto = autoTile.second
        addTile(autoTile)

        // ---- grouped rows
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(20))
            addView(sheetHandle())
            addView(tiles, LinearLayout.LayoutParams(MP, WC))
        }
        for ((name, idx) in sections) {
            val shown = idx.filter { it !in hidden }
            if (shown.isEmpty()) continue
            col.addView(sectionLabel(name))
            val group = sheetGroup()
            shown.forEachIndexed { n, i ->
                val row = sheetRow(icons[i], labels[i], n == shown.lastIndex) { onItem(i) }
                rows[i] = row
                group.addView(row.view)
            }
            col.addView(group, LinearLayout.LayoutParams(MP, WC))
        }
        val scroll = MaxHeightScroll(this, (resources.displayMetrics.heightPixels * 0.86f).toInt()).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            background = Sheets.sheetBackground(this@MainActivity)
            addView(col, ViewGroup.LayoutParams(MP, WC))
        }
        dlg = Sheets.create(this, scroll)
        dlg.show()
    }

    // ---------------------------------------------------------------- chatbot diagnosis
    // Copies what each detector (composer, send button, reply scan) sees on the chatbot page,
    // so a site that does not work can be fixed from the report.
    private fun diagnoseChat() {
        chatWv.evaluateJavascript(Js.diagChat()) { r ->
            val rep = try { JSONArray("[" + (r ?: "null") + "]").optString(0, "") } catch (e: Exception) { "" }
            val report = "chat url=" + (chatWv.url ?: "") + " | WebView " + webViewVer() + "\n" +
                rep.ifBlank { "(Empty — did the chatbot page finish loading?)" }
            copy(report)
            toast("🔍 Chatbot report copied — paste it here")
        }
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
            toast("🔍 Report copied — paste it here")
        }
    }

    // ---------------------------------------------------------------- site rules
    private fun siteRulesDialog() {
        val rules = SiteRules.all(this)
        val labels = ArrayList<String>()
        labels.add("➕ Add new site")
        rules.forEach {
            labels.add(it.host + "\n" + listOfNotNull(
                if (it.compat) "Read full page" else null,
                if (it.noAds) "Ad blocking off" else null,
                if (it.content.isNotBlank()) "content configured" else null
            ).joinToString(" • "))
        }
        AlertDialog.Builder(this)
            .setTitle("🌐 Site Settings")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i == 0) siteRuleEditor(null) else siteRuleEditor(rules[i - 1])
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun siteRuleEditor(old: SiteRules.Rule?) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val cur = SiteRules.hostOf(novelWv.url ?: "")
        val host = EditText(this).apply {
            hint = "Domain, e.g. example.com"
            setSingleLine(true)
            setText(old?.host ?: cur)
        }
        val compat = CheckBox(this).apply { text = "Read the full page and extract text (for JS sites)"; isChecked = old?.compat ?: true }
        val noAds = CheckBox(this).apply { text = "Disable ad blocking on this site"; isChecked = old?.noAds ?: true }
        fun field(h: String, v: String?) = EditText(this).apply { hint = h; setSingleLine(true); setText(v ?: "") }
        val content = field("content selector (optional), e.g. #chapter-content", old?.content)
        val next = field("next selector (optional)", old?.next)
        val prev = field("prev selector (optional)", old?.prev)
        listOf<View>(host, compat, noAds, content, next, prev).forEach { col.addView(it) }
        val sv = ScrollView(this).apply { addView(col) }

        val b = AlertDialog.Builder(this)
            .setTitle(if (old == null) "➕ Add Site" else "✏️ ${old.host}")
            .setView(sv)
            .setPositiveButton("Save") { _, _ ->
                val h = SiteRules.hostOf(host.text.toString())
                if (h.isEmpty() || !h.contains('.')) { toast("❌ Enter a valid domain"); return@setPositiveButton }
                if (old != null && old.host != h) SiteRules.remove(this, old.host)
                SiteRules.upsert(this, SiteRules.Rule(
                    h, compat.isChecked, noAds.isChecked,
                    content.text.toString().trim(), next.text.toString().trim(), prev.text.toString().trim()
                ))
                toast("✅ Saved — reload the page and tap ●")
            }
            .setNegativeButton("Cancel", null)
        if (old != null) b.setNeutralButton("🗑 Delete") { _, _ ->
            SiteRules.remove(this, old.host)
            toast("🗑 Deleted")
        }
        b.show()
    }

    private fun menuGlossaryLabel(): String =
        (if (Prefs.glossaryOn(this)) "✅" else "⬜") + " 📖 Glossary (" + Glossary.count + ") — Edit / Add"

    private fun menuSizeLabel(): String =
        "🔠 Translation Text Size: " + Prefs.sizePx(this) + "px"

    private fun menuFontLabel(): String =
        "🔤 Translation Font: " + Prefs.FONT_NAMES[Prefs.fontIdx(this)]

    private fun glossaryDialog() {
        val opts = arrayOf(
            "➕ Add a word (English = translation)",
            "📥 Paste multiple words",
            (if (Prefs.glossaryOn(this)) "✅" else "⬜") + " Include glossary in prompt",
            "📋 Copy full glossary",
            "🗑 Delete all"
        )
        AlertDialog.Builder(this)
            .setTitle("📖 Glossary — ${Glossary.count} entries")
            .setItems(opts) { _, i ->
                when (i) {
                    0 -> glossaryInput(false)
                    1 -> glossaryInput(true)
                    2 -> {
                        Prefs.putBool(this, "noGlossary", Prefs.glossaryOn(this))
                        toast(if (Prefs.glossaryOn(this)) "✅ Glossary enabled" else "⬜ Glossary disabled")
                    }
                    3 -> { copy(Glossary.read(this)); toast("📋 Glossary copied") }
                    4 -> AlertDialog.Builder(this)
                        .setMessage("The entire glossary will be deleted. Continue?")
                        .setPositiveButton("Delete") { _, _ -> Glossary.write(this, ""); toast("🗑 Deleted") }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun glossaryInput(bulk: Boolean) {
        val et = EditText(this).apply {
            hint = if (bulk) "dantian | dan tian | 丹田 => dantian\n(one entry per line)" else "young master = young master"
            if (bulk) { minLines = 8; gravity = Gravity.TOP } else setSingleLine(false)
        }
        val b = AlertDialog.Builder(this)
            .setTitle(if (bulk) "📥 Paste" else "➕ New word")
            .setView(et)
            .setPositiveButton("Add") { _, _ ->
                Glossary.append(this, et.text.toString())
                toast("✅ Added")
            }
            .setNegativeButton("Cancel", null)
        if (bulk) b.setNeutralButton("Replace all") { _, _ ->
            Glossary.write(this, et.text.toString())
            toast("✅ Glossary updated")
        }
        b.show()
    }

    private fun menuAutoLabel(): String =
        (if (Prefs.auto(this)) "✅" else "⬜") + " ⚡ Background translation (insert into site)"

    private fun menuDarkLabel(): String =
        "🌙 Dark Mode: " + arrayOf("Off", "Auto", "Force")[darkMode()]

    private fun menuAutoPasteLabel(): String =
        (if (Prefs.bool(this, "autoPaste")) "✅" else "⬜") + " Copy Mode: Auto Paste"

    private fun menuAutoSendLabel(): String =
        (if (Prefs.bool(this, "autoSend")) "✅" else "⬜") + " Copy Mode: Auto Send"

    private fun menuPromptLabel(): String =
        (if (Prefs.bool(this, "withPrompt")) "✅" else "⬜") + " Copy Mode: Include Prompt"

    private fun menuNextExtractLabel(): String =
        (if (Prefs.bool(this, "autoExtractNext", true)) "✅" else "⬜") + " ▶ Auto-Extract After Next / Previous"

    private fun menuSaveNextLabel(): String =
        (if (!Prefs.bool(this, "noSaveNext")) "✅" else "⬜") + " Copy Mode: Next Chapter after 💾"

    private fun menuAdBlockLabel(): String =
        (if (Prefs.adblock(this)) "✅" else "⬜") + " Ad Block"

    /** Glass bottom sheet with one tappable row per label (long-press optional). */
    private fun listDialog(title: String, labels: List<String>, onClick: (Int) -> Unit, onLong: ((Int) -> Unit)?) {
        lateinit var dlg: Dialog
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(20))
            addView(sheetHandle())
            addView(TextView(this@MainActivity).apply {
                text = title.replace(Regex("^[^A-Za-z0-9]+"), "").trim().ifBlank { title }
                textSize = 20f
                typeface = boldMedium
                setTextColor(UiTheme.text)
                setPadding(dp(6), dp(2), dp(6), dp(10))
            })
        }
        val group = sheetGroup()
        labels.forEachIndexed { i, label ->
            val nl = label.indexOf('\n')
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                minimumHeight = dp(52)
                gravity = Gravity.CENTER_VERTICAL
                background = Glass.ripple(this@MainActivity, null, 0)
                isClickable = true
                setOnClickListener { dlg.dismiss(); onClick(i) }
                if (onLong != null) setOnLongClickListener { dlg.dismiss(); onLong(i); true }
                addView(TextView(this@MainActivity).apply {
                    text = if (nl >= 0) label.substring(0, nl) else label
                    textSize = 14.5f
                    typeface = medium
                    setTextColor(UiTheme.text)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                if (nl >= 0) addView(TextView(this@MainActivity).apply {
                    text = label.substring(nl + 1)
                    textSize = 11.5f
                    setTextColor(UiTheme.dim)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            group.addView(row, LinearLayout.LayoutParams(MP, WC))
            if (i != labels.lastIndex) group.addView(View(this).apply { setBackgroundColor(0x10FFFFFF) },
                LinearLayout.LayoutParams(MP, dp(1)).also { it.marginStart = dp(16) })
        }
        col.addView(group, LinearLayout.LayoutParams(MP, WC))
        val scroll = MaxHeightScroll(this, (resources.displayMetrics.heightPixels * 0.82f).toInt()).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            background = Sheets.sheetBackground(this@MainActivity)
            addView(col, ViewGroup.LayoutParams(MP, WC))
        }
        dlg = Sheets.create(this, scroll)
        dlg.show()
    }

    // ---------- offline library ----------
    private fun libraryNovels() {
        val names = Store.novelNames(this)
        if (names.isEmpty()) return toast("Library is empty. Saved translations will appear here.")
        val labels = names.map { it + "   (" + Store.chapters(this, it).size + " chapters)" }
        listDialog("📚 Library", labels, { i -> novelActions(names[i]) }, null)
    }

    private fun novelActions(name: String) {
        AlertDialog.Builder(this).setTitle(name)
            .setItems(arrayOf("📖 Chapter List", "⬇️ Export this novel as TXT", "✏️ Rename", "🗑 Delete Novel")) { _, k ->
                when (k) {
                    0 -> chapterList(name)
                    1 -> exportAll(name)
                    2 -> renameNovel(name)
                    3 -> AlertDialog.Builder(this).setMessage("Delete all translations for \"$name\"?")
                        .setPositiveButton("Delete") { _, _ -> Store.deleteNovel(this, name) }
                        .setNegativeButton("No", null).show()
                    else -> {}
                }
            }.show()
    }

    private fun chapterList(name: String) {
        val l = Store.chapters(this, name)
        if (l.isEmpty()) return
        listDialog(name + " (long-press to delete)", l.map { it.label() },
            { i -> startActivity(Intent(this, ReaderActivity::class.java).putExtra("novel", name).putExtra("id", l[i].id)) },
            { i ->
                AlertDialog.Builder(this).setMessage("Delete \"${l[i].label()}\"?")
                    .setPositiveButton("Delete") { _, _ -> Store.delete(this, l[i].id) }
                    .setNegativeButton("No", null).show()
            })
    }

    private fun renameNovel(old: String) {
        val et = EditText(this).apply { setText(old) }
        AlertDialog.Builder(this).setTitle("Novel name")
            .setView(et)
            .setPositiveButton("Save") { _, _ -> Store.rename(this, old, et.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun exportAll(novel: String?) {
        if (Store.list(this).isEmpty()) return toast("There are no translations to export")
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
                toast("✅ Export completed")
            } catch (e: Exception) {
                toast("❌ " + (e.message ?: "Failed"))
            }
        }
    }

    // ---------- bookmarks ----------
    private fun saveBookmark() {
        val u = novelWv.url ?: return
        val name = (novelWv.title ?: "").ifBlank { Uri.parse(u).host ?: u }
        Store.addBookmark(this, Bookmark(name, u, u, ""))
        toast("🔖 Saved: $name")
    }

    private fun bookmarkList() {
        val l = Store.bookmarks(this)
        if (l.isEmpty()) return toast("Bookmarks are empty")
        val labels = l.map { it.name + (if (it.lastTitle.isNotEmpty()) "\n↳ " + it.lastTitle else "") }
        listDialog("🔖 Bookmarks (long-press to delete)", labels,
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
            .setTitle("Prompt")
            .setView(et)
            .setPositiveButton("Save") { _, _ -> Prefs.put(this, "prompt", et.text.toString()) }
            .setNegativeButton("Cancel", null)
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
        runToken++
        navToken++
        handler.removeCallbacksAndMessages(null)
        pulse?.cancel()
        modeTransitionHide?.let { handler.removeCallbacks(it) }
        modeTransitionPreDraw?.let { listener ->
            try {
                if (::content.isInitialized && content.viewTreeObserver.isAlive) {
                    content.viewTreeObserver.removeOnPreDrawListener(listener)
                }
            } catch (_: Exception) {}
        }
        modeTransitionPreDraw = null
        modeTransitionFrames = 0
        if (::modeTransitionCover.isInitialized) modeTransitionCover.setImageDrawable(null)
        modeTransitionBitmap?.let { if (!it.isRecycled) it.recycle() }
        modeTransitionBitmap = null
        modeTransitionHide = null
        novelWv.destroy()
        try { prefetchWv.destroy() } catch (_: Exception) {}
        botWebViews.values.toSet().forEach { wv -> try { wv.destroy() } catch (_: Exception) {} }
        botWebViews.clear()
        super.onDestroy()
    }
}
