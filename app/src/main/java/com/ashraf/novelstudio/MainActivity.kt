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
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
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
    private lateinit var autoBtn: TextView

    private var mode = Mode.SPLIT
    private var autoCopy = false
    private var polling = false
    private var lastChapter: Chapter? = null
    private var lastCopied = ""
    private var exportNovel: String? = null
    private var watchActive = false
    private var watchTries = 0
    private var watchStable = 0
    private var watchLast = ""
    private val handler = Handler(Looper.getMainLooper())

    @Volatile var pendingBridgeReply: String? = null

    private val DARK_ON = "(function(){var id='__nsdark';if(document.getElementById(id))return;var s=document.createElement('style');s.id=id;" +
        "s.textContent='html{filter:invert(1) hue-rotate(180deg)!important;background:#fff}img,video,picture,canvas{filter:invert(1) hue-rotate(180deg)!important}';" +
        "(document.head||document.documentElement).appendChild(s);})();"
    private val DARK_OFF = "(function(){var e=document.getElementById('__nsdark');if(e)e.remove();})();"

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
        chatWv.webChromeClient = WebChromeClient()
        chatWv.addJavascriptInterface(ReplyBridge(this), "NsBridge")
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
        val reloadBtn = TextView(this).apply {
            text = "⟳"
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { reloadPage() }
            setOnLongClickListener { novelWv.reload(); chatWv.reload(); toast("🔄 দুটোই রিলোড হচ্ছে"); true }
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
    }

    // ⟳ : reload the pane you are looking at (long press = reload both)
    private fun reloadPage() {
        val wv = if (mode == Mode.NOVEL) novelWv else chatWv
        wv.reload()
        toast(if (wv === chatWv) "🔄 চ্যাটবট রিলোড হচ্ছে…" else "🔄 নোভেল পেজ রিলোড হচ্ছে…")
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
        s.cacheMode = WebSettings.LOAD_NO_CACHE
        wv.setBackgroundColor(0xFF111114.toInt())
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
        }
    }

    // ------------------------------------------------------------------ dark mode (0 off, 1 auto, 2 force)
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
        novelWv.evaluateJavascript(if (d == 2) DARK_ON else DARK_OFF, null)   // "force" = CSS invert, novel site only
    }

    // ------------------------------------------------------------------ modes
    private fun setMode(m: Mode) {
        mode = m
        val nlp = novelBox.layoutParams as LinearLayout.LayoutParams
        val clp = chatBox.layoutParams as LinearLayout.LayoutParams
        when (m) {
            Mode.NOVEL -> {
                nlp.height = 0; nlp.weight = 1f; novelBox.visibility = View.VISIBLE
                clp.height = 0; clp.weight = 0.001f
                chatBox.visibility = View.INVISIBLE
            }
            Mode.CHAT -> {
                nlp.height = 0; nlp.weight = 0.001f
                novelBox.visibility = View.INVISIBLE
                clp.height = 0; clp.weight = 1f
                chatBox.visibility = View.VISIBLE
            }
            Mode.SPLIT -> {
                nlp.height = 0; nlp.weight = 1f; novelBox.visibility = View.VISIBLE
                clp.height = 0; clp.weight = 1f; chatBox.visibility = View.VISIBLE
            }
        }
        novelBox.layoutParams = nlp
        chatBox.layoutParams = clp
        modeBtn.text = when (m) { Mode.NOVEL -> "📖"; Mode.CHAT -> "💬"; Mode.SPLIT -> "◫" }
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
        AlertDialog.Builder(this)            .setTitle("চ্যাটবট যোগ করো")
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
            if (darkMode() == 2) view?.evaluateJavascript(DARK_ON, null)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (Prefs.adblock(this@MainActivity)) view?.evaluateJavascript(AdBlock.cosmeticJs(), null)
            if (darkMode() == 2) view?.evaluateJavascript(DARK_ON, null)
            if (url != null) Prefs.put(this@MainActivity, "lastNovelUrl", url)
            if (url != null && Prefs.bool(this@MainActivity, "autoReplace")) {
                val tr = savedTrFor(url)
                if (tr != null) handler.postDelayed({ runReplace(Store.read(this@MainActivity, tr.id), true) }, 1200)
            }
            if (autoCopy && !polling) {
                polling = true
                handler.postDelayed({ pollExtract(0) }, 800)
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

    private inner class ChatClient : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {}
        override fun onPageFinished(view: WebView?, url: String?) { view?.evaluateJavascript(STREAM_OBSERVER_JS, null) }
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
        Store.touchBookmark(this, ch)
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("novel", text))
        lastCopied = text
    }

    // copy to clipboard (always) + optional auto paste / send inside the chatbot page
    private fun deliver(full: String, label: String) {
        copy(full)
        toast("📋 কপি হয়েছে: $label (${full.length} অক্ষর)")
        if (Prefs.bool(this, "autoPaste")) {
            val send = Prefs.bool(this, "autoSend")
            handler.postDelayed({ pasteToChat(full, send) }, 500)
        }
    }

    private fun copyChapter(ch: Chapter) {
        val p = Prefs.prompt(this)
        val full = if (Prefs.bool(this, "withPrompt") && p.isNotBlank()) p + "\n\n---\n\n" + ch.text else ch.text
        val tag = (if (ch.number.isNotEmpty()) "Ch ${ch.number} — " else "") + ch.title
        deliver(full, tag)
    }

    // ●  : copy the chapter on the novel page right now
    private fun extractCopy() {
        extractNow { ch ->
            if (ch == null) checkCf("❌ এই পেজে চ্যাপ্টারের লেখা পাওয়া যায়নি")
            else { onChapter(ch); copyChapter(ch) }
        }
    }

    // ▶ / ◀ : next/prev chapter, then copy. Screen mode is NOT changed (works while chatbot is full screen)
    private fun step(dir: String) {
        toast("⏳ " + (if (dir == "next") "পরের" else "আগের") + " চ্যাপ্টার আনছি…")
        extractNow { cur ->
            val base = cur ?: lastChapter
            if (base == null) {
                toast("❌ আগে নোভেলের একটা চ্যাপ্টার পেজ খোলো")
                return@extractNow
            }
            if (cur != null) onChapter(cur)
            val target = if (dir == "next") base.next else base.prev
            if (target != null) {
                loadAndCopy(target)
            } else {
                clickAndWait(dir, base)
            }
        }
    }

    private fun loadAndCopy(url: String) {
        autoCopy = true
        polling = false
        novelWv.loadUrl(url)
        handler.postDelayed({ autoCopy = false; polling = false }, 60000)
    }

    // No usable link in the page (JS "Next" button, e.g. webnovel.com): click the site's own button
    private fun clickAndWait(dir: String, base: Chapter) {
        novelWv.evaluateJavascript(clickJs(dir)) { res ->
            if (res != null && res.contains("none")) {
                val g = Extractor.bump(base.url, if (dir == "next") 1 else -1)
                if (g != null) {
                    toast("⚠️ বাটন পাইনি — URL নম্বর দিয়ে অনুমান করছি")
                    loadAndCopy(g)
                } else {
                    toast("❌ নেক্সট/প্রিভ বাটন পাওয়া যায়নি — নোভেল ভিউতে নিজে পরের চ্যাপ্টারে গিয়ে ● চাপো")
                }
            } else {
                waitChange(base.text.hashCode(), 0)
            }
        }
    }

    private fun waitChange(oldHash: Int, n: Int) {
        handler.postDelayed({
            extractNow { ch ->
                if (ch != null && ch.text.length > 300 && ch.text.hashCode() != oldHash && novelWv.progress >= 100) {
                    onChapter(ch)
                    copyChapter(ch)
                } else if (n < 10) {
                    waitChange(oldHash, n + 1)
                } else {
                    toast("❌ বাটন চেপেছি কিন্তু নতুন চ্যাপ্টার আসেনি — চ্যাপ্টার লক/লগইন লাগতে পারে")
                }
            }
        }, 1500)
    }

    private fun clickJs(dir: String): String {
        val alts = if (dir == "next")
            "next|next chapter|next ›|next »|›|»|→|下一章|下一页|下一话|下一節|다음|다음화|次へ|次の話|পরবর্তী|নেক্সট"
        else
            "prev|previous|prev chapter|previous chapter|‹|«|←|上一章|上一页|上一话|이전|이전화|前へ|前の話|আগের|পূর্ববর্তী"
        val word = if (dir == "next") "next" else "prev(?!iew)"
        val js = """
(function(){
  var re=new RegExp('^('+'__ALTS__'+')$','i');
  var wre=new RegExp('__WORD__','i');
  var els=[].slice.call(document.querySelectorAll('a,button,[role=button],div,span,li,i'));
  var best=null,bs=0;
  for(var i=0;i<els.length;i++){
    var e=els[i];
    var tc=(e.textContent||'').trim();
    if(tc.length>25) continue;
    var cn=(typeof e.className==='string')?e.className:'';
    var meta=(e.getAttribute('aria-label')||'')+' '+(e.getAttribute('title')||'')+' '+cn+' '+(e.id||'')+' '+(e.getAttribute('data-eventname')||'');
    var s=0;
    if(re.test(tc)) s+=5; else if(tc.length<=20&&wre.test(tc)) s+=3;
    if(wre.test(meta)) s+=2;
    if(s===0) continue;
    if(/disabled/i.test(cn)||e.disabled||e.getAttribute('aria-disabled')==='true') continue;
    var r=e.getBoundingClientRect(); if(r.width<3||r.height<3) continue;
    if(/chap/i.test(meta+tc)) s+=1;
    if(s>bs){bs=s;best=e;}
  }
  if(!best) return 'none';
  try{ best.click(); }catch(x){}
  return 'clicked';
})()
"""
        return js.replace("__ALTS__", alts).replace("__WORD__", word)
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

    // ------------------------------------------------------------------ auto paste / send into chatbot
    private fun pasteToChat(text: String, send: Boolean) {
        val js = """
(function(text, send){
  var sleep=function(ms){return new Promise(function(r){setTimeout(r,ms)})};
  var sels=['#prompt-textarea','textarea[placeholder*="message" i]','textarea[placeholder*="prompt" i]','textarea','div[contenteditable="true"]','div[contenteditable="plaintext-only"]','[role="textbox"]','[contenteditable]'];
  function enabled(e){return !!e&&!e.disabled&&e.getAttribute('aria-disabled')!=='true'}
  function box(){
    for(var i=0;i<sels.length;i++){
      var a=[].slice.call(document.querySelectorAll(sels[i])).filter(enabled);
      if(a.length)return a[a.length-1];
    }
    return null;
  }
  function put(e){
    try{e.focus()}catch(x){}
    if(e.tagName==='TEXTAREA'||e.tagName==='INPUT'){
      try{
        var p=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
        var d=Object.getOwnPropertyDescriptor(p,'value');
        if(d&&d.set)d.set.call(e,text);else e.value=text;
        e.dispatchEvent(new Event('input',{bubbles:true}));
        e.dispatchEvent(new Event('change',{bubbles:true}));
        if((e.value||'')===text)return true;
      }catch(x){}
    }
    try{
      var r=document.createRange(),q=window.getSelection();
      r.selectNodeContents(e);q.removeAllRanges();q.addRange(r);
      document.execCommand('insertText',false,text);
      e.dispatchEvent(new Event('input',{bubbles:true}));
      if((e.innerText||e.textContent||'').trim())return true;
    }catch(x){}
    try{
      e.textContent=text;
      e.dispatchEvent(new Event('input',{bubbles:true}));
      return true;
    }catch(x){return false}
  }
  function sendBtn(){
    var ss=['button[data-testid="send-button"]','button[data-testid*="send" i]','button[aria-label*="Send" i]','button[aria-label*="Submit" i]','button[title*="Send" i]','button[type="submit"]'];
    for(var i=0;i<ss.length;i++){
      var a=document.querySelectorAll(ss[i]);
      for(var j=a.length-1;j>=0;j--){
        var b=a[j];
        if(enabled(b))return b;
      }
    }
    return null;
  }
  async function run(){
    var e=box();if(!e)return 'nobox';
    if(!put(e))return 'pastefail';
    await sleep(700);
    if(!send)return 'pasted';
    var b=sendBtn();
    if(b){try{b.click();return 'sent'}catch(x){}}
    try{
      e.focus();
      e.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));
      e.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));
      return 'sent';
    }catch(x){}
    return 'pasted';
  }
  return run();
})(__TEXT__,__SEND__)
""".trimIndent().replace("__TEXT__", JSONObject.quote(text)).replace("__SEND__", send.toString())
        chatWv.evaluateJavascript(js) { r ->
            val result = r ?: ""
            when {
                result.contains("sent") -> {
                    toast("📨 চ্যাপ্টা পাঠানো হয়েছে — AI উত্তর অটো-ওয়াচ করছি")
                    startWatchReply(text)
                }
                result.contains("pasted") -> toast("📋 চ্যাটবটে পেস্ট হয়েছে — Send নিজে চাপো")
                result.contains("nobox") -> toast("❌ চ্যাটবটের ইনপুট বক্স পাওয়া যায়নি")
                else -> toast("❌ অটো-পেস্ট ব্যর্থ: $result")
            }
        }
    }

    // ------------------------------------------------------------------ watch chatbot reply
    private fun startWatchReply(sentText: String) {
        val tail=sentText.replace(Regex("\\s+")," ").trim().takeLast(120)
        watchTries=0; watchStable=0; watchLast=""; watchActive=true; pendingBridgeReply=null
        chatWv.evaluateJavascript(STREAM_OBSERVER_JS,null)
        pollChatReply(tail)
    }
    private val STREAM_OBSERVER_JS = """
(function(){
  window.__nsGetLatestReply=function(tail){
    var n=function(s){return(s||'').replace(/\\s+/g,' ').trim()},k=n(tail),o=[];
    var sels=[
      '[data-message-author-role="assistant"]',
      '[data-author="assistant"]',
      '[data-author-role="assistant"]',
      '[data-testid*="assistant" i]',
      '[class*="assistant" i]',
      '[class*="response" i]',
      '[class*="markdown" i]'
    ];
    for(var i=0;i<sels.length;i++){
      var a=document.querySelectorAll(sels[i]);
      for(var j=a.length-1;j>=0;j--){
        var t=n(a[j].innerText||a[j].textContent);
        if(t.length>80&&(!k||t.indexOf(k)<0))o.push(t);
      }
    }
    if(o.length)return o[o.length-1];

    var turns=document.querySelectorAll('article[data-testid*="conversation-turn"],[data-message-id],[data-testid*="conversation-turn"]');
    for(var i=turns.length-1;i>=0;i--){
      var e=turns[i],t=n(e.innerText||e.textContent);
      var m=n((e.getAttribute('data-message-author-role')||'')+' '+(e.getAttribute('data-author')||'')+' '+(e.getAttribute('aria-label')||'')+' '+(e.className||''));
      if(t.length>80&&(!k||t.indexOf(k)<0)&&/assistant|model|bot|response/i.test(m))return t;
    }

    if(k){
      var body=n(document.body.innerText),p=body.lastIndexOf(k);
      if(p>=0){
        var z=body.substring(p+k.length).trim().replace(/^[:\\-–—]+/,'').trim();
        if(z.length>80)return z.slice(0,12000);
      }
    }

    var all=document.querySelectorAll('div,p');
    for(var i=all.length-1;i>=0;i--){
      var t=n(all[i].innerText||all[i].textContent);
      if(t.length>120&&t.length<30000&&(!k||t.indexOf(k)<0)&&/\\S/.test(t))return t;
    }
    return '';
  };
  return 'installed';
})();
""".trimIndent()

    private fun pollChatReply(tail:String){
        if(!watchActive)return
        handler.postDelayed({
            if(!watchActive)return@postDelayed
            val js="(function(t){try{return JSON.stringify(window.__nsGetLatestReply?window.__nsGetLatestReply(t):'')}catch(e){return JSON.stringify('')}})("+JSONObject.quote(tail)+")"
            chatWv.evaluateJavascript(js){raw->
                var resp=""
                try{resp=JSONArray("[$raw]").getString(0)}catch(_:Exception){}
                if(watchActive)onWatchTick(tail,resp)
            }
        },1800)
    }
    private fun onWatchTick(tail:String,resp:String){
        if(resp.length>120&&resp==watchLast)watchStable++else{watchStable=0;watchLast=resp}
        if(watchStable>=2){
            watchActive=false
            if(Prefs.bool(this,"noAutoSite"))toast("⚠️ অটো সাইট রিপ্লেস বন্ধ আছে — Menu থেকে এটি চালু করো") else translationArrived(resp)
            return
        }
        watchTries++
        if(watchTries>100){watchActive=false;toast("⌛ AI-এর উত্তর অটো ধরা যায়নি — Copy/💾 দিয়ে ম্যানুয়ালি সেভ করো");return}
        pollChatReply(tail)
    }
    private fun copyAutoReply(text:String){
        val cm=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("translation",text))
        toast("📋 AI-এর উত্তর অটো-কপি হয়েছে")
    }
    private fun translationArrived(text:String){
        copyAutoReply(text)
        val tr=Store.save(this,lastChapter,text)
        toast("✅ AI উত্তর পাওয়া গেছে — সাইটে বসানো হচ্ছে: "+tr.label())
        runReplace(text,true)
    }
    class ReplyBridge(private val activity:MainActivity){
        @JavascriptInterface fun complete(text:String){
            activity.runOnUiThread{val t=text.trim();if(t.length>=120&&activity.watchActive)activity.pendingBridgeReply=t}
        }
    }

    // ------------------------------------------------------------------ site replace
    private val REPLACE_SELS = "#chapter-content,.chapter-content,.chapter_content,#chr-content,.chr-c,.reading-content,.text-left,#content,.entry-content,.cha-content,.cha-words,.chapter-body,.novel_content,.j_readContent,#chaptercontent,.chapter-c,#article,.article-content,.content,article,#chp,#chapter,.chapter,.text,.chapter-text,#chapter-text,.reading-area"

    private fun replaceJs(translation: String): String {
        return """
(function(){
  var S='__SELS__'.split(','),el=null;
  for(var i=0;i<S.length;i++){var e=document.querySelector(S[i]);if(e&&(e.innerText||'').trim().length>200){el=e;break;}}
  if(!el){var ds=document.querySelectorAll('div,article,section,main'),bs=200;for(var j=0;j<ds.length;j++){var d=ds[j],t=(d.innerText||'').trim();if(t.length>bs){bs=t.length;el=d;}}}
  if(!el)return 'noel';
  if(!window.__nsOrig)window.__nsOrig=el.innerHTML;
  window.__nsText=__T__;
  var tr=el.querySelector('.ns-tr');
  if(!tr){tr=document.createElement('div');tr.className='ns-tr';tr.style.cssText='white-space:pre-wrap';el.innerHTML='';el.appendChild(tr);try{window.scrollTo(0,0);}catch(x){}}
  tr.textContent=window.__nsText;
  if(!document.getElementById('nsTgl')){
    var b=document.createElement('div');b.id='nsTgl';b.textContent='🌐';
    b.style.cssText='position:fixed;bottom:14px;right:14px;z-index:2147483647;background:#3A3A44;color:#fff;padding:10px 13px;border-radius:22px;font-size:14px;opacity:.75;box-shadow:0 2px 8px rgba(0,0,0,.4)';
    var showing=true;
    b.onclick=function(){if(showing){el.innerHTML=window.__nsOrig;showing=false;b.textContent='🌐';}else{el.innerHTML='';var d2=document.createElement('div');d2.className='ns-tr';d2.style.cssText='white-space:pre-wrap';d2.textContent=window.__nsText;el.appendChild(d2);showing=true;b.textContent='📖';}};
    if(document.body)document.body.appendChild(b);
  }
  return 'ok';
})()""".replace("__SELS__", REPLACE_SELS).replace("__T__", JSONObject.quote(translation))
    }

    private fun savedTrFor(url: String): Tr? {
        val key = url.substringBefore('#')
        return Store.list(this).firstOrNull { it.url.substringBefore('#') == key }
    }

    private fun runReplace(text: String, retry: Boolean) {
        novelWv.evaluateJavascript(replaceJs(text)) { r ->
            if (retry && (r == null || !r.contains("ok"))) {
                handler.postDelayed({ runReplace(text, false) }, 2500)
            }
        }
    }

    private fun checkCf(failMsg: String) {
        novelWv.evaluateJavascript(
            "(function(){var h=document.documentElement?document.documentElement.outerHTML:'';return /cf-challenge|__cf_chl_|cf-browser-verification|challenge-platform|Attention Required|cf-error-details|cf-please-wait/.test(h)?'cf':'no';})()"
        ) { r ->
            if (r != null && r.contains("cf")) toast("🛡 Cloudflare চ্যালেঞ্জ — পেজে ক্যাপচা সলভ করো, তারপর আবার চাপো")
            else toast(failMsg)
        }
    }

    private fun toggleAuto() {
        val on = !(Prefs.bool(this, "autoPaste") && Prefs.bool(this, "autoSend"))
        Prefs.putBool(this, "autoPaste", on)
        Prefs.putBool(this, "autoSend", on)
        refreshAutoBtn()
        toast(if (on) "⚡ অটো পেস্ট + সেন্ড চালু" else "⚡ অটো বন্ধ — শুধু কপি হবে")
    }

    private fun refreshAutoBtn() {
        val on = Prefs.bool(this, "autoPaste") && Prefs.bool(this, "autoSend")
        autoBtn.alpha = if (on) 1f else 0.35f
    }

    // ------------------------------------------------------------------ save translation (offline library)
    // AI reply is auto-copied and auto-replaced; 💾 remains available for manual fallback.
    private fun saveAnswer() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: ""
        if (t.length < 30) return toast("❌ ক্লিপবোর্ডে অনুবাদ নেই — আগে চ্যাটবটের উত্তরের Copy বাটন চাপো")
        if (t == lastCopied.trim()) return toast("❌ এটা তো সোর্স টেক্সট — চ্যাটবটের উত্তরের Copy বাটন চাপো")
        val tr = Store.save(this, lastChapter, t)
        toast("💾 লাইব্রেরিতে সেভ: ${tr.novel} — ${tr.label()}")
        if (Prefs.bool(this, "replaceOnSave")) runReplace(t, true)
        if (!Prefs.bool(this, "noSaveNext")) step("next")
    }

    // ------------------------------------------------------------------ menu
    private fun menu() {
        val ab = Prefs.adblock(this)
        val wp = Prefs.bool(this, "withPrompt")
        val gc = !Prefs.bool(this, "noGoChat")
        val sn = !Prefs.bool(this, "noSaveNext")
        val ro = Prefs.bool(this, "replaceOnSave")
        val ar = Prefs.bool(this, "autoReplace")
        val na = Prefs.bool(this, "noAutoSite")
        val ap = Prefs.bool(this, "autoPaste")
        val asd = Prefs.bool(this, "autoSend")
        val d = darkMode()
        val items = arrayOf(
            "📚 লাইব্রেরি (অফলাইনে পড়ো)","⬇️ সব অনুবাদ txt এক্সপোর্ট","🔖 এই পেজ বুকমার্ক করো","🔖 বুকমার্ক লিস্ট","📝 প্রম্পট এডিট",
            "🌙 ডার্ক মোড: " + arrayOf("বন্ধ", "অটো", "ফোর্স")[d] + "  (ট্যাপ করলে বদলায়)",
            (if (ap) "✅" else "⬜") + " অটো পেস্ট (চ্যাটবট বক্সে)",
            (if (asd) "✅" else "⬜") + " অটো সেন্ড",
            (if (wp) "✅" else "⬜") + " কপির সাথে প্রম্পট জুড়ে দাও",
            (if (sn) "✅" else "⬜") + " 💾 এর পর পরের চ্যাপ্টার কপি করো",
            (if (ro) "✅" else "⬜") + " 💾 এর পর সাইটেই অনুবাদ বসাও",
            (if (ar) "✅" else "⬜") + " পেজ খুললেই সেভ করা অনুবাদ অটো বসাও",
            (if (na) "⬜" else "✅") + " অনুবাদ এলেই সাইটে অটো বসাও (⚡ চালু থাকলে)",
            (if (gc) "✅" else "⬜") + " ● চাপার পর চ্যাটে যাও (শুধু 📖 মোডে)",
            (if (ab) "✅" else "⬜") + " Ad Block","🔄 Ad Block লিস্ট আপডেট","🔄 নোভেল পেজ রিলোড","🔄 চ্যাটবট রিলোড"
        )
        AlertDialog.Builder(this).setItems(items) { _, i ->
            when (i) {
                0 -> libraryNovels(); 1 -> exportAll(null); 2 -> saveBookmark(); 3 -> bookmarkList(); 4 -> editPrompt()
                5 -> { Prefs.put(this, "dark", ((d + 1) % 3).toString()); applyDark() }
                6 -> { Prefs.putBool(this, "autoPaste", !ap); refreshAutoBtn() }
                7 -> { Prefs.putBool(this, "autoSend", !asd); refreshAutoBtn() }
                8 -> Prefs.putBool(this, "withPrompt", !wp); 9 -> Prefs.putBool(this, "noSaveNext", sn)
                10 -> Prefs.putBool(this, "replaceOnSave", !ro); 11 -> Prefs.putBool(this, "autoReplace", !ar)
                12 -> Prefs.putBool(this, "noAutoSite", !na); 13 -> Prefs.putBool(this, "noGoChat", gc)
                14 -> Prefs.putBool(this, "noAdblock", ab)
                15 -> { toast("⏳ লিস্ট নামাচ্ছি…"); AdBlock.update(this) { n -> toast(if (n > 0) "✅ $n টা হোস্ট যোগ হয়েছে" else "❌ আপডেট হয়নি") } }
                16 -> novelWv.reload(); 17 -> chatWv.reload()
            }
        }.show()
    }

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
        if (names.isEmpty()) return toast("লাইব্রেরি খালি — অনুবাদের Copy করে 💾 চাপলে এখানে জমবে")
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