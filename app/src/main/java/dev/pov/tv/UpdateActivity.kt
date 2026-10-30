package dev.pov.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import dev.pov.tv.data.GitHubUpdates
import java.util.concurrent.Executors

/** User-confirmed, signed GitHub release updates for sideloaded TVs. */
class UpdateActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var updates: GitHubUpdates
    private lateinit var status: TextView
    private lateinit var notes: TextView
    private lateinit var progress: ProgressBar
    private lateinit var action: TextView
    private var release: GitHubUpdates.Release? = null
    private var ready = false
    private var busy = false

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    /** The same buttons as the Flix details page: a white main action and a translucent grey secondary one. */
    private fun button(label: String, primary: Boolean, click: () -> Unit) = TextView(this).apply {
        text = label; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true; setPadding(dp(28), 0, dp(28), 0)
        val idle = if (primary) Color.argb(225, 255, 255, 255) else Color.argb(150, 109, 109, 110)
        val lit = if (primary) Color.WHITE else Color.argb(220, 140, 140, 142)
        setTextColor(if (primary) Color.BLACK else Color.WHITE)
        background = rounded(idle, 4)
        setOnFocusChangeListener { view, focused ->
            view.background = rounded(if (focused) lit else idle, 4)
            view.foreground = if (focused) FocusRingDrawable(Color.WHITE, dp(4).toFloat(), true, dp(1).toFloat()) else null
            view.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f).setDuration(120).start()
        }
        setOnClickListener { if (isEnabled) click() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updates = GitHubUpdates(this)
        window.decorView.setBackgroundColor(BACKGROUND)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(64), dp(44), dp(64), dp(36)); setBackgroundColor(BACKGROUND)
        }
        fun label(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
            text = value; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        content.addView(label("APP UPDATES", 13f, MUTED, true).apply { letterSpacing = 0.12f })
        content.addView(label("POV Rajput", 34f, bold = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        status = label("Checking for updates…", 18f, Color.WHITE)
        content.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = true
            // A slim red bar on a dark track, like the player's.
            progressDrawable = LayerDrawable(arrayOf(rounded(TRACK, 2), ClipDrawable(rounded(ACCENT, 2), Gravity.START, ClipDrawable.HORIZONTAL)))
                .apply { setId(0, android.R.id.background); setId(1, android.R.id.progress) }
            indeterminateTintList = android.content.res.ColorStateList.valueOf(ACCENT)
        }
        content.addView(progress, LinearLayout.LayoutParams(dp(520), dp(4)).apply { topMargin = dp(16) })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(PANEL, 8); setPadding(dp(28), dp(22), dp(28), dp(22))
        }
        notes = label("", 16f, TEXT).apply { setLineSpacing(dp(4).toFloat(), 1f) }
        card.addView(notes, LinearLayout.LayoutParams(-1, -2))
        val scroll = ScrollView(this).apply { isFocusable = false; isVerticalScrollBarEnabled = false; addView(card, ViewGroup.LayoutParams(-1, -2)) }
        content.addView(scroll, LinearLayout.LayoutParams(dp(820), 0, 1f).apply { topMargin = dp(24) })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        action = button("Check again", true) { when { ready -> install(); release != null -> download(); else -> checkUpdates() } }.apply { isEnabled = false }
        buttons.addView(action, LinearLayout.LayoutParams(-2, dp(48)).apply { rightMargin = dp(16) })
        buttons.addView(button("Back", false) { finish() }, LinearLayout.LayoutParams(-2, dp(48)))
        content.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        setContentView(content)
        checkUpdates()
    }

    /** Release notes are Markdown: headings become bold lines, list items bullets, and **text** bold. */
    private fun formatNotes(markdown: String): CharSequence {
        val out = SpannableStringBuilder()
        markdown.lineSequence().map { it.trimEnd() }.forEach { line ->
            val heading = Regex("^(#{1,6})\\s+(.*)").find(line)
            val start = out.length
            when {
                heading != null -> {
                    if (heading.groupValues[1].length == 1) return@forEach  // the version title is already shown above
                    if (out.isNotEmpty()) out.append("\n")
                    out.append(heading.groupValues[2])
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RelativeSizeSpan(1.2f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.append("\n")
                }
                line.isBlank() -> return@forEach
                Regex("^\\s*[-*]\\s+").containsMatchIn(line) -> { appendInline(out, "•  " + line.replaceFirst(Regex("^\\s*[-*]\\s+"), "")); out.append("\n") }
                line.startsWith("Source and GPL") -> return@forEach
                else -> { appendInline(out, line); out.append("\n") }
            }
        }
        return out.trimEnd()
    }

    private fun appendInline(out: SpannableStringBuilder, line: String) {
        Regex("\\*\\*(.+?)\\*\\*").let { bold ->
            var last = 0
            bold.findAll(line).forEach { match ->
                out.append(line.substring(last, match.range.first))
                val start = out.length; out.append(match.groupValues[1])
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                last = match.range.last + 1
            }
            out.append(line.substring(last))
        }
    }

    private fun checkUpdates() {
        if (busy) return
        ready = false; release = null; notes.text = ""; status.text = "Checking for updates…"
        work({ updates.latest() }) { latest ->
            release = latest
            if (latest == null) {
                status.text = "You’re up to date"
                notes.text = "Installed: ${installedVersion()}\nUpdates come from ${GitHubUpdates.REPOSITORY}."
                action.text = "Check again"
            } else {
                status.text = "Version ${latest.version.removeSuffix("-rajput")} is available  ·  you have ${installedVersion().removeSuffix("-rajput")}"
                notes.text = latest.candidate.notes.ifBlank { "A new version of POV Rajput is available." }.let(::formatNotes)
                action.text = "Download and install"
            }
            action.requestFocus()
        }
    }

    private fun download() {
        val selected = release ?: return
        status.text = "Downloading ${selected.version}…"; progress.isIndeterminate = false; progress.progress = 0
        work({ updates.download(selected) { bytes, total -> runOnUiThread {
            if (!isDestroyed && !isFinishing) {
                progress.progress = (bytes * 100 / total).toInt().coerceIn(0, 100)
                status.text = "Downloading ${selected.version} · ${progress.progress}%"
            }
        } } }) {
            ready = true; action.text = "Install update"; status.text = "Download verified. Ready to install ${selected.version}."
            install()
        }
    }

    private fun install() {
        val selected = release ?: return
        if (busy) return
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this).setTitle("Allow app updates")
                .setMessage("Allow POV Rajput to install apps on the next screen. Return here and select Install update. Android will ask you to confirm the installation.")
                .setPositiveButton("Open settings") { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                    catch (_: android.content.ActivityNotFoundException) { status.text = "Open Android Settings → Security → Install unknown apps and allow POV Rajput, then return here." }
                }.setNegativeButton("Later", null).show()
            return
        }
        status.text = "Verifying update before installation…"
        work({ updates.validate(updates.apkFile, selected) }) {
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", updates.apkFile)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri("POV Rajput update", uri)
            try { startActivity(intent); status.text = "Choose Install on the next screen. If you cancelled, select Install update again." }
            catch (_: android.content.ActivityNotFoundException) { status.text = "No APK installer is available on this device. Install the signed APK manually from GitHub Releases." }
        }
    }

    private fun <T> work(task: () -> T, done: (T) -> Unit) {
        busy = true; action.isEnabled = false; progress.visibility = View.VISIBLE
        if (!ready) progress.isIndeterminate = release == null
        worker.execute {
            try {
                val result = task()
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) {
                        busy = false; action.isEnabled = true; progress.visibility = View.GONE; done(result)
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) {
                        busy = false; action.isEnabled = true; progress.visibility = View.GONE
                        status.text = if (error is IllegalStateException) error.message else "Could not complete the update request. Check your connection and try again."
                        action.text = if (ready) "Install update" else if (release != null) "Retry download" else "Check again"
                        action.requestFocus()
                    }
                }
            }
        }
    }
    private fun installedVersion() = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    private companion object {
        val BACKGROUND = Color.rgb(20, 20, 20)
        val PANEL = Color.rgb(31, 31, 31)
        val TEXT = Color.rgb(229, 229, 229)
        val MUTED = Color.rgb(179, 179, 179)
        val ACCENT = Color.rgb(229, 9, 20)
        val TRACK = Color.rgb(74, 74, 74)
    }
}
