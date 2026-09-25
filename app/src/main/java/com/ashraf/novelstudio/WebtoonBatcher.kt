package com.ashraf.novelstudio

data class WebtoonTextRegion(
    val id: String,
    val imageId: String,
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

data class WebtoonTranslationBatch(
    val imageId: String,
    val items: List<WebtoonTextRegion>,
    val estimatedChars: Int
)

object WebtoonBatcher {
    const val DEFAULT_MAX_ITEMS = 20
    const val DEFAULT_MAX_CHARS = 6000

    fun makeBatches(
        regions: List<WebtoonTextRegion>,
        maxItems: Int = DEFAULT_MAX_ITEMS,
        maxChars: Int = DEFAULT_MAX_CHARS
    ): List<WebtoonTranslationBatch> {
        if (regions.isEmpty()) return emptyList()
        val safeItems = maxItems.coerceAtLeast(1)
        val safeChars = maxChars.coerceAtLeast(100)
        val result = mutableListOf<WebtoonTranslationBatch>()
        var currentImage = ""
        var current = mutableListOf<WebtoonTextRegion>()
        var chars = 0

        fun flush() {
            if (current.isNotEmpty()) {
                result += WebtoonTranslationBatch(currentImage, current.toList(), chars)
                current = mutableListOf()
                chars = 0
            }
        }

        for (region in regions) {
            val text = region.text.trim()
            if (text.isEmpty()) continue

            if (current.isNotEmpty() && region.imageId != currentImage) flush()
            if (current.isEmpty()) currentImage = region.imageId

            val n = text.length
            if (current.isNotEmpty() &&
                (current.size >= safeItems || chars + n > safeChars)
            ) {
                flush()
                currentImage = region.imageId
            }

            current += region
            chars += n
            if (current.size >= safeItems || chars >= safeChars) flush()
        }

        flush()
        return result
    }

    fun buildPayload(batch: WebtoonTranslationBatch): String =
        batch.items.joinToString("\n") { item ->
            "[" + item.id + "] " + item.text.trim()
        }
}
