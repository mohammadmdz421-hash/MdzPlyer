package com.mdz.mdzplayer

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

private data class Vid(val uri: Uri, val name: String, val durMs: Long)

private val TABS = listOf(
    Triple("lib", "🎬", 0), Triple("playlists", "📺", 1), Triple("youtube", "▶", 2),
    Triple("downloads", "⬇", 3), Triple("settings", "⚙", 4)
)

@Composable
fun HomeScreen(
    activity: ComponentActivity,
    prefs: Prefs,
    themeIdx: Int,
    onTheme: (Int) -> Unit,
    lang: String,
    onLang: (String) -> Unit,
    incomingUrl: String?,
    onIncomingHandled: () -> Unit,
    onPlay: (PlayRequest) -> Unit
) {
    val th = LocalTheme.current
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(incomingUrl) { if (incomingUrl != null) tab = 2 }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(th.bgBrush())
            .safeDrawingPadding()
    ) {
        val wide = maxWidth >= 600.dp
        val content: @Composable () -> Unit = {
            when (tab) {
                0 -> LibraryTab(activity, prefs, onPlay)
                1 -> PlaylistsTab(prefs, onPlay)
                2 -> YoutubeTab(incomingUrl, onIncomingHandled, onPlay)
                3 -> DownloadsTab(onPlay)
                else -> SettingsTab(themeIdx, onTheme, lang, onLang)
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(containerColor = th.glass) {
                    Spacer(Modifier.weight(1f))
                    TABS.forEach { (key, icon, idx) ->
                        NavigationRailItem(
                            selected = tab == idx,
                            onClick = { tab = idx },
                            icon = { Text(icon, fontSize = 20.sp) },
                            label = { Text(T(key), fontSize = 11.sp) }
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                NavigationBar(containerColor = th.glass) {
                    TABS.forEach { (key, icon, idx) ->
                        NavigationBarItem(
                            selected = tab == idx,
                            onClick = { tab = idx },
                            icon = { Text(icon, fontSize = 20.sp) },
                            label = { Text(T(key), fontSize = 10.sp, maxLines = 1) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, color = Color.White, fontSize = 22.sp, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp))
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LocalTheme.current.glass)
            .padding(12.dp),
        content = content
    )
}

// ---------------- Library ----------------

@Composable
private fun VideoThumb(uri: Uri, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            try { ctx.contentResolver.loadThumbnail(uri, Size(480, 270), null) } catch (e: Exception) { null }
        }
    }
    Box(modifier.background(Color(0x33000000)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text("🎬", fontSize = 28.sp)
    }
}

@Composable
private fun LibraryTab(activity: ComponentActivity, prefs: Prefs, onPlay: (PlayRequest) -> Unit) {
    val ctx = LocalContext.current
    val th = LocalTheme.current
    val scope = rememberCoroutineScope()
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) }
    var videos by remember { mutableStateOf(listOf<Vid>()) }
    var query by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val doneCount = DownloadCenter.items.count { it.status == "done" }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    LaunchedEffect(granted, doneCount) {
        if (granted) {
            if (doneCount > 0) delay(600)
            videos = withContext(Dispatchers.IO) {
                val list = ArrayList<Vid>()
                val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val proj = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION
                )
                try {
                    ctx.contentResolver.query(base, proj, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
                        val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                        val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                        val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                        while (c.moveToNext()) {
                            list.add(Vid(ContentUris.withAppendedId(base, c.getLong(iId)), c.getString(iName) ?: "video", c.getLong(iDur)))
                        }
                    }
                } catch (_: Exception) {}
                list
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            onPlay(PlayRequest(uri = uri.toString(), title = displayName(ctx, uri)))
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(T("lib"))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text(T("search")) }, modifier = Modifier.weight(1f)
            )
            GlassBtn("📂 " + T("open_file")) {
                try { filePicker.launch(arrayOf("video/*", "audio/*", "*/*")) }
                catch (e: Exception) { note = "File picker is not available on this device — use the library list." }
            }
        }
        note?.let { Text(it, color = Color(0xFFFFB4A9), fontSize = 12.sp, modifier = Modifier.padding(16.dp, 4.dp)) }
        Spacer(Modifier.height(8.dp))

        if (!granted) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(T("perm_msg"), color = Color.White)
                Spacer(Modifier.height(12.dp))
                GlassBtn(T("grant")) { permLauncher.launch(perm) }
            }
        } else {
            val filtered = remember(videos, query) { videos.filter { query.isBlank() || it.name.contains(query, true) } }
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(T("no_videos"), color = Color(0xFFCDB8FF)) }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(180.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filtered) { v ->
                        Column(
                            Modifier
                                .tvFocus()
                                .clip(RoundedCornerShape(14.dp))
                                .background(th.glass)
                                .clickable {
                                    val list = filtered.map { Channel(it.name, it.uri.toString(), "", "") }
                                    onPlay(PlayRequest(list = list, index = filtered.indexOf(v), title = v.name))
                                }
                        ) {
                            Box {
                                VideoThumb(v.uri, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                                Text(
                                    fmtTime(v.durMs), color = Color.White, fontSize = 11.sp,
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                                        .background(Color(0xAA000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(v.name, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Playlists ----------------

@Composable
private fun PlaylistsTab(prefs: Prefs, onPlay: (PlayRequest) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(prefs.playlists()) }
    var showAdd by remember { mutableStateOf(false) }
    var addName by remember { mutableStateOf("") }
    var addUrl by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun save(l: List<PlaylistEntry>) { list = l; prefs.setPlaylists(l) }

    suspend fun addFromText(name: String, url: String, text: String) {
        val parsed = M3uParser.parse(text)
        if (parsed.isEmpty()) { msg = "Playlist is empty or invalid"; return }
        val id = System.currentTimeMillis().toString()
        withContext(Dispatchers.IO) { playlistFile(ctx, id).writeText(text) }
        save(list + PlaylistEntry(id, name.ifBlank { "Playlist ${list.size + 1}" }, url))
        msg = "${parsed.size} channels"
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                try {
                    val text = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }
                    addFromText(displayName(ctx, uri).substringBeforeLast('.'), "", text)
                } catch (e: Exception) { msg = e.message } finally { busy = false }
            }
        }
    }

    fun open(e: PlaylistEntry) {
        scope.launch {
            val ch = withContext(Dispatchers.IO) {
                val f = playlistFile(ctx, e.id)
                if (f.exists()) M3uParser.parse(f.readText()) else emptyList()
            }
            if (ch.isEmpty()) msg = "Playlist is empty"
            else onPlay(PlayRequest(title = e.name, list = ch, index = -1, openList = true))
        }
    }

    fun refresh(e: PlaylistEntry) {
        scope.launch {
            busy = true
            try {
                val text = withContext(Dispatchers.IO) { httpGet(e.url) }
                val n = M3uParser.parse(text).size
                if (n == 0) msg = "Playlist is empty or invalid"
                else { withContext(Dispatchers.IO) { playlistFile(ctx, e.id).writeText(text) }; msg = "$n channels" }
            } catch (ex: Exception) { msg = ex.message } finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(T("playlists"))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassBtn("🔗 " + T("add_link")) { showAdd = true }
            GlassBtn("📂 " + T("add_file")) {
                try { filePicker.launch(arrayOf("*/*")) } catch (e: Exception) { msg = "File picker is not available on this device" }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        msg?.let { Text(it, color = Color(0xFFCDB8FF), fontSize = 12.sp, modifier = Modifier.padding(16.dp, 6.dp)) }
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(T("empty_pl"), color = Color(0xFFCDB8FF)) }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list) { e ->
                    Card(Modifier.tvFocus().clickable { open(e) }) {
                        Text(e.name, color = Color.White, fontSize = 16.sp)
                        if (e.url.isNotEmpty()) Text(e.url, color = Color(0xFFB9A5E8), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassBtn("▶ " + T("play")) { open(e) }
                            if (e.url.isNotEmpty()) GlassBtn("⟳ " + T("refresh")) { refresh(e) }
                            GlassBtn("🗑 " + T("delete")) {
                                playlistFile(ctx, e.id).delete()
                                save(list.filter { it.id != e.id })
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(T("add_link")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(addName, { addName = it }, singleLine = true, label = { Text(T("name")) })
                    OutlinedTextField(addUrl, { addUrl = it }, singleLine = true, label = { Text(T("url")) })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val u = addUrl.trim()
                    val n = addName
                    showAdd = false
                    if (u.isNotEmpty()) scope.launch {
                        busy = true
                        try {
                            val text = withContext(Dispatchers.IO) { httpGet(u) }
                            addFromText(n, u, text)
                            addName = ""; addUrl = ""
                        } catch (e: Exception) { msg = e.message } finally { busy = false }
                    }
                }) { Text(T("save")) }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text(T("cancel")) } }
        )
    }
}

// ---------------- YouTube ----------------

@Composable
private fun NetImage(url: String?, modifier: Modifier) {
    val bmp by produceState<Bitmap?>(null, url) {
        value = if (url == null) null else withContext(Dispatchers.IO) {
            try {
                Net.client.newCall(Request.Builder().url(url).header("User-Agent", FIREFOX_UA).build()).execute().use { r ->
                    r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
            } catch (e: Exception) { null }
        }
    }
    Box(modifier.background(Color(0x33000000)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text("▶", fontSize = 28.sp, color = Color.White)
    }
}

@Composable
private fun YoutubeTab(incomingUrl: String?, onHandled: () -> Unit, onPlay: (PlayRequest) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var url by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<YtInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    val addedMsg = T("added_dl")

    fun fetch(u: String) {
        if (u.isBlank()) return
        scope.launch {
            loading = true; error = null; info = null
            try {
                info = withContext(Dispatchers.IO) { Yt.fetch(u.trim()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message ?: e.javaClass.simpleName
            } finally { loading = false }
        }
    }

    LaunchedEffect(incomingUrl) {
        if (incomingUrl != null) {
            url = incomingUrl
            onHandled()
            fetch(incomingUrl)
        }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2000); toast = null } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Header(T("youtube"))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = url, onValueChange = { url = it }, singleLine = true,
                placeholder = { Text(T("yt_hint")) }, modifier = Modifier.weight(1f)
            )
            GlassBtn("📋") { clipboard.getText()?.text?.let { url = it } }
            GlassBtn(T("fetch")) { fetch(url) }
        }
        Text(T("yt_notice"), color = Color(0xFFB9A5E8), fontSize = 11.sp, modifier = Modifier.padding(16.dp, 8.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        error?.let {
            Text(it, color = Color.White, fontSize = 13.sp,
                modifier = Modifier.padding(16.dp).background(Color(0xDDB00020), RoundedCornerShape(12.dp)).padding(12.dp))
        }
        toast?.let { Text(it, color = Color(0xFF9BE8B8), modifier = Modifier.padding(16.dp, 4.dp)) }

        info?.let { yi ->
            Card(Modifier.padding(horizontal = 16.dp)) {
                NetImage(yi.thumb, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.height(8.dp))
                Text(yi.title, color = Color.White, fontSize = 16.sp)
                Text("${yi.uploader}  •  ${fmtTime(yi.durationSec * 1000)}", color = Color(0xFFB9A5E8), fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            yi.options.forEach { opt ->
                Card(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(opt.label, color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassBtn("▶ " + T("play")) {
                            onPlay(
                                PlayRequest(
                                    uri = opt.url, title = yi.title, audioUri = opt.audioUrl,
                                    userAgent = FIREFOX_UA, key = "yt:" + yi.title + opt.label
                                )
                            )
                        }
                        if (opt.canDownload) GlassBtn("⬇ " + T("download")) {
                            DownloadCenter.start(ctx, yi.title, opt)
                            toast = addedMsg
                        } else Text(T("stream_only"), color = Color(0xFFB9A5E8), fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically))
                    }
                }
            }
        }
    }
}

// ---------------- Downloads ----------------

@Composable
private fun DownloadsTab(onPlay: (PlayRequest) -> Unit) {
    val dl = DownloadCenter.items
    Column(Modifier.fillMaxSize()) {
        Header(T("downloads"))
        if (dl.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(T("no_dl"), color = Color(0xFFCDB8FF)) }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(dl.toList()) { d ->
                    Card {
                        Text(d.title, color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        if (d.status == "running" || d.status == "queued") {
                            LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth())
                        }
                        val statusText = buildString {
                            append(T("st_" + d.status))
                            if (d.status == "running") append("  ${(d.progress * 100).toInt()}%")
                            if (d.status == "failed") append("  ${d.message}")
                        }
                        Text(
                            statusText,
                            color = if (d.status == "failed") Color(0xFFFFB4A9) else Color(0xFFCDB8FF), fontSize = 12.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val u = d.uri
                            if (d.status == "done" && u != null) GlassBtn("▶ " + T("play")) {
                                onPlay(PlayRequest(uri = u.toString(), title = d.title))
                            }
                            GlassBtn(if (d.status == "running" || d.status == "queued") T("cancel") else T("delete")) {
                                DownloadCenter.cancelOrRemove(d)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Settings ----------------

@Composable
private fun SettingsTab(themeIdx: Int, onTheme: (Int) -> Unit, lang: String, onLang: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Header(T("settings"))
        Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(T("theme"), color = Color.White, fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                THEMES.forEachIndexed { i, t ->
                    Column(
                        Modifier.tvFocus().clip(RoundedCornerShape(14.dp)).clickable { onTheme(i) }.padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier.size(46.dp).clip(CircleShape).background(t.primary)
                                .border(if (i == themeIdx) 3.dp else 0.dp, Color.White, CircleShape)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(t.name, color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        }
        Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(T("language"), color = Color.White, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassBtn((if (lang == "fa") "● " else "") + "فارسی") { onLang("fa") }
                GlassBtn((if (lang == "en") "● " else "") + "English") { onLang("en") }
            }
        }
        Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(T("about"), color = Color.White, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Text("MdzPlyer Android 0.2", color = Color(0xFFCDB8FF), fontSize = 12.sp)
            Text("YouTube extraction: NewPipe Extractor (GPLv3)", color = Color(0xFFB9A5E8), fontSize = 11.sp)
        }
    }
}
