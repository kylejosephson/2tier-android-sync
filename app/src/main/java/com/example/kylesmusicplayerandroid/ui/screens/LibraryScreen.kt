package com.example.kylesmusicplayerandroid.ui.screens

import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.kylesmusicplayerandroid.data.artwork.AlbumArtRepository
import com.example.kylesmusicplayerandroid.viewmodel.AppViewModel
import kotlin.random.Random

private data class LibraryRainColumn(
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
            LibraryRainColumn(
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: AppViewModel) {

    // =========================================================
    // MATRIX PALETTE (match Player)
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

    fun Modifier.thumbGlow(cornerDp: Float): Modifier =
        this.drawBehind {
            val r = cornerDp.dp.toPx()
            val cr = CornerRadius(r, r)

            val widths = listOf(10f, 6f, 3f)
            val alphas = listOf(0.06f, 0.08f, 0.12f)

            for (k in widths.indices) {
                val w = widths[k].dp.toPx()
                val inset = w / 2f
                drawRoundRect(
                    color = MatrixGreen.copy(alpha = alphas[k]),
                    topLeft = Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(
                        size.width - 2 * inset,
                        size.height - 2 * inset
                    ),
                    cornerRadius = cr,
                    style = Stroke(width = w)
                )
            }

            val innerW = 1.25f.dp.toPx()
            val inset = innerW / 2f
            drawRoundRect(
                color = MatrixGreen.copy(alpha = 0.26f),
                topLeft = Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(
                    size.width - 2 * inset,
                    size.height - 2 * inset
                ),
                cornerRadius = cr,
                style = Stroke(width = innerW)
            )
        }

    val breathe = rememberInfiniteTransition()
    val pulse by breathe.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    // Text glow (Shadow) used when pressed/selected
    fun glowTextStyle(on: Boolean): TextStyle {
        return if (!on) TextStyle.Default
        else TextStyle(
            shadow = Shadow(
                color = MatrixGreen.copy(alpha = 0.85f),
                offset = Offset(0f, 0f),
                blurRadius = 18f
            )
        )
    }

    val ctx = LocalContext.current
    val density = LocalDensity.current

    val artists = vm.libraryArtists
    val albums = vm.libraryAlbums
    val songs = vm.mediaStoreSongs

    val currentArtist = vm.currentArtist
    val currentAlbum = vm.currentAlbum

    val artistsListState = rememberLazyListState(
        vm.artistsScrollIndex,
        vm.artistsScrollOffset
    )

    val albumsListState = rememberLazyListState(
        vm.albumsScrollIndex,
        vm.albumsScrollOffset
    )

    // UI-only: keep a "last tapped song" so it stays highlighted
    var selectedSongUri by remember { mutableStateOf<String?>(null) }

    @Composable
    fun Thumb(
        bmp: android.graphics.Bitmap?,
        modifier: Modifier = Modifier.size(54.dp)
    ) {
        val corner = 10f
        val shape = RoundedCornerShape(corner.dp)

        Surface(
            modifier = modifier
                .clip(shape)
                .thumbGlow(corner)
                .background(MatrixPanel2, shape)
                .border(1.dp, MatrixGreenFaint, shape),
            shape = shape,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            color = Color.Transparent
        ) {
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Album art",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("♪", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                }
            }
        }
    }

    fun trackSortKey(tn: Int): Int = if (tn > 0) tn else Int.MAX_VALUE

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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .neonPanel(),
                color = Color.Transparent,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            when {
                                currentArtist == null -> "Artists"
                                currentAlbum == null -> currentArtist
                                else -> "$currentArtist • $currentAlbum"
                            },
                            color = TextPrimary
                        )
                    },
                    navigationIcon = {
                        if (currentArtist != null) {
                            IconButton(onClick = { vm.goBackLibrary() }) {
                                Icon(
                                    Icons.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = TextPrimary
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = TextPrimary,
                        navigationIconContentColor = TextPrimary,
                        actionIconContentColor = TextPrimary
                    )
                )
            }

            Spacer(Modifier.height(10.dp))

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

                // --------------------------------------------------
                // ARTISTS
                // --------------------------------------------------
                if (currentArtist == null) {
                    LazyColumn(state = artistsListState) {
                        items(artists, key = { it }) { artist ->

                            val rep = remember(artist, songs) {
                                val repSong = songs.asSequence()
                                    .filter { it.artist == artist }
                                    .sortedWith(
                                        compareBy(
                                            { it.album.lowercase() },
                                            { trackSortKey(it.trackNumber) },
                                            { it.title.lowercase() }
                                        )
                                    )
                                    .firstOrNull()

                                if (repSong == null) null
                                else Pair(
                                    repSong.album,
                                    AlbumArtRepository.RepresentativeSong(
                                        title = repSong.title,
                                        trackNumber = repSong.trackNumber,
                                        contentUri = repSong.contentUri.toString()
                                    )
                                )
                            }

                            var artistArt by remember(artist) { mutableStateOf<android.graphics.Bitmap?>(null) }

                            LaunchedEffect(artist, rep?.first, rep?.second?.contentUri) {
                                artistArt = if (rep == null) null
                                else {
                                    AlbumArtRepository.loadAlbumArtBitmap(
                                        ctx = ctx,
                                        artist = artist,
                                        album = rep.first,
                                        representativeSong = rep.second,
                                        maxSizePx = 192
                                    )
                                }
                            }

                            val rowInteraction = remember { MutableInteractionSource() }
                            val rowPressed by rowInteraction.collectIsPressedAsState()
                            val tapGlow by animateFloatAsState(
                                targetValue = if (rowPressed) 1f else 0f,
                                animationSpec = tween(110, easing = FastOutSlowInEasing),
                                label = "artistTapGlow"
                            )

                            // MUCH brighter press feedback
                            val accentAlpha = (0.28f + 0.55f * tapGlow).coerceIn(0f, 0.92f)
                            val bgAlpha = (0.14f + 0.26f * tapGlow).coerceIn(0f, 0.36f)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        interactionSource = rowInteraction,
                                        indication = null,
                                        onClick = {
                                            vm.saveArtistsScroll(
                                                artistsListState.firstVisibleItemIndex,
                                                artistsListState.firstVisibleItemScrollOffset
                                            )
                                            vm.selectArtist(artist)
                                        }
                                    )
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
                                        .background(MatrixGreen.copy(alpha = accentAlpha), RoundedCornerShape(2.dp))
                                )

                                Spacer(Modifier.width(10.dp))
                                Thumb(bmp = artistArt)
                                Spacer(Modifier.width(12.dp))

                                Text(
                                    artist,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (rowPressed) TextPrimary else TextMuted,
                                    style = glowTextStyle(rowPressed)
                                )
                            }

                            Divider(color = MatrixGreenFaint)
                        }
                    }
                    return@Surface
                }

                // --------------------------------------------------
                // ALBUMS
                // --------------------------------------------------
                if (currentAlbum == null) {
                    LazyColumn(state = albumsListState) {
                        items(albums, key = { it }) { album ->

                            val repSong = remember(currentArtist, album, songs) {
                                songs.asSequence()
                                    .filter { it.artist == currentArtist && it.album == album }
                                    .sortedWith(
                                        compareBy(
                                            { trackSortKey(it.trackNumber) },
                                            { it.title.lowercase() }
                                        )
                                    )
                                    .firstOrNull()
                                    ?.let {
                                        AlbumArtRepository.RepresentativeSong(
                                            title = it.title,
                                            trackNumber = it.trackNumber,
                                            contentUri = it.contentUri.toString()
                                        )
                                    }
                            }

                            var artBitmap by remember(currentArtist, album) { mutableStateOf<android.graphics.Bitmap?>(null) }

                            LaunchedEffect(currentArtist, album, repSong?.contentUri) {
                                artBitmap = AlbumArtRepository.loadAlbumArtBitmap(
                                    ctx = ctx,
                                    artist = currentArtist,
                                    album = album,
                                    representativeSong = repSong,
                                    maxSizePx = 192
                                )
                            }

                            val rowInteraction = remember { MutableInteractionSource() }
                            val rowPressed by rowInteraction.collectIsPressedAsState()
                            val tapGlow by animateFloatAsState(
                                targetValue = if (rowPressed) 1f else 0f,
                                animationSpec = tween(110, easing = FastOutSlowInEasing),
                                label = "albumTapGlow"
                            )

                            val accentAlpha = (0.28f + 0.55f * tapGlow).coerceIn(0f, 0.92f)
                            val bgAlpha = (0.14f + 0.26f * tapGlow).coerceIn(0f, 0.36f)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        interactionSource = rowInteraction,
                                        indication = null,
                                        onClick = {
                                            vm.saveAlbumsScroll(
                                                albumsListState.firstVisibleItemIndex,
                                                albumsListState.firstVisibleItemScrollOffset
                                            )
                                            vm.selectAlbum(album)
                                        },
                                        onLongClick = {
                                            vm.addAlbumToQueue(currentArtist, album)
                                        }
                                    )
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
                                        .background(MatrixGreen.copy(alpha = accentAlpha), RoundedCornerShape(2.dp))
                                )

                                Spacer(Modifier.width(10.dp))
                                Thumb(bmp = artBitmap)
                                Spacer(Modifier.width(12.dp))

                                Text(
                                    album,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (rowPressed) TextPrimary else TextMuted,
                                    style = glowTextStyle(rowPressed)
                                )
                            }

                            Divider(color = MatrixGreenFaint)
                        }
                    }
                    return@Surface
                }

                // --------------------------------------------------
                // SONGS (persistent selected highlight)
                // --------------------------------------------------
                val albumSongs = remember(currentArtist, currentAlbum, songs) {
                    songs
                        .filter { it.artist == currentArtist && it.album == currentAlbum }
                        .sortedWith(
                            compareBy(
                                { trackSortKey(it.trackNumber) },
                                { it.title.lowercase() }
                            )
                        )
                }

                val repSongForAlbum = remember(currentArtist, currentAlbum, albumSongs) {
                    albumSongs.firstOrNull()
                        ?.let {
                            AlbumArtRepository.RepresentativeSong(
                                title = it.title,
                                trackNumber = it.trackNumber,
                                contentUri = it.contentUri.toString()
                            )
                        }
                }

                var albumArtForSongs by remember(currentArtist, currentAlbum) { mutableStateOf<android.graphics.Bitmap?>(null) }

                LaunchedEffect(currentArtist, currentAlbum, repSongForAlbum?.contentUri) {
                    albumArtForSongs = AlbumArtRepository.loadAlbumArtBitmap(
                        ctx = ctx,
                        artist = currentArtist,
                        album = currentAlbum,
                        representativeSong = repSongForAlbum,
                        maxSizePx = 192
                    )
                }

                LazyColumn {
                    items(albumSongs, key = { it.contentUri.toString() }) { song ->

                        val songUri = song.contentUri.toString()
                        val isSelected = (selectedSongUri == songUri)

                        val rowInteraction = remember { MutableInteractionSource() }
                        val rowPressed by rowInteraction.collectIsPressedAsState()
                        val tapGlow by animateFloatAsState(
                            targetValue = if (rowPressed) 1f else 0f,
                            animationSpec = tween(110, easing = FastOutSlowInEasing),
                            label = "songTapGlow"
                        )

                        val selectedBoost = if (isSelected) 1f else 0f
                        val glowMix = (tapGlow + selectedBoost).coerceIn(0f, 1f)

                        val accentAlpha = (0.24f + 0.60f * glowMix + 0.10f * pulse).coerceIn(0f, 0.98f)
                        val bgAlpha = (if (isSelected) 0.18f else 0.00f) + (0.26f * tapGlow)
                        val bgA = bgAlpha.coerceIn(0f, 0.40f)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    interactionSource = rowInteraction,
                                    indication = null,
                                    onClick = {
                                        selectedSongUri = songUri
                                        vm.onSongClicked(song)
                                    }
                                )
                                .background(
                                    color = if (bgA > 0f) MatrixGreen.copy(alpha = bgA) else Color.Transparent
                                )
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(44.dp)
                                    .background(MatrixGreen.copy(alpha = accentAlpha), RoundedCornerShape(2.dp))
                            )

                            Spacer(Modifier.width(10.dp))
                            Thumb(bmp = albumArtForSongs)
                            Spacer(Modifier.width(12.dp))

                            Text(
                                song.title,
                                color = if (isSelected || rowPressed) TextPrimary else TextMuted,
                                fontWeight = if (isSelected || rowPressed) FontWeight.SemiBold else FontWeight.Normal,
                                style = glowTextStyle(isSelected || rowPressed)
                            )
                        }

                        Divider(color = MatrixGreenFaint)
                    }
                }
            }
        }
    }
}
