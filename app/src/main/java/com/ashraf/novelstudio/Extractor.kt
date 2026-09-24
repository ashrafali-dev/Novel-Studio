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
    val guessed: Boolean
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
    private val PREV = Regex("prev(ious)?\\b|上一|이전|前の|前へ|前章|পূর্ববর্তী|আগের|প্রিভিয়াস|‹|«|←", RegexOption.IGNORE_CASE)

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

    private fun strip(u: String) = u.substringBefore('#')

    private fun findLink(doc: Document, url: String, kind: String): String? {
        val re = if (kind == "next") NEXT else PREV
        val rel = doc.selectFirst("link[rel=$kind], a[rel=$kind]")
        if (rel != null) {
            val h = rel.absUrl("href")
            if (h.isNotEmpty() && strip(h) != strip(url)) return h
        }
        var best: String? = null
        var bs = 0
        for (a in doc.select("a[href]")) {
            val h = a.absUrl("href")
            if (!h.startsWith("http") || strip(h) == strip(url)) continue
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

    // Last resort: bump the last number in the URL path (chapter-12 -> chapter-13)
    private fun bump(url: String, d: Int): String? {
        val q = url.indexOfAny(charArrayOf('?', '#'))
        val base = if (q < 0) url else url.substring(0, q)
        val rest = if (q < 0) "" else url.substring(q)
        val hostEnd = base.indexOf('/', 8)
        if (hostEnd < 0) return null
        val head = base.substring(0, hostEnd)
        val path = base.substring(hostEnd)
        val m = Regex("(\\d+)(?!.*\\d)").find(path) ?: return null
        val n = m.value.toLong() + d
        if (n < 0) return null
        val nv = n.toString().padStart(m.value.length, '0')
        return head + path.replaceRange(m.range, nv) + rest
    }

    fun extract(doc: Document, url: String): Chapter? {
        val el = findContent(doc) ?: return null
        val body = textOf(el)
        val title = findTitle(doc)
        var next = findLink(doc, url, "next")
        var prev = findLink(doc, url, "prev")
        var guessed = false
        if (next == null) { next = bump(url, 1); if (next != null) guessed = true }
        if (prev == null) { prev = bump(url, -1); if (prev != null) guessed = true }
        val text = if (body.startsWith(title)) body else title + "\n\n" + body
        return Chapter(title, text, next, prev, url, guessed)
    }
}
