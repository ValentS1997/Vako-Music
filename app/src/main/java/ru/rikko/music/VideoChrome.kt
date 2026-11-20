package ru.rikko.music

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Button
import android.widget.TextView
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.media3.ui.DefaultTimeBar

/** Maps the effective Compose palette to Media3's Android View controls. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class VideoChrome(context: Context) {
    private val palette = AppPreferences(context).let { settings ->
        ThemePalette.resolve(
            settings.number("theme_base", 2), settings.number("theme_accent", 0),
            settings.enabled("theme_custom", false),
            settings.string("theme_background", "#101314"),
            settings.string("theme_surface", "#1C2021"),
            settings.string("theme_text", "#F0F3F1"),
            settings.string("theme_accent_hex", "#8FCF52")
        )
    }
    val accent: Int = palette.accent.toArgb()
    val text: Int = palette.scheme.onSurface.toArgb()
    val muted: Int = palette.scheme.onSurfaceVariant.toArgb()

    fun panel(radius: Float, opacity: Int = 236, active: Boolean = false): GradientDrawable {
        val top = lerp(palette.surface, palette.accent, if (active) .16f else .07f).toArgb()
        val bottom = palette.surface.toArgb()
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(withAlpha(top, opacity), withAlpha(bottom, opacity))).apply {
            cornerRadius = radius
            setStroke(1, withAlpha(if (active) accent else text, if (active) 125 else 42))
        }
    }

    /** Image tint is held by ImageButton when Media3 changes play/pause drawables. */
    fun tintMedia3(root: View) {
        fun visit(view: View) {
            when (view) {
                is ImageButton -> view.imageTintList = ColorStateList.valueOf(accent)
                is ViewGroup -> for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
        root.findViewById<TextView>(androidx.media3.ui.R.id.exo_position)?.setTextColor(text)
        root.findViewById<TextView>(androidx.media3.ui.R.id.exo_duration)?.setTextColor(muted)
        listOf(androidx.media3.ui.R.id.exo_rew_with_amount,
            androidx.media3.ui.R.id.exo_ffwd_with_amount).forEach { id ->
            root.findViewById<Button>(id)?.apply {
                backgroundTintList = ColorStateList.valueOf(accent)
                background?.mutate()?.setTint(accent)
                foregroundTintList = ColorStateList.valueOf(accent)
                foreground?.mutate()?.setTint(accent)
                setTextColor(accent)
            }
        }
        root.findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar)?.background = null
        root.findViewById<View>(androidx.media3.ui.R.id.exo_center_controls)?.background = null
        root.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
            setPlayedColor(accent)
            setScrubberColor(accent)
            setBufferedColor(withAlpha(accent, 110))
            setUnplayedColor(withAlpha(text, 90))
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
