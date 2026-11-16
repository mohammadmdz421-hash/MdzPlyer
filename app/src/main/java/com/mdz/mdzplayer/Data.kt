package com.mdz.mdzplayer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class Channel(val name: String, val url: String, val group: String, val logo: String)

object M3uParser {
    private val attrRegex = Regex("([\\w-]+)=\"([^\"]*)\"")

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name = ""
        var group = ""
        var logo = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val attrs = attrRegex.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                group = attrs["group-title"] ?: ""
                logo = attrs["tvg-logo"] ?: ""
                val afterComma = line.substringAfterLast(",", "").trim()
                name = afterComma.ifEmpty { attrs["tvg-name"] ?: "" }
            } else if (!line.startsWith("#")) {
                out.add(Channel(name.ifEmpty { line }, line, group, logo))
                name = ""; group = ""; logo = ""
            }
        }
        return out
    }
}

data class PlaylistEntry(val id: String, val name: String, val url: String)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("mdz", Context.MODE_PRIVATE)

    var themeIdx: Int
        get() = sp.getInt("theme", 0)
        set(v) { sp.edit().putInt("theme", v).apply() }

    var lang: String
        get() = sp.getString("lang", "en") ?: "en"
        set(v) { sp.edit().putString("lang", v).apply() }

    fun playlists(): List<PlaylistEntry> {
        return try {
            val arr = org.json.JSONArray(sp.getString("pls", "[]"))
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                PlaylistEntry(o.getString("id"), o.getString("name"), o.optString("url"))
            }
        } catch (e: Exception) { emptyList() }
    }

    fun setPlaylists(l: List<PlaylistEntry>) {
        val arr = org.json.JSONArray()
        l.forEach { arr.put(org.json.JSONObject().put("id", it.id).put("name", it.name).put("url", it.url)) }
        sp.edit().putString("pls", arr.toString()).apply()
    }

    var fitMode: Int
        get() = sp.getInt("fit", 0)
        set(v) { sp.edit().putInt("fit", v).apply() }

    fun favs(): Set<String> = HashSet(sp.getStringSet("favs", emptySet()) ?: emptySet())
    fun setFavs(s: Set<String>) { sp.edit().putStringSet("favs", HashSet(s)).apply() }

    fun getPos(key: String): Long = sp.getLong("pos_" + key.hashCode(), 0L)
    fun savePos(key: String, pos: Long, dur: Long) {
        val k = "pos_" + key.hashCode()
        if (dur > 0 && pos > dur - 15_000) sp.edit().remove(k).apply()
        else sp.edit().putLong(k, pos).apply()
    }
}

fun displayName(ctx: Context, uri: Uri): String {
    try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
    } catch (_: Exception) {
    }
    return uri.lastPathSegment ?: "video"
}

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

// Downloads text (playlist) using the same VLC User-Agent as the Windows version
fun httpGet(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.setRequestProperty("User-Agent", "VLC/3.0.20 LibVLC/3.0.20")
    conn.connectTimeout = 20_000
    conn.readTimeout = 30_000
    conn.instanceFollowRedirects = true
    try {
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

// Reads a subtitle file; if it is not valid UTF-8 (typical Persian cp1256 files)
// it is decoded as windows-1256 and saved as UTF-8 in the cache. Returns file + mime type.
fun prepareSubtitle(ctx: Context, uri: Uri): Pair<File, String> {
    val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    val text = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        String(bytes, Charset.forName("windows-1256"))
    }.removePrefix("\uFEFF")
    val (ext, mime) = when {
        text.trimStart().startsWith("WEBVTT") -> "vtt" to "text/vtt"
        text.contains("[Script Info]") -> "ssa" to "text/x-ssa"
        else -> "srt" to "application/x-subrip"
    }
    val f = File(ctx.cacheDir, "sub_${System.currentTimeMillis()}.$ext")
    f.writeText(text, Charsets.UTF_8)
    return f to mime
}

fun playlistFile(ctx: Context, id: String) = File(ctx.filesDir, "pl_$id.m3u")
