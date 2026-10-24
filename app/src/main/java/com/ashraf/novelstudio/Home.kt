package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class HomeRecent(val title: String, val url: String, val site: String, val timeMs: Long)
data class HomeSite(val name: String, val url: String)

/** "Home" / "Library" / "Updates" / "Sources" — the same view renders each. */
enum class HomeTab { HOME, LIBRARY, UPDATES, SOURCES }

// ============================================================================ favicons

/**
 * Website icons for the home screen. Memory cache + files/favicons on disk, fetched off the main thread.
 * Tries Google's icon service first (sharp 128px icons for almost every site), then the site's own /favicon.ico.
 */
object Favicons {
    private val mem = LruCache<String, Bitmap>(80)
    private val pool = Executors.newFixedThreadPool(3)
    private val ui = Handler(Looper.getMainLooper())
    private val failed = HashSet<String>()

    fun load(ctx: Context, host: String, cb: (Bitmap?) -> Unit) {
        val key = host.lowercase()
        val hit = mem.get(key)
        if (hit != null) { cb(hit); return }
        if (key in failed) { cb(null); return }
        val app = ctx.applicationContext
        pool.execute {
            val bmp = fromDisk(app, key) ?: download(app, key)
            ui.post {
                if (bmp != null) mem.put(key, bmp) else failed.add(key)
                cb(bmp)
            }
        }
    }

    private fun file(app: Context, key: String): File {
        val dir = File(app.filesDir, "favicons")
        dir.mkdirs()
        return File(dir, key.replace(Regex("[^a-z0-9.-]"), "_") + ".png")
    }

    private fun fromDisk(app: Context, key: String): Bitmap? = try {
        val f = file(app, key)
        if (f.exists() && f.length() > 0) BitmapFactory.decodeFile(f.path) else null
    } catch (_: Exception) {
        null
    }

    private fun download(app: Context, key: String): Bitmap? {
        val urls = listOf(
            "https://www.google.com/s2/favicons?sz=128&domain=$key",
            "https://$key/favicon.ico"
        )
        for (u in urls) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 6000
                c.readTimeout = 6000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (c.responseCode != 200) { c.disconnect(); continue }
                val bytes = c.inputStream.use { it.readBytes() }
                c.disconnect()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                if (bmp.width < 8) continue
                try {
                    file(app, key).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } catch (_: Exception) {}
                return bmp
            } catch (_: Exception) {}
        }
        return null
    }
}

// ============================================================================ shared pieces

/** Deterministic, muted cover colour per title (a calm palette, not a rainbow). */
internal object Covers {
    private val palette = intArrayOf(
        0xFF6D74C9.toInt(), 0xFF3E8E7E.toInt(), 0xFFB0607D.toInt(), 0xFF9A7742.toInt(),
        0xFF4A7FAE.toInt(), 0xFF6E8C4C.toInt(), 0xFFB0664C.toInt(), 0xFF6A5FA8.toInt()
    )
    fun colorFor(s: String): Int = palette[Math.abs(s.hashCode() % palette.size)]

    /** Book cover: tinted gradient, initial, inner hairline. */
    fun view(ctx: Context, title: String, wDp: Int, hDp: Int, radiusDp: Int, textSp: Float): View {
        val base = colorFor(title)
        val box = FrameLayout(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(base, ModeUi.lerp(base, Color.BLACK, 0.26f))
            ).apply { cornerRadius = ctx.dpf(radiusDp) }
        }
        box.addView(TextView(ctx).apply {
            text = title.trim().take(1).uppercase().ifEmpty { "N" }
            textSize = textSp
            typeface = Type.bold()
            setTextColor(0xE6FFFFFF.toInt())
            gravity = Gravity.CENTER
            isImportantForAccessibility = false
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box.addView(View(ctx).apply { background = Glass.solid(ctx, 0x00000000, radiusDp, 0x24FFFFFF) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box.layoutParams = LinearLayout.LayoutParams(ctx.dpi(wDp), ctx.dpi(hDp))
        return box
    }

    fun ago(ms: Long): String {
        if (ms <= 0L) return ""
        val m = (System.currentTimeMillis() - ms) / 60000L
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 24 * 60 -> "${m / 60} h ago"
            m < 48 * 60 -> "yesterday"
            m < 24 * 60 * 30 -> "${m / (24 * 60)} days ago"
            else -> "${m / (24 * 60 * 30)} months ago"
        }
    }
}

internal fun View.pressEffect() {
    setOnTouchListener { view, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90L).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
        }
        false
    }
}

internal fun View.clickable(onClick: () -> Unit): View {
    isClickable = true
    setOnClickListener { onClick() }
    pressEffect()
    return this
}

// ============================================================================ home screen

/**
 * Full-screen home ("Calm").
 *
 * One quiet title bar ([back] inside a section, title, menu). Content-first: saved books and updates
 * before site tiles. One dock at the bottom — [Library] [Search] [Browse] — where Search expands the
 * field in place and the "search tile" slot hosts MainActivity's mode switch. Library / Updates /
 * Browse sources render in this same view.
 */
class HomeView(private val ctx: Context) : FrameLayout(ctx) {
    var onOpen: (String) -> Unit = {}
    var onSearch: (String) -> Unit = {}
    var onMenu: () -> Unit = {}
    var onHub: (Hub.Part) -> Unit = {}
    var onOpenLibrary: () -> Unit = {}
    var onOpenUpdates: () -> Unit = {}
    var onOpenSources: () -> Unit = {}
    /** MainActivity inserts its mode switch + view tiles into the dock's search slot (idle state). */
    var onDockHost: ((View?) -> Unit)? = null

    private val mp = ViewGroup.LayoutParams.MATCH_PARENT
    private val wc = ViewGroup.LayoutParams.WRAP_CONTENT
    private val scroll = ScrollView(ctx)
    private val content = LinearLayout(ctx)
    private val topBar = LinearLayout(ctx)
    private val backBtn = ImageView(ctx)
    private val titleView = TextView(ctx)
    private val menuBtn = ImageView(ctx)
    private val tilesHost = LinearLayout(ctx)
    private val input = EditText(ctx)
    private val searchIcon = ImageView(ctx)
    private val searchBar = LinearLayout(ctx)
    private val searchField = LinearLayout(ctx)
    private val dockTiles = LinearLayout(ctx)
    private var notice: Snackbar? = null

    private var iconIsUrl = false
    private var token = 0
    private var riseIndex = 0
    private var searchActive = false
    private var tab = HomeTab.HOME
    private var searchQ = ""

    private var recents: List<HomeRecent> = emptyList()
    private var novelSites: List<HomeSite> = emptyList()
    private var comicSites: List<HomeSite> = emptyList()
    private var shelves: List<LibraryModel.Shelf> = emptyList()
    private var updates: List<LibraryModel.Update> = emptyList()
    private var sources: List<LibraryModel.Ext> = emptyList()

    init {
        isClickable = true
        content.orientation = LinearLayout.VERTICAL
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.clipToPadding = false
        scroll.setPadding(0, 0, 0, ctx.dpi(16))
        scroll.addView(content, ViewGroup.LayoutParams(mp, wc))

        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setPadding(ctx.dpi(10), ctx.dpi(6), ctx.dpi(10), ctx.dpi(4))
        backBtn.setImageDrawable(IconDrawable(Ic.BACK, UiTheme.iconIdle, 1.9f))
        backBtn.setPadding(ctx.dpi(10), ctx.dpi(10), ctx.dpi(10), ctx.dpi(10))
        backBtn.background = Glass.ripple(ctx, null)
        backBtn.contentDescription = "Back"
        backBtn.scaleType = ImageView.ScaleType.FIT_CENTER
        backBtn.visibility = View.GONE
        backBtn.setOnClickListener { resetToHome() }
        titleView.textSize = Type.title
        titleView.typeface = Type.bold()
        titleView.setTextColor(UiTheme.text)
        titleView.setPadding(ctx.dpi(6), 0, 0, 0)
        menuBtn.setImageDrawable(IconDrawable(Ic.DOTS, UiTheme.iconIdle))
        menuBtn.setPadding(ctx.dpi(10), ctx.dpi(10), ctx.dpi(10), ctx.dpi(10))
        menuBtn.background = Glass.ripple(ctx, null)
        menuBtn.scaleType = ImageView.ScaleType.FIT_CENTER
        menuBtn.contentDescription = "Menu"
        menuBtn.setOnClickListener { onMenu() }
        topBar.addView(backBtn, LinearLayout.LayoutParams(ctx.dpi(40), ctx.dpi(40)))
        topBar.addView(titleView, LinearLayout.LayoutParams(0, wc, 1f))
        topBar.addView(menuBtn, LinearLayout.LayoutParams(ctx.dpi(40), ctx.dpi(40)))

        buildSearchBar()
        buildDock()
        tilesHost.visibility = View.GONE

        addView(scroll, LayoutParams(mp, mp))
        addView(topBar, LayoutParams(mp, wc, Gravity.TOP))
    }

    // ---------------------------------------------------------------- dock + search

    private var dockRoot: ViewGroup? = null

    private fun buildDock() {
        dockTiles.orientation = LinearLayout.HORIZONTAL
        dockTiles.gravity = Gravity.CENTER_VERTICAL
        dockTiles.background = Glass.panel(ctx, 26, strong = true)
        dockTiles.elevation = ctx.dpf(8)
        dockTiles.setPadding(ctx.dpi(4), 0, ctx.dpi(4), 0)
        dockTiles.addView(dockTile(Ic.LIBRARY, "Library") { if (tab == HomeTab.LIBRARY) resetToHome() else openTab(HomeTab.LIBRARY) },
            LinearLayout.LayoutParams(0, ctx.dpi(52), 1f))
        dockTiles.addView(dockTile(Ic.SEARCH, "Search") { focusSearch() },
            LinearLayout.LayoutParams(0, ctx.dpi(52), 1f))
        dockTiles.addView(dockTile(Ic.GLOBE, "Browse") { if (tab == HomeTab.SOURCES) resetToHome() else openTab(HomeTab.SOURCES) },
            LinearLayout.LayoutParams(0, ctx.dpi(52), 1f))
    }

    private fun dockTile(ic: Ic, label: String, onClick: () -> Unit): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            contentDescription = label
            background = Glass.ripple(ctx, null)
            addView(ImageView(ctx).apply {
                setImageDrawable(IconDrawable(ic, UiTheme.iconIdle))
                isImportantForAccessibility = false
            }, LinearLayout.LayoutParams(ctx.dpi(20), ctx.dpi(20)))
            addView(TextView(ctx).apply {
                text = label
                textSize = Type.overline
                typeface = Type.medium()
                setTextColor(UiTheme.dim)
                gravity = Gravity.CENTER
                setPadding(0, ctx.dpi(3), 0, 0)
            })
            setOnClickListener { onClick() }
        }
    }

    private fun buildSearchBar() {
        searchIcon.setImageDrawable(IconDrawable(Ic.SEARCH, UiTheme.faint))
        input.apply {
            hint = "Search the web or paste a link"
            setHintTextColor(UiTheme.faint)
            setTextColor(UiTheme.text)
            textSize = Type.body
            background = null
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(0, 0, 0, 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) { submit(); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { refreshIcon(s?.toString().orEmpty()) }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
            onFocusChangeListener = View.OnFocusChangeListener { _, has -> searchField.background = Glass.field(ctx, 26, has) }
        }
        val closeBtn = ImageView(ctx).apply {
            setImageDrawable(IconDrawable(Ic.CLOSE, UiTheme.dim, 1.8f))
            val p = ctx.dpi(9)
            setPadding(p, p, p, p)
            background = Glass.ripple(ctx, null)
            contentDescription = "Close search"
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { closeSearch() }
        }
        val go = ImageView(ctx).apply {
            setImageDrawable(IconDrawable(Ic.FORWARD, Color.WHITE, 2.2f))
            val p = ctx.dpi(10)
            setPadding(p, p, p, p)
            background = Glass.oval(UiTheme.accent)
            isClickable = true
            contentDescription = "Go"
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { submit() }
        }
        searchField.orientation = LinearLayout.HORIZONTAL
        searchField.gravity = Gravity.CENTER_VERTICAL
        searchField.setPadding(ctx.dpi(14), 0, ctx.dpi(6), 0)
        searchField.background = Glass.field(ctx, 26, false)
        searchField.addView(searchIcon, LinearLayout.LayoutParams(ctx.dpi(18), ctx.dpi(18)).also { it.marginEnd = ctx.dpi(10) })
        searchField.addView(input, LinearLayout.LayoutParams(0, wc, 1f))
        searchField.addView(closeBtn, LinearLayout.LayoutParams(ctx.dpi(36), ctx.dpi(36)))
        searchField.addView(go, LinearLayout.LayoutParams(ctx.dpi(38), ctx.dpi(38)))
        searchBar.orientation = LinearLayout.HORIZONTAL
        searchBar.gravity = Gravity.CENTER_VERTICAL
        searchBar.setPadding(ctx.dpi(8), 0, ctx.dpi(8), 0)
        searchBar.addView(searchField, LinearLayout.LayoutParams(0, ctx.dpi(50), 1f))
        searchBar.addView(tilesHost, LinearLayout.LayoutParams(0, ctx.dpi(50), 1f))
        searchBar.visibility = View.GONE
    }

    /** MainActivity supplies the mode switch + view control that the reading dock used to hold. */
    fun setToolbar(toolbar: View?) {
        tilesHost.removeAllViews()
        if (toolbar != null) tilesHost.addView(toolbar, LinearLayout.LayoutParams(mp, ctx.dpi(46)))
    }

    private fun focusSearch() {
        searchActive = true
        searchBar.visibility = View.VISIBLE
        dockTiles.visibility = View.GONE
        tilesHost.visibility = if (tilesHost.childCount > 0) View.VISIBLE else View.GONE
        input.requestFocus()
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        searchActive = false
        searchBar.visibility = View.GONE
        dockTiles.visibility = View.VISIBLE
        tilesHost.visibility = View.GONE
        hideKeyboard()
        if (tab == HomeTab.LIBRARY && searchQ.isNotEmpty()) { searchQ = ""; rerender() }
    }

    /** The system back button; true when home consumed it. */
    fun handleBack(): Boolean = when {
        searchActive -> { closeSearch(); true }
        tab != HomeTab.HOME -> { resetToHome(); true }
        else -> false
    }

    private fun looksLikeUrl(s: String): Boolean {
        val t = s.trim()
        return t.startsWith("http") || (!t.contains(' ') && t.contains('.'))
    }

    private fun refreshIcon(text: String) {
        val url = looksLikeUrl(text)
        if (url == iconIsUrl) return
        iconIsUrl = url
        searchIcon.setImageDrawable(IconDrawable(if (url) Ic.GLOBE else Ic.SEARCH, UiTheme.faint))
    }

    private fun submit() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        hideKeyboard()
        input.setText("")
        closeSearch()
        onSearch(t)
    }

    fun hideKeyboard() {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
        input.clearFocus()
    }

    // ---------------------------------------------------------------- helpers

    private fun hostOf(url: String): String? = try {
        Uri.parse(url).host?.removePrefix("www.")
    } catch (_: Exception) {
        null
    }

    private fun rise(v: View): View {
        v.alpha = 0f
        v.translationY = ctx.dpf(10)
        v.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay((riseIndex++ * 40L).coerceAtMost(240L))
            .setDuration(300L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        return v
    }

    private fun sectionLabel(t: String, action: String? = null, onClick: (() -> Unit)? = null): View {
        val title = TextView(ctx).apply {
            text = t
            textSize = Type.overline
            letterSpacing = 0.1f
            setTextColor(UiTheme.faint)
            typeface = Type.bold()
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpi(20), ctx.dpi(18), ctx.dpi(20), ctx.dpi(6))
            addView(title, LinearLayout.LayoutParams(0, wc, 1f))
        }
        if (action != null && onClick != null) {
            row.addView(TextView(ctx).apply {
                text = action
                textSize = Type.label
                typeface = Type.medium()
                setTextColor(UiTheme.accent)
                setPadding(ctx.dpi(10), ctx.dpi(6), 0, ctx.dpi(6))
            }.also { it.clickable { onClick() } })
        }
        return row
    }

    private fun siteIcon(site: HomeSite, sizeDp: Int, radiusDp: Int, textSp: Float): View {
        val size = ctx.dpi(sizeDp)
        val frame = FrameLayout(ctx)
        val base = Covers.colorFor(site.name)
        frame.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(base, ModeUi.lerp(base, Color.BLACK, 0.25f))
        ).apply { cornerRadius = ctx.dpf(radiusDp) }
        val letter = TextView(ctx).apply {
            text = site.name.take(1).uppercase()
            setTextColor(Color.WHITE)
            textSize = textSp
            typeface = Type.bold()
            gravity = Gravity.CENTER
        }
        val img = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            val p = (size * 0.2f).toInt()
            setPadding(p, p, p, p)
        }
        frame.addView(letter, LayoutParams(mp, mp))
        frame.addView(img, LayoutParams(mp, mp))
        val mine = token
        val host = hostOf(site.url)
        if (host != null) {
            Favicons.load(ctx, host) { bmp ->
                if (bmp != null && mine == token) {
                    frame.background = GradientDrawable().apply {
                        setColor(0xFFF4F4F6.toInt())
                        cornerRadius = ctx.dpf(radiusDp)
                    }
                    img.setImageBitmap(bmp)
                    img.visibility = View.VISIBLE
                    letter.visibility = View.GONE
                }
            }
        }
        return frame
    }

    private fun siteTile(site: HomeSite): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, ctx.dpi(6), 0, ctx.dpi(6))
            contentDescription = site.name
            addView(siteIcon(site, 52, 15, 20f), LinearLayout.LayoutParams(ctx.dpi(52), ctx.dpi(52)))
            addView(TextView(ctx).apply {
                text = site.name
                textSize = Type.caption
                setTextColor(UiTheme.dim)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(ctx.dpi(2), ctx.dpi(8), ctx.dpi(2), 0)
            }, LinearLayout.LayoutParams(mp, wc))
            clickable { onOpen(site.url) }
        }
    }

    private fun siteGrid(sites: List<HomeSite>): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(12), 0, ctx.dpi(12), 0)
        }
        sites.chunked(4).forEach { rowSites ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            rowSites.forEach { row.addView(siteTile(it), LinearLayout.LayoutParams(0, wc, 1f)) }
            repeat(4 - rowSites.size) { row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f)) }
            col.addView(row, LinearLayout.LayoutParams(mp, wc))
        }
        return col
    }

    private fun chip(site: HomeSite): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpi(6), ctx.dpi(6), ctx.dpi(14), ctx.dpi(6))
            background = Glass.ripple(ctx, Glass.solid(ctx, 0x0FFFFFFF, 20, UiTheme.strokeSoft), 20)
            contentDescription = site.name
            addView(siteIcon(site, 24, 8, 12f), LinearLayout.LayoutParams(ctx.dpi(24), ctx.dpi(24)).also { it.marginEnd = ctx.dpi(8) })
            addView(TextView(ctx).apply {
                text = site.name
                textSize = Type.label
                setTextColor(UiTheme.text)
                maxLines = 1
            })
            clickable { onOpen(site.url) }
        }
    }

    private fun pad(v: View, h: Int = 16, vp: Int = 5): View = FrameLayout(ctx).apply {
        setPadding(ctx.dpi(h), ctx.dpi(vp), ctx.dpi(h), ctx.dpi(vp))
        addView(v, LayoutParams(mp, wc))
    }

    // ---------------------------------------------------------------- cards

    /** Flat row card with hairline edge; the caller decides what happens on tap. */
    private fun rowCard(inner: View, radius: Int = 18, onTap: (() -> Unit)? = null): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dpi(14), ctx.dpi(12), ctx.dpi(14), ctx.dpi(12))
        background = Glass.ripple(ctx, Glass.solid(ctx, 0x0AFFFFFF, radius, UiTheme.strokeSoft), radius)
        addView(inner, LinearLayout.LayoutParams(0, wc, 1f))
        if (onTap != null) { pressEffect(); setOnClickListener { onTap() } }
    }

    private fun valueBadge(t: String, fg: Int, bg: Int): TextView = TextView(ctx).apply {
        text = t
        textSize = Type.overline
        typeface = Type.bold()
        setTextColor(fg)
        setPadding(ctx.dpi(8), ctx.dpi(3), ctx.dpi(8), ctx.dpi(3))
        background = Glass.solid(ctx, bg, 9)
    }

    private fun shelfCard(s: LibraryModel.Shelf): View {
        val newCount = updates.firstOrNull { it.novel == s.name }?.newCount ?: 0
        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Covers.view(ctx, s.name, 52, 72, 10, 22f), LinearLayout.LayoutParams(ctx.dpi(52), ctx.dpi(72)).also { it.marginEnd = ctx.dpi(14) })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text = s.name
                    textSize = Type.heading
                    typeface = Type.bold()
                    setTextColor(UiTheme.text)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(ctx).apply {
                    text = LibraryModel.shelfMeta(s)
                    textSize = Type.caption
                    setTextColor(UiTheme.dim)
                    setPadding(0, ctx.dpi(4), 0, 0)
                })
                if (newCount > 0) addView(valueBadge("$newCount new", UiTheme.onOk, UiTheme.ok), LinearLayout.LayoutParams(wc, wc).also { it.topMargin = ctx.dpi(8) })
            }, LinearLayout.LayoutParams(0, wc, 1f))
            addView(ImageView(ctx).apply { setImageDrawable(IconDrawable(Ic.CHEVRON, UiTheme.faint)) },
                LinearLayout.LayoutParams(ctx.dpi(16), ctx.dpi(16)))
        }
        return rowCard(info) { showShelf(s) }
    }

    private fun updateRow(u: LibraryModel.Update): View {
        val meta = listOfNotNull(u.sourceHost.ifBlank { null }, "${u.chapters} ch", Covers.ago(u.at).ifBlank { null }).joinToString(" · ")
        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Covers.view(ctx, u.novel, 34, 34, 9, 15f), LinearLayout.LayoutParams(ctx.dpi(34), ctx.dpi(34)).also { it.marginEnd = ctx.dpi(12) })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text = u.novel
                    textSize = Type.body
                    typeface = Type.medium()
                    setTextColor(UiTheme.text)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(ctx).apply {
                    text = if (u.lastChapter.isNotBlank()) u.lastChapter + "  ·  " + meta else meta
                    textSize = Type.caption
                    setTextColor(UiTheme.faint)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, ctx.dpi(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, wc, 1f))
            if (u.newCount > 0) addView(valueBadge("${u.newCount} new", UiTheme.onOk, UiTheme.ok))
        }
        return rowCard(info, 16) { openShelfByName(u.novel) }
    }

    private fun sourceRow(e: LibraryModel.Ext, on: Boolean): View {
        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(ctx).apply {
                background = Glass.oval(0x14FFFFFF)
                addView(ImageView(ctx).apply {
                    setImageDrawable(IconDrawable(if (on) Ic.GLOBE else Ic.CLEAR, if (on) UiTheme.accent else UiTheme.faint))
                }, LayoutParams(ctx.dpi(17), ctx.dpi(17), Gravity.CENTER))
            }, LinearLayout.LayoutParams(ctx.dpi(34), ctx.dpi(34)).also { it.marginEnd = ctx.dpi(12) })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text = e.name
                    textSize = Type.body
                    typeface = Type.medium()
                    setTextColor(if (on) UiTheme.text else UiTheme.faint)
                })
                addView(TextView(ctx).apply {
                    text = listOfNotNull(e.kind, e.host).joinToString(" · ")
                    textSize = Type.caption
                    setTextColor(UiTheme.faint)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, ctx.dpi(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, wc, 1f))
            val chip = if (e.builtIn) valueBadge("Built-in", UiTheme.dim, 0x14FFFFFF)
            else valueBadge(if (on) "On" else "Off", if (on) UiTheme.onOk else UiTheme.faint, if (on) UiTheme.ok else 0x14FFFFFF)
            if (!e.builtIn) {
                chip.isClickable = true
                chip.setOnClickListener {
                    LibraryModel.setEnabled(ctx, e.id, !on)
                    rerender()
                }
            }
            addView(chip)
        }
        return rowCard(info, 16) { e.url?.let { u -> onOpen(u) } }
    }

    private fun emptyCard(title: String, body: String, action: String?, onClick: (() -> Unit)?): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(18), ctx.dpi(16), ctx.dpi(18), ctx.dpi(16))
            background = Glass.solid(ctx, 0x0AFFFFFF, 18, UiTheme.strokeSoft)
            addView(ImageView(ctx).apply { setImageDrawable(IconDrawable(Ic.LIBRARY, UiTheme.faint)) },
                LinearLayout.LayoutParams(ctx.dpi(22), ctx.dpi(22)))
            addView(TextView(ctx).apply {
                text = title
                textSize = Type.body
                typeface = Type.medium()
                setTextColor(UiTheme.text)
            }, LinearLayout.LayoutParams(mp, wc).also { it.topMargin = ctx.dpi(10) })
            addView(TextView(ctx).apply {
                text = body
                textSize = Type.label
                setTextColor(UiTheme.dim)
                setLineSpacing(ctx.dpf(3), 1f)
            }, LinearLayout.LayoutParams(mp, wc).also { it.topMargin = ctx.dpi(4) })
            if (action != null && onClick != null) addView(TextView(ctx).apply {
                text = action
                textSize = Type.label
                typeface = Type.medium()
                setTextColor(UiTheme.onAccent)
                gravity = Gravity.CENTER
                setPadding(ctx.dpi(16), ctx.dpi(9), ctx.dpi(16), ctx.dpi(9))
                background = Glass.ripple(ctx, Glass.accent(ctx, Space.radiusM), Space.radiusM)
                isClickable = true
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(wc, wc).also { it.topMargin = ctx.dpi(14) })
        }
        return col
    }

    // ---------------------------------------------------------------- sections

    fun render(
        recents: List<HomeRecent>,
        novelSites: List<HomeSite>,
        comicSites: List<HomeSite>,
        shelves: List<LibraryModel.Shelf>,
        updates: List<LibraryModel.Update>,
        sources: List<LibraryModel.Ext>
    ) {
        token++
        this.recents = recents
        this.novelSites = novelSites
        this.comicSites = comicSites
        this.shelves = shelves
        this.updates = updates
        this.sources = sources
        rerender()
    }

    private fun openTab(t: HomeTab) { tab = t; rerender() }
    private fun resetToHome() { tab = HomeTab.HOME; searchQ = ""; rerender() }

    private fun openShelfByName(name: String) {
        val s = shelves.firstOrNull { it.name == name }
        if (s != null) showShelf(s) else rerender()
    }

    private fun showShelf(s: LibraryModel.Shelf) {
        val activity = context as? android.app.Activity ?: return
        val chapters = LibraryModel.shelfChapters(ctx, s.name)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(16), ctx.dpi(10), ctx.dpi(16), ctx.dpi(16))
            background = Sheets.sheetBackground(ctx)
        }
        box.addView(View(ctx).apply { background = Glass.solid(ctx, 0x2EFFFFFF, 2) },
            LinearLayout.LayoutParams(ctx.dpi(36), ctx.dpi(4)).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.bottomMargin = ctx.dpi(12) })
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Covers.view(ctx, s.name, 56, 78, 10, 24f), LinearLayout.LayoutParams(ctx.dpi(56), ctx.dpi(78)).also { it.marginEnd = ctx.dpi(14) })
        head.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply {
                text = s.name
                textSize = Type.heading
                typeface = Type.bold()
                setTextColor(UiTheme.text)
                maxLines = 2
            })
            addView(TextView(ctx).apply {
                text = LibraryModel.shelfMeta(s)
                textSize = Type.caption
                setTextColor(UiTheme.dim)
                setPadding(0, ctx.dpi(4), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, wc, 1f))
        box.addView(head)
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ctx.dpi(14), 0, ctx.dpi(6)) }
        fun act(label: String, onClick: () -> Unit) {
            actions.addView(TextView(ctx).apply {
                text = label
                textSize = Type.label
                typeface = Type.medium()
                setTextColor(UiTheme.text)
                gravity = Gravity.CENTER
                setPadding(ctx.dpi(14), ctx.dpi(9), ctx.dpi(14), ctx.dpi(9))
                background = Glass.ripple(ctx, Glass.solid(ctx, 0x0FFFFFFF, 12, UiTheme.strokeSoft), 12)
                isClickable = true
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(0, wc, 1f).also { it.marginEnd = ctx.dpi(8) })
        }
        lateinit var detail: Dialog
        act("Read ${chapters.firstOrNull()?.number?.let { "Ch $it" } ?: "latest"}") {
            chapters.firstOrNull()?.let {
                LibraryModel.recordRead(ctx, s.name, it.id)
                detail.dismiss()
                ctx.startActivity(android.content.Intent(ctx, ReaderActivity::class.java).putExtra("novel", s.name).putExtra("id", it.id))
            }
        }
        act("Export") { detail.dismiss(); onExport(s.name) }
        actions.addView(TextView(ctx).apply {
            text = "More"
            textSize = Type.label
            typeface = Type.medium()
            setTextColor(UiTheme.dim)
            gravity = Gravity.CENTER
            setPadding(ctx.dpi(14), ctx.dpi(9), ctx.dpi(14), ctx.dpi(9))
            background = Glass.ripple(ctx, Glass.solid(ctx, 0x0FFFFFFF, 12, UiTheme.strokeSoft), 12)
            isClickable = true
            setOnClickListener { detail.dismiss(); onShelfMore(s) }
        }, LinearLayout.LayoutParams(0, wc, 1f))
        box.addView(actions, LinearLayout.LayoutParams(mp, wc))
        box.addView(TextView(ctx).apply {
            text = "CHAPTERS"
            textSize = Type.overline
            letterSpacing = 0.1f
            typeface = Type.bold()
            setTextColor(UiTheme.faint)
            setPadding(ctx.dpi(4), ctx.dpi(14), ctx.dpi(4), ctx.dpi(4))
        })
        chapters.forEach { tr ->
            box.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dpi(4), ctx.dpi(11), ctx.dpi(4), ctx.dpi(11))
                addView(TextView(ctx).apply {
                    text = tr.label()
                    textSize = Type.body
                    setTextColor(UiTheme.text)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, wc, 1f))
                addView(TextView(ctx).apply {
                    text = Covers.ago(tr.id)
                    textSize = Type.overline
                    setTextColor(UiTheme.faint)
                })
                isClickable = true
                setOnClickListener {
                    LibraryModel.recordRead(ctx, s.name, tr.id)
                    detail.dismiss()
                    ctx.startActivity(android.content.Intent(ctx, ReaderActivity::class.java).putExtra("novel", s.name).putExtra("id", tr.id))
                }
            }, LinearLayout.LayoutParams(mp, wc))
        }
        val scroll = MaxHeightScroll(ctx, (ctx.resources.displayMetrics.heightPixels * 0.8f).toInt()).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(box, ViewGroup.LayoutParams(mp, wc))
        }
        detail = Sheets.create(activity, scroll)
        detail.show()
    }

    /** Hooks filled in by MainActivity (export / rename / delete need activity-scoped helpers). */
    lateinit var onExport: (String) -> Unit
    lateinit var onShelfMore: (LibraryModel.Shelf) -> Unit

    private fun rerender() {
        riseIndex = 0
        content.removeAllViews()
        when (tab) {
            HomeTab.LIBRARY -> bodyLibrary()
            HomeTab.UPDATES -> bodyUpdates()
            HomeTab.SOURCES -> bodySources()
            else -> bodyHome()
        }
        titleView.text = when (tab) {
            HomeTab.LIBRARY -> "Library"
            HomeTab.UPDATES -> "Updates"
            HomeTab.SOURCES -> "Sources"
            else -> "Novel Studio"
        }
        backBtn.visibility = if (tab == HomeTab.HOME) View.GONE else View.VISIBLE
        if (searchActive) closeSearch()
    }

    private fun bodyHome() {
        content.addView(header(), LinearLayout.LayoutParams(mp, wc))
        content.addView(libraryCards(), LinearLayout.LayoutParams(mp, wc))
        val fresh = updates.firstOrNull { it.newCount > 0 }
        if (fresh != null) content.addView(pad(updatesBanner(fresh)), LinearLayout.LayoutParams(mp, wc))
        if (recents.isNotEmpty()) {
            content.addView(rise(sectionLabel("RECENTLY READ")), LinearLayout.LayoutParams(mp, wc))
            recents.take(3).forEach { content.addView(rise(recentRow(it)), LinearLayout.LayoutParams(mp, wc)) }
        }
        content.addView(
            rise(sectionLabel(if (recents.isEmpty()) "START READING" else "MORE SITES", "See all", { openTab(HomeTab.SOURCES) })),
            LinearLayout.LayoutParams(mp, wc)
        )
        content.addView(rise(siteGrid((novelSites + comicSites).distinctBy { it.url }.take(8))), LinearLayout.LayoutParams(mp, wc))
        if (recents.isEmpty() && comicSites.isNotEmpty()) {
            content.addView(rise(sectionLabel("COMICS")), LinearLayout.LayoutParams(mp, wc))
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(ctx.dpi(18), 0, ctx.dpi(18), ctx.dpi(8)) }
            comicSites.forEach { row.addView(chip(it), LinearLayout.LayoutParams(wc, wc).also { lp -> lp.marginEnd = ctx.dpi(8) }) }
            content.addView(rise(object : HorizontalScrollView(ctx) {
                init { isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; addView(row) }
            }), LinearLayout.LayoutParams(mp, wc))
        }
        content.addView(rise(sectionLabel("HUBS", "Webtoons", { onHub(Hub.Part.WEBTOON) })), LinearLayout.LayoutParams(mp, wc))
        content.addView(rise(hubRow()), LinearLayout.LayoutParams(mp, wc))
    }

    private fun bodyLibrary() {
        val total = shelves.sumOf { it.chapters }
        content.addView(pad(TextView(ctx).apply {
            text = if (shelves.isEmpty()) "No saved books yet." else "${shelves.size} book" + (if (shelves.size == 1) "" else "s") + " · $total chapter" + (if (total == 1) "" else "s")
            textSize = Type.label
            setTextColor(UiTheme.dim)
            setPadding(ctx.dpi(4), ctx.dpi(8), ctx.dpi(4), ctx.dpi(4))
        }), LinearLayout.LayoutParams(mp, wc))
        if (shelves.isEmpty()) {
            content.addView(pad(emptyCard(
                "Nothing saved yet",
                "Translations you save while reading are kept here — with chapter list, export and rename.",
                "Browse sources"
            ) { openTab(HomeTab.SOURCES) }), LinearLayout.LayoutParams(mp, wc))
            return
        }
        content.addView(rise(sectionLabel("BOOKS")), LinearLayout.LayoutParams(mp, wc))
        shelves.forEach { content.addView(pad(rise(shelfCard(it))), LinearLayout.LayoutParams(mp, wc)) }
        content.addView(pad(emptyCard(
            "Full library manager",
            "Search, rename, export and delete across every saved book.",
            "Open library manager"
        ) { onOpenLibrary() }), LinearLayout.LayoutParams(mp, wc))
    }

    private fun bodyUpdates() {
        val fresh = updates.filter { it.newCount > 0 }
        if (updates.isEmpty()) {
            content.addView(pad(emptyCard(
                "No updates yet",
                "Books appear here once chapters are translated after the chapter you last opened.",
                "Browse sources"
            ) { openTab(HomeTab.SOURCES) }), LinearLayout.LayoutParams(mp, wc))
            return
        }
        if (fresh.isNotEmpty()) {
            content.addView(rise(sectionLabel("NEW CHAPTERS")), LinearLayout.LayoutParams(mp, wc))
            fresh.forEach { content.addView(pad(rise(updateRow(it))), LinearLayout.LayoutParams(mp, wc)) }
        }
        content.addView(rise(sectionLabel("ALL BOOKS")), LinearLayout.LayoutParams(mp, wc))
        updates.forEach { content.addView(pad(rise(updateRow(it))), LinearLayout.LayoutParams(mp, wc)) }
        content.addView(pad(emptyCard(
            "Full update history",
            "Opens the library manager, where updates are marked as seen.",
            "Open library manager"
        ) { onOpenUpdates() }), LinearLayout.LayoutParams(mp, wc))
    }

    private fun bodySources() {
        val enabled = sources.filter { LibraryModel.enabled(ctx, it) }
        val off = sources.filter { !LibraryModel.enabled(ctx, it) }
        content.addView(pad(TextView(ctx).apply {
            text = "Built-in adapters have real extraction code. Other sites read \"Learned on this device\" only after the app has learned how to read them; a source is never shown as working before that."
            textSize = Type.label
            setTextColor(UiTheme.dim)
            setLineSpacing(ctx.dpf(3), 1f)
            setPadding(ctx.dpi(4), ctx.dpi(8), ctx.dpi(4), ctx.dpi(6))
        }), LinearLayout.LayoutParams(mp, wc))
        if (enabled.isNotEmpty()) {
            content.addView(rise(sectionLabel("ACTIVE (${enabled.size})")), LinearLayout.LayoutParams(mp, wc))
            enabled.forEach { content.addView(pad(rise(sourceRow(it, true))), LinearLayout.LayoutParams(mp, wc)) }
        }
        if (off.isNotEmpty()) {
            content.addView(rise(sectionLabel("AVAILABLE (${off.size})")), LinearLayout.LayoutParams(mp, wc))
            off.forEach { content.addView(pad(rise(sourceRow(it, false))), LinearLayout.LayoutParams(mp, wc)) }
        }
        content.addView(pad(emptyCard(
            "Source manager",
            "Full list with built-in status, learned details and switches.",
            "Open source manager"
        ) { onOpenSources() }), LinearLayout.LayoutParams(mp, wc))
    }

    private fun header(): View {
        val hello = TextView(ctx).apply {
            text = "Read, translated."
            textSize = Type.display
            typeface = Type.bold()
            setTextColor(UiTheme.text)
            letterSpacing = -0.02f
        }
        val newBooks = updates.count { it.newCount > 0 }
        val sub = TextView(ctx).apply {
            text = when {
                newBooks > 0 -> "$newBooks book" + (if (newBooks == 1) "" else "s") + " with new chapters"
                shelves.isNotEmpty() -> "Pick up where you left off"
                recents.isNotEmpty() -> "Continue reading, or start something new"
                else -> "Novels, webtoons and comics — with AI translation"
            }
            textSize = Type.label
            setTextColor(UiTheme.dim)
            setPadding(0, ctx.dpi(4), 0, 0)
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(20), ctx.dpi(12), ctx.dpi(20), ctx.dpi(8))
            addView(rise(hello))
            addView(rise(sub))
        }
    }

    private fun libraryCards(): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.dpi(14), ctx.dpi(6), ctx.dpi(14), ctx.dpi(4))
        }
        fun cardView(ic: Ic, title: String, sub: String, accent: Boolean, onClick: () -> Unit): View {
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ctx.dpi(13), ctx.dpi(12), ctx.dpi(13), ctx.dpi(12))
                background = Glass.ripple(ctx, Glass.solid(ctx, 0x0AFFFFFF, 18, UiTheme.strokeSoft), 18)
                contentDescription = "$title, $sub"
                addView(ImageView(ctx).apply { setImageDrawable(IconDrawable(ic, if (accent) UiTheme.ok else UiTheme.accent)) },
                    LinearLayout.LayoutParams(ctx.dpi(18), ctx.dpi(18)))
                addView(TextView(ctx).apply {
                    text = sub
                    textSize = Type.heading
                    typeface = Type.bold()
                    setTextColor(if (accent) UiTheme.ok else UiTheme.text)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(mp, wc).also { it.topMargin = ctx.dpi(10) })
                addView(TextView(ctx).apply {
                    text = title
                    textSize = Type.overline
                    setTextColor(UiTheme.faint)
                })
                setOnClickListener { onClick() }
            }
        }
        row.addView(cardView(Ic.LIBRARY, "Library", shelves.size.toString() + " book" + (if (shelves.size == 1) "" else "s"), false) { openTab(HomeTab.LIBRARY) },
            LinearLayout.LayoutParams(0, ctx.dpi(96), 1f).also { it.marginEnd = ctx.dpi(10) })
        val newBooks = updates.count { it.newCount > 0 }
        row.addView(cardView(Ic.BOLT, "Updates", if (newBooks > 0) "$newBooks new" else "Up to date", newBooks > 0) { openTab(HomeTab.UPDATES) },
            LinearLayout.LayoutParams(0, ctx.dpi(96), 1f))
        return row
    }

    private fun updatesBanner(u: LibraryModel.Update): View {
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Covers.view(ctx, u.novel, 36, 36, 18, 15f), LinearLayout.LayoutParams(ctx.dpi(36), ctx.dpi(36)).also { it.marginEnd = ctx.dpi(12) })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text = "${u.newCount} new chapter" + (if (u.newCount == 1) "" else "s")
                    textSize = Type.label
                    typeface = Type.medium()
                    setTextColor(UiTheme.text)
                })
                addView(TextView(ctx).apply {
                    text = u.novel
                    textSize = Type.overline
                    setTextColor(UiTheme.ok)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, wc, 1f))
            addView(ImageView(ctx).apply { setImageDrawable(IconDrawable(Ic.CHEVRON, UiTheme.dim)) },
                LinearLayout.LayoutParams(ctx.dpi(16), ctx.dpi(16)))
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.dpi(14), ctx.dpi(12), ctx.dpi(14), ctx.dpi(12))
            background = Glass.ripple(ctx, Glass.solid(ctx, 0x0F51D29B, 18, 0x2E51D29B), 18)
            addView(inner, LinearLayout.LayoutParams(0, wc, 1f))
            pressEffect()
            setOnClickListener { openTab(HomeTab.UPDATES) }
        }
    }

    private fun recentRow(r: HomeRecent): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpi(20), ctx.dpi(8), ctx.dpi(16), ctx.dpi(8))
            contentDescription = r.title
            addView(Covers.view(ctx, r.title, 34, 34, 17, 15f), LinearLayout.LayoutParams(ctx.dpi(34), ctx.dpi(34)).also { it.marginEnd = ctx.dpi(12) })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text = r.title
                    textSize = Type.body
                    typeface = Type.medium()
                    setTextColor(UiTheme.text)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(ctx).apply {
                    text = listOfNotNull(r.site, Covers.ago(r.timeMs).ifBlank { null }).joinToString(" · ")
                    textSize = Type.caption
                    setTextColor(UiTheme.faint)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, ctx.dpi(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, wc, 1f))
            addView(ImageView(ctx).apply { setImageDrawable(IconDrawable(Ic.CHEVRON, UiTheme.faint)) },
                LinearLayout.LayoutParams(ctx.dpi(16), ctx.dpi(16)))
            clickable { onOpen(r.url) }
        }
    }

    private fun hubRow(): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(ctx.dpi(16), 0, ctx.dpi(16), ctx.dpi(10))
        fun part(name: String, p: Hub.Part) {
            addView(TextView(ctx).apply {
                text = name
                textSize = Type.label
                typeface = Type.medium()
                setTextColor(UiTheme.text)
                gravity = Gravity.CENTER
                setPadding(0, ctx.dpi(11), 0, ctx.dpi(11))
                background = Glass.ripple(ctx, Glass.solid(ctx, 0x0FFFFFFF, 14, UiTheme.strokeSoft), 14)
                contentDescription = "Open $name"
                setOnClickListener { onHub(p) }
            }, LinearLayout.LayoutParams(0, ctx.dpi(44), 1f).also { it.marginEnd = ctx.dpi(8) })
        }
        part("Webtoon hub", Hub.Part.WEBTOON)
        part("Novel hub", Hub.Part.NOVEL)
    }

    // ---------------------------------------------------------------- notifications

    fun showNotice(message: String, kind: Snackbar.Kind) {
        val root = dockRoot ?: return
        val n = notice ?: Snackbar(ctx).also { notice = it }
        val slot = noticeSlot ?: FrameLayout(ctx).also {
            noticeSlot = it
            addView(it, LayoutParams(mp, wc, Gravity.BOTTOM))
        }
        if (n.parent == null) {
            slot.removeAllViews()
            slot.addView(n, LayoutParams(mp, wc, Gravity.CENTER))
        }
        slot.visibility = View.VISIBLE
        slot.bringToFront()
        n.show(message, kind, autoHideMs = (2600L + message.length * 24L).coerceIn(2600L, 6000L))
        n.postDelayed(object : Runnable {
            override fun run() {
                if (n.shown) n.postDelayed(this, 220L) else slot.visibility = View.GONE
            }
        }, 220L)
    }

    private var noticeSlot: FrameLayout? = null

    /** Lets MainActivity place the dock where the window insets allow. */
    fun attachDock(d: View) {
        onDock = d
        dockRoot = d as? ViewGroup
        if (d is ViewGroup) {
            d.addView(dockTiles, LayoutParams(mp, ctx.dpi(54), Gravity.CENTER))
            d.addView(searchBar, LayoutParams(mp, ctx.dpi(56), Gravity.CENTER))
        }
    }
}
