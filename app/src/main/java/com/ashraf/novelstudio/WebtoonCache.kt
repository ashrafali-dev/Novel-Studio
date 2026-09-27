package com.ashraf.novelstudio

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashMap

class WebtoonCache(
    context: Context,
    name: String,
    private val maxEntries: Int = 900,
) {
    private val file = File(context.filesDir, name)
    private val lock = Any()
    private val map = LinkedHashMap<String, String>(32, 0.75f, true)
    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        try {
            val obj = JSONObject(file.readText())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.optString(k, "")
            }
            trim()
        } catch (_: Throwable) {
            map.clear()
        }
    }

    fun get(key: String): String? = synchronized(lock) {
        ensureLoaded()
        map[key]
    }

    fun put(key: String, value: String) {
        synchronized(lock) {
            ensureLoaded()
            map[key] = value
            trim()
            flush()
        }
    }

    fun remove(key: String) {
        synchronized(lock) {
            ensureLoaded()
            map.remove(key)
            flush()
        }
    }

    fun clear() {
        synchronized(lock) {
            map.clear()
            loaded = true
            runCatching { file.delete() }
        }
    }

    private fun trim() {
        while (map.size > maxEntries) {
            val it = map.entries.iterator()
            if (!it.hasNext()) break
            it.next()
            it.remove()
        }
    }

    private fun flush() {
        try {
            val obj = JSONObject()
            for ((k, v) in map) obj.put(k, v)
            val tmp = File(file.parentFile, file.name + ".part")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Throwable) {
        }
    }
}
