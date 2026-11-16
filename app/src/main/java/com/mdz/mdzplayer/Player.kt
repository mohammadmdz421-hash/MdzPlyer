@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mdz.mdzplayer

import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.util.TypedValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class PlayRequest(
    val uri: String = "",
    val title: String = "",
    val audioUri: String? = null,      // separate audio track (HD YouTube streaming)
    val userAgent: String? = null,     // custom UA for YouTube streams
    val key: String? = null,           // resume key override
    val list: List<Channel> = emptyList(),
    val index: Int = -1,
    val openList: Boolean = false
)

private val RESIZE_MODES = intArrayOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT,
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    AspectRatioFrameLayout.RESIZE_MODE_FILL
)
private val FIT_NAMES = listOf("Fit", "Fill", "Stretch")
private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val SLEEP_MIN = intArrayOf(0, 15, 30, 60, 90)
private val ORIENTS = intArrayOf(
    ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
)
private val ORIENT_NAMES = listOf("Auto", "Landscape", "Portrait")

@Composable
fun GlassBtn(label: String, onClick: () -> Unit) {
    val th = LocalTheme.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Button(
        onClick = onClick,
        interactionSource = src,
        colors = ButtonDefaults.buttonColors(containerColor = th.primary.copy(alpha = 0.55f), contentColor = Color.White),
        border = if (focused) BorderStroke(2.dp, Color.White) else null,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = Modifier.heightIn(min = 36.dp)
    ) { Text(label, fontSize = 13.sp, maxLines = 1) }
}

@Composable
fun PlayerScreen(activity: ComponentActivity, req: PlayRequest, onExit: () -> Unit) {
    val context = LocalContext.current
    val th = LocalTheme.current
    val Purple = th.primary
    val Glass = th.glass
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(context) }
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    val exo = remember {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("VLC/3.0.20 LibVLC/3.0.20")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context).setDataSourceFactory(DefaultDataSource.Factory(context, http))
            )
            .build()
    }

    var title by remember { mutableStateOf(req.title.ifEmpty { "MdzPlyer" }) }
    var error by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var showControls by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var fitMode by remember { mutableIntStateOf(prefs.fitMode.coerceIn(0, 2)) }
    var speedIdx by remember { mutableIntStateOf(2) }
    var muted by remember { mutableStateOf(false) }
    var loopA by remember { mutableLongStateOf(-1L) }
    var loopB by remember { mutableLongStateOf(-1L) }
    var sleepIdx by remember { mutableIntStateOf(0) }
    var orient by remember { mutableIntStateOf(0) }
    var showList by remember { mutableStateOf(req.openList) }
    val channels = remember { req.list }
    var currentIdx by remember { mutableIntStateOf(req.index) }
    var query by remember { mutableStateOf("") }
    var favOnly by remember { mutableStateOf(false) }
    var favs by remember { mutableStateOf(prefs.favs()) }
    var osd by remember { mutableStateOf<String?>(null) }
    var osdTick by remember { mutableIntStateOf(0) }
    var currentKey by remember { mutableStateOf<String?>(null) }
    var bright by remember { mutableFloatStateOf(0.5f) }
    var volF by remember {
        mutableFloatStateOf(
            audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        )
    }
    val rootFocus = remember { FocusRequester() }
    val hasMedia = currentKey != null

    fun say(t: String) { osd = t; osdTick++ }

    fun seekBy(deltaMs: Long) {
        val d = if (exo.duration == C.TIME_UNSET) Long.MAX_VALUE else exo.duration
        exo.seekTo((exo.currentPosition + deltaMs).coerceIn(0L, d))
    }

    fun mediaSourceFor(url: String, ua: String): ProgressiveMediaSource {
        val h = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        return ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context, h))
            .createMediaSource(MediaItem.fromUri(url))
    }

    fun startPlayback(uri: String, name: String, audioUri: String?, ua: String?, key: String?) {
        error = null
        title = name
        val k = key ?: uri
        currentKey = k
        loopA = -1L; loopB = -1L
        val resume = prefs.getPos(k)
        val startAt = if (resume > 10_000) resume else 0L
        if (audioUri != null || ua != null) {
            val agent = ua ?: FIREFOX_UA
            val v = mediaSourceFor(uri, agent)
            if (audioUri != null) exo.setMediaSource(MergingMediaSource(v, mediaSourceFor(audioUri, agent)), startAt)
            else exo.setMediaSource(v, startAt)
        } else {
            exo.setMediaItem(MediaItem.fromUri(Uri.parse(uri)), startAt)
        }
        exo.prepare()
        exo.playWhenReady = true
        showControls = true
        interaction++
    }

    fun playIndex(i: Int) {
        if (channels.isEmpty()) return
        val idx = ((i % channels.size) + channels.size) % channels.size
        currentIdx = idx
        val ch = channels[idx]
        startPlayback(ch.url, ch.name, null, null, null)
    }

    LaunchedEffect(Unit) {
        if (req.list.isNotEmpty()) {
            if (req.index >= 0) playIndex(req.index)
        } else if (req.uri.isNotEmpty()) {
            startPlayback(req.uri, req.title, req.audioUri, req.userAgent, req.key)
        }
    }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = "${e.errorCodeName}: ${e.message}"
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) error = null
                if (state == Player.STATE_ENDED && channels.isNotEmpty() && currentIdx >= 0) playIndex(currentIdx + 1)
            }
        }
        exo.addListener(listener)
        onDispose {
            val k = currentKey
            if (k != null && exo.duration != C.TIME_UNSET && !exo.isCurrentMediaItemLive) {
                prefs.savePos(k, exo.currentPosition, exo.duration)
            }
            exo.removeListener(listener)
            exo.release()
        }
    }

    LaunchedEffect(exo) {
        var tick = 0
        while (true) {
            if (!dragging) pos = exo.currentPosition.coerceAtLeast(0L)
            dur = if (exo.duration == C.TIME_UNSET) 0L else exo.duration
            if (loopA >= 0 && loopB > loopA && exo.currentPosition >= loopB) exo.seekTo(loopA)
            tick++
            val k = currentKey
            if (tick % 20 == 0 && k != null && dur > 30_000 && !exo.isCurrentMediaItemLive) prefs.savePos(k, pos, dur)
            delay(250)
        }
    }

    LaunchedEffect(showControls, interaction, isPlaying, showList) {
        if (showControls && isPlaying && !showList) {
            delay(4000)
            showControls = false
        }
    }
    LaunchedEffect(showControls, showList) {
        if (!showControls && !showList) {
            try { rootFocus.requestFocus() } catch (_: Exception) {}
        }
    }
    LaunchedEffect(osdTick) { if (osd != null) { delay(1200); osd = null } }
    LaunchedEffect(sleepIdx) {
        val m = SLEEP_MIN[sleepIdx]
        if (m > 0) {
            delay(m * 60_000L)
            exo.pause()
            sleepIdx = 0
            say("Sleep timer: paused")
        }
    }
    LaunchedEffect(muted) { exo.volume = if (muted) 0f else 1f }

    BackHandler(enabled = true) {
        if (showList && hasMedia) showList = false else onExit()
    }

    val subPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val cur = exo.currentMediaItem
        if (uri != null && cur != null) {
            scope.launch {
                try {
                    val (file, mime) = withContext(Dispatchers.IO) { prepareSubtitle(context, uri) }
                    val cfg = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
                        .setMimeType(mime)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                    val p = exo.currentPosition
                    exo.setMediaItem(cur.buildUpon().setSubtitleConfigurations(listOf(cfg)).build(), p)
                    exo.prepare()
                    exo.play()
                    say("Subtitle loaded")
                } catch (e: Exception) {
                    error = "Subtitle: ${e.message}"
                }
            }
        }
    }

    val lift = showControls || !hasMedia

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                interaction++
                when (ev.key) {
                    Key.MediaPlayPause -> { if (exo.isPlaying) exo.pause() else exo.play(); true }
                    Key.MediaFastForward -> { seekBy(10_000); true }
                    Key.MediaRewind -> { seekBy(-10_000); true }
                    Key.MediaNext -> { playIndex(currentIdx + 1); true }
                    Key.MediaPrevious -> { playIndex(currentIdx - 1); true }
                    Key.DirectionLeft ->
                        if (!showControls && !showList) { seekBy(-10_000); say("-10s"); true } else false
                    Key.DirectionRight ->
                        if (!showControls && !showList) { seekBy(10_000); say("+10s"); true } else false
                    Key.DirectionCenter, Key.Enter, Key.DirectionUp, Key.DirectionDown ->
                        if (!showControls && !showList) { showControls = true; true } else false
                    else -> false
                }
            }
            .focusable()
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exo
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    subtitleView?.apply {
                        setStyle(
                            CaptionStyleCompat(
                                android.graphics.Color.WHITE,
                                android.graphics.Color.TRANSPARENT,
                                android.graphics.Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                android.graphics.Color.BLACK,
                                null
                            )
                        )
                        setApplyEmbeddedStyles(false)
                        setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    }
                }
            },
            update = { view ->
                view.resizeMode = RESIZE_MODES[fitMode]
                view.subtitleView?.setBottomPaddingFraction(if (lift) 0.24f else 0.08f)
            }
        )

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showControls = !showControls; interaction++ },
                        onDoubleTap = { off ->
                            if (off.x < size.width / 2) { seekBy(-10_000); say("-10s") }
                            else { seekBy(10_000); say("+10s") }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        val delta = -dragAmount / size.height
                        if (change.position.x < size.width / 2) {
                            bright = (bright + delta).coerceIn(0.02f, 1f)
                            val lp = activity.window.attributes
                            lp.screenBrightness = bright
                            activity.window.attributes = lp
                            say("Brightness ${(bright * 100).roundToInt()}%")
                        } else {
                            volF = (volF + delta).coerceIn(0f, 1f)
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volF * max).roundToInt(), 0)
                            say("Volume ${(volF * 100).roundToInt()}%")
                        }
                    }
                }
        )

        if (showControls || !hasMedia) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(8.dp)
                        .background(Glass, RoundedCornerShape(18.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlassBtn("←") { onExit() }
                    Text(
                        title, color = Color.White, fontSize = 15.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (channels.isNotEmpty()) GlassBtn("☰ List") { showList = !showList }
                }

                if (hasMedia) {
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(8.dp)
                            .background(Glass, RoundedCornerShape(22.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(fmtTime(if (dragging) (dragValue * dur).toLong() else pos), color = Color.White, fontSize = 12.sp)
                            Slider(
                                value = if (dragging) dragValue else if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                                onValueChange = { dragging = true; dragValue = it; interaction++ },
                                onValueChangeFinished = {
                                    exo.seekTo((dragValue * dur).toLong())
                                    pos = (dragValue * dur).toLong()
                                    dragging = false
                                },
                                enabled = dur > 0,
                                colors = SliderDefaults.colors(
                                    thumbColor = Purple,
                                    activeTrackColor = Purple,
                                    inactiveTrackColor = Color(0x44FFFFFF)
                                ),
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            )
                            Text(if (dur > 0) fmtTime(dur) else "LIVE", color = Color.White, fontSize = 12.sp)
                        }
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (channels.isNotEmpty()) GlassBtn("⏮") { playIndex(currentIdx - 1) }
                            GlassBtn("⏪ 10") { seekBy(-10_000); interaction++ }
                            GlassBtn(if (isPlaying) "⏸ Pause" else "▶ Play") {
                                if (exo.isPlaying) exo.pause() else exo.play(); interaction++
                            }
                            GlassBtn("10 ⏩") { seekBy(10_000); interaction++ }
                            if (channels.isNotEmpty()) GlassBtn("⏭") { playIndex(currentIdx + 1) }
                            GlassBtn("⚡ ${SPEEDS[speedIdx]}x") {
                                speedIdx = (speedIdx + 1) % SPEEDS.size
                                exo.setPlaybackSpeed(SPEEDS[speedIdx]); interaction++
                            }
                            GlassBtn("▭ ${FIT_NAMES[fitMode]}") {
                                fitMode = (fitMode + 1) % 3
                                prefs.fitMode = fitMode
                                say(FIT_NAMES[fitMode]); interaction++
                            }
                            GlassBtn(
                                when {
                                    loopA < 0 -> "A-B"
                                    loopB < 0 -> "A → ?"
                                    else -> "A-B ✕"
                                }
                            ) {
                                val p = exo.currentPosition
                                when {
                                    loopA < 0 -> { loopA = p; say("Loop A set") }
                                    loopB < 0 -> { loopB = p; say("Loop A-B on") }
                                    else -> { loopA = -1L; loopB = -1L; say("Loop off") }
                                }
                                interaction++
                            }
                            GlassBtn(if (muted) "🔇" else "🔊") { muted = !muted; interaction++ }
                            GlassBtn("🔄 ${ORIENT_NAMES[orient]}") {
                                orient = (orient + 1) % 3
                                activity.requestedOrientation = ORIENTS[orient]
                                interaction++
                            }
                            GlassBtn(if (SLEEP_MIN[sleepIdx] == 0) "💤 Off" else "💤 ${SLEEP_MIN[sleepIdx]}m") {
                                sleepIdx = (sleepIdx + 1) % SLEEP_MIN.size; interaction++
                            }
                            GlassBtn("CC") { subPicker.launch(arrayOf("*/*")) }
                        }
                    }
                }
            }
        }

        osd?.let {
            Text(
                it, color = Color.White, fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Glass, RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }

        error?.let {
            Text(
                it, color = Color.White, fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .safeDrawingPadding()
                    .padding(top = 72.dp, start = 16.dp, end = 16.dp)
                    .background(Color(0xDDB00020), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            )
        }

        if (showList) {
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .widthIn(max = 360.dp)
                    .fillMaxWidth(0.85f)
                    .safeDrawingPadding()
                    .background(th.bg1.copy(alpha = 0.95f), RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Playlist (${channels.size})", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    GlassBtn(if (favOnly) "★ only" else "All") { favOnly = !favOnly }
                    GlassBtn("✕") { if (hasMedia) showList = false else onExit() }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search…") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                val filtered = remember(query, favOnly, favs) {
                    channels.filter {
                        (query.isBlank() || it.name.contains(query, true) || it.group.contains(query, true)) &&
                            (!favOnly || favs.contains(it.url))
                    }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(filtered) { ch ->
                        val isCur = currentIdx in channels.indices && channels[currentIdx].url == ch.url
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(if (isCur) Purple.copy(alpha = 0.3f) else Color.Transparent, RoundedCornerShape(10.dp))
                                .clickable {
                                    playIndex(channels.indexOf(ch))
                                    showList = false
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(ch.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (ch.group.isNotEmpty())
                                    Text(ch.group, color = Color(0xFFB9A5E8), fontSize = 11.sp, maxLines = 1)
                            }
                            Text(
                                if (favs.contains(ch.url)) "★" else "☆",
                                color = Purple, fontSize = 20.sp,
                                modifier = Modifier
                                    .clickable {
                                        val n = if (favs.contains(ch.url)) favs - ch.url else favs + ch.url
                                        favs = n
                                        prefs.setFavs(n)
                                    }
                                    .padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
