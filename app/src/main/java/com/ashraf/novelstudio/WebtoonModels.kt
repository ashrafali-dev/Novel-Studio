package com.ashraf.novelstudio

data class WebtoonImageRef(
    val id: String,
    val src: String,
    val top: Float,
    val left: Float,
    val width: Float,
    val height: Float,
    val naturalWidth: Int,
    val naturalHeight: Int,
    val visible: Boolean,
    val distance: Float,
)

data class WebtoonOverlay(
    val id: String,
    val imageId: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val text: String,
    val background: String = "#f7f7f7",
    val foreground: String = "#111111",
)

enum class WebtoonImageState { NEW, LOADING, OCR, WAITING_AI, READY, FAILED }


data class WebtoonOcrResult(
    val text: String,
    val confidence: Float,
    val points: List<android.graphics.PointF>,
)

data class WebtoonOcrRunResult(
    val results: List<WebtoonOcrResult>,
)
