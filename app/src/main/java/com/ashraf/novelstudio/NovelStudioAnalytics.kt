package com.ashraf.novelstudio

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Privacy-conscious, best-effort app usage analytics for NovelStudio.
 *
 * Sends coarse feature events and app/device versions only. It never sends novel text,
 * prompts, translated text, page URLs, cookies, chatbot content, or credentials.
 */
object NovelStudioAnalytics {
    private const val TAG = "NovelStudioAnalytics"
    private const val SUPABASE_URL = "https://tezgkarktyftzhejaplp.supabase.co"
    private const val PUBLISHABLE_KEY = "sb_publishable_Y_MafVNuqKfCJ1KJh-aB9w_wHyfGije"
    private const val PREFS = "novelstudio_analytics"
    private const val INSTALL_ID_KEY = "install_id"
    private const val LAST_MODE_KEY = "last_mode"
    private const val HEARTBEAT_INTERVAL_MS = 30_000L

    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var foreground = false
    @Volatile private var lastContext: Context? = null
    @Volatile private var currentMode: String? = null

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            val context = lastContext ?: return
            if (!foreground) return
            sendPresence(context.applicationContext, currentMode)
            mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    fun appOpened(context: Context) {
        sendEvent(context.applicationContext, "app_open")
    }

    @Synchronized
    fun onForeground(context: Context, mode: String?) {
        val app = context.applicationContext
        lastContext = app
        foreground = true
        currentMode = normalizeMode(mode)
        mainHandler.removeCallbacks(heartbeatRunnable)
        sendPresence(app, currentMode)
        mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)

        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previousMode = prefs.getString(LAST_MODE_KEY, null)
        if (currentMode != null && currentMode != previousMode) {
            sendEvent(app, "mode_selected", mode = currentMode)
            prefs.edit().putString(LAST_MODE_KEY, currentMode).apply()
        }
    }

    @Synchronized
    fun onBackground() {
        foreground = false
        mainHandler.removeCallbacks(heartbeatRunnable)
        lastContext = null
    }

    fun logEvent(
        context: Context,
        eventName: String,
        mode: String? = currentMode,
        targetLanguage: String? = null,
        ocrModel: String? = null,
        provider: String? = null,
        success: Boolean? = null,
        durationMs: Int? = null
    ) {
        sendEvent(context.applicationContext, eventName, mode, targetLanguage, ocrModel, provider, success, durationMs)
    }

    private fun normalizeMode(mode: String?): String? =
        when (mode?.lowercase()) {
            "novel", "scanning", "realtime" -> mode.lowercase()
            else -> null
        }

    private fun installId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(INSTALL_ID_KEY, null)
        if (!stored.isNullOrBlank()) return stored
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(INSTALL_ID_KEY, generated).apply()
        return generated
    }

    private fun basePayload(context: Context, mode: String?): JSONObject {
        val packageInfo = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: Exception) {
            null
        }
        return JSONObject()
            .put("install_id", installId(context))
            .put("app_version", packageInfo?.versionName ?: "unknown")
            .put("android_version", Build.VERSION.RELEASE ?: "unknown")
            .putIfNotNull("mode", normalizeMode(mode))
    }

    private fun JSONObject.putIfNotNull(key: String, value: String?): JSONObject {
        if (value != null) put(key, value)
        return this
    }

    private fun sendPresence(context: Context, mode: String?) {
        post("novelstudio-presence", basePayload(context, mode))
    }

    private fun sendEvent(
        context: Context,
        eventName: String,
        mode: String? = null,
        targetLanguage: String? = null,
        ocrModel: String? = null,
        provider: String? = null,
        success: Boolean? = null,
        durationMs: Int? = null
    ) {
        val allowed = setOf(
            "app_open", "mode_selected", "translation_started", "translation_completed",
            "translation_failed", "target_language_selected", "ocr_model_selected",
            "prefetch_toggled", "prefetch_completed", "manual_ocr_used", "app_update_seen"
        )
        if (eventName !in allowed) return
        val body = basePayload(context, mode).put("event_name", eventName)
            .putIfNotNull("target_language", targetLanguage?.take(40))
            .putIfNotNull("ocr_model", ocrModel?.take(80))
            .putIfNotNull("provider", provider?.take(40))
        if (success != null) body.put("success", success)
        if (durationMs != null && durationMs >= 0) body.put("duration_ms", durationMs.coerceAtMost(3_600_000))
        post("novelstudio-analytics", body)
    }

    private fun post(functionName: String, body: JSONObject) {
        io.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("$SUPABASE_URL/functions/v1/$functionName").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("apikey", PUBLISHABLE_KEY)
                    setRequestProperty("Cache-Control", "no-store")
                }
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status !in 200..299) Log.w(TAG, "Analytics request rejected: endpoint=$functionName status=$status")
            } catch (e: Exception) {
                Log.d(TAG, "Analytics request skipped: endpoint=$functionName (${e.javaClass.simpleName})")
            } finally {
                connection?.disconnect()
            }
        }
    }
}
