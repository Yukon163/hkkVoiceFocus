package com.hkk.voicefocus.ui

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.*
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.hkk.voicefocus.ui.radiant.theme.LocalLiquidGlassTokens
import com.hkk.voicefocus.ui.radiant.theme.rememberLiquidGlassTokens

val LocalAmbient = staticCompositionLocalOf<Backdrop> { emptyBackdrop() }
val LocalContentBackdrop = staticCompositionLocalOf<Backdrop> { emptyBackdrop() }

@Composable
fun FocusTheme(mode: String, content: @Composable () -> Unit) {
    val dark = mode == "dark" || (mode == "system" && isSystemInDarkTheme())
    val activity = LocalActivity.current
    val view = LocalView.current
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    // Fixed teal on every device; the system preference controls light/dark mode only.
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFF8BD7DF), onPrimary = Color(0xFF00363D), primaryContainer = Color(0xFF164A51),
        secondary = Color(0xFFA8CBD2), secondaryContainer = Color(0xFF334C54), onSecondaryContainer = Color(0xFFD4EBEF),
        background = Color(0xFF11191C), surface = Color(0xFF1A2529), surfaceContainer = Color(0xFF223035),
        onBackground = Color(0xFFE2EDEF), onSurface = Color(0xFFE2EDEF), onSurfaceVariant = Color(0xFFB0C3C7),
        outlineVariant = Color(0xFF354C52)
    ) else lightColorScheme(
        primary = Color(0xFF16798A), onPrimary = Color.White, primaryContainer = Color(0xFFD7EEF0), onPrimaryContainer = Color(0xFF144D57),
        secondary = Color(0xFF51727A), secondaryContainer = Color(0xFFDDEDEF), onSecondaryContainer = Color(0xFF253F47),
        background = Color(0xFFF4F7F8), surface = Color(0xFFFDFEFF), surfaceContainer = Color(0xFFEAF0F2),
        onBackground = Color(0xFF162E35), onSurface = Color(0xFF162E35), onSurfaceVariant = Color(0xFF5D747C),
        outlineVariant = Color(0xFFDAE5E8)
    )
    MaterialTheme(colorScheme = scheme, shapes = Shapes(medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp))) {
        val tokens = rememberLiquidGlassTokens(enabled = true)
        CompositionLocalProvider(LocalLiquidGlassTokens provides tokens, LocalContentColor provides scheme.onSurface, content = content)
    }
}

@Composable
fun focusSwitchColors(): SwitchColors {
    val colors = MaterialTheme.colorScheme
    return SwitchDefaults.colors(
        checkedThumbColor = colors.onPrimary,
        checkedTrackColor = colors.primary,
        checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = colors.primary.copy(alpha = .85f).compositeOver(colors.surface),
        uncheckedTrackColor = colors.primary.copy(alpha = .12f).compositeOver(colors.surface),
        uncheckedBorderColor = colors.primary.copy(alpha = .35f),
        disabledCheckedThumbColor = colors.surface,
        disabledCheckedTrackColor = colors.primary.copy(alpha = .22f).compositeOver(colors.surface),
        disabledCheckedBorderColor = Color.Transparent,
        disabledUncheckedThumbColor = colors.primary.copy(alpha = .38f).compositeOver(colors.surface),
        disabledUncheckedTrackColor = colors.primary.copy(alpha = .05f).compositeOver(colors.surface),
        disabledUncheckedBorderColor = colors.primary.copy(alpha = .12f),
    )
}

@Composable
fun RadiantHost(content: @Composable BoxScope.(Modifier) -> Unit) {
    val ambient = rememberLayerBackdrop()
    val scene = rememberLayerBackdrop()
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(colors.background)) {
        Box(Modifier.matchParentSize().layerBackdrop(ambient).background(Brush.verticalGradient(listOf(
            colors.background, colors.primary.copy(alpha = .09f), colors.tertiary.copy(alpha = .04f), colors.background
        ))))
        CompositionLocalProvider(LocalAmbient provides ambient, LocalContentBackdrop provides scene) {
            content(Modifier.layerBackdrop(scene))
        }
    }
}

@Composable
fun Modifier.glassSurface(floating: Boolean = false, radius: Int = 26): Modifier {
    val shape = RoundedCornerShape(radius.dp)
    val base = MaterialTheme.colorScheme.surface
    val edge = MaterialTheme.colorScheme.outlineVariant
    val backdrop = if (floating) LocalContentBackdrop.current else LocalAmbient.current
    return if (Build.VERSION.SDK_INT >= 31) {
        drawBackdrop(backdrop = backdrop, shape = { shape }, effects = {
            vibrancy(); blur(if (floating) 20.dp.toPx() else 16.dp.toPx())
            if (floating && Build.VERSION.SDK_INT >= 33) lens(5.dp.toPx(), 9.dp.toPx())
        }, onDrawSurface = { drawRect(base.copy(alpha = if (floating) .7f else .66f)) })
            .border(.7.dp, edge.copy(alpha = .7f), shape)
    } else clip(shape).background(base).border(.7.dp, edge, shape)
}

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = if (onClick != null) {
        // Match the whole panel's outline, including its padding, instead of a rectangular text area.
        Modifier.clip(RoundedCornerShape(26.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    } else Modifier
    Column(modifier.fillMaxWidth().glassSurface().then(interaction).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}
