package dev.pov.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import dev.pov.tv.data.ApiException
import dev.pov.tv.data.LinkDebrids
import dev.pov.tv.data.LinkTransport
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.util.concurrent.Executor

/** Exercises the dialog used by Settings, including TV input and asynchronous account validation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ApiKeySaveTest {
    private lateinit var activity: Activity
    private lateinit var worker: ManualExecutor
    private lateinit var flow: ApiKeyDialog
    private var connected = false

    @Before fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.AppTheme)
        worker = ManualExecutor()
    }

    @After fun cleanup() {
        if (::flow.isInitialized) flow.dismiss()
        activity.finish()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `failed validation stays visible with pasted key available to retry`() {
        show { throw ApiException(401, "Account authorization is required or has expired.") }
        val dialog = dialog()
        val input = descendants(dialog.window!!.decorView).filterIsInstance<TvEditText>().single()
        input.setText("synthetic-test-key")
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        worker.runNext()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(connected)
        assertTrue("Save must keep validation errors in the visible dialog", dialog.isShowing)
        assertEquals("synthetic-test-key", input.text.toString())
        assertTrue(descendants(dialog.window!!.decorView).filterIsInstance<TextView>()
            .any { it.text.contains("authorization") })
    }

    @Test fun `successful validation completes the save callback`() {
        var saved = ""
        show { saved = it }
        val dialog = dialog()
        descendants(dialog.window!!.decorView).filterIsInstance<TvEditText>().single().setText("  synthetic-test-key\n")
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        worker.runNext()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("synthetic-test-key", saved)
        assertTrue(connected)
        assertFalse(dialog.isShowing)
    }

    @Test fun `save stays open with visible progress and ignores repeated submissions`() {
        var saves = 0
        show { saves++ }
        input().setText("synthetic-test-key")
        positive().performClick()
        assertTrue(dialog().isShowing)
        assertTrue(labels().contains("Checking your Real-Debrid account"))
        assertFalse(positive().isEnabled)
        assertFalse(input().isEnabled)
        positive().performClick()
        input().onEditorAction(EditorInfo.IME_ACTION_DONE)
        assertEquals(1, worker.size)
        work()
        assertEquals(1, saves)
        assertTrue(connected)
    }

    @Test fun `blank input stays open without silently discarding the save`() {
        show {}
        input().setText(" \n ")
        positive().performClick()
        assertTrue(dialog().isShowing)
        assertEquals("Enter your Real-Debrid API key", input().error.toString())
        assertEquals(0, worker.size)
        assertFalse(connected)
    }

    @Test fun `storage error stays visible and retry can complete with the same key`() {
        var attempts = 0
        show {
            assertEquals("synthetic-test-key", it)
            if (++attempts == 1) throw IllegalStateException("Account data could not be saved. Please check device storage.")
        }
        val original = dialog()
        input().setText("synthetic-test-key")
        positive().performClick(); work()
        assertSame(original, dialog())
        assertTrue(original.isShowing)
        assertTrue(labels().contains("check device storage"))
        assertTrue(positive().isEnabled)
        assertTrue(input().isEnabled)
        assertFalse(connected)
        positive().performClick(); work()
        assertEquals(2, attempts)
        assertTrue(connected)
        assertFalse(original.isShowing)
    }

    @Test fun `cancel before queued validation prevents saving and clears input`() {
        var saves = 0
        show { saves++ }
        val field = input()
        field.setText("synthetic-test-key")
        positive().performClick()
        dialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        work()
        assertEquals(0, saves)
        assertFalse(connected)
        assertFalse(dialog().isShowing)
        assertEquals("", field.text.toString())
    }

    @Test fun `cancel during validation discards late success`() {
        show { flow.dismiss() }
        input().setText("synthetic-test-key")
        positive().performClick(); work()
        assertFalse(connected)
        assertFalse(dialog().isShowing)
    }

    @Test fun `finishing activity does not begin a queued save`() {
        var saves = 0
        show { saves++ }
        input().setText("synthetic-test-key")
        positive().performClick()
        activity.finish(); work()
        assertEquals(0, saves)
        assertFalse(connected)
    }

    @Test fun `keyboard done submits and back hands focus to save without submitting`() {
        show {}
        input().setText("synthetic-test-key")
        input().onKeyPreIme(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        input().onKeyPreIme(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        assertTrue(positive().hasFocus())
        assertEquals(0, worker.size)
        input().onEditorAction(EditorInfo.IME_ACTION_DONE)
        assertEquals(1, worker.size)
        work()
        assertTrue(connected)
    }

    @Test fun `remote enter submits once on release`() {
        show {}
        val field = input()
        field.setText("synthetic-test-key")
        field.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        assertEquals(0, worker.size)
        field.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        assertEquals(1, worker.size)
        work()
        assertTrue(connected)
    }

    @Test fun `unexpected errors cannot expose a credential in dialog status`() {
        show { throw IllegalStateException("Request failed: https://example.com/?api_key=synthetic-test-key") }
        input().setText("synthetic-test-key")
        positive().performClick(); work()
        assertTrue(dialog().isShowing)
        val message = descendants(dialog().window!!.decorView).filterIsInstance<TextView>()
            .filterNot { it is TvEditText }.joinToString { it.text }
        assertFalse(message.contains("synthetic-test-key"))
        assertFalse(message.contains("https://"))
        assertTrue(message.contains("Check your connection"))
    }

    @Test fun `real-debrid validation and persistence finish before connected callback`() {
        val credentials = mutableMapOf<String, String>()
        val transport = object : LinkTransport {
            override fun get(url: String, headers: Map<String, String>): String {
                assertEquals("https://api.real-debrid.com/rest/1.0/user", url)
                assertEquals("Bearer synthetic-test-key", headers["Authorization"])
                assertFalse(connected)
                assertTrue(credentials.isEmpty())
                return """{"id":123}"""
            }
            override fun delete(url: String, headers: Map<String, String>) = error("Unexpected request")
            override fun form(method: String, url: String, headers: Map<String, String>, fields: List<Pair<String, String>>) = error("Unexpected request")
            override fun json(method: String, url: String, headers: Map<String, String>, body: JSONObject) = error("Unexpected request")
        }
        val api = LinkDebrids({ credentials[it].orEmpty() }, { id, key -> credentials[id] = key }, transport)
        flow = ApiKeyDialog(activity, "Real-Debrid", worker,
            save = { key, cancelled -> api.setKey("realdebrid", key, cancelled) },
            onConnected = {
                assertTrue(api.connected("realdebrid"))
                assertEquals("synthetic-test-key", credentials["realdebrid"])
                connected = true
            }).also { it.show() }
        input().setText(" \nsynthetic-test-key\r\n ")
        positive().performClick(); work()
        assertTrue(connected)
        assertFalse(dialog().isShowing)
    }

    private fun show(save: (String) -> Unit) {
        flow = ApiKeyDialog(activity, "Real-Debrid", worker, { key, _ -> save(key) }, { connected = true }).also { it.show() }
    }

    private fun dialog(): AlertDialog = ShadowAlertDialog.getLatestAlertDialog()
    private fun input() = descendants(dialog().window!!.decorView).filterIsInstance<TvEditText>().single()
    private fun positive() = dialog().getButton(DialogInterface.BUTTON_POSITIVE)
    private fun labels() = descendants(dialog().window!!.decorView).filterIsInstance<TextView>().joinToString { it.text }
    private fun work() { worker.runNext(); shadowOf(Looper.getMainLooper()).idle() }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val size get() = tasks.size
        override fun execute(command: Runnable) { tasks.add(command) }
        fun runNext() { tasks.removeFirst().run() }
    }
}
