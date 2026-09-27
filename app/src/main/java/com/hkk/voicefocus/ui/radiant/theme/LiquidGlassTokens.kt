// Adapted from OpenAHU/AHUTong-Android, commit 0492acc076c81533a8238acd1b796c2c6e0fe488.
// SPDX-License-Identifier: GPL-3.0-only
// Changes: package and palette integration for Voice Focus (2026-09-27).
package com.hkk.voicefocus.ui.radiant.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The rendering tier used for liquid glass on the current device.
 *
 * Keeping this policy independent from [Build] makes the API deterministic and unit-testable.
 */
enum class LiquidGlassQuality(
    val supportsBackdrop: Boolean,
    val supportsBlur: Boolean,
    val supportsRefraction: Boolean
) {
    Disabled(supportsBackdrop = false, supportsBlur = false, supportsRefraction = false),
    Tinted(supportsBackdrop = false, supportsBlur = false, supportsRefraction = false),
    Blurred(supportsBackdrop = true, supportsBlur = true, supportsRefraction = false),
    Refractive(supportsBackdrop = true, supportsBlur = true, supportsRefraction = true)
}

fun resolveLiquidGlassQuality(enabled: Boolean, sdkInt: Int): LiquidGlassQuality = when {
    !enabled -> LiquidGlassQuality.Disabled
    sdkInt >= Build.VERSION_CODES.TIRAMISU -> LiquidGlassQuality.Refractive
    sdkInt >= Build.VERSION_CODES.S -> LiquidGlassQuality.Blurred
    else -> LiquidGlassQuality.Tinted
}

/** Visual hierarchy for reusable glass surfaces. */
enum class LiquidGlassSurfaceLevel {
    /** Large, mostly static content groups. Never refracts. */
    Panel,

    /** Navigation, sheets, dialogs, and other surfaces floating over page content. */
    Floating,

    /** Compact interactive controls. Refraction is intentionally restrained. */
    Control
}

@Immutable
data class LiquidGlassSurfaceTokens(
    val tint: Color,
    val legacyTint: Color,
    val outline: Color,
    val blurRadius: Dp,
    val refractionHeight: Dp,
    val refractionAmount: Dp,
    val shadowRadius: Dp,
    val shadowColor: Color,
    val highlightAlpha: Float
)

@Immutable
data class LiquidGlassTokens(
    val quality: LiquidGlassQuality,
    val screenBackground: Color,
    val ambientPrimary: Color,
    val ambientSecondary: Color,
    val panel: LiquidGlassSurfaceTokens,
    val floating: LiquidGlassSurfaceTokens,
    val control: LiquidGlassSurfaceTokens
) {
    val enabled: Boolean
        get() = quality != LiquidGlassQuality.Disabled

    fun surface(level: LiquidGlassSurfaceLevel): LiquidGlassSurfaceTokens = when (level) {
        LiquidGlassSurfaceLevel.Panel -> panel
        LiquidGlassSurfaceLevel.Floating -> floating
        LiquidGlassSurfaceLevel.Control -> control
    }

    companion object {
        val Disabled = LiquidGlassTokens(
            quality = LiquidGlassQuality.Disabled,
            screenBackground = Color.Transparent,
            ambientPrimary = Color.Transparent,
            ambientSecondary = Color.Transparent,
            panel = disabledSurfaceTokens(),
            floating = disabledSurfaceTokens(),
            control = disabledSurfaceTokens()
        )
    }
}

val LocalLiquidGlassTokens = staticCompositionLocalOf { LiquidGlassTokens.Disabled }

@Composable
fun rememberLiquidGlassTokens(
    enabled: Boolean,
    sdkInt: Int = Build.VERSION.SDK_INT
): LiquidGlassTokens {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.5f
    return remember(enabled, sdkInt, colors, isDark) {
        val outline = if (isDark) {
            Color.White.copy(alpha = 0.22f)
        } else {
            colors.outline.copy(alpha = 0.42f)
        }
        val shadow = Color.Black.copy(alpha = if (isDark) 0.16f else 0.06f)
        val panelBase = if (isDark) colors.surfaceContainer else colors.surface
        val floatingBase = if (isDark) colors.surfaceContainerHigh else colors.surfaceContainerLowest
        val controlBase = if (isDark) colors.surfaceContainerHighest else colors.surface

        LiquidGlassTokens(
            quality = resolveLiquidGlassQuality(enabled, sdkInt),
            screenBackground = colors.surfaceContainerLowest,
            ambientPrimary = colors.primary.copy(alpha = if (isDark) 0.09f else 0.045f),
            ambientSecondary = colors.secondary.copy(alpha = if (isDark) 0.07f else 0.03f),
            panel = LiquidGlassSurfaceTokens(
                tint = panelBase.copy(alpha = if (isDark) 0.54f else 0.38f),
                legacyTint = panelBase.copy(alpha = if (isDark) 0.82f else 0.76f),
                outline = outline,
                blurRadius = 18.dp,
                refractionHeight = 0.dp,
                refractionAmount = 0.dp,
                shadowRadius = 8.dp,
                shadowColor = shadow,
                highlightAlpha = if (isDark) 0.22f else 0.28f
            ),
            floating = LiquidGlassSurfaceTokens(
                tint = floatingBase.copy(alpha = if (isDark) 0.56f else 0.44f),
                legacyTint = floatingBase.copy(alpha = if (isDark) 0.86f else 0.82f),
                outline = outline,
                blurRadius = 14.dp,
                refractionHeight = 6.dp,
                refractionAmount = 12.dp,
                shadowRadius = 18.dp,
                shadowColor = shadow,
                highlightAlpha = if (isDark) 0.26f else 0.34f
            ),
            control = LiquidGlassSurfaceTokens(
                tint = controlBase.copy(alpha = if (isDark) 0.58f else 0.46f),
                legacyTint = controlBase.copy(alpha = if (isDark) 0.86f else 0.80f),
                outline = outline,
                blurRadius = 10.dp,
                refractionHeight = 8.dp,
                refractionAmount = 14.dp,
                shadowRadius = 8.dp,
                shadowColor = shadow,
                highlightAlpha = if (isDark) 0.28f else 0.38f
            )
        )
    }
}

private fun disabledSurfaceTokens() = LiquidGlassSurfaceTokens(
    tint = Color.Transparent,
    legacyTint = Color.Transparent,
    outline = Color.Transparent,
    blurRadius = 0.dp,
    refractionHeight = 0.dp,
    refractionAmount = 0.dp,
    shadowRadius = 0.dp,
    shadowColor = Color.Transparent,
    highlightAlpha = 0f
)
