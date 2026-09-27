package com.hkk.voicefocus.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt

/** The ambient background varies vertically only, so one pixel of width is sufficient. */
@Composable
internal fun rememberStaticGlassBackdrop(
    background: Color,
    stops: List<Color>,
    height: Int,
    hostCoordinates: State<LayoutCoordinates?>,
): Backdrop {
    val blurRadius = with(LocalDensity.current) { 16.dp.toPx() }
    // Use color values as keys: playback recompositions can recreate the ColorScheme object.
    return remember(background, stops, height, blurRadius, hostCoordinates) {
        val image = if (height <= 0 || Build.VERSION.SDK_INT < 31) null
            else renderAmbientStrip(background, stops, height, blurRadius)
        // Keep coordinate tracking active even before the host's first size callback.
        StaticGlassBackdrop(image, hostCoordinates)
    }
}

private class StaticGlassBackdrop(
    private val image: ImageBitmap?,
    private val hostCoordinates: State<LayoutCoordinates?>,
) : Backdrop {
    override val isCoordinatesDependent = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val texture = image ?: return
        val host = hostCoordinates.value ?: return
        val panel = coordinates ?: return
        if (!host.isAttached || !panel.isAttached) return
        val offset = runCatching { host.localPositionOf(panel) }
            .getOrElse { panel.positionInWindow() - host.positionInWindow() }
        // Only placement changes while scrolling. The already blurred bitmap is reused.
        translate(top = -offset.y) {
            drawImage(texture, dstSize = IntSize(ceil(size.width).toInt().coerceAtLeast(1), texture.height))
        }
    }
}

private fun renderAmbientStrip(background: Color, stops: List<Color>, height: Int, sigma: Float): ImageBitmap {
    val bitmap = Bitmap.createBitmap(1, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(background.toArgb())
    val paint = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, height.toFloat(), stops.map(Color::toArgb).toIntArray(), null, Shader.TileMode.CLAMP)
    }
    canvas.drawRect(0f, 0f, 1f, height.toFloat(), paint)

    val pixels = IntArray(height)
    bitmap.getPixels(pixels, 0, 1, 0, 0, 1, height)
    val red = FloatArray(height)
    val green = FloatArray(height)
    val blue = FloatArray(height)
    for (y in 0 until height) {
        val r = (pixels[y] ushr 16 and 255).toFloat()
        val g = (pixels[y] ushr 8 and 255).toFloat()
        val b = (pixels[y] and 255).toFloat()
        // Match Backdrop's vibrancy saturation of 1.5 before applying its blur.
        val luma = .213f * r + .715f * g + .072f * b
        red[y] = (1.5f * r - .5f * luma).coerceIn(0f, 255f)
        green[y] = (1.5f * g - .5f * luma).coerceIn(0f, 255f)
        blue[y] = (1.5f * b - .5f * luma).coerceIn(0f, 255f)
    }

    val radius = ceil(3 * sigma).toInt().coerceAtLeast(1)
    val kernel = FloatArray(radius * 2 + 1) { i ->
        val distance = (i - radius).toDouble()
        exp(-distance * distance / (2.0 * sigma * sigma)).toFloat()
    }
    val totalWeight = kernel.sum()
    for (i in kernel.indices) kernel[i] /= totalWeight
    for (y in 0 until height) {
        var r = 0f
        var g = 0f
        var b = 0f
        for (i in kernel.indices) {
            val sample = (y + i - radius).coerceIn(0, height - 1)
            val weight = kernel[i]
            r += red[sample] * weight
            g += green[sample] * weight
            b += blue[sample] * weight
        }
        pixels[y] = (0xff shl 24) or (r.roundToInt().coerceIn(0, 255) shl 16) or
            (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)
    }
    bitmap.setPixels(pixels, 0, 1, 0, 0, 1, height)
    return bitmap.asImageBitmap()
}
