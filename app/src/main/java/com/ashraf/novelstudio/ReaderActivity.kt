package com.ashraf.novelstudio

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class ReaderActivity : Activity() {
    private var list: List<Tr> = emptyList()
    private var idx = 0
    private var fs = 18f
    private var theme = 0
    private lateinit var root: LinearLayout
    private lateinit var titleTv: TextView
    private lateinit var body: TextView
    private lateinit var scroll: ScrollView
    private lateinit var bar: LinearLayout
    private val bg = intArrayOf(0xFF111114.toInt(), 0xFFF4ECD8.toInt(), 0xFFFFFFFF.toInt())
    private val fg = intArrayOf(0xFFDDDDDD.toInt(), 0xFF3B2F1E.toInt(), 0xFF111111.toInt())
    private var novel = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 20f
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        novel = intent.getStringExtra("novel") ?: ""
        val id = intent.getLongExtra("id", -1L)
        list = Store.chapters(this, novel)
        if (list.isEmpty()) { finish(); return }
        idx = list.indexOfFirst { it.id == id }.coerceAtLeast(0)
        fs = Prefs.get(this, "fs", "18").toFloatOrNull() ?: 18f
        theme = Prefs.get(this, "rtheme", "0").toIntOrNull() ?: 0

        titleTv = TextView(this).apply {
            textSize = 13f
            setPadding(dp(14), dp(10), dp(14), dp(6))
        }
        body = TextView(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(24))
            setLineSpacing(0f, 1.35f)
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(body) }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("◀") { go(-1) })
            addView(btn("A−") { setFont(fs - 1f) })
            addView(btn("A+") { setFont(fs + 1f) })
            addView(btn("🌙") { theme = (theme + 1) % 3; Prefs.put(this@ReaderActivity, "rtheme", theme.toString()); applyTheme() })
            addView(btn("▶") { go(1) })
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
        applyTheme()
        show()
    }

    private fun setFont(v: Float) {
        fs = v.coerceIn(12f, 34f)
        Prefs.put(this, "fs", fs.toString())
        body.textSize = fs
    }

    private fun applyTheme() {
        root.setBackgroundColor(bg[theme])
        body.setTextColor(fg[theme])
        titleTv.setTextColor(fg[theme])
        bar.setBackgroundColor(if (theme == 0) 0xFF202024.toInt() else 0x22000000)
        for (i in 0 until bar.childCount) (bar.getChildAt(i) as TextView).setTextColor(fg[theme])
        body.textSize = fs
    }

    private fun go(d: Int) {
        val n = idx + d
        if (n in list.indices) { idx = n; show() }
    }

    private fun show() {
        val t = list[idx]
        titleTv.text = "$novel  •  ${t.label()}   (${idx + 1}/${list.size})"
        body.text = Store.read(this, t.id)
        scroll.post { scroll.scrollTo(0, 0) }
        Prefs.put(this, "last_$novel", t.id.toString())
    }
}
