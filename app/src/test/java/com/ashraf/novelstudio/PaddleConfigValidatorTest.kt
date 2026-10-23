package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Paddle pack config is accepted at download time only if the vendored runtime
 * (com.paddle.ocr.model.ModelConfig) will really find a character dictionary in it.
 * These fixtures mirror the real PP-OCR inference.yml layout (including the Korean instructions
 * "PostProcess:"/"character_dict:" that made a plain substring check useless).
 */
class PaddleConfigValidatorTest {

    private fun recYml(entries: List<String>, postProcessIndent: Int = 0): String = buildString {
        appendLine("Global:")
        appendLine("  model_name: PP-OCRv6_small_rec")
        appendLine("PreProcess:")
        appendLine("  transform_ops:")
        appendLine("  - DecodeImage:")
        appendLine("      img_mode: BGR")
        appendLine("PostProcess:")
        appendLine("  name: CTCLabelDecode")
        appendLine("  character_dict:")
        val bullet = " ".repeat(postProcessIndent + 2) + "- "
        for (e in entries) appendLine(bullet + e)
        appendLine("  use_space_char: true")
    }

    @Test fun realLayoutIsAcceptedAndCountsEveryEntry() {
        val entries = (0 until 6000).map { "'x$it'" }
        val layout = PaddleConfigValidator.inspect(recYml(entries))
        assertNotNull(layout)
        assertEquals(6000, layout!!.entries)
        assertEquals(0, layout.postProcessIndent)
        assertEquals(2, layout.dictionaryIndent)
    }

    @Test fun koreanDictionaryWithHangulIsAccepted() {
        val entries = (0 until 5000).map { "'가$it'" } + listOf("'\u3131'", "' '")
        assertTrue(PaddleConfigValidator.isValid(recYml(entries)))
    }

    @Test fun detectorYmlIsRejected() {
        // The detector config contains a "PostProcess:" block but no character_dict: the old
        // contains() check found both strings in other broken files and accepted them, leaving OCR empty.
        val det = """
            Global:
              model_name: PP-OCRv6_small_det
            PostProcess:
              box_thresh: 0.45
              max_candidates: 3000
              name: DBPostProcess
              thresh: 0.2
              unclip_ratio: 1.4
        """.trimIndent()
        assertNull(PaddleConfigValidator.inspect(det))
        assertFalse(PaddleConfigValidator.isValid(det))
    }

    @Test fun truncatedDictionaryIsRejected() {
        // Fewer than MIN_DICTIONARY_ENTRIES lines: the runtime would decode almost everything as mixtures.
        val short = recYml(List(50) { "'a$it'" })
        assertFalse(PaddleConfigValidator.isValid(short))
    }

    @Test fun dictionaryMustNestBelowPostProcess() {
        val wrong = buildString {
            appendLine("PostProcess:")
            appendLine("  name: CTCLabelDecode")
            appendLine("character_dict:")          // same indent as PostProcess: the runtime stops before it
            for (i in 0 until 6000) appendLine("- 'x$i'")
        }
        assertNull(PaddleConfigValidator.inspect(wrong))
    }

    @Test fun commentsAndBlankLinesAreSkippedLikeTheRuntime() {
        val body = buildString {
            appendLine("PostProcess:")
            appendLine("  name: CTCLabelDecode")
            appendLine("  character_dict:")
            val entries = (0 until 100).map { "  - 'x$it'" }
            for (e in entries) {
                appendLine(e)
                if (e.endsWith("'9'") || e.endsWith("'19'")) { appendLine("  # comment"); appendLine() }
            }
        }
        val layout = PaddleConfigValidator.inspect(body)
        assertNotNull(layout)
        assertEquals(100, layout!!.entries)
    }

    @Test fun yamlScalarsWithQuotesCountAsOneEntryEach() {
        val body = buildString {
            appendLine("PostProcess:")
            appendLine("  character_dict:")
            for (i in 0 until 300) appendLine("  - '${(0xAC00 + i).toChar()}'")
            appendLine("  - \"'\"")
            appendLine("  - ','")
        }
        assertEquals(302, PaddleConfigValidator.inspect(body)!!.entries)
    }
}
