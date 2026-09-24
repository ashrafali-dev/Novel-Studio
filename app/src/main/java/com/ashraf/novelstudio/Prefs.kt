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

    private fun sp(c: Context) = c.getSharedPreferences("ns", Context.MODE_PRIVATE)

    fun get(c: Context, k: String, def: String = ""): String = sp(c).getString(k, def) ?: def
    fun put(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()
    fun bool(c: Context, k: String): Boolean = sp(c).getBoolean(k, false)
    fun bool(c: Context, k: String, def: Boolean): Boolean = sp(c).getBoolean(k, def)
    fun putBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
    fun prompt(c: Context): String = get(c, "prompt", DEFAULT_PROMPT)
    fun adblock(c: Context): Boolean = !bool(c, "noAdblock")
}
