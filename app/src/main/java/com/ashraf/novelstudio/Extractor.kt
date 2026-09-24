package com.ashraf.novelstudio

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

data class Chapter(
    val title: String,
    val text: String,
    val next: String?,
    val prev: String?,
    val url: String,
    val novel: String,
    val number: String,
    val contentSel: String
)

object Extractor {
    private const val BAD = "script,style,noscript,iframe,nav,header,footer,aside,form,button,svg,.ads,.ad,[class*=advert],[id*=advert],[class*=comment],[id*=comment],[class*=sidebar]"

    private val SELECTORS = listOf(
        "#chapter-content", ".chapter-content", ".chapter_content", "#chr-content", ".chr-c",
        ".reading-content", ".text-left", "#content", ".entry-content", ".cha-content", ".cha-words",
        ".chapter-body", ".novel_content", ".j_readContent", ".txt", "#chaptercontent", ".chapter-c",
        "#article", ".article-content", ".content", "article"
    )

    private val NEXT = Regex("next|下一|다음|次の|次へ|次章|পরবর্তী|নেক্সট|›|»|→", RegexOption.IGNORE_CASE)
    private val PREV = Regex("prev(?!iew)|上一|이전|前の|前へ|前章|পূর্ববর্তী|আগের|প্রিভিয়াস|‹|«|←", RegexOption.IGNORE_CASE)
    private val CH_RE = Regex("chapter|chap\\.|\\bch\\b|episode|\\bep\\b|第.{1,8}[章话話节節回]|제\\s*\\d+\\s*화|\\bpart\\b|\\bvol", RegexOption.IGNORE_CASE)
    private val GENERIC = setOf("novel", "novels", "book", "books", "library", "browse", "home", "series", "manga", "genres", "ranking", "latest")

    // ------------------------------------------------------------ text
    private fun textOf(el: Element): String {
        val c = el.clone()
        c.children().select(BAD).remove()
        for (br in c.select("br")) br.after(TextNode("\n"))
        val ps = c.select("p")
        val t = if (ps.size >= 3) {
            ps.map { it.text().trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
        } else {
            c.wholeText()
        }
        return t.replace(Regex("[ \t\u00a0]+"), " ")
            .replace(Regex(" ?\n ?"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun score(el: Element): Int {
        var s = 0
        for (n in el.childNodes()) {
            if (n is TextNode) s += n.text().trim().length
            else if (n is Element && n.tagName() == "p") s += n.text().length
        }
        return s
    }

    private fun findContent(doc: Document): Element? {
        for (sel in SELECTORS) {
            val e = doc.selectFirst(sel)
            if (e != null && textOf(e).length > 500) return e
        }
        var best: Element? = null
        var bs = 300
        for (e in doc.select("div,article,section,main")) {
            val s = score(e)
            if (s > bs) { bs = s; best = e }
        }
        return best
    }

    private fun findTitle(doc: Document): String {
        for (sel in listOf(".chapter-title", ".chr-title", "#chapter-heading", "h1", "h2")) {
            val t = doc.selectFirst(sel)?.text()?.trim() ?: ""
            if (t.isNotEmpty() && t.length < 200) return t
        }
        return doc.title().trim()
    }

    // ------------------------------------------------------------ story name / chapter number
    private fun findNovel(doc: Document, url: String, title: String): String {
        for (p in listOf("meta[property=og:novel:book_name]", "meta[property=og:novel:novel_name]", "meta[name=book_name]")) {
            val v = doc.selectFirst(p)?.attr("content")?.trim() ?: ""
            if (v.isNotEmpty()) return v
        }
        val host = hostOf(url).removePrefix("www.")
        val siteWord = host.substringBefore('.').lowercase()
        val parts = doc.title().split(Regex("\\s[-|–—»:]+\\s|\\s*_\\s*|\\s*\\|\\s*"))
            .map { it.trim() }.filter { it.length in 2..90 }
        for (p in parts) {
            if (CH_RE.containsMatchIn(p) || p == title) continue
            if (siteWord.isNotEmpty() && p.lowercase().replace(" ", "").contains(siteWord)) continue
            return p
        }
        for (a in doc.select("a[href]")) {
            val h = a.absUrl("href").lowercase()
            val t = a.text().trim()
            if ((h.contains("/book/") || h.contains("/novel/") || h.contains("/series/")) &&
                t.length in 3..80 && !CH_RE.containsMatchIn(t) && t.lowercase() !in GENERIC && h != url.lowercase()) return t
        }
        return host
    }

    private fun findNumber(title: String, url: String): String {
        val pats = listOf(
            Regex("\\b(?:chapter|chap|ch|episode|ep)\\.?\\s*[-#:.]?\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE),
            Regex("第\\s*(\\d+)\\s*[章话話节節回]"),
            Regex("제\\s*(\\d+)\\s*화"),
            Regex("(\\d+)\\s*[화話话]")
        )
        for (p in pats) {
            val m = p.find(title)
            if (m != null) return m.groupValues[1]
        }
        val m2 = Regex("(\\d{1,5})").find(title)
        if (m2 != null) return m2.groupValues[1]
        val m3 = Regex("(\\d{1,5})(?!.*\\d)").find(pathOf(url))
        return if (m3 != null && m3.value.length <= 5) m3.value else ""
    }

    // ------------------------------------------------------------ links
    private fun strip(u: String) = u.substringBefore('#')

    private fun hostOf(u: String): String {
        val i = u.indexOf("://")
        if (i < 0) return ""
        return u.substring(i + 3).substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
    }

    private fun pathOf(u: String): String {
        val i = u.indexOf("://")
        if (i < 0) return ""
        val rest = u.substring(i + 3)
        val s = rest.indexOf('/')
        if (s < 0) return ""
        return rest.substring(s).substringBefore('?').substringBefore('#')
    }

    private fun segs(u: String) = pathOf(u).split('/').count { it.isNotEmpty() }
    private fun hostRoot(u: String) = hostOf(u).split('.').takeLast(2).joinToString(".")

    // A "next chapter" link must stay on the same site and must not jump UP to a home/book page
    private fun plausible(cur: String, cand: String): Boolean {
        if (hostRoot(cur) != hostRoot(cand)) return false
        val c = segs(cand)
        return c >= 1 && c >= segs(cur)
    }

    private fun findLink(doc: Document, url: String, kind: String): String? {
        val re = if (kind == "next") NEXT else PREV
        val rel = doc.selectFirst("link[rel=$kind], a[rel=$kind]")
        if (rel != null) {
            val h = rel.absUrl("href")
            if (h.isNotEmpty() && strip(h) != strip(url) && plausible(url, h)) return h
        }
        var best: String? = null
        var bs = 0
        for (a in doc.select("a[href]")) {
            val h = a.absUrl("href")
            if (!h.startsWith("http") || strip(h) == strip(url) || !plausible(url, h)) continue
            if (a.className().contains("disabled", true) || a.attr("aria-disabled") == "true") continue
            val txt = a.text().trim()
            val meta = listOf(a.attr("aria-label"), a.attr("title"), a.id(), a.className(), a.attr("rel")).joinToString(" ")
            var s = 0
            if (txt.length <= 30 && re.containsMatchIn(txt)) s += 3
            if (re.containsMatchIn(meta)) s += 2
            if (s > bs) { bs = s; best = h }
        }
        return best
    }

    // Last resort only (short chapter numbers only — never long IDs like webnovel's)
    fun bump(url: String, d: Int): String? {
        val q = url.indexOfAny(charArrayOf('?', '#'))
        val base = if (q < 0) url else url.substring(0, q)
        val rest = if (q < 0) "" else url.substring(q)
        val hostEnd = base.indexOf('/', 8)
        if (hostEnd < 0) return null
        val head = base.substring(0, hostEnd)
        val path = base.substring(hostEnd)
        val m = Regex("(\\d+)(?!.*\\d)").find(path) ?: return null
        if (m.value.length > 6) return null
        val n = m.value.toLong() + d
        if (n < 0) return null
        val nv = n.toString().padStart(m.value.length, '0')
        return head + path.replaceRange(m.range, nv) + rest
    }

    fun extract(doc: Document, url: String): Chapter? {
        val el = findContent(doc) ?: return null
        val body = textOf(el)
        val title = findTitle(doc)
        val next = findLink(doc, url, "next")
        val prev = findLink(doc, url, "prev")
        val text = if (body.startsWith(title)) body else title + "\n\n" + body
        val sel = try { el.cssSelector() } catch (e: Exception) { "" }
        return Chapter(title, text, next, prev, url, findNovel(doc, url, title), findNumber(title, url), sel)
    }
}
