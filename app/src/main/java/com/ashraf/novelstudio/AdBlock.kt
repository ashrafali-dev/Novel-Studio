package com.ashraf.novelstudio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AdBlock {
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
    private val PATTERNS = listOf("/pagead/", "/adserver/", "adsbygoogle.js", "/ads/banner")

    @Volatile private var hosts: Set<String> = builtIn.toHashSet()

    fun load(ctx: Context) {
        val s = HashSet<String>(builtIn)
        val f = File(ctx.filesDir, "blocklist.txt")
        if (f.exists()) f.forEachLine { val t = it.trim(); if (t.isNotEmpty()) s.add(t) }
        hosts = s
    }

    fun root(h: String): String = h.split('.').takeLast(2).joinToString(".")

    fun blocked(u: Uri): Boolean {
        val full = u.toString()
        if (PATTERNS.any { full.contains(it) }) return true
        var h = u.host ?: return false
        val set = hosts
        while (h.contains('.')) {
            if (set.contains(h)) return true
            h = h.substringAfter('.')
        }
        return false
    }

    fun cosmeticJs(): String {
        val css = ".adsbygoogle,ins.adsbygoogle,[id^=google_ads],[id^=div-gpt-ad],[id*=taboola],[id*=outbrain]," +
            "[class*=adsbygoogle],.ad-banner,.ad-container,.advert,.advertisement,.ads-wrapper,.ad-slot," +
            "iframe[src*=doubleclick],iframe[src*=googlesyndication],iframe[src*=adsterra],[class*=sponsored-ad]" +
            "{display:none!important}"
        return "(function(){try{var s=document.createElement('style');s.textContent='" + css +
            "';(document.head||document.documentElement).appendChild(s);}catch(e){}})();"
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
