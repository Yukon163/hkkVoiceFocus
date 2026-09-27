// Adapted from OpenAHU/AHUTong-Android, commit 0492acc076c81533a8238acd1b796c2c6e0fe488.
// SPDX-License-Identifier: GPL-3.0-only
// Changes: package and palette integration for Voice Focus (2026-09-27).
package com.hkk.voicefocus.ui.radiant.utils

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.fastCoerceIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class InteractiveHighlight(
    val animationScope: CoroutineScope,
    val userDragEnabled: Boolean = true,
    val highlightRadiusMultiplier: Float = 1.5f,
    val fixedHighlightPressProgress: Float? = null,
    val drawGlobalPressOverlay: Boolean = true,
    val holdHighlightPositionOnRelease: Boolean = false,
    val positionReturnDampingRatio: Float = 0.5f,
    val releaseAnimationDurationMillis: Int? = null,
    val onPressStart: () -> Unit = {},
    val onPressEnd: () -> Unit = {},
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset }
) {

    private val pressProgressAnimationSpec =
        spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec =
        spring(positionReturnDampingRatio, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val positionAnimation =
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero
    private var releasedHighlightPosition: Offset? = null
    val pressProgress: Float get() = pressProgressAnimation.value
    val offset: Offset get() = positionAnimation.value - startPosition

    private val shader =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RuntimeShader(
                """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;

half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}"""
            )
        } else {
            null
        }

    val modifier: Modifier =
        if (userDragEnabled) {
            Modifier.drawWithContent {
                val progress = pressProgressAnimation.value
                if (progress > 0f) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null) {
                        if (drawGlobalPressOverlay) {
                            drawRect(
                                Color.White.copy(0.08f * progress),
                                blendMode = BlendMode.Plus
                            )
                        }
                        shader.apply {
                            val highlightPosition = position(
                                size,
                                releasedHighlightPosition ?: positionAnimation.value
                            )
                            val highlightProgress = fixedHighlightPressProgress ?: progress
                            setFloatUniform("size", size.width, size.height)
                            setColorUniform("color", Color.White.copy(0.15f * highlightProgress).toArgb())
                            setFloatUniform("radius", size.minDimension * highlightRadiusMultiplier)
                            setFloatUniform(
                                "position",
                                highlightPosition.x.fastCoerceIn(0f, size.width),
                                highlightPosition.y.fastCoerceIn(0f, size.height)
                            )
                        }
                        drawRect(
                            ShaderBrush(shader),
                            blendMode = BlendMode.Plus
                        )
                    } else {
                        val highlightProgress = fixedHighlightPressProgress ?: progress
                        if (drawGlobalPressOverlay) {
                            drawRect(
                                Color.White.copy(0.25f * highlightProgress),
                                blendMode = BlendMode.Plus
                            )
                        } else {
                            val center = position(
                                size,
                                releasedHighlightPosition ?: positionAnimation.value
                            )
                            drawCircle(
                                color = Color.White.copy(0.25f * highlightProgress),
                                radius = size.minDimension * highlightRadiusMultiplier,
                                center = Offset(
                                    center.x.fastCoerceIn(0f, size.width),
                                    center.y.fastCoerceIn(0f, size.height)
                                ),
                                blendMode = BlendMode.Plus
                            )
                        }
                    }
                }

                drawContent()
            }
        } else {
            Modifier
        }

    val gestureModifier: Modifier =
        if (userDragEnabled) {
            Modifier.pointerInput(animationScope) {
                inspectDragGestures(
                    onDragStart = { down ->
                        onPressStart()
                        releasedHighlightPosition = null
                        startPosition = down.position
                        animationScope.launch {
                            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                            launch { positionAnimation.snapTo(startPosition) }
                        }
                    },
                    onDragEnd = { up ->
                        onPressEnd()
                        if (holdHighlightPositionOnRelease) {
                            releasedHighlightPosition = up.position
                        }
                        animationScope.launch {
                            launch {
                                releasePressProgress()
                                releasedHighlightPosition = null
                            }
                            launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                        }
                    },
                    onDragCancel = {
                        onPressEnd()
                        if (holdHighlightPositionOnRelease) {
                            releasedHighlightPosition = positionAnimation.value
                        }
                        animationScope.launch {
                            launch {
                                releasePressProgress()
                                releasedHighlightPosition = null
                            }
                            launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                        }
                    }
                ) { change, _ ->
                    animationScope.launch { positionAnimation.snapTo(change.position) }
                }
            }
        } else {
            Modifier
        }

    private suspend fun releasePressProgress() {
        val durationMillis = releaseAnimationDurationMillis
        if (durationMillis != null) {
            pressProgressAnimation.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = durationMillis,
                    easing = LinearEasing
                )
            )
        } else {
            pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec)
        }
    }
}
