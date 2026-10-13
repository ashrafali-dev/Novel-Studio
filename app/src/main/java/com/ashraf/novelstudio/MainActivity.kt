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
    private var prefetchWtrSpaPending = false
    private var prefetchWtrSourceNumber = ""
    private var prefetchCatalogTitle = ""
    private var prefetchSourceUrl = ""
    private val prefetchRequestedUrls = mutableSetOf<String>()
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
    private lateinit var progressBar: BookwormProgress
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
    // WTR-LAB keeps the same URL while its SPA swaps the visible chapter.
    private var pendingWtrChapterNumber = ""

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
    // WebNovel-specific behavior lives behind this site adapter.
    private val webNovelSystem by lazy { WebNovelSystem(this) }
    private val wtrLabSystem by lazy { WtrLabSystem(this) }
    private val qidianSystem by lazy { QidianSystem(this) }
    private val novel543System by lazy { Novel543System(this) }

    // background translation queue
    private val queue = mutableListOf<Chapter>()
    private var running: Chapter? = null
    private var runToken = 0
    private var progress = 0

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
        // Reader sites commonly mark chapter/title links as target="_blank".
        // With multiple windows enabled, NovelChrome rejects onCreateWindow(), so those
        // taps appear to do nothing. Route user-opened links into this same reader WebView.
        novelWv.settings.setSupportMultipleWindows(false)
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
                    if (prefetchWtrSpaPending) {
                        // WTR-LAB keeps the same URL. Load the reader once, click
                        // its own Next button, then wait for the chapter number/DOM
                        // to change before extracting the prefetched chapter.
                        prefetchWtrSpaPending = false
                        val sourceNumber = prefetchWtrSourceNumber
                        fun clickAttempt(attempt: Int) {
                            if (!prefetchLoading || token != prefetchToken) return
                            prefetchWv.evaluateJavascript(Js.clickNext("next")) { raw ->
                                if (!prefetchLoading || token != prefetchToken) return@evaluateJavascript
                                val result = decode(raw).lowercase()
                                if (result.contains("clicked")) {
                                    fun readWtr(chAttempt: Int) {
                                        if (!prefetchLoading || token != prefetchToken) return
                                        wtrLabSystem.extract(prefetchWv, 0) { next ->
                                            if (!prefetchLoading || token != prefetchToken) return@extract
                                            val changed = next != null &&
                                                next.number.isNotBlank() &&
                                                (sourceNumber.isBlank() || next.number != sourceNumber) &&
                                                next.text.length >= 80
                                            if (changed) {
                                                prefetchLoading = false
                                                prefetchTarget = ""
                                                val current = lastChapter
                                                if (current != null && keyOf(next!!) == keyOf(current)) return@extract
                                                if (Store.find(this@MainActivity, next!!) != null) return@extract
                                                val nextKey = keyOf(next)
                                                if (prefetchKeys.add(nextKey)) {
                                                    prefetchGeneratedKeys.add(nextKey)
                                                    enqueue(next)
                                                }
                                            } else if (chAttempt < 20) {
                                                // Nudge WTR's lazy renderer: bring the active chapter into view.
                                                prefetchWv.evaluateJavascript(
                                                    "(function(){try{var a=document.querySelector('.chapter-tracker.active');if(a)a.scrollIntoView({block:'start'});window.dispatchEvent(new Event('scroll'));}catch(e){}return 'n';})()",
                                                    null
                                                )
                                                handler.postDelayed({ readWtr(chAttempt + 1) }, 500L)
                                            } else {
                                                prefetchLoading = false
                                                prefetchTarget = ""
                                            }
                                        }
                                    }
                                    handler.postDelayed({ readWtr(0) }, 400L)
                                } else if (attempt < 10) {
                                    handler.postDelayed({ clickAttempt(attempt + 1) }, 500L)
                                } else {
                                    prefetchLoading = false
                                    prefetchTarget = ""
                                }
                            }
                        }
                        handler.postDelayed({ clickAttempt(0) }, 700L)
                        return
                    }
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
                                    prefetchRequestedUrls.remove(cleanUrl(prefetchTarget))
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
                            // WTR: the chapter we extract must be the one whose URL we loaded.
                            val wantNo = if (wtrLabSystem.matches(prefetchTarget))
                                Regex("/chapter-(\\d+)", RegexOption.IGNORE_CASE)
                                    .find(prefetchTarget)?.groupValues?.get(1) else null
                            val wtrWrong = next != null && wantNo != null &&
                                next.number.isNotBlank() && next.number != wantNo
                            if (next == null || next.text.length < 80 || wtrWrong) {
                                if (attempt < 14) {
                                    // SPA readers fetch the chapter text after load; scroll a little to wake lazy loaders.
                                    prefetchWv.evaluateJavascript(
                                        "try{window.scrollTo(0,Math.min(document.body.scrollHeight,900))}catch(e){}", null)
                                    handler.postDelayed({ readAttempt(attempt + 1) }, 900L)
                                } else {
                                    prefetchLoading = false
                                    prefetchRequestedUrls.remove(cleanUrl(prefetchTarget))
                                    prefetchTarget = ""
                                }
                                return@prefetchExtractNow
                            }
                            prefetchLoading = false
                            val requestedUrl = cleanUrl(prefetchTarget)
                            prefetchTarget = ""
                            val extractedUrl = cleanUrl(next.url)
                            // The extracted chapter must match the URL we deliberately prefetched.
                            if (requestedUrl.isNotBlank() && extractedUrl.isNotBlank() &&
                                requestedUrl != extractedUrl && pageKey(next.url) != pageKey(requestedUrl)) {
                                prefetchRequestedUrls.remove(requestedUrl)
                                return@prefetchExtractNow
                            }
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
        progressBar = BookwormProgress(this)
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
            clipChildren = false // lets the mascot's sparkles overshoot the bar a little
            setPadding(dp(14), dp(10), dp(10), dp(6))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xF2282C3E.toInt(), 0xF2181B28.toInt())
            ).apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0x668C9CFF)
            }
            elevation = dp(10).toFloat()
            addView(progRow, LinearLayout.LayoutParams(MP, WC))
            addView(progressBar, LinearLayout.LayoutParams(MP, dp(44)).also { it.topMargin = dp(2) })
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
                    it.layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).also { lp -> lp.marginStart = dp(4) }                })
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
                view.evaluateJavascript(webNovelSystem.catalogPickScript(dir, title, sourceUrl)) { raw ->
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

            if (view === novelWv && wtrLabSystem.matches(url)) startWtrWatch()

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
        webNovelSystem.extract(novelWv, 0, cb)
    }

    // Generic full-page fallback for non-WebNovel sites. WebNovel never uses
    // this path unless its dedicated rendered-DOM extractor explicitly falls back.
    private fun extractFullNow(cb: (Chapter?) -> Unit) {
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
                } catch (_: Exception) { null }
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
        if (webNovelSystem.isWebNovel(url)) {
            webNovelSystem.extract(novelWv, attempt, cb)
            return
        }
        if (wtrLabSystem.matches(url)) {
            wtrLabSystem.extract(novelWv, attempt, cb)
            return
        }
        if (qidianSystem.matches(url)) {
            qidianSystem.extract(novelWv, attempt, cb)
            return
        }
        if (novel543System.matches(url)) {
            novel543System.extract(novelWv, attempt, cb)
            return
        }

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
                extractFullNow(cb)
                return@evaluateJavascript
            }

            try {
                val o = JSONObject(payload)
                if (!o.optBoolean("ok", false)) {
                    lastFastWhy = o.optString("why", "no-units") + " roots=" + o.optInt("roots", -1)
                    // Keep the proven Jsoup fallback for JS-heavy/BR-based sites.
                    // Novel543 is explicitly included because its chapter body is
                    // stable at .chapter-content but
