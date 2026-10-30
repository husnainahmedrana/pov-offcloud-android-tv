package dev.pov.tv

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ThemePreferencesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clearAppearance() {
        context.getSharedPreferences("pov_appearance", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `new installs default to Flix`() {
        assertEquals(ThemeSpec.FLIX, ThemePreferences(context).selected())
    }

    @Test fun `a saved Editorial choice from before its retirement opens Flix`() {
        context.getSharedPreferences("pov_appearance", Context.MODE_PRIVATE).edit().putString("theme", "editorial").commit()
        assertEquals(ThemeSpec.FLIX, ThemePreferences(context).selected())
    }

    @Test fun `unknown stored theme safely falls back without changing other preferences`() {
        val saved = context.getSharedPreferences("pov_appearance", Context.MODE_PRIVATE)
        saved.edit().putString("theme", "removed-future-theme").putString("unrelated", "keep").commit()
        assertEquals(ThemeSpec.FLIX, ThemePreferences(context).selected())
        assertEquals("keep", saved.getString("unrelated", null))
    }
}
