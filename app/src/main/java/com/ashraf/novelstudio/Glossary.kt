package com.ashraf.novelstudio

import android.content.Context
import java.io.File

/**
 * Local glossary. File: filesDir/glossary.txt, one entry per line:
 *   alias1 | alias2 | 中文 | 日本語 => বাংলা      (also accepts "=" instead of "=>")
 * Later lines override earlier ones for the same alias.
 * Matching uses Aho-Corasick, so scanning a chapter takes a few milliseconds.
 */
object Glossary {
    private class Node {
        val next = HashMap<Char, Node>(2)
        var fail: Node? = null
        var out: IntArray? = null      // alias indexes ending at this node
    }

    private class Built(val root: Node, val aliases: Array<String>, val entryOf: IntArray, val bn: Array<String>)

    @Volatile private var built: Built? = null
    @Volatile private var loading = false
    @Volatile var count = 0
        private set

    private fun file(c: Context) = File(c.filesDir, "glossary.txt")

    fun read(c: Context): String = try { if (file(c).exists()) file(c).readText() else "" } catch (e: Exception) { "" }

    fun write(c: Context, text: String) {
        try { file(c).writeText(text) } catch (e: Exception) { }
        reload(c)
    }

    fun append(c: Context, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val old = read(c)
        write(c, (if (old.isEmpty() || old.endsWith("\n")) old else old + "\n") + t + "\n")
    }

    /** Call once at startup and after every change. Builds off the main thread. */
    fun reload(c: Context) {
        val app = c.applicationContext
        loading = true
        Thread {
            try { built = build(read(app)) } catch (e: Exception) { built = null; count = 0 }
            loading = false
        }.start()
    }

    private fun build(text: String): Built {
        // alias(lowercase) -> entry index; later lines win
        val bnList = ArrayList<String>()
        val map = LinkedHashMap<String, Int>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            var i = line.indexOf("=>")
            var skip = 2
            if (i < 0) { i = line.indexOf('='); skip = 1 }
            if (i <= 0) continue
            val bn = line.substring(i + skip).trim()
            if (bn.isEmpty()) continue
            val aliases = line.substring(0, i).split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (aliases.isEmpty()) continue
            val e = bnList.size
            bnList.add(bn)
            for (a in aliases) map[a.lowercase()] = e
        }
        count = bnList.size
        val al = map.keys.toTypedArray()
        val eo = IntArray(al.size) { map[al[it]]!! }
        val root = Node()
        for ((k, a) in al.withIndex()) {
            var n = root
            for (ch in a) n = n.next.getOrPut(ch) { Node() }
            n.out = (n.out ?: IntArray(0)) + k
        }
        // failure links (BFS)
        val q = ArrayDeque<Node>()
        for (n in root.next.values) { n.fail = root; q.addLast(n) }
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            for ((ch, m) in n.next) {
                var f = n.fail
                while (f != null && !f.next.containsKey(ch)) f = f.fail
                m.fail = f?.next?.get(ch) ?: root
                val fo = m.fail?.out
                if (fo != null) m.out = (m.out ?: IntArray(0)) + fo
                q.addLast(m)
            }
        }
        return Built(root, al, eo, bnList.toTypedArray())
    }

    private fun isAsciiAlnum(c: Char) = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '_'

    /** Entries that occur in [text], as "as-written-in-text → বাংলা" lines. Longest match wins. */
    fun matches(text: String): List<String> {
        val b = built ?: return emptyList()
        if (b.aliases.isEmpty() || text.isEmpty()) return emptyList()
        val low = text.lowercase()
        // lowercase() can change length for a few exotic chars; then skip to stay safe
        if (low.length != text.length) return emptyList()

        // collect (start, end, aliasIdx) with word-boundary check for latin edges
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        val ids = ArrayList<Int>()
        var n = b.root
        for (i in low.indices) {
            val ch = low[i]
            while (n !== b.root && !n.next.containsKey(ch)) n = n.fail ?: b.root
            n = n.next[ch] ?: b.root
            val o = n.out ?: continue
            for (k in o) {
                val a = b.aliases[k]
                val s = i - a.length + 1
                if (s < 0) continue
                if (isAsciiAlnum(a.first()) && s > 0 && isAsciiAlnum(low[s - 1])) continue
                if (isAsciiAlnum(a.last()) && i + 1 < low.length && isAsciiAlnum(low[i + 1])) continue
                starts.add(s); ends.add(i + 1); ids.add(k)
            }
        }
        if (ids.isEmpty()) return emptyList()

        // longest first, no overlaps
        val order = ids.indices.sortedWith(compareBy({ -(ends[it] - starts[it]) }, { starts[it] }))
        val used = BooleanArray(low.length)
        val seenEntry = LinkedHashMap<Int, String>()    // entry -> surface text
        val picked = ArrayList<Int>()
        for (j in order) {
            var free = true
            for (p in starts[j] until ends[j]) if (used[p]) { free = false; break }
            if (!free) continue
            for (p in starts[j] until ends[j]) used[p] = true
            picked.add(j)
        }
        picked.sortBy { starts[it] }                   // keep order of appearance
        for (j in picked) {
            val e = b.entryOf[ids[j]]
            if (!seenEntry.containsKey(e)) seenEntry[e] = text.substring(starts[j], ends[j])
        }
        return seenEntry.map { (e, surface) -> surface + " → " + b.bn[e] }
    }

    /** Ready-to-append block for the prompt, or "" when nothing matched. */
    fun block(text: String): String {
        val m = matches(text)
        if (m.isEmpty()) return ""
        return "গ্লোসারি — নিচের শব্দগুলো অনুবাদে অবশ্যই এই বাংলাই ব্যবহার করবে:\n" + m.joinToString("\n")
    }
}
