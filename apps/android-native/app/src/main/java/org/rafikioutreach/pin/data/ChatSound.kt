package org.rafikioutreach.pin.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * The @mention chime (chatpa): two soft rising tones, made on the phone (no sound file), distinct from the send whoosh.
 * Quiet when the phone is on silent or vibrate; the caller checks Do-Not-Disturb and the setting.
 */
object Chime {
    /** Tests switch the real sound off and count instead. */
    var enabled = true
    @Volatile var played = 0
    fun mention(ctx: Context) {
        played++
        if (!enabled) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        Thread {
            runCatching {
                val rate = 22050; val n = (rate * 0.32).toInt()
                val pcm = ShortArray(n)
                for (i in 0 until n) {
                    val tSec = i.toDouble() / rate
                    val second = tSec > 0.13
                    val f = if (second) 1318.5 else 987.8 // B5 then E6
                    val local = if (second) tSec - 0.13 else tSec
                    val env = kotlin.math.exp(-local * 14) * 0.25
                    pcm[i] = (kotlin.math.sin(2 * Math.PI * f * tSec) * env * Short.MAX_VALUE).toInt().toShort()
                }
                val tr = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(n * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
                tr.write(pcm, 0, n); tr.play(); Thread.sleep(400); tr.release()
            }
        }.start()
    }
}
