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
import androidx.compose.ui.draw.shadow
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
    urls: List<String>,
    initialIndex: Int = 0,
    pdfPages: List<String>,
    onClose: () -> Unit,
    onNavigate: (Int) -> Unit
) {
    val url = urls.getOrNull(initialIndex) ?: return
    val isPdf = pdfPages.isNotEmpty()
    val context = LocalContext.current
    val mimeType = remember(url) {
        try { context.contentResolver.getType(Uri.parse(url)) } catch (_: Exception) { null }
    }
    val isSingleVideo = mimeType?.startsWith("video/") == true ||
        url.endsWith(".mp4", true) || url.endsWith(".webm", true) ||
        url.endsWith(".mov", true) || url.endsWith(".avi", true) ||
        url.contains("vid_original_")

    if (isSingleVideo && urls.size == 1) {
        var showOverlay by remember { mutableStateOf(true) }
        var closing by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            VideoPlayer(uri = url, onClose = onClose, closing = closing)
            AnimatedVisibility(
                visible = showOverlay,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(24.dp)
            ) {
                Surface(
                    onClick = { closing = true },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.shadow(8.dp, CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.provider_close), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(48.dp).padding(12.dp))
                }
            }
        }
        return
    }

    if (isPdf) {
        PdfPager(pdfPages, url, onClose, onNavigate)
        return
    }

    if (urls.size > 1) {
        MediaPager(urls, initialIndex, onClose, onNavigate)
        return
    }

    // Single image — full zoom/pan experience
    SingleImage(url = url, onClose = onClose)
}

// --- PDF pager (existing logic) ---

@Composable
private fun PdfPager(
    pdfPages: List<String>,
    url: String,
    onClose: () -> Unit,
    onNavigate: (Int) -> Unit
) {
    val pdfInitialPage = pdfPages.indexOf(url).coerceIn(0, pdfPages.size - 1)
    var currentScale by remember { mutableFloatStateOf(1f) }
    var showOverlay by remember { mutableStateOf(true) }
    val pagerState = rememberPagerState(initialPage = pdfInitialPage) { pdfPages.size }
    LaunchedEffect(pagerState.currentPage) {
        val idx = pagerState.currentPage
        if (idx in pdfPages.indices) onNavigate(idx)
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
                onTap = { showOverlay = !showOverlay },
                onScaleChanged = { if (page == pagerState.currentPage) currentScale = it },
                consumeConditionally = true
            )
        }
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.shadow(8.dp, RoundedCornerShape(50))
                ) {
                    Text(
                        "${pagerState.currentPage + 1} / ${pdfPages.size}",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                Surface(
                    onClick = { onClose() },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.shadow(8.dp, CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.provider_close), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(48.dp).padding(12.dp))
                }
            }
        }
    }
}

// --- Multi-image/video pager ---

@Composable
private fun MediaPager(
    urls: List<String>,
    initialIndex: Int,
    onClose: () -> Unit,
    onNavigate: (Int) -> Unit
) {
    val context = LocalContext.current
    var currentScale by remember { mutableFloatStateOf(1f) }
    var showOverlay by remember { mutableStateOf(true) }
    var closing by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, urls.size - 1)) { urls.size }
    LaunchedEffect(pagerState.currentPage) { onNavigate(pagerState.currentPage) }
    LaunchedEffect(closing) { if (closing) { kotlinx.coroutines.delay(400); onClose() } }
    BackHandler { closing = true }
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f))
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            userScrollEnabled = currentScale <= 1.05f
        ) { page ->
            val mediaUrl = urls[page]
            val isVideo = remember(mediaUrl) {
                val mt = try { context.contentResolver.getType(Uri.parse(mediaUrl)) } catch (_: Exception) { null }
                mt?.startsWith("video/") == true || mediaUrl.endsWith(".mp4", true) ||
                    mediaUrl.endsWith(".webm", true) || mediaUrl.endsWith(".mov", true) ||
                    mediaUrl.endsWith(".avi", true) || mediaUrl.contains("vid_original_")
            }
            if (isVideo) {
                if (page == pagerState.currentPage) {
                    VideoPlayer(uri = mediaUrl, onClose = onClose, closing = closing)
                }
            } else {
                ZoomableImageItem(
                    url = mediaUrl,
                    onTap = { showOverlay = !showOverlay },
                    onScaleChanged = { if (page == pagerState.currentPage) currentScale = it },
                    consumeConditionally = true
                )
            }
        }
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (urls.size > 1) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.shadow(8.dp, RoundedCornerShape(50))
                    ) {
                        Text(
                            "${pagerState.currentPage + 1} / ${urls.size}",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Surface(
                    onClick = { closing = true },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.shadow(8.dp, CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.provider_close), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(48.dp).padding(12.dp))
                }
            }
        }
    }
}

// --- Single image (full pinch-zoom / pan) ---

@Composable
private fun SingleImage(
    url: String,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current

    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offsetX by remember(url) { mutableFloatStateOf(0f) }
    var offsetY by remember(url) { mutableFloatStateOf(0f) }

    var containerSize by remember { mutableStateOf(Size.Zero) }
    var imageSize by remember(url) { mutableStateOf(Size.Zero) }
    var animationJob by remember { mutableStateOf<Job?>(null) }
    var showOverlay by remember { mutableStateOf(true) }
    var lastCentroid by remember { mutableStateOf(Offset.Unspecified) }

    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Scale factor that maps "fit between system bars" to user scale = 1f
    val baseScale = remember(containerSize, imageSize, statusBarHeight, navBarHeight) {
        if (containerSize == Size.Zero || imageSize == Size.Zero) 1f
        else {
            val imageAspect = imageSize.width / imageSize.height
            val containerAspect = containerSize.width / containerSize.height
            val fittedWidth = if (imageAspect > containerAspect) containerSize.width else containerSize.height * imageAspect
            val fittedHeight = if (imageAspect > containerAspect) containerSize.width / imageAspect else containerSize.height
            val barsPx = with(density) { statusBarHeight.toPx() + navBarHeight.toPx() }
            val sHeight = if (fittedHeight > 0f) (containerSize.height - barsPx) / fittedHeight else 1f
            val sWidth = if (fittedWidth > 0f) containerSize.width / fittedWidth else 1f
            minOf(sHeight, sWidth).coerceIn(0.1f, 1f)
        }
    }

    fun getMaxOffsets(currentScale: Float): Pair<Float, Float> {
        if (imageSize == Size.Zero || containerSize == Size.Zero) return 0f to 0f
        val effectiveScale = currentScale * baseScale
        val imageAspectRatio = imageSize.width / imageSize.height
        val containerAspectRatio = containerSize.width / containerSize.height
        val contentWidth = if (imageAspectRatio > containerAspectRatio) containerSize.width else containerSize.height * imageAspectRatio
        val contentHeight = if (imageAspectRatio > containerAspectRatio) containerSize.width / imageAspectRatio else containerSize.height
        val maxX = (contentWidth * effectiveScale - containerSize.width).coerceAtLeast(0f) / 2f
        val maxY = (contentHeight * effectiveScale - containerSize.height).coerceAtLeast(0f) / 2f
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
                    onTap = { if (scale <= 1.05f) showOverlay = !showOverlay },
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
                                    spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy, visibilityThreshold = 0.001f)
                                ) {
                                    scale = value
                                    val r = if (startScale != 0f) value / startScale else 1f
                                    val ux = startOffsetX * r + (tapOffset.x - center.x) * (1f - r)
                                    val uy = startOffsetY * r + (tapOffset.y - center.y) * (1f - r)
                                    val (maxX, maxY) = getMaxOffsets(value)
                                    offsetX = ux.coerceIn(-maxX, maxX)
                                    offsetY = uy.coerceIn(-maxY, maxY)
                                }
                            } else {
                                val targetScale = 3f
                                val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                AnimationState(startScale).animateTo(
                                    targetScale,
                                    spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy, visibilityThreshold = 0.001f)
                                ) {
                                    scale = value
                                    val r = if (startScale != 0f) value / startScale else 1f
                                    val ux = startOffsetX * r + (tapOffset.x - center.x) * (1f - r)
                                    val uy = startOffsetY * r + (tapOffset.y - center.y) * (1f - r)
                                    val (maxX, maxY) = getMaxOffsets(value)
                                    offsetX = ux.coerceIn(-maxX, maxX)
                                    offsetY = uy.coerceIn(-maxY, maxY)
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
            onSuccess = { state -> imageSize = state.painter.intrinsicSize },
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
                                if (zoomChange != 1f || panAmount > touchSlop) pastTouchSlop = true
                            }
                            if (pastTouchSlop) {
                                val centroid = event.calculateCentroid(useCurrent = false)
                                if (zoomChange != 1f && centroid != Offset.Unspecified) lastCentroid = centroid
                                if (zoomChange != 1f || panChange != Offset.Zero) {
                                    val oldVisualScale = scale
                                    logicalScale = (logicalScale * zoomChange).coerceIn(0.1f, 30f)
                                    val newVisualScale = when {
                                        logicalScale < 1f -> 1f - rubberBandValue(1f - logicalScale, 1f)
                                        logicalScale > 10f -> 10f + rubberBandValue(logicalScale - 10f, 5f)
                                        else -> logicalScale
                                    }
                                    val r = if (oldVisualScale != 0f) newVisualScale / oldVisualScale else 1f
                                    val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                    logicalOffsetX = logicalOffsetX * r + (centroid.x - center.x) * (1f - r) + panChange.x
                                    logicalOffsetY = logicalOffsetY * r + (centroid.y - center.y) * (1f - r) + panChange.y
                                    val (maxX, maxY) = getMaxOffsets(newVisualScale)
                                    scale = newVisualScale
                                    offsetX = logicalOffsetX.coerceIn(-maxX, maxX)
                                    offsetY = logicalOffsetY.coerceIn(-maxY, maxY)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            }
                            if (event.changes.size == 1) {
                                val change = event.changes.first()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                            } else velocityTracker.resetTracking()
                        } while (event.changes.any { it.pressed })
                        val rawVelocity = velocityTracker.calculateVelocity()
                        val maxV = with(density) { 2500.dp.toPx() }
                        val velocity = Velocity(
                            x = if (rawVelocity.x.isNaN()) 0f else rawVelocity.x.coerceIn(-maxV, maxV),
                            y = if (rawVelocity.y.isNaN()) 0f else rawVelocity.y.coerceIn(-maxV, maxV)
                        )
                        animationJob = scope.launch {
                            if (scale < 0.95f || scale > 10.05f) {
                                val sS = scale; val sX = offsetX; val sY = offsetY
                                val targetS = scale.coerceIn(1f, 10f)
                                val (targetMaxX, targetMaxY) = getMaxOffsets(targetS)
                                val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                val pivot = if (lastCentroid != Offset.Unspecified) lastCentroid else center
                                val targetR = if (sS != 0f) targetS / sS else 1f
                                val fpx = sX * targetR + (pivot.x - center.x) * (1f - targetR)
                                val fpy = sY * targetR + (pivot.y - center.y) * (1f - targetR)
                                val targetX = fpx.coerceIn(-targetMaxX, targetMaxX)
                                val targetY = fpy.coerceIn(-targetMaxY, targetMaxY)
                                AnimationState(0f).animateTo(1f, spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy)) {
                                    val cs = sS + (targetS - sS) * value
                                    scale = cs
                                    val r = if (sS != 0f) cs / sS else 1f
                                    offsetX = (sX * r + (pivot.x - center.x) * (1f - r)) + (targetX - fpx) * value
                                    offsetY = (sY * r + (pivot.y - center.y) * (1f - r)) + (targetY - fpy) * value
                                }
                            } else {
                                launch {
                                    val (maxX, _) = getMaxOffsets(scale)
                                    if (offsetX > maxX || offsetX < -maxX) {
                                        offsetX = offsetX.coerceIn(-maxX, maxX)
                                    } else if (velocity.x != 0f) {
                                        val decay = splineBasedDecay<Float>(density)
                                        AnimationState(initialValue = offsetX, initialVelocity = velocity.x)
                                            .animateDecay(decay) {
                                                val (curMaxX, _) = getMaxOffsets(scale)
                                                if (value > curMaxX || value < -curMaxX) {
                                                    offsetX = value.coerceIn(-curMaxX, curMaxX)
                                                    cancelAnimation()
                                                } else offsetX = value
                                            }
                                    }
                                }
                                launch {
                                    val (_, maxY) = getMaxOffsets(scale)
                                    if (offsetY > maxY || offsetY < -maxY) {
                                        offsetY = offsetY.coerceIn(-maxY, maxY)
                                    } else if (velocity.y != 0f) {
                                        val decay = splineBasedDecay<Float>(density)
                                        AnimationState(initialValue = offsetY, initialVelocity = velocity.y)
                                            .animateDecay(decay) {
                                                val (_, curMaxY) = getMaxOffsets(scale)
                                                if (value > curMaxY || value < -curMaxY) {
                                                    offsetY = value.coerceIn(-curMaxY, curMaxY)
                                                    cancelAnimation()
                                                } else offsetY = value
                                            }
                                    }
                                }
                            }
                        }
                        velocityTracker.resetTracking()
                    }
                }
                .graphicsLayer(scaleX = scale * baseScale, scaleY = scale * baseScale, translationX = offsetX, translationY = offsetY),
            contentScale = ContentScale.Fit
        )
        if (imageSize == Size.Zero) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White, strokeWidth = 2.dp)
        }
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(24.dp)
        ) {
            Surface(
                onClick = onClose,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.shadow(8.dp, CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.provider_close), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(48.dp).padding(12.dp))
            }
        }
    }

    BackHandler {
        if (scale > 1.05f) {
            animationJob?.cancel()
            animationJob = scope.launch {
                val startScale = scale; val startOffsetX = offsetX; val startOffsetY = offsetY
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
