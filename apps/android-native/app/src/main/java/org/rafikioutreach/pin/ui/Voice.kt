package org.rafikioutreach.pin.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.rafikioutreach.pin.t
import java.io.File

/** A short voice note (up to 2 minutes), kept on the phone until the visit is sent. */
@Composable
fun VoiceNote(file: File?, onChange: (File?) -> Unit) {
    val ctx = LocalContext.current
    var rec by remember { mutableStateOf<MediaRecorder?>(null) }
    var out by remember { mutableStateOf<File?>(null) }
    var secs by remember { mutableStateOf(0) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun stop() {
        val r = rec ?: return
        runCatching { r.stop() }.onSuccess { onChange(out) }.onFailure { out?.delete(); msg = t("Too short. Hold on a little longer.", "Fupi mno. Endelea kidogo zaidi.") }
        r.release(); rec = null
    }
    fun start() {
        val dir = File(ctx.filesDir, "photos").apply { mkdirs() }
        val f = File(dir, "v${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        runCatching {
            r.setAudioSource(MediaRecorder.AudioSource.MIC); r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64000); r.setAudioSamplingRate(44100); r.setMaxDuration(120000); r.setOutputFile(f.path)
            r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stop() }
            r.prepare(); r.start()
        }.onSuccess { rec = r; out = f; secs = 0; msg = null }.onFailure { r.release(); msg = t("The microphone couldn’t start.", "Kipaza sauti hakikuweza kuanza.") }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) start() else msg = t("Allow the microphone to record a voice note.", "Ruhusu kipaza sauti ili kurekodi ujumbe wa sauti.") }
    LaunchedEffect(rec) { while (rec != null) { delay(1000); secs++ } }
    DisposableEffect(Unit) { onDispose { rec?.let { runCatching { it.stop() }; it.release() }; player?.release() } }

    Text(t("Voice note (optional)", "Ujumbe wa sauti (si lazima)"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            rec != null -> Btn(t("Stop", "Simamisha") + "  %d:%02d".format(secs / 60, secs % 60), { stop() }, small = true, icon = "x")
            file != null -> {
                Btn(if (player != null) t("Playing…", "Inacheza…") else t("Play", "Cheza"), {
                    player?.release(); player = MediaPlayer().apply { runCatching { setDataSource(file.path); prepare(); start(); setOnCompletionListener { it.release(); player = null } } }
                }, small = true, light = true, icon = "arrow")
                Btn(t("Remove", "Ondoa"), { file.delete(); onChange(null) }, small = true, light = true, icon = "x")
            }
            else -> Btn(t("Record a voice note", "Rekodi ujumbe wa sauti"), {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start() else ask.launch(Manifest.permission.RECORD_AUDIO)
            }, small = true, light = true, icon = "mic")
        }
    }
    Msg(msg, "warn")
}
