package dev.pov.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps validation and storage feedback beside the key until the account is connected. */
internal class ApiKeyDialog(
    private val activity: Activity,
    private val service: String,
    private val executor: Executor,
    private val save: (String, () -> Boolean) -> Unit,
    private val onConnected: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(false)
    private var dialog: AlertDialog? = null

    fun show() {
        if (dialog != null || activity.isFinishing || activity.isDestroyed) return
        val input = TvEditText(activity).apply {
            hint = "Paste your $service API key"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSaveEnabled = false
        }
        val status = TextView(activity).apply {
            textSize = 14f
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (18 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset / 2, inset, inset / 2)
            addView(input, LinearLayout.LayoutParams(-1, -2))
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = inset / 2 })
        }
        val window = AlertDialog.Builder(activity).setTitle("$service API key")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog = window
        var busy = false
        fun cancelled() = !active.get() || activity.isFinishing || activity.isDestroyed
        window.setCanceledOnTouchOutside(false)
        window.setOnDismissListener { active.set(false); input.setText("") }
        active.set(true)
        window.show()
        window.submitOnEnter(input)
        val positive = window.getButton(DialogInterface.BUTTON_POSITIVE)
        fun failed(error: Exception) {
            if (cancelled()) return
            busy = false
            input.isEnabled = true
            positive.isEnabled = true
            status.text = uiErrorMessage(error)
            status.visibility = View.VISIBLE
            positive.requestFocus()
        }
        // A Builder button listener dismisses immediately, before the worker can report failure.
        positive.setOnClickListener {
            if (busy || cancelled()) return@setOnClickListener
            val key = input.text.toString().trim()
            if (key.isBlank()) {
                input.error = "Enter your $service API key"
                input.requestFocus()
                return@setOnClickListener
            }
            input.error = null
            busy = true
            input.isEnabled = false
            positive.isEnabled = false
            status.text = "Checking your $service account…"
            status.visibility = View.VISIBLE
            try {
                executor.execute {
                    if (cancelled()) return@execute
                    try {
                        save(key, ::cancelled)
                        handler.post {
                            if (!cancelled()) {
                                window.dismiss()
                                onConnected()
                            }
                        }
                    } catch (error: Exception) {
                        handler.post { failed(error) }
                    }
                }
            } catch (error: RuntimeException) { failed(error) }
        }
    }

    fun dismiss() { active.set(false); dialog?.dismiss() }
}
