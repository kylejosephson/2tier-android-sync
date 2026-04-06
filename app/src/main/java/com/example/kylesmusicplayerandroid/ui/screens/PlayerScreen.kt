package com.example.kylesmusicplayerandroid.ui.screens

import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.kylesmusicplayerandroid.data.artwork.AlbumArtRepository
import com.example.kylesmusicplayerandroid.data.service.MediaPlaybackService
import com.example.kylesmusicplayerandroid.viewmodel.AppViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToLong
import kotlin.random.Random

private data class RainColumn(
    val xFrac: Float,
    val speed: Float,
    val offset: Float,
    val trail: Int
)

@Composable
private fun MatrixRainBackground(
    modifier: Modifier = Modifier,
    glyphColor: Color,
    densityScale: Float
) {
    val t by rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    val columns = remember {
        val r = Random(1337)
        List(44) {
            RainColumn(
                xFrac = r.nextFloat(),
                speed = 0.25f + r.nextFloat() * 0.85f,
                offset = r.nextFloat(),
                trail = 8 + r.nextInt(10)
            )
        }
    }

    val chars = remember { "01アイウエオカキクケコサシスセソタチツテトナニヌネノ".toCharArray() }

    val paint = remember {
        AndroidPaint().apply {
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
            textAlign = AndroidPaint.Align.CENTER
        }
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val fontPx = (12f * densityScale).coerceIn(10f, 18f)
        val gap = (14f * densityScale).coerceIn(12f, 22f)

        paint.textSize = fontPx
        paint.color = glyphColor.toArgb()

        val baseA = 0.18f
        val headA = 0.40f

        val native = drawContext.canvas.nativeCanvas

        columns.forEachIndexed { colIndex, c ->
            val x = c.xFrac * w
            val speedPx = (c.speed * h) * 1.15f
            val headY = ((c.offset * h) + (t * speedPx)) % h

            for (j in 0 until c.trail) {
                val y = headY - j * gap
                if (y < -gap || y > h + gap) continue

                val a = if (j == 0) headA else (baseA * (1f - (j.toFloat() / c.trail.toFloat())))
                if (a <= 0f) continue

                val seed = (colIndex * 97 + j * 31) % chars.size
                val ch = chars[seed]

                paint.alpha = (a * 255f).coerceIn(0f, 255f).toInt()
                native.drawText(ch.toString(), x, y, paint)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(vm: AppViewModel) {

    // ---------------------------------------------------------
    // MediaSession refactor (Model B):
    // Ensure playback service is alive while Player UI is active.
    // Service owns ExoPlayer + MediaSession; VM borrows playback via MediaController.
    // ---------------------------------------------------------
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        MediaPlaybackService.ensureServiceRunning(ctx.applicationContext)
    }

    // =========================================================
    // MATRIX PALETTE
    // =========================================================
    val MatrixBg = Color(0xFF040805)
    val MatrixPanel = Color(0xFF06130A)
    val MatrixPanel2 = Color(0xFF040E07)
    val MatrixGreen = Color(0xFF00FF66)
    val MatrixGreenSoft = MatrixGreen.copy(alpha = 0.55f)
    val MatrixGreenFaint = MatrixGreen.copy(alpha = 0.25f)
    val TextPrimary = MatrixGreen.copy(alpha = 0.92f)
    val TextMuted = MatrixGreen.copy(alpha = 0.65f)

    val panelShape = RoundedCornerShape(12.dp)

    fun Modifier.neonPanel(): Modifier =
        this.background(MatrixPanel, panelShape)
            .border(1.dp, MatrixGreenSoft, panelShape)

    fun Modifier.neonPanelDeep(): Modifier =
        this.background(MatrixPanel2, panelShape)
            .border(1.dp, MatrixGreenSoft, panelShape)

    fun Modifier.fauxNeonGlow(cornerDp: Float, glowColor: Color): Modifier =
        this.drawBehind {
            val r = cornerDp.dp.toPx()
            val cr = CornerRadius(r, r)

            val baseA = glowColor.alpha.coerceIn(0f, 1f)

            val widths = listOf(18f, 12f, 8f, 4f)
            val alphas = listOf(0.08f, 0.10f, 0.14f, 0.18f)

            for (k in widths.indices) {
                val w = widths[k].dp.toPx()
                val inset = w / 2f
                drawRoundRect(
                    color = glowColor.copy(alpha = (alphas[k] * baseA).coerceIn(0f, 1f)),
                    topLeft = Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(
                        size.width - 2 * inset,
                        size.height - 2 * inset
                    ),
                    cornerRadius = cr,
                    style = Stroke(width = w)
                )
            }

            val innerW = 1.5f.dp.toPx()
            val inset = innerW / 2f
            drawRoundRect(
                color = glowColor.copy(alpha = (0.55f * baseA).coerceIn(0f, 1f)),
                topLeft = Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(
                    size.width - 2 * inset,
                    size.height - 2 * inset
                ),
                cornerRadius = cr,
                style = Stroke(width = innerW)
            )
        }

    fun Modifier.queueDepthGlow(cornerDp: Float): Modifier =
        this.drawBehind {
            val r = cornerDp.dp.toPx()
            val cr = CornerRadius(r, r)

            val hazeColor = MatrixGreen.copy(alpha = 0.22f)
            val hazeWidths = listOf(10f, 6f, 3f)
            val hazeAlphas = listOf(0.05f, 0.07f, 0.10f)

            for (k in hazeWidths.indices) {
                val w = hazeWidths[k].dp.toPx()
                val inset = w / 2f
                drawRoundRect(
                    color = hazeColor.copy(alpha = hazeAlphas[k]),
                    topLeft = Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(
                        size.width - 2 * inset,
                        size.height - 2 * inset
                    ),
                    cornerRadius = cr,
                    style = Stroke(width = w)
                )
            }

            val inner1 = 2.5f.dp.toPx()
            val inset1 = inner1 / 2f
            drawRoundRect(
                color = MatrixGreen.copy(alpha = 0.12f),
                topLeft = Offset(inset1, inset1),
                size = androidx.compose.ui.geometry.Size(
                    size.width - 2 * inset1,
                    size.height - 2 * inset1
                ),
                cornerRadius = cr,
                style = Stroke(width = inner1)
            )

            val inner2 = 1.25f.dp.toPx()
            val inset2 = inner2 / 2f
            drawRoundRect(
                color = MatrixGreen.copy(alpha = 0.18f),
                topLeft = Offset(inset2, inset2),
                size = androidx.compose.ui.geometry.Size(
                    size.width - 2 * inset2,
                    size.height - 2 * inset2
                ),
                cornerRadius = cr,
                style = Stroke(width = inner2)
            )

            val innerHair = 0.75f.dp.toPx()
            val inset3 = innerHair / 2f
            drawRoundRect(
                color = MatrixGreen.copy(alpha = 0.10f),
                topLeft = Offset(inset3, inset3),
                size = androidx.compose.ui.geometry.Size(
                    size.width - 2 * inset3,
                    size.height - 2 * inset3
                ),
                cornerRadius = cr,
                style = Stroke(width = innerHair)
            )
        }

    // =========================================================
    // Phase 4B: Neon press glow wrapper (BRIGHTER)
    // =========================================================
    @Composable
    fun NeonPressOutlinedButton(
        onClick: () -> Unit,
        enabled: Boolean,
        modifier: Modifier,
        content: @Composable RowScope.() -> Unit
    ) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()

        val glow by animateFloatAsState(
            targetValue = if (pressed) 1f else 0f,
            animationSpec = tween(140, easing = FastOutSlowInEasing),
            label = "pressGlow"
        )

        Box(
            modifier = modifier.drawBehind {
                if (glow > 0f) {
                    val corner = 12.dp.toPx()
                    val cr = CornerRadius(corner, corner)

                    drawRoundRect(
                        color = MatrixGreen.copy(alpha = 0.42f * glow),
                        topLeft = Offset(0f, 0f),
                        size = size,
                        cornerRadius = cr
                    )

                    val insetPx = 1.dp.toPx()
                    drawRoundRect(
                        color = MatrixGreen.copy(alpha = 0.78f * glow),
                        topLeft = Offset(insetPx, insetPx),
                        size = androidx.compose.ui.geometry.Size(
                            size.width - 2 * insetPx,
                            size.height - 2 * insetPx
                        ),
                        cornerRadius = cr,
                        style = Stroke(width = 3.6.dp.toPx())
                    )

                    drawRoundRect(
                        color = MatrixGreen.copy(alpha = 0.16f * glow),
                        topLeft = Offset(0f, 0f),
                        size = size,
                        cornerRadius = cr,
                        style = Stroke(width = 7.dp.toPx())
                    )
                }
            }
        ) {
            OutlinedButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                interactionSource = interaction,
                border = androidx.compose.foundation.BorderStroke(1.dp, MatrixGreen),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = TextPrimary,
                    containerColor = MatrixPanel
                ),
                content = content
            )
        }
    }

    // =========================================================
    // Phase 2.1: Premium subtle breathing (time-based only)
    // =========================================================
    val breathe = rememberInfiniteTransition()
    val artScale by breathe.animateFloat(
        initialValue = 1.00f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val glowPulse by breathe.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val queuePulse = glowPulse
    val density = LocalDensity.current

    val queue = vm.playerQueue
    val idx = vm.playerQueueIndex
    val nowPlaying = vm.nowPlayingSong

    val isPlaying = vm.isPlaying
    val posMs = vm.playbackPositionMs
    val durMs = vm.playbackDurationMs

    var nowPlayingArt by remember(
        nowPlaying?.artist,
        nowPlaying?.album,
        nowPlaying?.contentUri
    ) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(nowPlaying?.artist, nowPlaying?.album, nowPlaying?.contentUri) {
        nowPlayingArt = if (nowPlaying == null) {
            null
        } else {
            val rep = AlbumArtRepository.RepresentativeSong(
                title = nowPlaying.title,
                trackNumber = nowPlaying.trackNumber,
                contentUri = nowPlaying.contentUri.toString()
            )

            AlbumArtRepository.loadAlbumArtBitmap(
                ctx = ctx,
                artist = nowPlaying.artist,
                album = nowPlaying.album,
                representativeSong = rep,
                maxSizePx = 512
            )
        }
    }

    fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val m = totalSec / 60L
        val s = totalSec % 60L
        return "${m}:${s.toString().padStart(2, '0')}"
    }

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }

    val safeDuration = durMs.coerceAtLeast(1L)
    val liveFraction = (posMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val shownFraction = if (isDragging) dragFraction else liveFraction

    // =========================================================
    // Phase 2.2: Metadata entrance choreography (on song change)
    // =========================================================
    val titleX = remember { Animatable(0f) }
    val artistX = remember { Animatable(0f) }
    val albumY = remember { Animatable(0f) }
    val trackA = remember { Animatable(1f) }
    val infoA = remember { Animatable(1f) }

    LaunchedEffect(nowPlaying?.contentUri) {
        if (nowPlaying == null) {
            titleX.snapTo(0f)
            artistX.snapTo(0f)
            albumY.snapTo(0f)
            trackA.snapTo(1f)
            infoA.snapTo(1f)
            return@LaunchedEffect
        }

        val dx = with(density) { 26.dp.toPx() }
        val dy = with(density) { 14.dp.toPx() }

        infoA.snapTo(0f)
        titleX.snapTo(-dx)
        artistX.snapTo(dx)
        albumY.snapTo(dy)
        trackA.snapTo(0f)

        infoA.animateTo(1f, animationSpec = tween(220, easing = FastOutSlowInEasing))

        titleX.animateTo(0f, animationSpec = tween(280, easing = FastOutSlowInEasing))
        delay(70)

        artistX.animateTo(0f, animationSpec = tween(280, easing = FastOutSlowInEasing))
        delay(70)

        albumY.animateTo(0f, animationSpec = tween(280, easing = FastOutSlowInEasing))
        delay(90)

        trackA.animateTo(1f, animationSpec = tween(220, easing = FastOutSlowInEasing))
    }

    // =========================================================
    // Phase 4A: Album Art Tap Shimmer
    // =========================================================
    val shimmer = remember { Animatable(0f) }
    var shimmerTrigger by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(shimmerTrigger) {
        if (shimmerTrigger == 0) return@LaunchedEffect
        shimmer.snapTo(0f)
        shimmer.animateTo(1f, animationSpec = tween(650, easing = FastOutSlowInEasing))
        shimmer.snapTo(0f)
    }

    // =========================================================
    // PHASE 3.1: Matrix Rain background layer (LOCKED)
    // =========================================================
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MatrixBg)
    ) {
        MatrixRainBackground(
            modifier = Modifier.fillMaxSize(),
            glyphColor = MatrixGreen.copy(alpha = 0.35f),
            densityScale = density.density
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {

            // =========================================================
            // LEFT PANEL
            // =========================================================
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(end = 10.dp)
            ) {

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .neonPanel(),
                    color = Color.Transparent,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Text(
                        text = vm.playerBannerMessage,
                        modifier = Modifier.padding(10.dp),
                        fontWeight = FontWeight.SemiBold,
                        color = TextMuted
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    NeonPressOutlinedButton(
                        onClick = { vm.togglePlayPause() },
                        enabled = (nowPlaying != null),
                        modifier = Modifier.width(180.dp)
                    ) {
                        Text(
                            when {
                                nowPlaying == null -> "Play"
                                isPlaying -> "Pause"
                                else -> "Play"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // =========================================================
                // Phase 2.3: Glow-only progress (thumb hidden unless dragging)
                // =========================================================
                Column(modifier = Modifier.fillMaxWidth()) {

                    val beamColor = TextPrimary

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                    ) {
                        Canvas(modifier = Modifier.matchParentSize()) {
                            if (nowPlaying != null && durMs > 0L) {
                                val frac = shownFraction.coerceIn(0f, 1f)
                                val y = size.height / 2f
                                val xEnd = size.width * frac

                                fun drawGlow(widthDp: Float, alpha: Float) {
                                    drawLine(
                                        color = beamColor.copy(alpha = alpha),
                                        start = Offset(0f, y),
                                        end = Offset(xEnd, y),
                                        strokeWidth = widthDp.dp.toPx(),
                                        cap = StrokeCap.Round
                                    )
                                }

                                drawGlow(10f, 0.10f)
                                drawGlow(7f, 0.16f)
                                drawGlow(5f, 0.22f)
                                drawGlow(3f, 0.55f)
                            }
                        }

                        Slider(
                            value = shownFraction,
                            onValueChange = { v ->
                                isDragging = true
                                dragFraction = v.coerceIn(0f, 1f)
                            },
                            onValueChangeFinished = {
                                val targetMs = (dragFraction * safeDuration.toFloat()).roundToLong()
                                vm.seekTo(targetMs)
                                isDragging = false
                            },
                            enabled = (nowPlaying != null && durMs > 0L),
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.Center),
                            colors = SliderDefaults.colors(
                                thumbColor = if (isDragging) MatrixGreen.copy(alpha = 0.90f) else Color.Transparent,
                                activeTrackColor = Color.Transparent,
                                inactiveTrackColor = Color.Transparent
                            )
                        )
                    }

                    Spacer(Modifier.height(2.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        val leftMs =
                            if (isDragging) (dragFraction * safeDuration.toFloat()).roundToLong() else posMs
                        Text(formatTime(leftMs), color = TextMuted)
                        Spacer(Modifier.weight(1f))
                        Text(formatTime(durMs), color = TextMuted)
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {

                    val artCorner = 10f
                    val artShape = RoundedCornerShape(artCorner.dp)
                    val pulsingGlowColor = MatrixGreen.copy(alpha = glowPulse)

                    Surface(
                        modifier = Modifier
                            .size(180.dp)
                            .graphicsLayer {
                                scaleX = artScale
                                scaleY = artScale
                            }
                            .fauxNeonGlow(artCorner, pulsingGlowColor)
                            .background(MatrixPanel2, artShape)
                            .border(1.dp, MatrixGreenSoft, artShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                scope.launch { shimmerTrigger += 1 }
                            },
                        shape = artShape,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                        color = Color.Transparent
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(artShape)
                        ) {
                            if (nowPlayingArt != null) {
                                Image(
                                    bitmap = nowPlayingArt!!.asImageBitmap(),
                                    contentDescription = "Now playing artwork",
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("♪", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
                                }
                            }

                            Canvas(modifier = Modifier.matchParentSize()) {
                                val p = shimmer.value
                                if (p > 0f) {
                                    val w = size.width
                                    val h = size.height
                                    val band = w * 0.35f
                                    val cx = (p * (w + band * 2f)) - band
                                    val brush = Brush.linearGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            MatrixGreen.copy(alpha = 0.22f),
                                            Color.Transparent
                                        ),
                                        start = Offset(cx - band, 0f),
                                        end = Offset(cx + band, h)
                                    )
                                    drawRect(brush = brush)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(
                        modifier = Modifier
                            .height(180.dp)
                            .padding(top = 4.dp)
                            .graphicsLayer { alpha = infoA.value },
                        verticalArrangement = Arrangement.SpaceEvenly
                    ) {

                        Text(
                            text = "Title: ${nowPlaying?.title ?: ""}",
                            color = TextPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.graphicsLayer { translationX = titleX.value }
                        )

                        Text(
                            text = "Artist: ${nowPlaying?.artist ?: ""}",
                            color = TextPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.graphicsLayer { translationX = artistX.value }
                        )

                        Text(
                            text = "Album: ${nowPlaying?.album ?: ""}",
                            color = TextPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.graphicsLayer { translationY = albumY.value }
                        )

                        Text(
                            text = "Track: ${nowPlaying?.trackNumber ?: ""}",
                            color = TextMuted,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.graphicsLayer { alpha = trackA.value }
                        )
                    }
                }
            }

            // =========================================================
            // RIGHT PANEL (QUEUE)
            // =========================================================
            Column(
                modifier = Modifier
                    .width(320.dp)
                    .fillMaxHeight()
                    .neonPanelDeep()
                    .padding(10.dp)
            ) {

                var menuExpanded by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Queue",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        color = TextPrimary
                    )

                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Queue menu", tint = TextPrimary)
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Randomize queue") },
                            onClick = {
                                menuExpanded = false
                                vm.randomizeQueue()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Clear queue") },
                            onClick = {
                                menuExpanded = false
                                vm.clearQueue()
                            }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Surface(
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .queueDepthGlow(12f)
                        .border(1.dp, MatrixGreenFaint, panelShape),
                    color = MatrixPanel2,
                    shape = panelShape
                ) {
                    if (queue.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Queue is empty", color = TextMuted)
                        }
                    } else {
                        LazyColumn {
                            itemsIndexed(queue, key = { i, s -> "${s.contentUri}#$i" }) { i, song ->
                                val isCurrent = (i == idx)

                                val rowAlpha = remember { Animatable(0.88f) }
                                val rowX = remember { Animatable(0f) }

                                LaunchedEffect(isCurrent) {
                                    if (isCurrent) {
                                        val dx = with(density) { 10.dp.toPx() }
                                        rowAlpha.snapTo(0.72f)
                                        rowX.snapTo(dx)
                                        rowAlpha.animateTo(1.00f, animationSpec = tween(210, easing = FastOutSlowInEasing))
                                        rowX.animateTo(0f, animationSpec = tween(240, easing = FastOutSlowInEasing))
                                    } else {
                                        rowAlpha.animateTo(0.88f, animationSpec = tween(180, easing = FastOutSlowInEasing))
                                        rowX.animateTo(0f, animationSpec = tween(180, easing = FastOutSlowInEasing))
                                    }
                                }

                                val rowInteraction = remember { MutableInteractionSource() }
                                val rowPressed by rowInteraction.collectIsPressedAsState()
                                val tapGlow by animateFloatAsState(
                                    targetValue = if (rowPressed) 1f else 0f,
                                    animationSpec = tween(120, easing = FastOutSlowInEasing),
                                    label = "queueTapGlow"
                                )

                                val baseAccent =
                                    if (isCurrent) (0.78f + 0.20f * queuePulse).coerceIn(0f, 1f) else 0.22f
                                val accentAlpha = (baseAccent + 0.26f * tapGlow).coerceIn(0f, 1f)

                                val selectedBgA = if (isCurrent) 0.14f else 0f
                                val pressBgA = 0.18f * tapGlow
                                val bgAlpha = (selectedBgA + pressBgA).coerceIn(0f, 0.30f)

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer {
                                            alpha = rowAlpha.value
                                            translationX = rowX.value
                                        }
                                        .clickable(
                                            interactionSource = rowInteraction,
                                            indication = null
                                        ) { vm.selectQueueIndex(i) }
                                        .background(
                                            color = if (bgAlpha > 0f) MatrixGreen.copy(alpha = bgAlpha) else Color.Transparent
                                        )
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                ) {

                                    Box(
                                        modifier = Modifier
                                            .width(4.dp)
                                            .fillMaxHeight()
                                            .background(MatrixGreen.copy(alpha = accentAlpha), RoundedCornerShape(2.dp))
                                    )

                                    Spacer(Modifier.width(10.dp))

                                    Text(
                                        text = "${song.trackNumber.toString().padStart(2, '0')} - ${song.title}",
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isCurrent) TextPrimary else TextMuted
                                    )
                                }
                                Divider(color = MatrixGreenFaint)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth()) {

                    NeonPressOutlinedButton(
                        onClick = { vm.previousSong() },
                        enabled = true,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Previous", fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(Modifier.width(8.dp))

                    NeonPressOutlinedButton(
                        onClick = { vm.nextSong() },
                        enabled = true,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Next", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}