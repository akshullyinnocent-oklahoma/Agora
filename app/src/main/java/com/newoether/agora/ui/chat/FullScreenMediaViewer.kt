package com.newoether.agora.ui.chat

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun FullScreenMediaViewer(
    url: String,
    pdfPages: List<String>,
    onClose: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    val mimeType = remember(url) {
        try { context.contentResolver.getType(Uri.parse(url)) } catch (_: Exception) { null }
    }
    val isVideo = mimeType?.startsWith("video/") == true ||
        url.endsWith(".mp4", true) || url.endsWith(".webm", true) ||
        url.endsWith(".mov", true) || url.endsWith(".avi", true) ||
        url.contains("vid_original_")

    if (isVideo) {
        VideoPlayer(uri = url, onClose = onClose)
    } else {
        if (pdfPages.isNotEmpty()) {
            val pdfInitialPage = pdfPages.indexOf(url).coerceIn(0, pdfPages.size - 1)
            var currentScale by remember { mutableFloatStateOf(1f) }
            val pagerState = rememberPagerState(initialPage = pdfInitialPage) { pdfPages.size }
            LaunchedEffect(pagerState.currentPage) {
                val idx = pagerState.currentPage
                if (idx in pdfPages.indices) onNavigate(pdfPages[idx])
            }
            BackHandler { onClose() }
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f))
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1,
                    userScrollEnabled = currentScale <= 1.05f
                ) { page ->
                    ZoomableImageItem(
                        url = pdfPages[page],
                        onTap = { onClose() },
                        onScaleChanged = { if (page == pagerState.currentPage) currentScale = it },
                        consumeConditionally = true
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp).statusBarsPadding()
                ) {
                    Text(
                        "${pagerState.currentPage + 1} / ${pdfPages.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
                IconButton(
                    onClick = { onClose() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.provider_close),
                        tint = Color.White
                    )
                }
            }
        } else {
            val scope = rememberCoroutineScope()
            val density = LocalDensity.current
            val viewConfiguration = LocalViewConfiguration.current

            var scale by remember(url) { mutableFloatStateOf(1f) }
            var offsetX by remember(url) { mutableFloatStateOf(0f) }
            var offsetY by remember(url) { mutableFloatStateOf(0f) }

            var containerSize by remember { mutableStateOf(Size.Zero) }
            var imageSize by remember { mutableStateOf(Size.Zero) }
            var animationJob by remember { mutableStateOf<Job?>(null) }
            var lastCentroid by remember { mutableStateOf(Offset.Unspecified) }

            fun getMaxOffsets(currentScale: Float): Pair<Float, Float> {
                if (imageSize == Size.Zero || containerSize == Size.Zero) return 0f to 0f
                val imageAspectRatio = imageSize.width / imageSize.height
                val containerAspectRatio = containerSize.width / containerSize.height

                val contentWidth = if (imageAspectRatio > containerAspectRatio) containerSize.width else containerSize.height * imageAspectRatio
                val contentHeight = if (imageAspectRatio > containerAspectRatio) containerSize.width / imageAspectRatio else containerSize.height

                val maxX = (contentWidth * currentScale - containerSize.width).coerceAtLeast(0f) / 2f
                val maxY = (contentHeight * currentScale - containerSize.height).coerceAtLeast(0f) / 2f
                return maxX to maxY
            }

            fun rubberBandValue(fullDelta: Float, dimension: Float): Float {
                if (dimension <= 0f) return 0f
                val c = 0.45f
                return (fullDelta * c * dimension) / (dimension + c * fullDelta)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f))
                    .onSizeChanged { containerSize = Size(it.width.toFloat(), it.height.toFloat()) }
                    .pointerInput(url) {
                        detectTapGestures(
                            onTap = {
                                if (scale <= 1.05f) onClose()
                                    },
                            onDoubleTap = { tapOffset ->
                                animationJob?.cancel()
                                animationJob = scope.launch {
                                    val startScale = scale
                                    val startOffsetX = offsetX
                                    val startOffsetY = offsetY

                                    if (startScale > 1.05f) {
                                        val targetScale = 1f
                                        val center = Offset(containerSize.width / 2f, containerSize.height / 2f)

                                        AnimationState(startScale).animateTo(
                                            targetScale,
                                            spring(
                                                stiffness = Spring.StiffnessMediumLow,
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                visibilityThreshold = 0.001f
                                            )
                                        ) {
                                            scale = value
                                            val r = if (startScale != 0f) value / startScale else 1f
                                            val unconstrainedX = startOffsetX * r + (tapOffset.x - center.x) * (1f - r)
                                            val unconstrainedY = startOffsetY * r + (tapOffset.y - center.y) * (1f - r)

                                            val (maxX, maxY) = getMaxOffsets(value)
                                            offsetX = unconstrainedX.coerceIn(-maxX, maxX)
                                            offsetY = unconstrainedY.coerceIn(-maxY, maxY)
                                        }
                                    } else {
                                        val targetScale = 3f
                                        val center = Offset(containerSize.width / 2f, containerSize.height / 2f)

                                        AnimationState(startScale).animateTo(
                                            targetScale,
                                            spring(
                                                stiffness = Spring.StiffnessMediumLow,
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                visibilityThreshold = 0.001f
                                            )
                                        ) {
                                            scale = value
                                            val r = if (startScale != 0f) value / startScale else 1f

                                            val unconstrainedX = startOffsetX * r + (tapOffset.x - center.x) * (1f - r)
                                            val unconstrainedY = startOffsetY * r + (tapOffset.y - center.y) * (1f - r)
                                            val (maxX, maxY) = getMaxOffsets(value)
                                            offsetX = unconstrainedX.coerceIn(-maxX, maxX)
                                            offsetY = unconstrainedY.coerceIn(-maxY, maxY)
                                        }
                                    }
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                coil.compose.AsyncImage(
                    model = url,
                    contentDescription = null,
                    onSuccess = { state ->
                        imageSize = state.painter.intrinsicSize
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(url) {
                            val velocityTracker = VelocityTracker()
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                animationJob?.cancel()
                                var pastTouchSlop = false
                                val touchSlop = viewConfiguration.touchSlop
                                lastCentroid = Offset.Unspecified
                                var logicalScale = scale
                                var logicalOffsetX = offsetX
                                var logicalOffsetY = offsetY
                                do {
                                    val event = awaitPointerEvent()
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()
                                    if (!pastTouchSlop) {
                                        val panAmount = panChange.getDistance()
                                        if (zoomChange != 1f || panAmount > touchSlop) {
                                            pastTouchSlop = true
                                        }
                                    }
                                    if (pastTouchSlop) {
                                        val centroid = event.calculateCentroid(useCurrent = false)
                                        if (zoomChange != 1f && centroid != Offset.Unspecified) {
                                            lastCentroid = centroid
                                        }
                                        if (zoomChange != 1f || panChange != Offset.Zero) {
                                            val oldVisualScale = scale
                                            logicalScale = (logicalScale * zoomChange).coerceIn(0.1f, 30f)
                                            val newVisualScale = if (logicalScale < 1f) {
                                                1f - rubberBandValue(1f - logicalScale, 1f)
                                            } else if (logicalScale > 10f) {
                                                10f + rubberBandValue(logicalScale - 10f, 5f)
                                            } else {
                                                logicalScale
                                            }
                                            val r = if (oldVisualScale != 0f) newVisualScale / oldVisualScale else 1f
                                            val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                            logicalOffsetX = logicalOffsetX * r + (centroid.x - center.x) * (1f - r) + panChange.x
                                            logicalOffsetY = logicalOffsetY * r + (centroid.y - center.y) * (1f - r) + panChange.y
                                            val (maxX, maxY) = getMaxOffsets(newVisualScale)
                                            scale = newVisualScale
                                            offsetX = if (logicalOffsetX > maxX) maxX + rubberBandValue(logicalOffsetX - maxX, containerSize.width)
                                            else if (logicalOffsetX < -maxX) -maxX - rubberBandValue(-maxX - logicalOffsetX, containerSize.width)
                                            else logicalOffsetX
                                            offsetY = if (logicalOffsetY > maxY) maxY + rubberBandValue(logicalOffsetY - maxY, containerSize.height)
                                            else if (logicalOffsetY < -maxY) -maxY - rubberBandValue(-maxY - logicalOffsetY, containerSize.height)
                                            else logicalOffsetY
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                        }
                                    }
                                    if (event.changes.size == 1) {
                                        val change = event.changes.first()
                                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                                    } else {
                                        velocityTracker.resetTracking()
                                    }
                                } while (event.changes.any { it.pressed })
                                val rawVelocity = velocityTracker.calculateVelocity()
                                val maxV = with(density) { 2500.dp.toPx() }
                                val velocity = Velocity(
                                    x = if (rawVelocity.x.isNaN()) 0f else rawVelocity.x.coerceIn(-maxV, maxV),
                                    y = if (rawVelocity.y.isNaN()) 0f else rawVelocity.y.coerceIn(-maxV, maxV)
                                )

                                animationJob = scope.launch {
                                    val rbCoeff = 0.45f
                                    if (scale < 0.95f || scale > 10.05f) {
                                        val sS = scale
                                        val sX = offsetX
                                        val sY = offsetY
                                        val targetS = scale.coerceIn(1f, 10f)
                                        val (targetMaxX, targetMaxY) = getMaxOffsets(targetS)
                                        val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                        val pivot = if (lastCentroid != Offset.Unspecified) lastCentroid else center
                                        val targetR = if (sS != 0f) targetS / sS else 1f
                                        val finalPivotX = sX * targetR + (pivot.x - center.x) * (1f - targetR)
                                        val finalPivotY = sY * targetR + (pivot.y - center.y) * (1f - targetR)
                                        val targetX = finalPivotX.coerceIn(-targetMaxX, targetMaxX)
                                        val targetY = finalPivotY.coerceIn(-targetMaxY, targetMaxY)
                                        AnimationState(0f).animateTo(
                                            1f,
                                            spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy)
                                        ) {
                                            val currentScale = sS + (targetS - sS) * value
                                            scale = currentScale
                                            val r = if (sS != 0f) currentScale / sS else 1f
                                            val pivotOffsetX = sX * r + (pivot.x - center.x) * (1f - r)
                                            val pivotOffsetY = sY * r + (pivot.y - center.y) * (1f - r)
                                            offsetX = pivotOffsetX + (targetX - finalPivotX) * value
                                            offsetY = pivotOffsetY + (targetY - finalPivotY) * value
                                        }
                                    } else {
                                        launch {
                                            val (maxX, _) = getMaxOffsets(scale)
                                            if (offsetX > maxX || offsetX < -maxX) {
                                                val targetX = offsetX.coerceIn(-maxX, maxX)
                                                AnimationState(initialValue = offsetX, initialVelocity = velocity.x * rbCoeff)
                                                    .animateTo(targetX, spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy)) {
                                                        offsetX = value
                                                    }
                                            } else if (velocity.x != 0f) {
                                                val decay = splineBasedDecay<Float>(density)
                                                var hitX = false
                                                var velX = 0f
                                                var posX = 0f
                                                AnimationState(initialValue = offsetX, initialVelocity = velocity.x)
                                                    .animateDecay(decay) {
                                                        val (curMaxX, _) = getMaxOffsets(scale)
                                                        if (value > curMaxX || value < -curMaxX) {
                                                            velX = this.velocity
                                                            posX = value
                                                            hitX = true
                                                            cancelAnimation()
                                                        } else {
                                                            offsetX = value
                                                        }
                                                    }
                                                if (hitX) {
                                                    val (curMaxX, _) = getMaxOffsets(scale)
                                                    val finalTargetX = posX.coerceIn(-curMaxX, curMaxX)
                                                    AnimationState(initialValue = posX, initialVelocity = velX * rbCoeff)
                                                        .animateTo(finalTargetX, spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy)) {
                                                            offsetX = value
                                                        }
                                                }
                                            }
                                        }
                                        launch {
                                            val (_, maxY) = getMaxOffsets(scale)
                                            if (offsetY > maxY || offsetY < -maxY) {
                                                val targetY = offsetY.coerceIn(-maxY, maxY)
                                                AnimationState(initialValue = offsetY, initialVelocity = velocity.y * rbCoeff)
                                                    .animateTo(targetY, spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy)) {
                                                        offsetY = value
                                                    }
                                            } else if (velocity.y != 0f) {
                                                val decay = splineBasedDecay<Float>(density)
                                                var hitY = false
                                                var velY = 0f
                                                var posY = 0f
                                                AnimationState(initialValue = offsetY, initialVelocity = velocity.y)
                                                    .animateDecay(decay) {
                                                        val (_, curMaxY) = getMaxOffsets(scale)
                                                        if (value > curMaxY || value < -curMaxY) {
                                                            velY = this.velocity
                                                            posY = value
                                                            hitY = true
                                                            cancelAnimation()
                                                        } else {
                                                            offsetY = value
                                                        }
                                                    }
                                                if (hitY) {
                                                    val (_, curMaxY) = getMaxOffsets(scale)
                                                    val finalTargetY = posY.coerceIn(-curMaxY, curMaxY)
                                                    AnimationState(initialValue = posY, initialVelocity = velY * rbCoeff)
                                                        .animateTo(finalTargetY, spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy)) {
                                                            offsetY = value
                                                        }
                                                }
                                            }
                                        }
                                    }
                                }
                                velocityTracker.resetTracking()
                            }
                        }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        ),
                    contentScale = ContentScale.Fit
                )
                if (imageSize == Size.Zero) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White, strokeWidth = 2.dp)
                }
                // PDF page counter and navigation
                val pdfPageIndex = pdfPages.indexOf(url)
                if (pdfPageIndex >= 0) {
                    Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.TopStart).padding(12.dp).statusBarsPadding()) {
                        Text("${pdfPageIndex + 1} / ${pdfPages.size}", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                    }
                    if (pdfPageIndex > 0) Box(Modifier.fillMaxHeight().fillMaxWidth(0.2f).align(Alignment.CenterStart).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onNavigate(pdfPages[pdfPageIndex - 1]) })
                    if (pdfPageIndex < pdfPages.size - 1) Box(Modifier.fillMaxHeight().fillMaxWidth(0.2f).align(Alignment.CenterEnd).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onNavigate(pdfPages[pdfPageIndex + 1]) })
                }

                // Close button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.provider_close),
                        tint = Color.White
                    )
                }
            }

            BackHandler {
                if (scale > 1.05f) {
                    animationJob?.cancel()
                    animationJob = scope.launch {
                        val startScale = scale
                        val startOffsetX = offsetX
                        val startOffsetY = offsetY
                        AnimationState(0f).animateTo(1f, spring(stiffness = Spring.StiffnessLow)) {
                            scale = startScale + (1f - startScale) * value
                            offsetX = startOffsetX + (0f - startOffsetX) * value
                            offsetY = startOffsetY + (0f - startOffsetY) * value
                        }
                    }
                } else {
                    onClose()
                }
            }
        }
    }
}
