package com.ashraf.novelstudio

/**
 * Marks URLs currently handled by the optional Japanese manga engine.
 *
 * This is deliberately separate from the standard Web Mode switch: Japanese manga OCR can
 * submit its own geometry-mapped chapter to the normal translation queue without changing
 * any global reader preference or affecting other pages.
 */
internal object JapaneseMangaSession {
    private val activePages = LinkedHashSet<String>()

    private fun key(url: String): String = WebModeLogic.normalizeUrl(url)

    @Synchronized
    fun register(url: String) {
        val k = key(url)
        if (k.isNotBlank()) activePages.add(k)
        while (activePages.size > 16) activePages.remove(activePages.first())
    }

    @Synchronized
    fun isManaged(url: String): Boolean = key(url) in activePages

    @Synchronized
    fun clear(url: String) {
        activePages.remove(key(url))
    }
}
