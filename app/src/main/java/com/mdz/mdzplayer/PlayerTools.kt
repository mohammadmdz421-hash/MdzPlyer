package com.mdz.mdzplayer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import java.io.File
import kotlin.math.roundToInt

// ---------- Audio effects (system Equalizer + LoudnessEnhancer) ----------

class FxHolder {
    private var eq: Equalizer? = null
    private var boost: LoudnessEnhancer? = null

    fun rebuild(session: Int, prefs: Prefs) {
        release()
        if (session == 0 || session == C.AUDIO_SESSION_ID_UNSET) return
        try { eq = Equalizer(0, session).also { it.setEnabled(true) } } catch (_: Throwable) { eq = null }
        try { boost = LoudnessEnhancer(session) } catch (_: Throwable) { boost = null }
        apply(prefs)
    }

    fun apply(prefs: Prefs) {
        try {
            eq?.let { e ->
                val range = e.bandLevelRange
                for (b in 0 until e.numberOfBands.toInt()) {
                    val hz = e.getCenterFreq(b.toShort()) / 1000
                    val lv = eqLevel(prefs.eqPreset, hz, range[0].toInt(), range[1].toInt())
                    e.setBandLevel(b.toShort(), lv.toShort())
                }
            }
            boost?.let {
                it.setTargetGain(prefs.boostMb)
                it.setEnabled(prefs.boostMb > 0)
            }
        } catch (_: Throwable) {
        }
    }

    fun release() {
        try { eq?.release() } catch (_: Throwable) {}
        try { boost?.release() } catch (_: Throwable) {}
        eq = null
        boost = null
    }
}

// ---------- Screenshot saving ----------

fun saveBitmapToGallery(ctx: Context, bmp: Bitmap, name: String): Boolean {
    val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(50).ifBlank { "frame" }
    val fname = "${safe}_${System.currentTimeMillis()}.jpg"
    return try {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fname)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/MdzPlayer")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v)
                ?: return false
            ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: ctx.filesDir
            File(dir, fname).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        }
        true
    } catch (e: Exception) {
        false
    }
}

// ---------- Dialogs ----------

@Composable
fun InfoDialog(lines: List<Pair<String, String>>, onClose: () -> Unit) {
    val th = LocalTheme.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Video X-Ray") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                lines.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(k, color = th.soft, fontSize = 12.sp, modifier = Modifier.weight(0.4f))
                        Text(v, color = th.text, fontSize = 12.sp, modifier = Modifier.weight(0.6f))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Values are exactly what the player/decoder reports. HDR and hardware decoding are shown only when the stream/decoder exposes them.",
                    color = th.soft, fontSize = 10.sp
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(T("close")) } }
    )
}

@Composable
fun BookmarksDialog(
    items: List<Pair<Long, String>>,
    onAdd: (String) -> Unit,
    onSeek: (Long) -> Unit,
    onDelete: (Int) -> Unit,
    onClose: () -> Unit
) {
    val th = LocalTheme.current
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Scene bookmarks") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = note, onValueChange = { note = it }, singleLine = true,
                        placeholder = { Text("Note (optional)") }, modifier = Modifier.weight(1f)
                    )
                    GlassBtn("＋ Here") { onAdd(note); note = "" }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    itemsIndexed(items) { i, (pos, n) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onSeek(pos) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${fmtTime(pos)}  $n", color = th.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text("✕", color = th.soft, modifier = Modifier.clickable { onDelete(i) }.padding(8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(T("close")) } }
    )
}

@Composable
fun SubLabDialog(
    prefs: Prefs,
    delayMs: Long,
    onApplyDelay: (Long) -> Unit,
    onStyle: () -> Unit,
    onPickFile: () -> Unit,
    onClose: () -> Unit
) {
    val th = LocalTheme.current
    var size by remember { mutableFloatStateOf(prefs.subSize) }
    var color by remember { mutableIntStateOf(prefs.subColor) }
    var bg by remember { mutableStateOf(prefs.subBg) }
    var bottom by remember { mutableFloatStateOf(prefs.subBottom) }
    var dly by remember { mutableLongStateOf(delayMs) }
    val swatches = listOf(-1, 0xFFFFEB3B.toInt(), 0xFF80DEEA.toInt(), 0xFFA5D6A7.toInt())

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Subtitle Lab") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Size: ${size.roundToInt()} sp", color = th.text, fontSize = 13.sp)
                Slider(size, { size = it; prefs.subSize = it; onStyle() }, valueRange = 12f..48f)
                Text("Vertical position", color = th.text, fontSize = 13.sp)
                Slider(bottom, { bottom = it; prefs.subBottom = it; onStyle() }, valueRange = 0.02f..0.4f)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Color", color = th.text, fontSize = 13.sp)
                    swatches.forEach { c ->
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(Color(c))
                                .border(if (c == color) 3.dp else 1.dp, if (c == color) th.primary else Color.Gray, CircleShape)
                                .clickable { color = c; prefs.subColor = c; onStyle() }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Background box", color = th.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(bg, { bg = it; prefs.subBg = it; onStyle() })
                }
                Spacer(Modifier.height(8.dp))
                Text("Delay: $dly ms  (external subtitle files)", color = th.text, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GlassBtn("−0.5s") { dly -= 500 }
                    GlassBtn("−0.1s") { dly -= 100 }
                    GlassBtn("+0.1s") { dly += 100 }
                    GlassBtn("+0.5s") { dly += 500 }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GlassBtn("Apply delay") { onApplyDelay(dly) }
                    GlassBtn("Reset") { dly = 0; onApplyDelay(0) }
                }
                Spacer(Modifier.height(8.dp))
                GlassBtn("📁 Load subtitle file") { onPickFile() }
                Text(
                    "Files that are not UTF-8 (e.g. Persian cp1256) are converted automatically. Delay applies to files you load here, not to embedded tracks.",
                    color = th.soft, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(T("close")) } }
    )
}

@Composable
fun EqDialog(prefs: Prefs, onChanged: () -> Unit, onClose: () -> Unit) {
    val th = LocalTheme.current
    var preset by remember { mutableIntStateOf(prefs.eqPreset) }
    var boost by remember { mutableIntStateOf(prefs.boostMb) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Audio FX") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                EQ_PRESETS.forEachIndexed { i, n ->
                    Row(
                        Modifier.fillMaxWidth().clickable { preset = i; prefs.eqPreset = i; onChanged() }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = preset == i, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Text(n, color = th.text, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Volume boost: ${"%.1f".format(java.util.Locale.US, boost / 100f)} dB", color = th.text, fontSize = 13.sp)
                Slider(boost.toFloat(), { boost = it.toInt(); prefs.boostMb = boost; onChanged() }, valueRange = 0f..1000f)
                Text(
                    "Uses the device's system audio effects. Availability depends on the device; large boosts can distort. Vocal preset raises the speech range — it is not true dialogue separation.",
                    color = th.soft, fontSize = 10.sp
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(T("close")) } }
    )
}
