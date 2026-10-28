package dev.pov.tv

import android.content.Context
import android.app.AlertDialog
import android.content.DialogInterface
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/** A text field whose Back press closes the TV keyboard and hands focus to the dialog's actions. */
internal class TvEditText(context: Context) : EditText(context) {
    var onBack: (() -> Unit)? = null

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        val handler = onBack
        if (keyCode == KeyEvent.KEYCODE_BACK && handler != null) {
            if (event.action == KeyEvent.ACTION_UP) handler()
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }
}

/** Enter submits the last field; Back closes the TV keyboard and focuses the main action. */
internal fun AlertDialog.submitOnEnter(vararg fields: TvEditText): AlertDialog {
    val positive = getButton(DialogInterface.BUTTON_POSITIVE)
    fields.forEachIndexed { index, field ->
        val last = index == fields.lastIndex
        field.imeOptions = if (last) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
        fun hideKeyboard() = (field.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(field.windowToken, 0)
        fun advance(): Boolean {
            hideKeyboard()
            if (last) positive?.let { it.requestFocus(); if (it.isEnabled) it.performClick() } else fields[index + 1].requestFocus()
            return true
        }
        field.setOnEditorActionListener { _, action, event ->
            when {
                action != EditorInfo.IME_NULL -> advance()
                event?.keyCode in setOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER) -> { if (event?.action == KeyEvent.ACTION_UP) advance(); true }
                else -> false
            }
        }
        field.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER) { if (event.action == KeyEvent.ACTION_UP) advance(); true } else false
        }
        field.onBack = { hideKeyboard(); positive?.requestFocus() }
    }
    return this
}
