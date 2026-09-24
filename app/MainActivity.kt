package com.ashraf.novelstudio

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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

    private lateinit var novelWv: WebView
    private lateinit var chatWv: WebView
    private lateinit var activeWv: WebView
    private lateinit var urlBar: EditText
    private lateinit var novelBox: FrameLayout
    private lateinit var chatBox: LinearLayout
    private lateinit var strip: LinearLayout
    private lateinit var modeBtn: TextView

    private var mode = Mode.SPLIT
    private var autoCopy = false
    private var polling = false
    private var lastChapter: Chapter? = null
    private var lastCopied = ""
    private val handler = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------ UI
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
        textSize = 20f
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
        chatWv.webViewClient = WebViewClient()
        chatWv.webChromeClient = WebChromeClient()
        activeWv = novelWv
        novelWv.setOnTouchListener { _, _ -> activeWv = novelWv; false }
        chatWv.setOnTouchListener { _, _ -> activeWv = chatWv; false }

        // top bar
        urlBar = EditText(this).apply {
            hint = "সার্চ করো বা লিংক দাও"
            setSingleLine()
            textSize = 14f
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, _, _ -> go(this.text.toString()); true }
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
            addView(goBtn, LinearLayout.LayoutParams(dp(48), dp(40)))
        }

        // panes
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
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(novelBox, LinearLayout.LayoutParams(MP, 0, 1f))
            addView(View(this@MainActivity).apply { setBackgroundColor(0xFF555555.toInt()) }, LinearLayout.LayoutParams(MP, dp(2)))
            addView(chatBox, LinearLayout.LayoutParams(MP, 0, 1f))
        }

        // bottom bar
        modeBtn = barBtn("◫") { cycleMode() }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF202024.toInt())
            addView(modeBtn)
            addView(barBtn("📝") { copy(Prefs.prompt(this@MainActivity)); toast("📋 প্রম্পট কপি হয়েছে") })
            addView(barBtn("◀") { step("prev") })
            addView(barBtn("●") { extractCopy() })
            addView(barBtn("▶") { step("next") })
            addView(barBtn("💾") { saveAnswer() })
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
        novelWv.loadUrl(Prefs.get(this, "lastNovelUrl", "https://duckduckgo.com/"))
        val firstBot = bots().getJSONObject(0).getString("u")
        chatWv.loadUrl(Prefs.get(this, "botUrl", firstBot))
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
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
        }
    }

    private fun setMode(m: Mode) {
        mode = m
        novelBox.visibility = if (m == Mode.CHAT) View.GONE else View.VISIBLE
        chatBox.visibility = if (m == Mode.NOVEL) View.GONE else View.VISIBLE
        modeBtn.text = when (m) {
            Mode.NOVEL -> "📖"
            Mode.CHAT -> "💬"
            Mode.SPLIT -> "◫"
        }
        activeWv = if (m == Mode.CHAT) chatWv else novelWv
    }

    private fun cycleMode() {
        setMode(when (mode) {
            Mode.SPLIT -> Mode.NOVEL
            Mode.NOVEL -> Mode.CHAT
            Mode.CHAT -> Mode.SPLIT
        })
    }

    // ------------------------------------------------------------------ chatbots
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
        if (mode == Mode.NOVEL) setMode(Mode.CHAT)
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

    // ------------------------------------------------------------------ navigation / search
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
            if (scheme != "http" && scheme != "https") return true          // intent://, market:// ...
            if (Prefs.adblock(this@MainActivity) && r.isForMainFrame && !r.hasGesture()) {
                if (AdBlock.blocked(r.url)) return true
                val cur = Uri.parse(view?.url ?: "").host
                val nh = r.url.host
                if (cur != null && nh != null && AdBlock.root(cur) != AdBlock.root(nh)) return true   // auto redirect to another site
            }
            return false
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (url != null && !urlBar.hasFocus()) urlBar.setText(url)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (Prefs.adblock(this@MainActivity)) view?.evaluateJavascript(AdBlock.cosmeticJs(), null)
            if (url != null) Prefs.put(this@MainActivity, "lastNovelUrl", url)
            if (autoCopy && !polling) {
                polling = true
                handler.postDelayed({ pollExtract(0) }, 800)
            }
        }
    }

    private inner class NovelChrome : WebChromeClient() {
        // target=_blank links (only with a real tap) open in the same view; script popups are dropped
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

    // ------------------------------------------------------------------ extraction
    private fun extractNow(cb: (Chapter?) -> Unit) {
        novelWv.evaluateJavascript("document.documentElement.outerHTML") { raw ->
            val url = novelWv.url ?: ""
            Thread {
                val ch: Chapter? = try {
                    if (raw == null || raw == "null") null
                    else {
                        val html = JSONArray("[$raw]").getString(0)
                        Extractor.extract(Jsoup.parse(html, url), url)
                    }
                } catch (e: Exception) { null }
                runOnUiThread { cb(ch) }
            }.start()
        }
    }

    private fun onChapter(ch: Chapter) {
        lastChapter = ch
        Store.touchNovel(this, ch)
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("novel", text))
        lastCopied = text
    }

    private fun copyChapter(ch: Chapter) {
        val p = Prefs.prompt(this)
        val full = if (Prefs.bool(this, "withPrompt") && p.isNotBlank()) p + "\n\n---\n\n" + ch.text else ch.text
        copy(full)
        toast("📋 কপি হয়েছে: ${ch.title} (${full.length} অক্ষর)" + (if (ch.guessed) "\n⚠️ Next/Prev লিংক অনুমান করা" else ""))
        if (mode == Mode.NOVEL && !Prefs.bool(this, "noGoChat")) setMode(Mode.CHAT)
    }

    // ●  : copy the chapter on the novel page right now
    private fun extractCopy() {
        extractNow { ch ->
            if (ch == null) toast("❌ এই পেজে চ্যাপ্টারের লেখা পাওয়া যায়নি")
            else { onChapter(ch); copyChapter(ch) }
        }
    }

    // ▶ / ◀ : go to next/prev chapter, then copy it automatically
    private fun step(dir: String) {
        extractNow { cur ->
            val base = cur ?: lastChapter
            val target = if (dir == "next") base?.next else base?.prev
            if (target == null) {
                toast("❌ লিংক পাওয়া যায়নি")
            } else {
                if (cur != null) onChapter(cur)
                autoCopy = true
                polling = false
                if (mode == Mode.CHAT) setMode(Mode.SPLIT)
                novelWv.loadUrl(target)
                handler.postDelayed({ autoCopy = false; polling = false }, 60000)
            }
        }
    }

    private fun pollExtract(n: Int) {
        extractNow { ch ->
            if (ch != null && ch.text.length > 300) {
                autoCopy = false
                polling = false
                onChapter(ch)
                copyChapter(ch)
            } else if (n < 6) {
                handler.postDelayed({ pollExtract(n + 1) }, 1200)
            } else {
                autoCopy = false
                polling = false
                toast("❌ চ্যাপ্টারের লেখা পাওয়া যায়নি — ● চেপে আবার চেষ্টা করো")
            }
        }
    }

    // 💾 : the chatbot's own "Copy" button puts the answer on the clipboard; we store it
    private fun saveAnswer() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: ""
        if (t.length < 30) return toast("❌ ক্লিপবোর্ডে অনুবাদ নেই — আগে চ্যাটবটের Copy বাটন চাপো")
        if (t == lastCopied.trim()) return toast("❌ এটা তো সোর্স টেক্সট — চ্যাটবটের উত্তরের Copy বাটন চাপো")
        val ch = lastChapter
        Store.save(this, ch?.title ?: "অনুবাদ", ch?.url ?: "", t)
        toast("💾 সেভ হয়েছে (${t.length} অক্ষর)")
        if (!Prefs.bool(this, "noSaveNext")) step("next")
    }

    // ------------------------------------------------------------------ menu / library
    private fun menu() {
        val ab = Prefs.adblock(this)
        val wp = Prefs.bool(this, "withPrompt")
        val gc = !Prefs.bool(this, "noGoChat")
        val sn = !Prefs.bool(this, "noSaveNext")
        val items = arrayOf(
            "📖 এই পেজ নোভেল লিস্টে সেভ করো",
            "📚 নোভেল লিস্ট",
            "💾 সেভ করা অনুবাদ (পড়ো / মুছো)",
            "⬇️ সব অনুবাদ txt এক্সপোর্ট",
            "📝 প্রম্পট এডিট",
            (if (wp) "✅" else "⬜") + " কপির সাথে প্রম্পট জুড়ে দাও",
            (if (ab) "✅" else "⬜") + " Ad Block",
            "🔄 Ad Block লিস্ট আপডেট",
            (if (gc) "✅" else "⬜") + " কপির পর চ্যাটে যাও (শুধু 📖 মোডে)",
            (if (sn) "✅" else "⬜") + " 💾 এর পর পরের চ্যাপ্টার কপি করো"
        )
        AlertDialog.Builder(this).setItems(items) { _, i ->
            when (i) {
                0 -> saveNovel()
                1 -> novelList()
                2 -> trList()
                3 -> exportAll()
                4 -> editPrompt()
                5 -> Prefs.putBool(this, "withPrompt", !wp)
                6 -> Prefs.putBool(this, "noAdblock", ab)
                7 -> {
                    toast("⏳ লিস্ট নামাচ্ছি…")
                    AdBlock.update(this) { n -> toast(if (n > 0) "✅ $n টা হোস্ট যোগ হয়েছে" else "❌ আপডেট হয়নি") }
                }
                8 -> Prefs.putBool(this, "noGoChat", gc)
                9 -> Prefs.putBool(this, "noSaveNext", sn)
            }
        }.show()
    }

    private fun saveNovel() {
        val u = novelWv.url ?: return
        val name = (novelWv.title ?: "").ifBlank { Uri.parse(u).host ?: u }
        Store.addNovel(this, Novel(name, u, u, ""))
        toast("📖 সেভ হয়েছে: $name")
    }

    private fun novelList() {
        val l = Store.novels(this)
        if (l.isEmpty()) return toast("লিস্ট খালি — মেনু থেকে নোভেল সেভ করো")
        val labels = l.map { it.name + (if (it.lastTitle.isNotEmpty()) "\n↳ " + it.lastTitle else "") }.toTypedArray()
        AlertDialog.Builder(this).setTitle("📚 নোভেল").setItems(labels) { _, i ->
            AlertDialog.Builder(this).setItems(arrayOf("খোলো (শেষ চ্যাপ্টার)", "মুছো")) { _, k ->
                if (k == 0) {
                    if (mode == Mode.CHAT) setMode(Mode.SPLIT)
                    novelWv.loadUrl(l[i].lastUrl.ifEmpty { l[i].url })
                } else {
                    Store.removeNovel(this, i)
                }
            }.show()
        }.show()
    }

    private fun trList() {
        val l = Store.list(this)
        if (l.isEmpty()) return toast("কোনো অনুবাদ সেভ নেই")
        AlertDialog.Builder(this)
            .setTitle("💾 অনুবাদ (${l.size})")
            .setItems(l.map { it.title }.toTypedArray()) { _, i -> reader(l[i]) }
            .show()
    }

    private fun reader(t: Tr) {
        val tv = TextView(this).apply {
            text = Store.read(this@MainActivity, t.id)
            textSize = 16f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setTitle(t.title)
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("কপি") { _, _ -> copy(Store.read(this, t.id)); toast("📋 কপি হয়েছে") }
            .setNegativeButton("মুছো") { _, _ -> Store.delete(this, t.id) }
            .setNeutralButton("বন্ধ", null)
            .show()
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

    private fun exportAll() {
        if (Store.list(this).isEmpty()) return toast("এক্সপোর্ট করার মতো অনুবাদ নেই")
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "translations.txt")
        }
        startActivityForResult(i, 42)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == RESULT_OK) {
            val u = data?.data ?: return
            try {
                contentResolver.openOutputStream(u)?.use { it.write(Store.exportAll(this).toByteArray()) }
                toast("✅ এক্সপোর্ট হয়েছে")
            } catch (e: Exception) {
                toast("❌ " + (e.message ?: "ব্যর্থ"))
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (activeWv.canGoBack()) activeWv.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        novelWv.destroy()
        chatWv.destroy()
        super.onDestroy()
    }
}
