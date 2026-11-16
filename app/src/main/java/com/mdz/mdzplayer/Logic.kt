package com.mdz.mdzplayer

// Pure Kotlin logic (no Android imports) so it can be unit-tested on the JVM.

import java.util.Locale

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

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%02d:%02d", m, sec)
}

// ---------- Subtitle delay (shifts every timestamp of SRT / WebVTT / ASS-SSA text) ----------

private val SRT_TS = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})([.,])(\\d{3})")
private val ASS_TS = Regex("^(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2})$")

fun shiftSubtitle(text: String, deltaMs: Long): String {
    if (deltaMs == 0L) return text
    return text.lineSequence().joinToString("\n") { line ->
        when {
            line.contains("-->") -> SRT_TS.replace(line) { m ->
                val h = m.groupValues[1].toLong()
                val mi = m.groupValues[2].toLong()
                val s = m.groupValues[3].toLong()
                val ms = m.groupValues[5].toLong()
                val t = (((h * 60 + mi) * 60 + s) * 1000 + ms + deltaMs).coerceAtLeast(0)
                String.format(
                    Locale.US, "%02d:%02d:%02d%s%03d",
                    t / 3_600_000, (t / 60_000) % 60, (t / 1000) % 60, m.groupValues[4], t % 1000
                )
            }
            line.startsWith("Dialogue:") -> {
                val parts = line.split(",", limit = 10)
                if (parts.size < 3) line
                else {
                    val mutable = parts.toMutableList()
                    for (i in 1..2) mutable[i] = shiftAss(mutable[i].trim(), deltaMs)
                    mutable.joinToString(",")
                }
            }
            else -> line
        }
    }
}

private fun shiftAss(ts: String, deltaMs: Long): String {
    val m = ASS_TS.find(ts) ?: return ts
    val t = ((m.groupValues[1].toLong() * 3600 + m.groupValues[2].toLong() * 60 + m.groupValues[3].toLong()) * 1000 +
        m.groupValues[4].toLong() * 10 + deltaMs).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d:%02d.%02d", t / 3_600_000, (t / 60_000) % 60, (t / 1000) % 60, (t % 1000) / 10)
}

// ---------- Playback Doctor: turns a player error code into a plain explanation ----------
// Codes are the public androidx.media3.common.PlaybackException.ERROR_CODE_* values.

enum class Fix { NONE, RETRY, RESTART_LIVE, SOFTWARE_DECODER }

data class Diagnosis(val title: String, val advice: String, val fix: Fix)

object PlaybackDoctor {
    fun explain(code: Int): Diagnosis = when (code) {
        2001, 2002, 1003 -> Diagnosis(
            "Network problem",
            "The connection failed or timed out. Check the internet / VPN. The player retries automatically a few times.",
            Fix.RETRY
        )
        2004 -> Diagnosis(
            "The server refused the request (HTTP error)",
            "The link may have expired, be geo-blocked, need a login, or the server blocks this app's User-Agent.",
            Fix.NONE
        )
        2005 -> Diagnosis("File not found", "The file was moved, renamed or deleted, or the storage was removed.", Fix.NONE)
        2006 -> Diagnosis("No permission to read this file", "Allow access to videos, or re-pick the file with the file picker.", Fix.NONE)
        2007 -> Diagnosis("Plain HTTP traffic blocked", "This address uses http:// and the system blocked it.", Fix.NONE)
        1002 -> Diagnosis("Fell behind the live stream", "Jumping back to the live edge.", Fix.RESTART_LIVE)
        3001, 3002 -> Diagnosis(
            "The file or playlist looks corrupt",
            "The container could not be parsed. The download may be incomplete — try another copy.",
            Fix.NONE
        )
        3003, 3004 -> Diagnosis(
            "Unsupported container / playlist type",
            "This container is not supported by the built-in engine.",
            Fix.NONE
        )
        4001, 4003 -> Diagnosis(
            "Decoder failed",
            "The device decoder could not start or crashed. Another decoder is tried automatically. " +
                "If it still fails: Settings → Playback profile → Compatibility (software decoders), then reopen.",
            Fix.SOFTWARE_DECODER
        )
        4004 -> Diagnosis(
            "Video exceeds this device's capabilities",
            "Resolution, bit depth or profile is too demanding for the hardware decoder. Try the Compatibility profile.",
            Fix.SOFTWARE_DECODER
        )
        4005 -> Diagnosis("Video format not supported on this device", "No decoder on this device handles this codec/profile.", Fix.NONE)
        4006 -> Diagnosis("Decoder resources were reclaimed", "Another app took the hardware decoder. Retrying.", Fix.RETRY)
        5001, 5002, 5003, 5004 -> Diagnosis(
            "Audio output problem",
            "The audio output could not be opened. Check the connected audio device (Bluetooth/HDMI) and try again.",
            Fix.RETRY
        )
        else -> Diagnosis("Playback error", "An unexpected error occurred (code $code).", Fix.NONE)
    }
}

// ---------- Equalizer preset -> band level (milli-bels) ----------
// preset: 0 Flat, 1 Bass, 2 Vocal (dialogue), 3 Treble, 4 Cinema

val EQ_PRESETS = listOf("Flat", "Bass boost", "Vocal / dialogue", "Treble", "Cinema")

fun eqLevel(preset: Int, freqHz: Int, minMb: Int, maxMb: Int): Int {
    val top = maxMb.coerceAtLeast(0)
    val bottom = minMb.coerceAtMost(0)
    val v = when (preset) {
        1 -> when { freqHz < 250 -> 0.6f; freqHz < 500 -> 0.3f; else -> 0f }
        2 -> when { freqHz < 120 -> -0.3f; freqHz in 1000..4000 -> 0.5f; freqHz in 500..999 -> 0.2f; else -> 0f }
        3 -> when { freqHz >= 8000 -> 0.6f; freqHz >= 4000 -> 0.4f; else -> 0f }
        4 -> when { freqHz < 250 -> 0.4f; freqHz >= 8000 -> 0.4f; freqHz in 1000..4000 -> -0.1f; else -> 0f }
        else -> 0f
    }
    val mb = if (v >= 0) v * top else -v * bottom
    return mb.toInt().coerceIn(bottom, top)
}
