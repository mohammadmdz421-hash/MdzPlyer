package com.mdz.mdzplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    @Test fun m3u_parsesNamesGroupsAndUrls() {
        val txt = "\uFEFF#EXTM3U\n" +
            "#EXTINF:-1 tvg-logo=\"http://x/l.png\" group-title=\"News\",Channel One\n" +
            "http://a/1.m3u8\n\n" +
            "#EXTINF:-1,Second\nhttp://a/2.ts\nhttp://a/3.ts\n"
        val l = M3uParser.parse(txt)
        assertEquals(3, l.size)
        assertEquals("Channel One", l[0].name)
        assertEquals("News", l[0].group)
        assertEquals("http://x/l.png", l[0].logo)
        assertEquals("Second", l[1].name)
        assertEquals("http://a/3.ts", l[2].name)   // no EXTINF -> url used as name
    }

    @Test fun m3u_emptyAndGarbageGiveNoChannels() {
        assertEquals(0, M3uParser.parse("").size)
        assertEquals(0, M3uParser.parse("#EXTM3U\n#EXTINF:-1,Only name no url\n").size)
    }

    @Test fun fmtTime_formats() {
        assertEquals("00:00", fmtTime(0))
        assertEquals("01:05", fmtTime(65_000))
        assertEquals("1:01:01", fmtTime(3_661_000))
        assertEquals("00:00", fmtTime(-5000))
    }

    @Test fun subtitle_srtShiftForwardAndClamp() {
        val srt = "1\n00:00:01,000 --> 00:00:03,500\nHello\n"
        assertTrue(shiftSubtitle(srt, 1500).contains("00:00:02,500 --> 00:00:05,000"))
        assertTrue(shiftSubtitle(srt, -2000).contains("00:00:00,000 --> 00:00:01,500"))
        assertEquals(srt, shiftSubtitle(srt, 0))
    }

    @Test fun subtitle_vttKeepsDotSeparator() {
        val vtt = "WEBVTT\n\n00:01:00.000 --> 00:01:02.250\nHi\n"
        assertTrue(shiftSubtitle(vtt, 500).contains("00:01:00.500 --> 00:01:02.750"))
    }

    @Test fun subtitle_assDialogueShift() {
        val ass = "Dialogue: 0,0:00:01.00,0:00:02.50,Default,,0,0,0,,Text, with comma"
        val out = shiftSubtitle(ass, 2000)
        assertTrue(out, out.contains("0:00:03.00,0:00:04.50"))
        assertTrue(out.endsWith("Text, with comma"))
    }

    @Test fun subtitle_textLinesAreNotTouched() {
        val s = "1\n00:00:01,000 --> 00:00:02,000\nAt 00:00:05,000 we meet\n"
        assertTrue(shiftSubtitle(s, 1000).contains("At 00:00:05,000 we meet"))
    }

    @Test fun doctor_mapsKnownCodes() {
        assertEquals(Fix.RETRY, PlaybackDoctor.explain(2001).fix)
        assertEquals(Fix.RESTART_LIVE, PlaybackDoctor.explain(1002).fix)
        assertEquals(Fix.SOFTWARE_DECODER, PlaybackDoctor.explain(4001).fix)
        assertEquals(Fix.NONE, PlaybackDoctor.explain(2005).fix)
        assertTrue(PlaybackDoctor.explain(9999).advice.contains("9999"))
    }

    @Test fun eq_presetsRespectBandRange() {
        for (p in 0..4) for (f in listOf(60, 230, 910, 3600, 14000)) {
            val v = eqLevel(p, f, -1500, 1500)
            assertTrue("preset $p f=$f -> $v", v in -1500..1500)
        }
        assertEquals(0, eqLevel(0, 100, -1500, 1500))
        assertTrue(eqLevel(1, 60, -1500, 1500) > 0)
        assertTrue(eqLevel(2, 60, -1500, 1500) < 0)
    }
}
