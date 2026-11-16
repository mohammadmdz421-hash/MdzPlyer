package com.mdz.mdzplayer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

const val FIREFOX_UA = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"

object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}

class NpDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val b = okhttp3.Request.Builder()
            .method(request.httpMethod(), body)
            .url(request.url())
            .addHeader("User-Agent", FIREFOX_UA)
        for ((name, values) in request.headers()) {
            b.removeHeader(name)
            for (v in values) b.addHeader(name, v)
        }
        Net.client.newCall(b.build()).execute().use { resp ->
            if (resp.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            val text = resp.body?.string()
            return Response(
                resp.code,
                resp.message,
                resp.headers.toMultimap(),
                text,
                resp.request.url.toString()
            )
        }
    }
}

data class YtOption(
    val label: String,
    val url: String,
    val audioUrl: String?,   // set for HD "stream only" options (video-only + separate audio)
    val ext: String,
    val isAudio: Boolean,
    val canDownload: Boolean
)

data class YtInfo(val title: String, val uploader: String, val durationSec: Long, val thumb: String?, val options: List<YtOption>)

object Yt {
    private var inited = false

    private fun ensureInit() {
        if (!inited) {
            NewPipe.init(NpDownloader(), Localization.DEFAULT)
            inited = true
        }
    }

    // Blocking: call from Dispatchers.IO
    fun fetch(url: String): YtInfo {
        ensureInit()
        val info = StreamInfo.getInfo(url)
        val opts = ArrayList<YtOption>()

        val progressive = info.videoStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            .sortedByDescending { it.height }
        for (v in progressive) {
            val ext = v.format?.suffix ?: "mp4"
            opts.add(YtOption("${v.resolution} • video+audio • $ext", v.content, null, ext, false, true))
        }
        val progMax = progressive.maxOfOrNull { it.height } ?: 0

        val audios = info.audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
        val bestAudio = audios.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: audios.maxByOrNull { it.averageBitrate }

        if (bestAudio != null) {
            info.videoOnlyStreams
                .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && it.format == MediaFormat.MPEG_4 }
                .filter { it.height > progMax }
                .sortedByDescending { it.height }
                .distinctBy { it.height }
                .forEach { v ->
                    opts.add(YtOption("${v.resolution} HD • stream only", v.content, bestAudio.content, "mp4", false, false))
                }
            val aext = bestAudio.format?.suffix ?: "m4a"
            opts.add(YtOption("Audio only • $aext • ${bestAudio.averageBitrate}kbps", bestAudio.content, null, aext, true, true))
        }

        val thumb = try { info.thumbnails.firstOrNull()?.url } catch (_: Throwable) { null }
        return YtInfo(info.name ?: "YouTube", info.uploaderName ?: "", info.duration, thumb, opts)
    }
}

class DlItem(val id: Long, val title: String, val isAudio: Boolean) {
    var progress by mutableFloatStateOf(0f)
    var status by mutableStateOf("queued")   // queued | running | done | failed | cancelled
    var message by mutableStateOf("")
    var uri: Uri? = null
    var job: Job? = null
}

object DownloadCenter {
    val items = mutableStateListOf<DlItem>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var nextId = 1L
    private const val CHUNK = 4L * 1024 * 1024

    fun start(ctx: Context, title: String, opt: YtOption) {
        val app = ctx.applicationContext
        val item = DlItem(nextId++, title, opt.isAudio)
        items.add(0, item)
        item.job = scope.launch {
            try {
                item.status = "running"
                item.uri = download(app, item, title, opt)
                item.progress = 1f
                item.status = "done"
            } catch (e: CancellationException) {
                item.status = "cancelled"
                throw e
            } catch (e: Throwable) {
                item.message = e.message ?: e.javaClass.simpleName
                item.status = "failed"
            }
        }
    }

    fun cancelOrRemove(item: DlItem) {
        item.job?.cancel()
        items.remove(item)
    }

    private suspend fun download(ctx: Context, item: DlItem, title: String, opt: YtOption): Uri {
        val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "video" }
        val mime = when (opt.ext) {
            "m4a" -> "audio/mp4"
            "mp4" -> if (opt.isAudio) "audio/mp4" else "video/mp4"
            "webm" -> if (opt.isAudio) "audio/webm" else "video/webm"
            "3gp" -> "video/3gpp"
            else -> "application/octet-stream"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$safe.${opt.ext}")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (opt.isAudio) "Music/MdzPlayer" else "Movies/MdzPlayer")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (opt.isAudio)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val resolver = ctx.contentResolver
        val uri = resolver.insert(collection, values) ?: throw IOException("Cannot create file")
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                fetchChunks(opt.url, out) { p -> item.progress = p }
            }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return uri
        } catch (e: Throwable) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
    }

    // YouTube throttles long single requests, so the file is fetched in 4 MB range chunks
    private suspend fun fetchChunks(url: String, out: OutputStream, onProgress: (Float) -> Unit) {
        var start = 0L
        var total = -1L
        var written = 0L
        val buf = ByteArray(64 * 1024)
        var full = false
        while (!full && (total < 0 || start < total)) {
            currentCoroutineContext().ensureActive()
            val end = start + CHUNK - 1
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", FIREFOX_UA)
                .header("Range", "bytes=$start-$end")
                .build()
            Net.client.newCall(req).execute().use { resp ->
                if (resp.code != 200 && resp.code != 206) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("Empty response")
                if (resp.code == 206) {
                    if (total < 0) {
                        total = resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
                    }
                } else {
                    total = body.contentLength()
                    full = true
                }
                val ins = body.byteStream()
                var got = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    got += n
                    written += n
                    if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 0.99f))
                }
                if (!full) {
                    if (got == 0L) throw IOException("No data received")
                    start += got
                }
            }
            if (!full && total < 0) throw IOException("Unknown file size")
        }
    }
}
