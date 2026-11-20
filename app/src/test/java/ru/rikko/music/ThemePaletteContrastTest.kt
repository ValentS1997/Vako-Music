package ru.rikko.music

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class ThemePaletteContrastTest {
    @Test
    fun presetTextAndPrimaryControlsMeetNormalTextContrast() {
        for (base in ThemePalette.bases.indices) {
            for (accent in ThemePalette.accents.indices) {
                val scheme = ThemePalette.resolve(base, accent, false, "", "", "", "").scheme
                val case = "base=$base accent=$accent"
                assertTrue("primary control $case", contrast(scheme.primary, scheme.onPrimary) >= 4.5f)
                assertTrue("gloss highlight $case", contrast(lerp(scheme.primary, Color.White, .03f), scheme.onPrimary) >= 4.5f)
                assertTrue("body text $case", contrast(scheme.surface, scheme.onSurface) >= 4.5f)
                assertTrue("muted text $case", contrast(scheme.surfaceVariant, scheme.onSurfaceVariant) >= 4.5f)
            }
        }
    }

    private fun contrast(first: Color, second: Color): Float {
        val a = first.luminance()
        val b = second.luminance()
        return (max(a, b) + .05f) / (min(a, b) + .05f)
    }
}
