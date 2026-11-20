package ru.rikko.music

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Presentation tokens only; colors continue to come from ThemePalette. */
object VakoUi {
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )
    val row = RoundedCornerShape(16.dp)
    val panel = RoundedCornerShape(20.dp)
    val pill = RoundedCornerShape(50)
    val rowGap = 6.dp
}

/** Static, palette-derived gloss. Shadow is reserved for selected controls, never list rows. */
@Composable
fun Modifier.vakoGloss(selected: Boolean, shape: RoundedCornerShape = VakoUi.pill): Modifier {
    val scheme = MaterialTheme.colorScheme
    val fill = remember(selected, scheme.primary, scheme.surface, scheme.surfaceVariant) {
        if (selected) Brush.verticalGradient(listOf(lerp(scheme.primary, Color.White, .03f), scheme.primary))
        else Brush.verticalGradient(listOf(lerp(scheme.surface, scheme.surfaceVariant, .45f), scheme.surfaceVariant))
    }
    val rim = remember(selected, scheme.primary, scheme.onSurface) {
        if (selected) Brush.verticalGradient(listOf(
            lerp(scheme.primary, Color.White, .25f).copy(alpha = .82f), scheme.primary.copy(alpha = .20f)))
        else Brush.verticalGradient(listOf(
            scheme.onSurface.copy(alpha = .13f), scheme.onSurface.copy(alpha = .03f)))
    }
    val raised = if (selected) shadow(4.dp, shape,
        ambientColor = scheme.primary.copy(alpha = .16f), spotColor = scheme.primary.copy(alpha = .13f)) else this
    return raised.clip(shape).background(fill, shape).border(BorderStroke(1.dp, rim), shape)
}

/** One soft halo behind the screen; no per-row blur or animated overdraw. */
@Composable
fun Modifier.vakoBackdrop(): Modifier {
    val scheme = MaterialTheme.colorScheme
    return drawWithCache {
        val light = scheme.background.luminance() > .5f
        val halo = Brush.radialGradient(
            listOf(scheme.primary.copy(alpha = if (light) .05f else .17f),
                scheme.background.copy(alpha = 0f)),
            center = Offset(size.width * .76f, size.height * .11f), radius = size.width * .82f,
        )
        val lowerHalo = Brush.radialGradient(
            listOf(scheme.primary.copy(alpha = if (light) .035f else .09f),
                scheme.background.copy(alpha = 0f)),
            center = Offset(size.width * .24f, size.height * .94f), radius = size.width * .7f,
        )
        onDrawBehind {
            drawRect(scheme.background)
            drawRect(halo)
            drawRect(lowerHalo)
        }
    }
}

/** Static glass-like panel shared by navigation, player and prominent cards. */
@Composable
fun Modifier.vakoPanel(active: Boolean = false, shape: Shape = VakoUi.panel): Modifier {
    val scheme = MaterialTheme.colorScheme
    val top = if (active) lerp(scheme.surface, scheme.primary, .17f)
        else lerp(scheme.surface, scheme.surfaceVariant, .36f)
    val bottom = if (active) lerp(scheme.surfaceVariant, scheme.primary, .08f)
        else scheme.surface
    val rim = if (active) scheme.primary.copy(alpha = .55f)
        else scheme.onSurface.copy(alpha = .13f)
    val raised = if (active) shadow(6.dp, shape,
        ambientColor = scheme.primary.copy(alpha = .12f), spotColor = scheme.primary.copy(alpha = .12f)) else this
    return raised.clip(shape).background(Brush.verticalGradient(listOf(top, bottom)), shape)
        .border(1.dp, rim, shape)
}

@Composable
fun VakoChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.vakoGloss(selected).clickable(onClick = onClick)
        .semantics { this.selected = selected }.heightIn(min = 48.dp)
        .padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
fun VakoSearchField(value: String, onValueChange: (String) -> Unit, label: String,
                    modifier: Modifier = Modifier, onSearch: (() -> Unit)? = null) {
    val keyboard = LocalSoftwareKeyboardController.current
    val scheme = MaterialTheme.colorScheme
    val fill = remember(scheme.surface, scheme.surfaceVariant) {
        Brush.verticalGradient(listOf(lerp(scheme.surface, scheme.surfaceVariant, .30f), scheme.surface))
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = { Text("⌕", color = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingIcon = if (value.isBlank()) null else ({
            IconButton(onClick = { onValueChange("") },
                modifier = Modifier.semantics { contentDescription = "Очистить поиск" }) { Text("×") }
        }),
        singleLine = true,
        shape = VakoUi.panel,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedBorderColor = scheme.primary,
            unfocusedBorderColor = scheme.onSurface.copy(alpha = .22f),
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onSearch?.invoke() }),
        modifier = modifier.background(fill, VakoUi.panel),
    )
}

@Composable
fun VakoSectionHeader(title: String, subtitle: String? = null, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 1)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}
