package com.newoether.agora.ui.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun ZoomableImage(
    model: Any?,
    modifier: Modifier = Modifier,
    onSingleTap: (() -> Unit)? = null,
    contentScale: ContentScale = ContentScale.Fit,
    onScaleChanged: ((Float) -> Unit)? = null,
    resetZoomTrigger: Int = 0
) {
    val scope = rememberCoroutineScope()

    var scale by remember(model) { mutableStateOf(1f) }
    var offsetX by remember(model) { mutableStateOf(0f) }
    var offsetY by remember(model) { mutableStateOf(0f) }
    var containerSize by remember { mutableStateOf(Size.Zero) }
    var imageSize by remember { mutableStateOf(Size.Zero) }
    var animationJob by remember { mutableStateOf<Job?>(null) }

    if (onScaleChanged != null) {
        val currentOnScaleChanged by rememberUpdatedState(onScaleChanged)
        LaunchedEffect(Unit) {
            snapshotFlow { scale }.collect { currentOnScaleChanged.invoke(it) }
        }
    }

    LaunchedEffect(resetZoomTrigger) {
        if (resetZoomTrigger > 0 && scale > 1.05f) {
            animationJob?.cancel()
            animationJob = scope.launch {
                val sS = scale; val sX = offsetX; val sY = offsetY
                AnimationState(0f).animateTo(1f, spring(stiffness = Spring.StiffnessLow)) {
                    scale = sS + (1f - sS) * value
                    offsetX = sX + (0f - sX) * value
                    offsetY = sY + (0f - sY) * value
                }
            }
        }
    }

    fun maxOffsets(currentScale: Float): Pair<Float, Float> {
        if (imageSize == Size.Zero || containerSize == Size.Zero) return 0f to 0f
        val iar = imageSize.width / imageSize.height
        val car = containerSize.width / containerSize.height
        val cw = if (iar > car) containerSize.width else containerSize.height * iar
        val ch = if (iar > car) containerSize.width / iar else containerSize.height
        return ((cw * currentScale - containerSize.width).coerceAtLeast(0f) / 2f) to
               ((ch * currentScale - containerSize.height).coerceAtLeast(0f) / 2f)
    }

    fun clampOffset(value: Float, max: Float, dim: Float): Float {
        if (max <= 0f) return 0f
        val c = 0.45f
        return when {
            value > max -> max + (value - max) * c * dim / (dim + c * (value - max))
            value < -max -> -max - (-max - value) * c * dim / (dim + c * (-max - value))
            else -> value
        }
    }

    fun visualScale(logical: Float): Float = when {
        logical < 1f -> 1f - (1f - logical) * 0.45f * 1f / (1f + 0.45f * (1f - logical))
        logical > 10f -> 10f + (logical - 10f) * 0.45f * 5f / (5f + 0.45f * (logical - 10f))
        else -> logical
    }

    coil.compose.AsyncImage(
        model = model,
        contentDescription = null,
        onSuccess = { imageSize = it.painter.intrinsicSize },
        modifier = modifier
            .onSizeChanged { containerSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(model) {
                detectTapGestures(
                    onTap = { onSingleTap?.invoke() },
                    onDoubleTap = { tapOffset ->
                        animationJob?.cancel()
                        animationJob = scope.launch {
                            val sS = scale; val sX = offsetX; val sY = offsetY
                            val targetS = if (sS > 1.05f) 1f else 3f
                            val ctr = Offset(containerSize.width / 2f, containerSize.height / 2f)
                            AnimationState(sS).animateTo(targetS, spring(Spring.StiffnessMediumLow, Spring.DampingRatioNoBouncy, 0.001f)) {
                                scale = value
                                val r = if (sS != 0f) value / sS else 1f
                                val (mX, mY) = maxOffsets(value)
                                offsetX = (sX * r + (tapOffset.x - ctr.x) * (1f - r)).coerceIn(-mX, mX)
                                offsetY = (sY * r + (tapOffset.y - ctr.y) * (1f - r)).coerceIn(-mY, mY)
                            }
                        }
                    }
                )
            }
            .pointerInput(model) {
                // Logical scale resets to visual scale at each gesture start
                var logS = scale
                while (true) {
                    logS = scale
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        animationJob?.cancel()
                        logS = (logS * zoom).coerceIn(0.1f, 30f)
                        val newScale = visualScale(logS)
                        val oldScale = scale
                        val r = if (oldScale != 0f) newScale / oldScale else 1f
                        val ctr = Offset(containerSize.width / 2f, containerSize.height / 2f)
                        val rawX = offsetX * r + (centroid.x - ctr.x) * (1f - r) + pan.x
                        val rawY = offsetY * r + (centroid.y - ctr.y) * (1f - r) + pan.y
                        scale = newScale
                        val (mX, mY) = maxOffsets(newScale)
                        offsetX = clampOffset(rawX, mX, containerSize.width)
                        offsetY = clampOffset(rawY, mY, containerSize.height)
                    }
                }
            }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY
            ),
        contentScale = contentScale
    )
}
