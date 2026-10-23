package com.ashraf.novelstudio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AdBlock {
    const val TAG = "NSAdBlock"

    // ---- Debug instrumentation (does not change the default behaviour: mode 0 = full AdBlock) ----
    // 0 = full (network + cosmetic), 1 = network only, 2 = cosmetic only,
    // 3 = log only (nothing is blocked or removed, everything that WOULD be is logged)
    @Volatile var debugMode = 0
    val networkEnforce: Boolean get() = debugMode == 0 || debugMode == 1
    val cosmeticEnabled: Boolean get() = debugMode != 1
    private val cosmeticDry: Boolean get() = debugMode == 3
    private val logLines = java.util.ArrayDeque<String>()

    // ---- User rules (pasted into the app's "AdBlock Rules" box) ----
    class SiteRule(val site: String) {
        val allowUrl = ArrayList<String>()
        val blockUrl = ArrayList<String>()
        val allowHost = ArrayList<String>()
        val blockHost = ArrayList<String>()
        val hide = ArrayList<String>()
        val keep = ArrayList<String>()
        var navStrict = false
        var cosmetic = true
        var off = false
    }

    @Volatile private var siteRules: List<SiteRule> = emptyList()
    @Volatile var pageHost: String? = null   // host of the page being loaded in the reader WebView

    fun rulesText(ctx: Context): String = Prefs.get(ctx, "adblockRules", "")

    /** Saves and applies the rules. Returns how many lines were not understood. */
    fun setRules(ctx: Context, text: String): Int {
        Prefs.put(ctx, "adblockRules", text)
        return parseRules(text)
    }

    private fun parseRules(text: String): Int {
        val out = ArrayList<SiteRule>()
        var cur: SiteRule? = null
        var warn = 0
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("```")) continue
            val i = line.indexOf(':')
            if (i <= 0) { warn++; continue }
            val k = line.substring(0, i).trim().lowercase()
            val v = line.substring(i + 1).trim()
            if (v.isEmpty()) { warn++; continue }
            if (k == "site") {
                cur = SiteRule(v.lowercase().removePrefix("www."))
                out.add(cur)
                continue
            }
            val r = cur ?: SiteRule("*").also { cur = it; out.add(it) }
            when (k) {
                "allow-url" -> r.allowUrl.add(v)
                "block-url" -> r.blockUrl.add(v)
                "allow-host" -> r.allowHost.add(v.lowercase())
                "block-host" -> r.blockHost.add(v.lowercase())
                "hide" -> r.hide.add(v)
                "keep" -> r.keep.add(v)
                "nav" -> when (v.lowercase()) {
                    "block-cross" -> r.navStrict = true
                    "allow" -> r.navStrict = false
                    else -> warn++
                }
                "cosmetic" -> r.cosmetic = v.lowercase() != "off"
                "adblock" -> r.off = v.lowercase() == "off"
                else -> warn++
            }
        }
        siteRules = out
        return warn
    }

    private fun hostMatches(h: String, x: String) = h == x || h.endsWith(".$x")

    private fun rulesFor(host: String?): List<SiteRule> {
        val h = host?.lowercase()
        return siteRules.filter { it.site == "*" || (h != null && hostMatches(h, it.site)) }
    }

    /** True when this site asked for "nav: block-cross" (old strict redirect blocking). */
    fun navStrict(page: String?): Boolean = rulesFor(page).any { it.navStrict }

    fun debugModeName(): String = when (debugMode) {
        1 -> "Network only"
        2 -> "Cosmetic only"
        3 -> "Log only (no blocking)"
        else -> "Full"
    }

    fun nextDebugMode(ctx: Context) {
        debugMode = (debugMode + 1) % 4
        Prefs.put(ctx, "adblockDebugMode", debugMode.toString())
    }

    fun logEntry(line: String) {
        synchronized(logLines) {
            if (logLines.size >= 400) logLines.removeFirst()
            logLines.addLast(line)
        }
        Log.i(TAG, line)
    }

    /** Prompt + report in one piece: paste it into any chatbot and it answers with the fix. */
    fun report(): String = synchronized(logLines) {
        promptText() + "\n\n===== DEBUG REPORT =====\n" +
            "AdBlock debug mode: ${debugModeName()} (hosts=${hosts.size})\n" +
            (if (logLines.isEmpty()) "(nothing logged yet — load the page first)" else logLines.joinToString("\n"))
    }

    private fun promptText(): String = """You are an Android WebView ad-blocking expert. Analyse the DEBUG REPORT at the bottom of this message and give me a fix.

APP: Kotlin Android WebView app "NovelStudio" (AGP 8.2.2, Kotlin 1.9.22, minSdk 24). It has its own small AdBlock (object AdBlock in AdBlock.kt). Third-party ad-block libraries are NOT allowed.
PROBLEM: with AdBlock ON some web pages come up blank or broken; with AdBlock OFF the same page loads fine.

HOW THE ADBLOCK WORKS NOW
1. Network (WebViewClient.shouldInterceptRequest, sub-resources only, not the main frame): requests to the SAME registrable domain as the top-level page are never blocked by the generic rules. A THIRD-PARTY request is blocked when its host (or any parent domain) is in a list of ${hosts.size} ad/tracker hosts, or - for non-image/font/css URLs only - when the full URL contains one of: ${PATTERNS.joinToString(" ")}. A blocked request gets a neutral stand-in (stub script / empty css / 1x1 gif / empty html / 204), not an error.
2. Navigation gate (shouldOverrideUrlLoading, main frame, only when there is NO user gesture): the navigation is cancelled only if blockReason(url) matches a rule above. Other cross-domain redirects are allowed and only logged.
3. Cosmetic (JS run in onPageFinished): injects CSS that hides high-confidence ad markup, and HIDES (never removes) elements whose class/id TOKEN starts with one of ${FUZZY_TOKENS.joinToString(" ")}; elements holding real content (a reader container, 2+ pictures, long text, taller than 1.6 screens) are skipped. A MutationObserver does the same for later nodes. The full selector list: ${COSMETIC_SEL}.
3b. Self-healing: if the page looks blank/ad-wall AND the blocker acted, the app reloads once with lighter blocking (host list only, no cosmetic) and remembers it, then once more with the blocker off for that site.
4. Debug modes (the first line of the report shows the current one): Full, Network only, Cosmetic only, Log only (nothing is blocked or removed, everything that WOULD be is logged).

HOW TO READ THE REPORT
BLOCK / WOULD-BLOCK [rule] type= referer= url=  -> a sub-resource request matched a rule (type is only a guess)
NAV gesture= redirect= url=  -> a main-frame navigation; NAV CANCELLED [rule] / NAV allowed -> what the gate did
PAGE url= bodyTextLen= htmlLen=  -> taken 2.5 s after load; bodyTextLen=0 means the page has no visible text
HTML-START  -> first 900 characters of the page's HTML
COSMETIC-removed / COSMETIC-would-remove / CSS-HIDDEN  -> elements the cosmetic part removed or hid

HOW MY FIX IS APPLIED
I will NOT edit code. The app has a text box called "AdBlock Rules". Your answer must contain ONE code block with rules in exactly the format below; I paste it into that box and press Save.

FORMAT (one rule per line; a line starting with # is a comment; a "site:" line starts a block and the lines under it apply to that site and its subdomains; "site: *" means all sites)
site: example.com
allow-url: <text>     never block a request whose full URL contains this text
block-url: <text>     also block a request whose full URL contains this text
allow-host: <host>    never block requests to this host or its subdomains
block-host: <host>    also block requests to this host or its subdomains
nav: allow            main-frame redirects without a user tap are allowed (default)
nav: block-cross      cancel redirects to another domain without a user tap
cosmetic: off         do not hide or remove any element on this site
hide: <css selector>  also hide elements matching this selector
keep: <css selector>  never hide or remove elements matching (or inside) this selector
adblock: off          switch AdBlock off completely for this site (last resort)

RULES FOR YOUR ANSWER
- Decide from the evidence in the report. Quote the exact report lines that prove the cause. If the report is not enough to prove it, say exactly which debug mode or extra information I should collect -- do not guess.
- Use the narrowest rule that fixes the problem: prefer allow-url / allow-host / keep over cosmetic: off, and cosmetic: off over adblock: off. Use adblock: off only if nothing else can work.
- Use only the keys above. If the problem cannot be fixed with these rules (it needs a code change), say so clearly and describe the change in words instead of inventing keys.
- Output: (1) the cause with its proof, (2) the rules in one code block, (3) one line about side effects. Reply in Bengali, but keep the rules block exactly in the format above. Do not claim it works without testing."""

    fun clearLog() = synchronized(logLines) { logLines.clear() }

    private fun guessType(u: Uri, h: Map<String, String>?): String {
        val path = (u.path ?: "").lowercase()
        val ext = path.substringAfterLast('.', "")
        val accept = (h?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value ?: "").lowercase()
        val xhr = h?.entries?.any { it.key.equals("X-Requested-With", true) } == true
        return when {
            ext == "js" || ext == "mjs" -> "script"
            ext == "css" -> "stylesheet"
            ext in setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif") -> "image"
            ext in setOf("woff", "woff2", "ttf", "otf") -> "font"
            ext in setOf("mp4", "webm", "m3u8", "mp3") -> "media"
            ext == "json" -> "json/xhr"
            xhr -> "xhr"
            accept.contains("text/html") -> "document/iframe"
            accept.contains("application/json") -> "json/xhr"
            accept.contains("image/") -> "image"
            accept.contains("text/css") -> "stylesheet"
            else -> "other"
        }
    }

    /**
     * Single entry point for the network side. Returns true when the request must be blocked.
     * The decision is identical to blocked(); in addition every match is logged with the rule,
     * the guessed resource type and the referer, so the offending filter can be proven.
     */
    fun shouldBlockRequest(u: Uri, headers: Map<String, String>?): Boolean {
        val ref = headers?.entries?.firstOrNull { it.key.equals("Referer", true) }?.value ?: "-"
        // First party = the TOP-LEVEL page. The Referer of an ad iframe's own sub-requests is the ad iframe, which
        // would make every one of them look "first party".
        val page = pageHost ?: (if (ref != "-") Uri.parse(ref).host else null)
        val why = blockReason(u, page) ?: return false
        val enforce = networkEnforce
        if (enforce) blockedThisPage++
        logEntry("${if (enforce) "BLOCK" else "WOULD-BLOCK"} [$why] type=${guessType(u, headers)} referer=$ref url=$u")
        return enforce
    }

    /** Requests the blocker stopped on the current page (reset on every navigation); used by the health check. */
    @Volatile var blockedThisPage = 0

    private val EMPTY_GIF: ByteArray by lazy { android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", android.util.Base64.DEFAULT) }

    /**
     * What a blocked request gets back. An empty "text/plain" for everything (the old behaviour) made the page's
     * OWN code crash: a missing `googletag` / `gtag` / `adsbygoogle` throws, and one uncaught error can stop the
     * lazy-loader that puts the manga panels on screen. Like the "redirect to a neutral resource" of desktop
     * blockers, scripts now get harmless stubs and every other type an empty body of the matching type.
     */
    fun blockedResponse(u: Uri, headers: Map<String, String>?): WebResourceResponse {
        val path = (u.path ?: "").lowercase()
        val full = u.toString().lowercase()
        val isScript = guessType(u, headers) == "script" || path.contains(".js") || full.contains("gtag/js") || full.contains("gtm.js")
        return when {
            isScript -> WebResourceResponse("application/javascript", "utf-8", ByteArrayInputStream(STUB_JS.toByteArray()))
            else -> when (guessType(u, headers)) {
                "stylesheet" -> WebResourceResponse("text/css", "utf-8", ByteArrayInputStream(ByteArray(0)))
                "image" -> WebResourceResponse("image/gif", null, ByteArrayInputStream(EMPTY_GIF))
                "document/iframe" -> WebResourceResponse("text/html", "utf-8", ByteArrayInputStream("<!doctype html><title></title>".toByteArray()))
                "json/xhr", "xhr" -> WebResourceResponse("application/json", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                else -> WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
        }
    }

    /** Neutral stand-ins for the globals ad/analytics scripts define, so the site's own code keeps running. */
    private const val STUB_JS = """(function(){try{var w=window;
w.canRunAds=true;w.adsLoaded=true;
w.adsbygoogle=w.adsbygoogle||[];
w.dataLayer=w.dataLayer||[];
w.gtag=w.gtag||function(){};w.ga=w.ga||function(){};w.fbq=w.fbq||function(){};
var q=(w.googletag&&w.googletag.cmd&&w.googletag.cmd.length!==undefined)?w.googletag.cmd:[];
function chain(o,names){names.forEach(function(k){o[k]=function(){return o;};});return o;}
var g=w.googletag||{};
g.apiReady=true;
g.pubads=function(){return chain({},['addEventListener','setTargeting','collapseEmptyDivs','enableSingleRequest','disableInitialLoad','refresh','enableLazyLoad','setPrivacySettings','set','clear','updateCorrelator']);};
g.defineSlot=function(){return chain({},['addService','setTargeting','defineSizeMapping','setCollapseEmptyDiv','updateTargetingFromMap']);};
g.defineOutOfPageSlot=function(){return null;};
g.enableServices=function(){};g.display=function(){};g.destroySlots=function(){return true;};
g.sizeMapping=function(){var b={};return chain(b,['addSize']),b.build=function(){return [];},b;};
g.cmd={push:function(f){try{if(typeof f==='function')f();}catch(e){}return 1;}};
w.googletag=g;
for(var i=0;i<q.length;i++){try{if(typeof q[i]==='function')q[i]();}catch(e){}}
}catch(e){}})();"""

    /**
     * Collect page links and already-requested resources for optional AI classification.
     * Only URLs and short labels are returned; page text and cookies are not collected.
     */
    fun pageLinkScanJs(): String = """
        (function(){
          try {
            var out=[], seen=new Set();
            function add(raw,kind,label){
              if(!raw || out.length>=220) return;
              var u;
              try { u=new URL(raw,location.href); } catch(e) { return; }
              if(u.protocol!=='http:' && u.protocol!=='https:') return;
              u.hash='';
              // Avoid sending authentication/session material to the chatbot.
              try {
                var sensitive=/(token|auth|session|password|passwd|secret|api.?key|access.?key|email|username|userid|reset|signature)/i, remove=[];
                u.searchParams.forEach(function(v,k){if(sensitive.test(k))remove.push(k);});
                remove.forEach(function(k){u.searchParams.delete(k);});
              } catch(e) {}
              var key=u.href;
              if(seen.has(key)) return;
              seen.add(key);
              out.push({url:u.href,host:u.hostname.toLowerCase(),kind:kind,
                text:String(label||'').replace(/\s+/g,' ').slice(0,60)});
            }
            var nodes=[].slice.call(document.querySelectorAll('a[href],area[href],iframe[src],frame[src],script[src],link[href],form[action]'));
            function rank(e){var t=(e.tagName||'').toLowerCase();return (t==='iframe'||t==='frame')?0:t==='script'?1:t==='form'?2:t==='link'?3:4;}
            nodes.sort(function(a,b){return rank(a)-rank(b);});
            for(var i=0;i<nodes.length && out.length<220;i++){
              var e=nodes[i], tag=(e.tagName||'').toLowerCase();
              var raw=e.href || e.src || e.action || e.getAttribute('href') || e.getAttribute('src') || e.getAttribute('action');
              var label=(e.innerText||e.getAttribute('aria-label')||e.title||'').trim();
              add(raw,tag,label);
            }
            try {
              var entries=performance.getEntriesByType('resource')||[];
              for(var j=0;j<entries.length && out.length<220;j++) add(entries[j].name,'resource','');
            } catch(e) {}
            return JSON.stringify({page:location.href,pageHost:location.hostname.toLowerCase(),total:seen.size,links:out});
          } catch(e) { return JSON.stringify({error:String(e)}); }
        })()
    """.trimIndent()

    private val aiNeverBlockHosts = setOf(
        "google.com", "googleapis.com", "gstatic.com", "googleusercontent.com",
        "cloudflare.com", "cloudflareinsights.com", "jsdelivr.net", "unpkg.com",
        "cdnjs.cloudflare.com", "bootstrapcdn.com", "w3.org", "mozilla.org",
        "microsoft.com", "apple.com", "github.com", "githubusercontent.com",
        "stripe.com", "paypal.com", "auth0.com", "firebaseapp.com", "web.app",
        "amazonaws.com", "youtube.com", "youtube-nocookie.com", "facebook.com",
        "facebook.net", "tiktok.com", "x.com", "twitter.com", "reddit.com",
        "wikipedia.org", "gravatar.com", "wordpress.com", "wp.com", "imgur.com",
        "discord.com", "discordapp.com", "sentry.io", "recaptcha.net", "hcaptcha.com",
        // Tag Manager is often a site dependency; do not let AI page scans accidentally block it.
        "googletagmanager.com", "tagmanager.google.com"
    )
    // Public suffixes can appear if a model guesses a naive two-label root (e.g. co.uk).
    // Reject these exact values, but do not exempt every real subdomain under them.
    private val publicSuffixGuards = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "edu.au",
        "co.nz", "com.br", "com.cn", "com.hk", "com.sg", "com.my", "co.in", "firm.in",
        "net.in", "org.in", "co.jp", "co.kr", "com.mx", "com.tr", "com.tw", "com.ar",
        "com.pl", "com.ua", "com.es", "com.fr", "co.za", "com.sa", "com.eg", "com.bd",
        "com.pk", "co.id", "com.ph", "com.vn", "com.ng"
    )

    /** Persist AI suggestions only when their hosts appeared in the scan. */
    fun addAiBlockedHosts(
        ctx: Context,
        suggestions: Collection<String>,
        candidates: Set<String>,
        pageHost: String?
    ): Int {
        val validHost = Regex("^[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$")
        val ipV4 = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")
        val page = (pageHost ?: "").trim('.').lowercase()
        val pageRoot = if (page.isNotBlank() && page.contains('.')) root(page) else ""
        val candidateHosts = candidates.map { it.trim('.').lowercase() }.filter { it.isNotBlank() }.toSet()
        val accepted = linkedSetOf<String>()
        for (raw in suggestions) {
            val h = raw.trim().trim('.').lowercase()
            if (!validHost.matches(h) || !h.contains('.') || ipV4.matches(h) || h.contains("..")) continue
            if (h in publicSuffixGuards) continue
            if (candidateHosts.none { it == h || it.endsWith(".$h") }) continue
            if (page.isNotBlank() && (h == page || h.endsWith(".$page") || page.endsWith(".$h"))) continue
            if (pageRoot.isNotBlank() && root(h) == pageRoot) continue
            if (aiNeverBlockHosts.any { hostMatches(h, it) }) continue
            // The AI must not add a domain that is already covered by any active list.
            if (hosts.any { hostMatches(h, it) }) continue
            accepted.add(h)
        }
        if (accepted.isEmpty()) return 0
        val file = File(ctx.applicationContext.filesDir, "ai-blocklist.txt")
        val existing = if (file.exists()) file.readLines().map { it.trim().lowercase() }.filter { it.isNotBlank() }.toMutableSet() else mutableSetOf()
        val before = existing.size
        existing.addAll(accepted)
        if (existing.size == before) return 0
        file.writeText(existing.sorted().joinToString("\n"))
        load(ctx.applicationContext)
        return existing.size - before
    }

    /** Current persisted AI additions, used to report exactly what changed after a scan. */
    fun aiBlockedHosts(ctx: Context): Set<String> {
        val file = File(ctx.applicationContext.filesDir, "ai-blocklist.txt")
        if (!file.exists()) return emptySet()
        return try { file.readLines().map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet() }
        catch (_: Exception) { emptySet() }
    }

    /** JS that reads back what the cosmetic part touched, plus how much text the page really has. */
    fun probeJs(): String = "(function(){try{" +
        "function d(e){return {t:e.tagName,id:(e.id||'').slice(0,60),c:(typeof e.className==='string'?e.className:'').slice(0,80),n:(e.textContent||'').length};}" +
        "var css=[];try{document.querySelectorAll(${org.json.JSONObject.quote(COSMETIC_SEL)}).forEach(function(e){css.push(d(e));});}catch(e){}" +
        "var imgs=0;try{imgs=Array.prototype.filter.call(document.images,function(i){return i.naturalWidth>=150&&i.naturalHeight>=100;}).length;}catch(e){}" +
        "var tx=document.body?document.body.innerText:'';" +
        "var ab=(tx.length<4000&&/(disable|turn off|pause|whitelist|allow).{0,50}ad ?block|ad ?block(er)?.{0,40}(detected|enabled|is on)/i.test(tx.slice(0,1500)))?1:0;" +
        "return JSON.stringify({log:(window.__nsAdLog||[]).slice(0,80),css:css.slice(0,80),text:tx.length,imgs:imgs,ab:ab,html:document.documentElement.outerHTML.length,snip:document.documentElement.outerHTML.slice(0,900)});" +
        "}catch(e){return '{}';}})()"

    /**
     * Logs the probe and decides whether the page looks BROKEN BY THE BLOCKER, so ordinary users never have to
     * paste rules. Broken = (almost no text and no real picture, or an "disable your ad blocker" wall) AND the
     * blocker actually blocked/hid something here. Verdict: 0 = fine, 1 = reload with lighter blocking (host list
     * only, no cosmetic) and remember that for the site, 2 = still broken: switch the blocker off for the site.
     * At most two automatic reloads per site per app run.
     */
    fun recordProbe(ctx: Context, pageUrl: String?, raw: String?): Int {
        try {
            val str = org.json.JSONTokener(raw ?: return 0).nextValue() as? String ?: return 0
            val o = org.json.JSONObject(str)
            logEntry("PAGE url=$pageUrl bodyTextLen=${o.optInt("text")} imgs=${o.optInt("imgs")} adWall=${o.optInt("ab")} htmlLen=${o.optInt("html")} blocked=$blockedThisPage")
            logEntry("HTML-START ${o.optString("snip").replace("\n", " ")}")
            val log = o.optJSONArray("log")
            if (log != null) for (i in 0 until log.length()) {
                val e = log.getJSONObject(i)
                logEntry("COSMETIC-${e.optString("k")} <${e.optString("t")} id='${e.optString("id")}' class='${e.optString("c")}'> textLen=${e.optInt("n")}")
            }
            val css = o.optJSONArray("css")
            if (css != null) for (i in 0 until css.length()) {
                val e = css.getJSONObject(i)
                logEntry("CSS-HIDDEN <${e.optString("t")} id='${e.optString("id")}' class='${e.optString("c")}'> textLen=${e.optInt("n")}")
            }
            val hidden = (0 until (log?.length() ?: 0)).count { log!!.getJSONObject(it).optString("k") == "hidden" } + (css?.length() ?: 0)
            val touched = blockedThisPage + hidden
            val broken = (o.optInt("text") < 40 && o.optInt("imgs") == 0 && o.optInt("html") > 0) || o.optInt("ab") == 1
            if (!broken || touched <= 0 || networkEnforce.not()) return 0
            val host = Uri.parse(pageUrl ?: return 0).host ?: return 0
            val key = registrable(host)
            val level = synchronized(healLevel) { healLevel[key] ?: 0 }
            if (level >= 2) return 0
            synchronized(healLevel) { healLevel[key] = level + 1 }
            if (level == 0) {
                relaxHosts = relaxHosts + key
                Prefs.put(ctx, "adblockRelaxHosts", relaxHosts.joinToString(","))
            } else {
                autoOffHosts = autoOffHosts + key
                Prefs.put(ctx, "adblockAutoOffHosts", autoOffHosts.joinToString(","))
            }
            logEntry("AUTO-HEAL level=${level + 1} host=$key (page looked broken, blocked=$blockedThisPage hidden=$hidden)")
            return level + 1
        } catch (e: Exception) {
            logEntry("PAGE probe failed: ${e.message}")
            return 0
        }
    }

    private val builtIn = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "google-analytics.com", "adnxs.com", "taboola.com", "outbrain.com", "popads.net", "popcash.net",
        "propellerads.com", "exoclick.com", "juicyads.com", "trafficjunky.com", "adsterra.com",
        "mgid.com", "revcontent.com", "pubmatic.com", "rubiconproject.com", "openx.net", "criteo.com",
        "criteo.net", "amazon-adsystem.com", "scorecardresearch.com", "hilltopads.net", "admaven.com",
        "ad-maven.com", "clickadu.com", "richads.com", "onclickads.net", "monetag.com", "a-ads.com",
        "bidvertiser.com", "adsafeprotected.com", "moatads.com", "adform.net", "smartadserver.com",
        "yieldmo.com", "connatix.com", "teads.tv", "sharethrough.com", "33across.com", "lijit.com",
        "sovrn.com", "zedo.com", "yllix.com", "ero-advertising.com", "tsyndicate.com", "adcash.com",
        "pushwoosh.com", "onesignal.com", "ropsumemu.com", "adskeeper.com", "adspyglass.com", "dtscout.com",
        "histats.com", "quantserve.com", "casalemedia.com", "contextweb.com", "serving-sys.com",
        "advertising.com", "adsrvr.org", "bidswitch.net", "media.net", "cpmstar.com", "adtng.com"
    )
    private val PATTERNS = listOf(
        "/pagead/", "/adserver/", "/ads/", "/ads.", "/adframe", "/adservice", "/adclick",
        "adsbygoogle.js", "googlesyndication", "doubleclick", "googleadservices",
        "popunder", "popup_ad", "/banner-ad", "/banners/", "/advert/", "/advertising/",
        "/sponsor/", "prebid", "tracking-pixel", "analytics.js", "gtag/js"
        // removed: "bidder", "collect?v=", "/beacon", "fingerprint" - they match legitimate resources
        // (Cloudflare's challenge/beacon scripts, bot-check libraries, site APIs).
    )
    private val STATIC_EXT = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp",
        "woff", "woff2", "ttf", "otf", "css", "mp4", "webm", "m3u8", "mp3"
    )

    /** Registrable domain, public-suffix aware for the common 2-level suffixes (co.uk, com.bd, ...). */
    fun registrable(h: String): String {
        val parts = h.lowercase().trim('.').split('.')
        if (parts.size <= 2) return parts.joinToString(".")
        val last2 = parts.takeLast(2).joinToString(".")
        return if (last2 in publicSuffixGuards) parts.takeLast(3).joinToString(".") else last2
    }

    private fun thirdParty(reqHost: String?, page: String?): Boolean {
        if (reqHost == null || page == null) return false   // unknown: never guess "third party"
        return registrable(reqHost) != registrable(page)
    }

    @Volatile private var hosts: Set<String> = builtIn.toHashSet()

    fun load(ctx: Context) {
        val s = HashSet<String>(builtIn)
        val f = File(ctx.filesDir, "blocklist.txt")
        if (f.exists()) f.forEachLine { val t = it.trim().lowercase(); if (t.isNotEmpty()) s.add(t) }
        // Keep AI-discovered hosts separate so a normal filter refresh cannot erase them.
        val ai = File(ctx.filesDir, "ai-blocklist.txt")
        if (ai.exists()) ai.forEachLine { val t = it.trim().lowercase(); if (t.isNotEmpty()) s.add(t) }
        hosts = s
        relaxHosts = Prefs.get(ctx, "adblockRelaxHosts", "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        autoOffHosts = Prefs.get(ctx, "adblockAutoOffHosts", "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        parseRules(Prefs.get(ctx, "adblockRules", ""))
        debugMode = (Prefs.get(ctx, "adblockDebugMode", "0").toIntOrNull() ?: 0).coerceIn(0, 3)
    }

    fun root(h: String): String = h.split('.').takeLast(2).joinToString(".")

    // webnovel.com serves its reader (chapter text, API, next/prev) from its own
    // domain; the broad URL patterns and cosmetic rules below break it.
    @Volatile var extraExempt: Set<String> = emptySet()   // user rules (SiteRules)

    @Volatile private var relaxHosts: Set<String> = emptySet()     // auto-learned: hosts-list only, no cosmetic
    @Volatile private var autoOffHosts: Set<String> = emptySet()   // auto-learned: blocker fully off
    private val healLevel = HashMap<String, Int>()

    fun isRelaxed(page: String?): Boolean {
        val h = page?.lowercase() ?: return false
        return relaxHosts.any { hostMatches(h, it) }
    }

    /** Page host OR request host is exempt (a "no ads" site setting must also cover the CDNs that page uses). */
    fun exempt(host: String?, page: String?): Boolean = exempt(host) || exempt(page)

    fun exempt(host: String?): Boolean {
        val h = (host ?: return false).lowercase()
        if (autoOffHosts.any { hostMatches(h, it) }) return true
        if (h == "webnovel.com" || h.endsWith(".webnovel.com")) return true
        // Tapas is a JavaScript-heavy SPA; generic ad filters can block URLs needed for episode rendering.
        // Exempt its pages and subresources from network and cosmetic filtering to avoid reader stalls.
        if (h == "tapas.io" || h.endsWith(".tapas.io")) return true
        for (x in extraExempt) if (h == x || h.endsWith(".$x")) return true
        if (siteRules.any { it.off && it.site != "*" && hostMatches(h, it.site) }) return true
        return false
    }

    /** Which rule matches this URL ("pattern:/ads/" or "host:example.com"), or null if none. */
    fun blockReason(u: Uri, page: String? = pageHost): String? {
        if (exempt(u.host, page)) return null
        val full = u.toString()
        if (siteRules.isNotEmpty()) {
            val rs = rulesFor(page)
            val rh = (u.host ?: "").lowercase()
            if (rs.any { it.off }) return null
            if (rs.any { r -> r.allowUrl.any { full.contains(it) } || r.allowHost.any { hostMatches(rh, it) } }) return null
            for (r in rs) {
                r.blockUrl.firstOrNull { full.contains(it) }?.let { return "rule:block-url:$it" }
                r.blockHost.firstOrNull { hostMatches(rh, it) }?.let { return "rule:block-host:$it" }
            }
        }
        val reqHost = u.host
        // Like desktop blockers: the site's OWN requests (same registrable domain as the top-level page) are never
        // blocked by generic rules. Ads live on other domains; blocking first-party URLs is what broke readers.
        val third = thirdParty(reqHost, page)
        val relaxed = isRelaxed(page)
        val ext = (u.path ?: "").substringAfterLast('.', "").lowercase()
        // Panels, fonts and stylesheets are never judged by a URL SUBSTRING (only by the ad-host list below).
        if (third && !relaxed && ext !in STATIC_EXT) {
            PATTERNS.firstOrNull { full.contains(it) }?.let { return "pattern:$it" }
        }
        if (page != null && !third) return null
        var h = reqHost ?: return null
        val set = hosts
        while (h.contains('.')) {
            if (set.contains(h)) return "host:$h"
            h = h.substringAfter('.')
        }
        return null
    }

    fun blocked(u: Uri): Boolean = blockReason(u) != null

    // High-confidence ad markup: hidden with plain CSS.
    private const val HARD_SEL = ".adsbygoogle,ins.adsbygoogle,[id^=google_ads],[id*=div-gpt-ad],[id*=taboola],[id*=outbrain]," +
        "[class*=adsbygoogle],iframe[src*=doubleclick],iframe[src*=googlesyndication],iframe[src*=adsterra]"

    // Fuzzy names. They match only at the START of a class/id token ("ad-banner", "advert-box"), never in the
    // middle of a word: the old [class*="ad-"] also matched "re*ad-*container", "lo*ad-*more", "down*load-*", "he*ad-*"
    // and, because those elements were REMOVED, deleted the whole reader on Madara-theme manga sites.
    private val FUZZY_TOKENS = listOf(
        "ad-", "ads-", "ad_", "ads_", "advert", "banner-ad", "sponsored", "popup-ad", "popunder", "push-notification"
    )
    private val FUZZY_SEL: String = FUZZY_TOKENS.joinToString(",") { "[class^=\"$it\"],[class*=\" $it\"],[id^=\"$it\"]" }
    private val COSMETIC_SEL: String get() = "$HARD_SEL,$FUZZY_SEL"

    // An element that holds the page's real content is never hidden, whatever its class is called.
    private const val CONTENT_GUARD = "article,main,[role=main],#content,#main,.reading-content,.read-container,.chapter-content," +
        ".entry-content,#chapter-content,#readerarea,.reader-area,.chapter-container,.wp-manga-chapter-img,.page-break"

    private const val OFF_JS = "(function(){try{var s=document.getElementById('__nsAdStyle');if(s)s.remove();if(window.__nsAdObs)window.__nsAdObs.disconnect();}catch(e){}})();"

    fun cosmeticJs(page: String? = pageHost): String {
        val rs = if (siteRules.isEmpty()) emptyList() else rulesFor(page)
        if (rs.any { !it.cosmetic || it.off } || isRelaxed(page)) return OFF_JS
        val keep = rs.flatMap { it.keep }.joinToString(",")
        val hard = if (keep.isEmpty()) HARD_SEL else HARD_SEL.split(",").joinToString(",") { "$it:not($keep)" }
        val extra = rs.flatMap { it.hide }
        val sel = if (extra.isNotEmpty()) hard + "," + extra.joinToString(",") else hard
        val css = sel + "{display:none!important;visibility:hidden!important;pointer-events:none!important}"
        val dry = cosmeticDry
        return """(function(){
          try{
            var DRY=$dry;
            var KEEP=${org.json.JSONObject.quote(keep)};
            var FUZZY=${org.json.JSONObject.quote(FUZZY_SEL)};
            var GUARD=${org.json.JSONObject.quote(CONTENT_GUARD)};
            window.__nsAdLog=window.__nsAdLog||[];
            var note=function(k,e){
              try{
                if(window.__nsAdLog.length<200)window.__nsAdLog.push({k:k,t:e.tagName,id:(e.id||'').slice(0,60),c:(typeof e.className==='string'?e.className:'').slice(0,80),n:(e.textContent||'').length});
              }catch(x){}
            };
            var css=${org.json.JSONObject.quote(css)};
            var s=document.getElementById('__nsAdStyle');
            if(!DRY){
              if(!s){s=document.createElement('style');s.id='__nsAdStyle';(document.head||document.documentElement).appendChild(s);}
              s.textContent=css;
            }
            // Real content is never hidden: it holds the reader / article, several pictures, lots of text or is
            // taller than a screen and a half. Ads are none of those.
            var risky=function(e){
              try{
                if(e.querySelector(GUARD))return true;
                if(e.querySelectorAll('img,picture,video,canvas').length>=2)return true;
                if((e.innerText||'').length>600)return true;
                var r=e.getBoundingClientRect();
                if(r.height>(window.innerHeight||800)*1.6)return true;
              }catch(x){}
              return false;
            };
            var hide=function(e){
              try{
                if(!e||e.nodeType!==1||e.tagName==='BODY'||e.tagName==='HTML'||e.getAttribute('data-ns-ad'))return;
                if(KEEP&&(e.matches(KEEP)||e.closest(KEEP)))return;
                if(risky(e)){note('kept-content',e);return;}
                if(DRY){note('would-hide',e);return;}
                // hidden, never removed: the page's own scripts may still hold a reference to this node
                e.setAttribute('data-ns-ad','1');
                e.style.setProperty('display','none','important');
                note('hidden',e);
              }catch(x){}
            };
            var scan=function(root){
              try{
                if(root.matches&&root.matches(FUZZY))hide(root);
                if(root.querySelectorAll)root.querySelectorAll(FUZZY).forEach(hide);
              }catch(x){}
            };
            scan(document);
            if(window.__nsAdObs)window.__nsAdObs.disconnect();
            var queue=[],timer=0;
            window.__nsAdObs=new MutationObserver(function(ms){
              for(var i=0;i<ms.length;i++)for(var j=0;j<ms[i].addedNodes.length;j++){
                var n=ms[i].addedNodes[j];
                if(n.nodeType===1)queue.push(n);
              }
              if(!timer&&queue.length){timer=setTimeout(function(){timer=0;var q=queue;queue=[];for(var k=0;k<q.length&&k<300;k++)scan(q[k]);},150);}
            });
            window.__nsAdObs.observe(document.documentElement,{childList:true,subtree:true});
          }catch(e){}
        })();"""
    }

    // Downloads a bigger public ad-server hosts list and merges it with the built-in one
    fun update(ctx: Context, cb: (Int) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            var n = 0
            try {
                val conn = URL("https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                val lines = conn.inputStream.bufferedReader().readLines()
                val out = ArrayList<String>()
                for (l in lines) {
                    val t = l.trim()
                    if (t.isEmpty() || t.startsWith("#")) continue
                    val h = t.split(Regex("\\s+")).last()
                    if (h.contains('.')) out.add(h)
                }
                File(app.filesDir, "blocklist.txt").writeText(out.joinToString("\n"))
                n = out.size
            } catch (e: Exception) {
            }
            if (n > 0) load(app)
            Handler(Looper.getMainLooper()).post { cb(n) }
        }.start()
    }
}
