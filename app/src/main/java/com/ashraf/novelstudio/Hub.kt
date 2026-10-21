package com.ashraf.novelstudio

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.Toast

/**
 * Hub: the Webtoon (taihon) and Novel (lnreader) parts live in their own APKs.
 * This object launches them, offers a download link when one is not installed,
 * and decodes the novelstudio://open?url=... links they send back.
 */
object Hub {
    enum class Part(val title: String, val prefKey: String, val packages: List<String>) {
        // Tried in order. Add your own applicationId here if you changed it.
        WEBTOON("Webtoon", "hubWebtoonUrl", listOf("app.taihon", "app.taihon.foss", "app.taihon.dev", "app.taihon.debug")),
        NOVEL("Novel", "hubNovelUrl", listOf("com.rajarsheechatterjee.LNReader"))
    }

    /** Fill these with your own release links (GitHub release page or direct .apk). Can also be changed in the dialog. */
    private val DEFAULT_URLS = mapOf(
        Part.WEBTOON to "",
        Part.NOVEL to ""
    )

    fun installedPackage(ctx: Context, part: Part): String? =
        part.packages.firstOrNull { ctx.packageManager.getLaunchIntentForPackage(it) != null }

    fun open(activity: Activity, part: Part) {
        val pkg = installedPackage(activity, part)
        if (pkg == null) { askDownload(activity, part); return }
        val launch = activity.packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) { askDownload(activity, part); return }
        try { activity.startActivity(launch) }
        catch (_: Exception) { Toast.makeText(activity, "Could not open ${part.title}", Toast.LENGTH_SHORT).show() }
    }

    private fun askDownload(activity: Activity, part: Part) {
        val pad = activity.dpi(20)
        val input = EditText(activity).apply {
            hint = "https://.../${part.title.lowercase()}.apk"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(Prefs.get(activity, part.prefKey, DEFAULT_URLS[part].orEmpty()))
            setPadding(pad, activity.dpi(8), pad, 0)
        }

        AlertDialog.Builder(activity)
            .setTitle("${part.title} is not installed")
            .setMessage("Download the ${part.title} app, then tap Install when Android asks.")
            .setView(input)
            .setPositiveButton("Download") { _, _ ->
                val url = input.text.toString().trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    Toast.makeText(activity, "Paste a valid link first", Toast.LENGTH_SHORT).show()
                } else {
                    Prefs.put(activity, part.prefKey, url)
                    try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    catch (_: ActivityNotFoundException) { Toast.makeText(activity, "No app can open this link", Toast.LENGTH_SHORT).show() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** novelstudio://open?url=<encoded http(s) link>  ->  the link, or null. */
    fun urlFromIntent(i: Intent?): String? {
        val data = i?.data ?: return null
        if (i.action != Intent.ACTION_VIEW || data.scheme != "novelstudio") return null
        val u = data.getQueryParameter("url")?.trim() ?: return null
        return if (u.startsWith("http://") || u.startsWith("https://")) u else null
    }
}
