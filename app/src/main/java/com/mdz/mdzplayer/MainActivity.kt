package com.mdz.mdzplayer

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val lp = window.attributes
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = lp
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        handleIntent(intent)

        setContent {
            val prefs = remember { Prefs(this@MainActivity) }
            var themeIdx by remember { mutableIntStateOf(prefs.themeIdx.coerceIn(0, THEMES.lastIndex)) }
            var lang by remember { mutableStateOf(prefs.lang) }
            val th = THEMES[themeIdx]
            var splash by remember { mutableStateOf(true) }
            var request by remember { mutableStateOf<PlayRequest?>(null) }

            LaunchedEffect(incoming) { if (incoming != null) request = null }

            CompositionLocalProvider(
                LocalTheme provides th,
                LocalLang provides lang,
                LocalLayoutDirection provides (if (lang == "fa") LayoutDirection.Rtl else LayoutDirection.Ltr)
            ) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = th.primary,
                        background = th.bg1,
                        surface = th.bg1,
                        onSurface = Color.White,
                        onBackground = Color.White,
                        secondaryContainer = th.primary.copy(alpha = 0.35f),
                        onSecondaryContainer = Color.White
                    )
                ) {
                    val r = request
                    when {
                        splash -> SplashScreen { splash = false }
                        r != null -> CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            PlayerScreen(this@MainActivity, r) {
                                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                                request = null
                            }
                        }
                        else -> HomeScreen(
                            activity = this@MainActivity,
                            prefs = prefs,
                            themeIdx = themeIdx,
                            onTheme = { themeIdx = it; prefs.themeIdx = it },
                            lang = lang,
                            onLang = { lang = it; prefs.lang = it },
                            incomingUrl = incoming,
                            onIncomingHandled = { incoming = null },
                            onPlay = { request = it }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT)
            val m = t?.let { Regex("https?://\\S+").find(it)?.value }
            if (m != null) incoming = m
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }
}
