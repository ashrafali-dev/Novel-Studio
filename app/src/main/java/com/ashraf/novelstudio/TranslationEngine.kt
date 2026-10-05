package com.ashraf.novelstudio

import org.json.JSONArray
import org.json.JSONObject

/**
 * One-shot translation protocol.
 *
 * A chapter is sent to the chatbot exactly once. Each source DOM text block gets
 * a stable marker such as [001]. The chatbot must keep those markers, so the
 * reply can be written back to the exact source elements without guessing
 * paragraph boundaries.
 */
object TranslationEngine {
    data class Segment(
        val id: String,
        val text: String,
        val selector: String = ""
    )

    fun segments(ch: Chapter): List<Segment> {
        if (ch.segmentsJson.isNotBlank()) {
            try {
                val a = JSONArray(ch.segmentsJson)
                val out = ArrayList<Segment>(a.length())
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val id = o.optString("id", "").trim()
                    val text = o.optString("text", "").trim()
                    if (id.isNotEmpty() && text.isNotEmpty()) {
                        out += Segment(id, text, o.optString("selector", ""))
                    }
                }
                if (out.isNotEmpty()) return out
            } catch (_: Exception) {}
        }

        return ch.text.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapIndexed { i, text -> Segment((i + 1).toString().padStart(3, '0'), text) }
    }

    fun sourceWithIds(ch: Chapter): String =
        segments(ch).joinToString("\n\n") { "[${it.id}]\n${it.text}" }

    fun buildPrompt(base: String, glossaryBlock: String, ch: Chapter): String {
        val p = base.trim().ifBlank {
            "Translate this novel chapter into fluent, natural Bengali. Keep names, titles, terms and the story meaning consistent."
        }

        val format = """
TRANSLATION OUTPUT RULES:
1. Translate every numbered block.
2. Keep every marker exactly as written, for example [001], [002], [003].
3. Put the Bengali translation immediately after its marker.
4. Keep the same number of blocks and the same order.
5. Do not add explanations, notes, summaries, or an introduction.
6. Do not merge two blocks or split one block into multiple blocks.
7. Glossary translations are fixed and must be used exactly.
""".trimIndent()

        val g = glossaryBlock.trim()
        return buildString {
            append(p)
            append("\n\n")
            if (g.isNotEmpty()) {
                append(g)
                append("\n\n")
            }
            append(format)
            append("\n\n--- CHAPTER ---\n")
            append(sourceWithIds(ch))
        }
    }

    /**
     * Returns a JSON object { "001":"...", "002":"..." } only when every
     * expected block is present. Empty string means parsing failed.
     */
    fun parseMappedReply(reply: String, expected: List<Segment>): JSONObject {
        val out = JSONObject()
        val clean = reply
            .replace("\uFEFF", "")
            .replace(Regex("^\\s*`{3}(?:text|markdown)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\`{3}\\s*$"), "")
            .trim()

        if (clean.isEmpty() || expected.isEmpty()) return out

        val marker = Regex("(?m)^\\s*\\[(\\d{1,4})\\]\\s*(?:\\r?\\n)?")
        val matches = marker.findAll(clean).toList()
        if (matches.isEmpty()) return out

        for (i in matches.indices) {
            val m = matches[i]
            val id = m.groupValues[1].padStart(3, '0')
            val start = m.range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else clean.length
            val text = clean.substring(start, end).trim()
                .replace(Regex("^\\s*\\n"), "")
                .replace(Regex("\\n\\s*$"), "")
            if (text.isNotEmpty()) out.put(id, text)
        }

        for (s in expected) {
            if (!out.has(s.id) || out.optString(s.id).trim().length < 1) {
                return JSONObject()
            }
        }
        return out
    }

    fun numberedPlainReply(reply: String): String {
        return reply
            .replace(Regex("(?m)^\\s*\\[\\d{1,4}\\]\\s*$"), "")
            .trim()
    }
}
