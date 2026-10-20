package com.ashraf.novelstudio

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Privacy-conscious, best-effort usage telemetry.
 * Sends only a random install ID, app version, Android version and event type.
 * It does not collect page URLs, novel text, translations, credentials or chatbot content.
 */
internal object NovelStudioAnalytics {
    private const val TAG = "NovelStudioAnalytics"
    private const val SUPABASE_URL = "https://tezgkarktyftzhejaplp.supabase.co"
    private const val PREFS_NAME = "novelstudio_analytics"
    private const val INSTALL_ID_KEY = "install_id"
    private const val HEARTBEAT_SECONDS = 60L

    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "NovelStudio-Analytics").apply { isDaemon = true }
    }

    @Volatile
    private var heartbeat: ScheduledFuture<*>? = null

    @Synchronized
    fun onForeground(context: Context) {
        val app = context.applicationContext
        val id = installId(app)
        val version = appVersion(app)
        val androidVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()

        worker.execute {
            post("novelstudio-analytics", JSONObject()
                .put("event_name", "app_open")
                .put("install_id", id)
                .put("app_version", version)
                .put("android_version", androidVersion))
        }
        worker.execute {
            post("novelstudio-presence", presencePayload(id, version, androidVersion))
        }

        if (heartbeat == null || heartbeat?.isCancelled == true || heartbeat?.isDone == true) {
            heartbeat = worker.scheduleAtFixedRate({
                post("novelstudio-presence", presencePayload(id, version, androidVersion))
            }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS)
        }
    }

    @Synchronized
    fun onBackground() {
        heartbeat?.cancel(false)
        heartbeat = null
    }

    private fun presencePayload(id: String, version: String, androidVersion: String) =
        JSONObject()
            .put("install_id", id)
            .put("app_version", version)
            .put("android_version", androidVersion)

    private fun installId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(INSTALL_ID_KEY, null)
        if (!saved.isNullOrBlank()) {
            try {
                val parsed = UUID.fromString(saved)
                if (parsed.version() == 4) return parsed.toString()
            } catch (_: IllegalArgumentException) {
                // Regenerate a malformed value.
            }
        }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(INSTALL_ID_KEY, generated).apply()
        return generated
    }

    private fun appVersion(context: Context): String =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }

    private fun post(functionName: String, payload: JSONObject) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("$SUPABASE_URL/functions/v1/$functionName")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 5_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            connection.outputStream.use { stream ->
                stream.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                Log.w(TAG, functionName + " returned HTTP " + status)
                connection.errorStream?.close()
            } else {
                connection.inputStream?.close()
            }
        } catch (e: Exception) {
            // Analytics must never interrupt browsing, OCR or translation.
            Log.w(TAG, "Telemetry request failed (" + e.javaClass.simpleName + ")")
        } finally {
            connection?.disconnect()
        }
    }
}
