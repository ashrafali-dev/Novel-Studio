package com.ashraf.novelstudio.japanesemanga

import android.content.Context
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.FileInputStream

/** Serves the tiny bundled OCR page and user-installed engine files from a local origin. */
internal class JapaneseMangaAssetLoader(context: Context, private val manager: JapaneseMangaModelManager) {
    private val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context.applicationContext))
        .addPathHandler("/manga-engine/", WebViewAssetLoader.PathHandler { path ->
            if (path.isBlank() || path.startsWith("/") || path.contains("\\") || path.split("/").any { it == ".." }) {
                return@PathHandler null
            }
            val file = manager.resolve(path)?.takeIf { it.isFile } ?: return@PathHandler null
            val mime = when (file.extension.lowercase()) {
                "js", "mjs" -> "application/javascript"
                "wasm" -> "application/wasm"
                "onnx" -> "application/octet-stream"
                "txt", "json" -> "text/plain"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
            }
            try {
                WebResourceResponse(mime, if (mime == "application/wasm" || mime == "application/octet-stream") null else "UTF-8",
                    FileInputStream(file))
            } catch (_: Exception) { null }
        })
        .build()

    fun intercept(uri: android.net.Uri): WebResourceResponse? {
        val response = loader.shouldInterceptRequest(uri) ?: return null
        // ONNX Runtime's threaded SIMD WASM build uses SharedArrayBuffer. Give the hidden, same-origin
        // OCR page the response headers Chromium needs for cross-origin isolation. All runtime resources
        // are served from this same appassets origin, not third-party hosts.
        if (uri.path == "/assets/japanese_manga_engine.html") {
            val headers = LinkedHashMap(response.responseHeaders ?: emptyMap())
            headers["Cross-Origin-Opener-Policy"] = "same-origin"
            headers["Cross-Origin-Embedder-Policy"] = "require-corp"
            headers["Origin-Agent-Cluster"] = "?1"
            response.responseHeaders = headers
        }
        return response
    }
}