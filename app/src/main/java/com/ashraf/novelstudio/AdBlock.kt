package com.ashraf.novelstudio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
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

    fun report(): String = synchronized(logLines) {
        "AdBlock debug mode: ${debugModeName()} (hosts=${hosts.size})\n" +
            (if (logLines.isEmpty()) "(nothing logged yet — load the page first)" else logLines.joinToString("\n"))
    }

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
        val why = blockReason(u) ?: return false
        val enforce = networkEnforce
        val ref = headers?.entries?.firstOrNull { it.key.equals("Referer", true) }?.value ?: "-"
        logEntry("${if (enforce) "BLOCK" else "WOULD-BLOCK"} [$why] type=${guessType(u, headers)} referer=$ref url=$u")
        return enforce
    }

    /** JS that reads back what the cosmetic part touched, plus how much text the page really has. */
    fun probeJs(): String = "(function(){try{" +
        "function d(e){return {t:e.tagName,id:(e.id||'').slice(0,60),c:(typeof e.className==='string'?e.className:'').slice(0,80),n:(e.textContent||'').length};}" +
        "var css=[];try{document.querySelectorAll(${org.json.JSONObject.quote(COSMETIC_SEL)}).forEach(function(e){css.push(d(e));});}catch(e){}" +
        "return JSON.stringify({log:(window.__nsAdLog||[]).slice(0,80),css:css.slice(0,80),text:document.body?document.body.innerText.length:-1,html:document.documentElement.outerHTML.length});" +
        "}catch(e){return '{}';}})()"

    fun recordProbe(pageUrl: String?, raw: String?) {
        try {
            val str = org.json.JSONTokener(raw ?: return).nextValue() as? String ?: return
            val o = org.json.JSONObject(str)
            logEntry("PAGE url=$pageUrl bodyTextLen=${o.optInt("text")} htmlLen=${o.optInt("html")}")
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
        } catch (e: Exception) {
            logEntry("PAGE probe failed: ${e.message}")
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
        "pushwoosh.com", "onesignal.com", "adskeeper.com", "adspyglass.com", "dtscout.com",
        "histats.com", "quantserve.com", "casalemedia.com", "contextweb.com", "serving-sys.com",
        "advertising.com", "adsrvr.org", "bidswitch.net", "media.net", "cpmstar.com", "adtng.com"
    )
    private val PATTERNS = listOf(
        "/pagead/", "/adserver/", "/ads/", "/ads.", "/adframe", "/adservice", "/adclick",
        "adsbygoogle.js", "googlesyndication", "doubleclick", "googleadservices",
        "popunder", "popup_ad", "/banner-ad", "/banners/", "/advert/", "/advertising/",
        "/sponsor/", "prebid", "bidder", "tracking-pixel", "analytics.js", "gtag/js",
        "collect?v=", "/beacon", "fingerprint"
    )

    @Volatile private var hosts: Set<String> = builtIn.toHashSet()

    fun load(ctx: Context) {
        val s = HashSet<String>(builtIn)
        val f = File(ctx.filesDir, "blocklist.txt")
        if (f.exists()) f.forEachLine { val t = it.trim(); if (t.isNotEmpty()) s.add(t) }
        hosts = s
        debugMode = (Prefs.get(ctx, "adblockDebugMode", "0").toIntOrNull() ?: 0).coerceIn(0, 3)
    }

    fun root(h: String): String = h.split('.').takeLast(2).joinToString(".")

    // webnovel.com serves its reader (chapter text, API, next/prev) from its own
    // domain; the broad URL patterns and cosmetic rules below break it.
    @Volatile var extraExempt: Set<String> = emptySet()   // user rules (SiteRules)

    fun exempt(host: String?): Boolean {
        val h = (host ?: return false).lowercase()
        if (h == "webnovel.com" || h.endsWith(".webnovel.com")) return true
        for (x in extraExempt) if (h == x || h.endsWith(".$x")) return true
        return false
    }

    /** Which rule matches this URL ("pattern:/ads/" or "host:example.com"), or null if none. */
    fun blockReason(u: Uri): String? {
        if (exempt(u.host)) return null
        val full = u.toString()
        PATTERNS.firstOrNull { full.contains(it) }?.let { return "pattern:$it" }
        var h = u.host ?: return null
        val set = hosts
        while (h.contains('.')) {
            if (set.contains(h)) return "host:$h"
            h = h.substringAfter('.')
        }
        return null
    }

    fun blocked(u: Uri): Boolean = blockReason(u) != null

    private const val COSMETIC_SEL = ".adsbygoogle,ins.adsbygoogle,[id^=google_ads],[id*=div-gpt-ad],[id*=taboola],[id*=outbrain]," +
        "[class*=adsbygoogle],[class*=ad-banner],[class*=ad-container],[class*=advert],[class*=advertisement]," +
        "[class*=ads-wrapper],[class*=ad-slot],[class*=popup],[class*=popunder],[id*=ad-banner],[id*=advert]," +
        "[id*=popup],[id*=popunder],[class*=sponsored],[class*=social-share],[class*=push-notification]," +
        "iframe[src*=doubleclick],iframe[src*=googlesyndication],iframe[src*=adsterra]"

    fun cosmeticJs(): String {
        val css = COSMETIC_SEL + "{display:none!important;visibility:hidden!important;pointer-events:none!important}"
        val dry = cosmeticDry
        return """(function(){
          try{
            var DRY=$dry;
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
            var kill=function(root){
              try{
                root.querySelectorAll('[class*="ad-"],[id*="ad-"],[class*="advert"],[id*="advert"],[class*="popup"],[id*="popup"],iframe[src*="doubleclick"],iframe[src*="googlesyndication"]').forEach(function(e){
                  if(e.tagName==='BODY'||e.tagName==='HTML')return;
                  if(DRY){note('would-remove',e);return;}
                  note('removed',e);
                  e.remove();
                });
              }catch(e){}
            };
            kill(document);
            if(window.__nsAdObs)window.__nsAdObs.disconnect();
            window.__nsAdObs=new MutationObserver(function(ms){
              for(var i=0;i<ms.length;i++)for(var j=0;j<ms[i].addedNodes.length;j++){
                var n=ms[i].addedNodes[j];
                if(n.nodeType===1)kill(n.parentNode||document);
              }
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
