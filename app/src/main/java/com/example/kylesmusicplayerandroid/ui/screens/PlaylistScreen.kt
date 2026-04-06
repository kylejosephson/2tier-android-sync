package com.example.kylesmusicplayerandroid.ui.screens

import android.content.Context
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.kylesmusicplayerandroid.data.mediastore.MediaStoreSong
import com.example.kylesmusicplayerandroid.data.service.MediaPlaybackService
import com.example.kylesmusicplayerandroid.viewmodel.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * Persist playlist-list scroll position across tab switches.
 * Uses a polling loop for maximum Compose compatibility.
 */
private object PlaylistUiCache {
    var index: Int = 0
    var offset: Int = 0
}

// Unique names to avoid redeclaration with other screens in same package
private data class PlaylistRainColumn(
    val xFrac: Float,
    val speed: Float,
    val offset: Float,
    val trail: Int
)

@Composable
private fun PlaylistMatrixRainBackground(
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
            PlaylistRainColumn(
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

@Composable
fun PlaylistScreen(vm: AppViewModel) {

    // ---------------------------------------------------------
    // MediaSession refactor (Model B):
    // Keep playback service alive while this tab is active.
    // Service owns ExoPlayer + MediaSession; VM borrows playback via MediaController.
    // ---------------------------------------------------------
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        MediaPlaybackService.ensureServiceRunning(ctx.applicationContext)
    }

    // =========================================================
    // MATRIX PALETTE (match Player/Library)
    // =========================================================
    val MatrixBg = Color(0xFF040805)
    val MatrixPanel = Color(0xFF06130A)
    val MatrixPanel2 = Color(0xFF040E07)
    val MatrixGreen = Color(0xFF00FF66)
    val MatrixGreenSoft = MatrixGreen.copy(alpha = 0.55f)
    val MatrixGreenFaint = MatrixGreen.copy(alpha = 0.25f)
    val TextPrimary = MatrixGreen.copy(alpha = 0.92f)
    val TextMuted = MatrixGreen.copy(alpha = 0.65f)

    // “warning” tone for missing entries (still Matrix-y)
    val MissingText = Color(0xFF00FF66).copy(alpha = 0.45f)

    val panelShape = RoundedCornerShape(12.dp)

    fun Modifier.neonPanel(): Modifier =
        this.background(MatrixPanel, panelShape)
            .border(1.dp, MatrixGreenSoft, panelShape)

    fun Modifier.neonPanelDeep(): Modifier =
        this.background(MatrixPanel2, panelShape)
            .border(1.dp, MatrixGreenSoft, panelShape)

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

    fun glowTextStyle(on: Boolean): TextStyle {
        return if (!on) TextStyle.Default else TextStyle(
            shadow = Shadow(
                color = MatrixGreen.copy(alpha = 0.85f),
                offset = Offset(0f, 0f),
                blurRadius = 18f
            )
        )
    }

    // =========================================================
    // Neon press button (match Player vibe)
    // =========================================================
    @Composable
    fun NeonPressOutlinedButton(
        onClick: () -> Unit,
        enabled: Boolean,
        modifier: Modifier,
        text: String
    ) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()

        val glow by animateFloatAsState(
            targetValue = if (pressed) 1f else 0f,
            animationSpec = tween(160, easing = FastOutSlowInEasing),
            label = "pressGlow"
        )

        Box(
            modifier = modifier.drawBehind {
                if (glow > 0f) {
                    val corner = 12.dp.toPx()
                    val cr = CornerRadius(corner, corner)

                    drawRoundRect(
                        color = MatrixGreen.copy(alpha = 0.32f * glow),
                        topLeft = Offset(0f, 0f),
                        size = size,
                        cornerRadius = cr
                    )

                    drawRoundRect(
                        color = MatrixGreen.copy(alpha = 0.55f * glow),
                        topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(
                            size.width - 2.dp.toPx(),
                            size.height - 2.dp.toPx()
                        ),
                        cornerRadius = cr,
                        style = Stroke(width = 3.0.dp.toPx())
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
                )
            ) {
                Text(text, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    // =========================================================
    // Existing logic (UNCHANGED)
    // =========================================================
    val recomposeAnchor = vm.libraryArtists
    val context = remember { AppViewModel.appContext }

    var playlists by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var selectedPlaylistName by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val byId = remember(recomposeAnchor, vm.mediaStoreSongs) {
        vm.mediaStoreSongs.associateBy { it.mediaStoreId }
    }

    var relativeToId by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }

    // Old-Compose-safe scroll state
    val playlistListState = rememberLazyListState(
        PlaylistUiCache.index,
        PlaylistUiCache.offset
    )

    // Save scroll position periodically (left pane only)
    LaunchedEffect(playlistListState) {
        while (isActive) {
            PlaylistUiCache.index = playlistListState.firstVisibleItemIndex
            PlaylistUiCache.offset = playlistListState.firstVisibleItemScrollOffset
            delay(200)
        }
    }

    // Load playlists.json
    LaunchedEffect(Unit) {
        val ctx2 = context
        if (ctx2 == null) {
            statusMessage = "App context not ready"
            return@LaunchedEffect
        }

        val map = loadPlaylistsFromDisk(ctx2)
        playlists = map
        selectedPlaylistName = map.keys.sorted().firstOrNull()
        statusMessage = if (map.isEmpty()) "No playlists found" else null
    }

    // Build relativePath → MediaStoreId mapping
    LaunchedEffect(recomposeAnchor, context) {
        val ctx2 = context ?: return@LaunchedEffect
        if (vm.mediaStoreSongs.isEmpty()) return@LaunchedEffect
        relativeToId = buildRelativePathToMediaStoreId(ctx2)
    }

    val playlistNames = playlists.keys.sorted()
    val selectedEntries = selectedPlaylistName?.let { playlists[it] } ?: emptyList()

    val resolvedSongs = remember(selectedPlaylistName, playlists, relativeToId, byId) {
        selectedEntries.map { raw ->
            val rel = toRelativePath(raw)
            val id = relativeToId[normalizeRel(rel)]
            ResolvedEntry(raw, rel, id?.let { byId[it] })
        }
    }

    val matchedCount = resolvedSongs.count { it.matched != null }
    val totalCount = resolvedSongs.size

    // =========================================================
    // SUBTLE PULSE (used to sweeten selected playlist)
    // =========================================================
    val breathe = rememberInfiniteTransition()
    val pulse by breathe.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val density = LocalDensity.current

    // =========================================================
    // UI
    // =========================================================
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MatrixBg)
    ) {
        PlaylistMatrixRainBackground(
            modifier = Modifier.fillMaxSize(),
            glyphColor = MatrixGreen.copy(alpha = 0.35f),
            densityScale = density.density
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {

            // Header panel
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .neonPanel(),
                color = Color.Transparent,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        "Playlists",
                        style = MaterialTheme.typography.headlineSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    statusMessage?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = TextMuted)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                // LEFT PANE — playlists (scroll restored)
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .neonPanelDeep()
                        .queueDepthGlow(12f)
                        .padding(10.dp),
                    color = Color.Transparent,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text(
                            "Available",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Divider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MatrixGreenFaint
                        )

                        LazyColumn(state = playlistListState) {
                            items(playlistNames) { name ->
                                val selected = name == selectedPlaylistName
                                val count = playlists[name]?.size ?: 0

                                val rowInteraction = remember { MutableInteractionSource() }
                                val pressed by rowInteraction.collectIsPressedAsState()
                                val pressGlow by animateFloatAsState(
                                    targetValue = if (pressed) 1f else 0f,
                                    animationSpec = tween(110, easing = FastOutSlowInEasing),
                                    label = "playlistPressGlow"
                                )

                                val selBoost = if (selected) 1f else 0f
                                val glowMix = (selBoost + pressGlow).coerceIn(0f, 1f)

                                val accentAlpha = (0.22f + 0.62f * glowMix + 0.08f * pulse).coerceIn(0f, 0.98f)
                                val bgAlpha =
                                    ((if (selected) 0.18f else 0f) + (0.22f * pressGlow)).coerceIn(0f, 0.40f)

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(
                                            interactionSource = rowInteraction,
                                            indication = null
                                        ) { selectedPlaylistName = name }
                                        .background(
                                            color = if (bgAlpha > 0f) MatrixGreen.copy(alpha = bgAlpha) else Color.Transparent
                                        )
                                        .padding(horizontal = 10.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(4.dp)
                                            .height(44.dp)
                                            .background(
                                                MatrixGreen.copy(alpha = accentAlpha),
                                                RoundedCornerShape(2.dp)
                                            )
                                    )

                                    Spacer(Modifier.width(10.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            name,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                            color = if (selected || pressed) TextPrimary else TextMuted,
                                            style = glowTextStyle(selected || pressed)
                                        )
                                        Text(
                                            "$count songs",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextMuted
                                        )
                                    }
                                }

                                Divider(color = MatrixGreenFaint)
                            }
                        }
                    }
                }

                // RIGHT PANE — songs
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .neonPanelDeep()
                        .queueDepthGlow(12f)
                        .padding(10.dp),
                    color = Color.Transparent,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text(
                            selectedPlaylistName ?: "Select a playlist",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )

                        if (selectedPlaylistName != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Matched: $matchedCount / $totalCount",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }

                        Divider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MatrixGreenFaint
                        )

                        LazyColumn {
                            items(resolvedSongs) { e ->
                                val song = e.matched
                                val isMissing = (song == null)

                                val rowInteraction = remember { MutableInteractionSource() }
                                val pressed by rowInteraction.collectIsPressedAsState()
                                val pressGlow by animateFloatAsState(
                                    targetValue = if (pressed) 1f else 0f,
                                    animationSpec = tween(110, easing = FastOutSlowInEasing),
                                    label = "songRowPressGlow"
                                )

                                val bgAlpha = (if (pressed) 0.18f else 0f).coerceIn(0f, 0.22f)
                                val accentAlpha = (0.16f + 0.44f * pressGlow).coerceIn(0f, 0.70f)

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(
                                            interactionSource = rowInteraction,
                                            indication = null
                                        ) { /* UI only: no action in playlist view */ }
                                        .background(
                                            color = if (bgAlpha > 0f) MatrixGreen.copy(alpha = bgAlpha) else Color.Transparent
                                        )
                                        .padding(horizontal = 10.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(4.dp)
                                            .height(44.dp)
                                            .background(
                                                MatrixGreen.copy(alpha = accentAlpha),
                                                RoundedCornerShape(2.dp)
                                            )
                                    )

                                    Spacer(Modifier.width(10.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        if (!isMissing) {
                                            Text(
                                                song!!.title,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = if (pressed) TextPrimary else TextMuted,
                                                fontWeight = if (pressed) FontWeight.SemiBold else FontWeight.Normal,
                                                style = glowTextStyle(pressed)
                                            )
                                            Text(
                                                "${song.artist} • ${song.album}",
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextMuted
                                            )
                                        } else {
                                            Text(
                                                e.relativePath,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = MissingText,
                                                fontWeight = FontWeight.Normal
                                            )
                                            Text(
                                                "Missing on device",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MissingText
                                            )
                                        }
                                    }
                                }

                                Divider(color = MatrixGreenFaint)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Bottom button panel
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .neonPanel(),
                color = Color.Transparent,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        NeonPressOutlinedButton(
                            enabled = matchedCount > 0,
                            onClick = {
                                val songs = resolvedSongs.mapNotNull { it.matched }
                                playPlaylistDeterministic(vm, songs)
                            },
                            modifier = Modifier.width(220.dp),
                            text = "Play Playlist"
                        )
                    }
                }
            }
        }
    }
}

/* ---------------- helpers ---------------- */

private data class ResolvedEntry(
    val rawEntry: String,
    val relativePath: String,
    val matched: MediaStoreSong?
)

private suspend fun loadPlaylistsFromDisk(context: Context): Map<String, List<String>> =
    withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "playlists.json")
        if (!file.exists()) return@withContext emptyMap()

        val json = JSONObject(file.readText())
        val out = LinkedHashMap<String, List<String>>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val arr = json.optJSONArray(name) ?: continue
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotBlank()) list.add(s)
            }
            out[name] = list
        }
        out
    }

private fun toRelativePath(entry: String): String {
    val s = entry.trim()
    val win = Regex("^[A-Za-z]:\\\\")
    return if (!win.containsMatchIn(s)) {
        s.replace("\\", "/").trimStart('/')
    } else {
        val idx = s.lowercase().indexOf("\\music\\")
        val sub = if (idx >= 0) s.substring(idx + 7) else s
        sub.replace("\\", "/").trimStart('/')
    }
}

private fun normalizeRel(s: String): String =
    s.replace("\\", "/").removePrefix("Music/").trimStart('/')

private suspend fun buildRelativePathToMediaStoreId(context: Context): Map<String, Long> =
    withContext(Dispatchers.IO) {
        val out = HashMap<String, Long>(4096)
        val uri: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH
        )
        val sel = "${MediaStore.Audio.Media.IS_MUSIC}=1"

        context.contentResolver.query(uri, proj, sel, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val relCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: continue
                var rel = c.getString(relCol) ?: ""
                rel = rel.replace("\\", "/")
                if (rel.startsWith("Music/")) rel = rel.removePrefix("Music/")
                out[normalizeRel(rel + name)] = id
            }
        }
        out
    }

/**
 * Model B friendly “Play Playlist”:
 * - Clear existing queue (stops old playback cleanly)
 * - Append playlist songs
 * - Select index 0
 * - Start playing (only if not already playing)
 */
private fun playPlaylistDeterministic(vm: AppViewModel, songs: List<MediaStoreSong>) {
    if (songs.isEmpty()) return

    vm.clearQueue()
    songs.forEach { vm.onSongClicked(it) }
    vm.selectQueueIndex(0)

    if (!vm.isPlaying) {
        vm.togglePlayPause()
    }
}