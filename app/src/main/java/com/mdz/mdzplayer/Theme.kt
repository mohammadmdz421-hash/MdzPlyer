package com.mdz.mdzplayer

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class AppTheme(val name: String, val primary: Color, val bg1: Color, val bg2: Color, val glass: Color) {
    fun bgBrush() = Brush.verticalGradient(listOf(bg1, bg2))
}

val THEMES = listOf(
    AppTheme("Amethyst", Color(0xFF9B6BFF), Color(0xFF0B0618), Color(0xFF2A1655), Color(0xAA241447)),
    AppTheme("Midnight", Color(0xFF5B8CFF), Color(0xFF050914), Color(0xFF0F1E3D), Color(0xAA0F1E3D)),
    AppTheme("Orchid", Color(0xFFE06BFF), Color(0xFF14061A), Color(0xFF3A1250), Color(0xAA35124A)),
    AppTheme("Plum", Color(0xFFC65B9B), Color(0xFF16060F), Color(0xFF42142F), Color(0xAA3A1228)),
    AppTheme("Emerald", Color(0xFF3DDC97), Color(0xFF04120D), Color(0xFF0F3B2B), Color(0xAA0F3326)),
    AppTheme("Sunset", Color(0xFFFF8A5B), Color(0xFF160805), Color(0xFF47200F), Color(0xAA3D1B0C))
)

val LocalTheme = compositionLocalOf { THEMES[0] }
val LocalLang = compositionLocalOf { "en" }

private val STR: Map<String, Pair<String, String>> = mapOf(
    "lib" to ("Library" to "کتابخانه"),
    "playlists" to ("Playlists" to "پلی‌لیست‌ها"),
    "youtube" to ("YouTube" to "یوتیوب"),
    "downloads" to ("Downloads" to "دانلودها"),
    "settings" to ("Settings" to "تنظیمات"),
    "open_file" to ("Open file" to "باز کردن فایل"),
    "search" to ("Search…" to "جستجو…"),
    "no_videos" to ("No videos found on this device" to "ویدیویی روی این دستگاه پیدا نشد"),
    "grant" to ("Allow access to videos" to "اجازه‌ی دسترسی به ویدیوها"),
    "perm_msg" to ("MdzPlyer needs permission to show the videos on this device." to "برای نمایش ویدیوهای دستگاه، دسترسی لازم است."),
    "add_link" to ("Add from link" to "افزودن با لینک"),
    "add_file" to ("Add from file" to "افزودن از فایل"),
    "refresh" to ("Refresh" to "به‌روزرسانی"),
    "delete" to ("Delete" to "حذف"),
    "name" to ("Name" to "نام"),
    "url" to ("URL" to "آدرس"),
    "save" to ("Save" to "ذخیره"),
    "cancel" to ("Cancel" to "انصراف"),
    "empty_pl" to ("No playlists yet. Add an M3U link or file." to "هنوز پلی‌لیستی نیست. یک لینک یا فایل M3U اضافه کنید."),
    "yt_hint" to ("Paste a YouTube link" to "لینک یوتیوب را بچسبانید"),
    "paste" to ("Paste" to "چسباندن"),
    "fetch" to ("Fetch" to "دریافت"),
    "play" to ("Play" to "پخش"),
    "download" to ("Download" to "دانلود"),
    "yt_notice" to (
        "Download only videos you own or have permission to save. YouTube's terms restrict downloading." to
            "فقط ویدیوهایی را دانلود کنید که مالک آن‌ها هستید یا اجازه‌ی ذخیره‌شان را دارید. شرایط استفاده‌ی یوتیوب دانلود را محدود کرده است."
        ),
    "stream_only" to ("stream only" to "فقط پخش"),
    "added_dl" to ("Added to Downloads" to "به دانلودها اضافه شد"),
    "no_dl" to ("No downloads yet" to "هنوز دانلودی نیست"),
    "theme" to ("Theme" to "تم"),
    "language" to ("Language" to "زبان"),
    "about" to ("About" to "درباره"),
    "loading" to ("Loading…" to "در حال بارگذاری…"),
    "st_queued" to ("Queued" to "در صف"),
    "st_running" to ("Downloading" to "در حال دانلود"),
    "st_done" to ("Done" to "انجام شد"),
    "st_failed" to ("Failed" to "ناموفق"),
    "st_cancelled" to ("Cancelled" to "لغو شد")
)

@Composable
fun T(key: String): String {
    val lang = LocalLang.current
    val p = STR[key] ?: return key
    return if (lang == "fa") p.second else p.first
}

// Visible focus ring for D-pad / remote navigation (projectors, Android TV)
fun Modifier.tvFocus(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.hasFocus }
        .border(
            if (focused) 3.dp else 0.dp,
            if (focused) Color.White else Color.Transparent,
            RoundedCornerShape(14.dp)
        )
}
