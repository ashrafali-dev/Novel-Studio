package com.ashraf.novelstudio

import android.content.Context

object Prefs {
    const val DEFAULT_PROMPT = """তুমি একজন দক্ষ নোভেল অনুবাদক। আমি তোমাকে ইংরেজি/জাপানি/কোরিয়ান/চীনা ওয়েব নোভেলের চ্যাপ্টার পাঠাব। প্রতিটি চ্যাপ্টার স্বাভাবিক, সাবলীল বাংলায় অনুবাদ করো।

নিয়ম:
- কিছু বাদ দেবে না, সারাংশ করবে না — পুরো চ্যাপ্টার অনুবাদ করবে।
- চরিত্রের নাম আর বিশেষ শব্দ (skill, rank, জায়গার নাম) সব চ্যাপ্টারে একই রাখবে।
- সংলাপ আর বর্ণনার টোন মূল লেখার মতো রাখবে।
- শুধু অনুবাদ দেবে, বাড়তি মন্তব্য নয়।

এখন থেকে আমি যা পাঠাব সেটাই অনুবাদ করবে।"""

    const val DEFAULT_REALTIME_PROMPT = """তুমি একজন দক্ষ ওয়েবটুন, ম্যানহো ও কমিক অনুবাদক। ছবির OCR থেকে পাওয়া প্রতিটি লেখা প্রাঞ্জল, স্বাভাবিক বাংলায় অনুবাদ করো।

নিয়ম:
- প্রতিটি [001], [002] ইত্যাদি মার্কার হুবহু একই ক্রমে ঠিক একবার রাখবে।
- সংলাপ, বর্ণনা, সিস্টেম উইন্ডো এবং শব্দ-প্রভাব—কোনো লেখা বাদ দেবে না।
- কথার আবেগ, চরিত্রের কণ্ঠ এবং দৃশ্যের অর্থ বজায় রাখবে। বাবলের জন্য অনুবাদ সংক্ষিপ্ত ও স্বাভাবিক রাখবে।
- মূল ভাষার লেখা পুনরায় লিখবে না; সারাংশ বা ব্যাখ্যাও দেবে না।
- নিজের চিন্তা, reasoning, status, heading, disclaimer বা অতিরিক্ত কোনো মন্তব্য দেবে না।
- শুধু মার্কারসহ বাংলা অনুবাদ ফেরত দেবে।"""

    private fun sp(c: Context) = c.getSharedPreferences("ns", Context.MODE_PRIVATE)

    fun get(c: Context, k: String, def: String = ""): String = sp(c).getString(k, def) ?: def
    fun put(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()
    fun bool(c: Context, k: String): Boolean = sp(c).getBoolean(k, false)
    fun bool(c: Context, k: String, def: Boolean): Boolean = sp(c).getBoolean(k, def)
    fun putBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
    fun prompt(c: Context): String = get(c, "prompt", DEFAULT_PROMPT)
    fun realtimePrompt(c: Context): String =
        get(c, "realtimePrompt", "").ifBlank { DEFAULT_REALTIME_PROMPT }

    /** Use its own editable prompt only while AI Real-time Translation is enabled. */
    fun activeTranslationPrompt(c: Context): String =
        if (DeepSeekRealtimeTranslation.isEnabled(c)) realtimePrompt(c) else prompt(c)
    fun adblock(c: Context): Boolean = !bool(c, "noAdblock")
    fun auto(c: Context): Boolean = !bool(c, "noAuto")

    // Bengali font for the translated text: 0 = normal, 1..3 = bundled fonts in assets/fonts
    val FONT_FILES = arrayOf("", "tiro", "hind", "galada")
    val FONT_NAMES = arrayOf("সাধারণ", "Tiro Bangla (সেরিফ)", "Hind Siliguri (পরিষ্কার)", "Galada (স্টাইলিশ)")
    fun fontIdx(c: Context): Int = (get(c, "trFont", "1").toIntOrNull() ?: 1).coerceIn(0, FONT_FILES.size - 1)
    fun fontFile(c: Context): String = FONT_FILES[fontIdx(c)]
    val SIZES = intArrayOf(16, 18, 20, 22, 24)
    fun sizeIdx(c: Context): Int = (get(c, "trSize", "1").toIntOrNull() ?: 1).coerceIn(0, SIZES.size - 1)
    fun sizePx(c: Context): Int = SIZES[sizeIdx(c)]
    fun glossaryOn(c: Context): Boolean = !bool(c, "noGlossary")
}
