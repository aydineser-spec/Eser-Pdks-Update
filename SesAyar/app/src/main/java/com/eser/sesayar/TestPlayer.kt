package com.eser.sesayar

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.sin

/**
 * Efektin gercekten calistigini denemek icin kendi ses oturumuna bagli kucuk calar.
 * Efekt dogrudan bu calarin oturumuna baglandigi icin telefon markasina bagli degil, kesin calisir.
 */
class TestPlayer(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var fx: SessionEffects? = null
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private var media: MediaPlayer? = null
    @Volatile private var running = false

    @Volatile var effectOk = false
        private set

    val isPlaying: Boolean get() = running || media != null

    /** Bas (100 Hz), orta (1 kHz) ve tiz (6 kHz) bolumlerinden olusan, tekrar eden test sesi. */
    fun playTone(p: Profile) {
        stop()
        val session = audioManager.generateAudioSessionId()
        attachEffects(session, p)

        val sampleRate = 44100
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(attributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setSessionId(session)
            .build()
        track = t
        running = true
        t.play()
        val pattern = buildPattern(sampleRate)
        thread = Thread {
            var pos = 0
            while (running) {
                val n = minOf(CHUNK, pattern.size - pos)
                val written = t.write(pattern, pos, n)
                if (written <= 0) break
                pos = (pos + written) % pattern.size
            }
        }.also { it.start() }
    }

    fun playUri(uri: Uri, p: Profile, onError: (String) -> Unit) {
        stop()
        val session = audioManager.generateAudioSessionId()
        attachEffects(session, p)

        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attributes())
            mp.audioSessionId = session
            mp.setDataSource(context, uri)
            mp.isLooping = true
            mp.setOnPreparedListener { it.start() }
            mp.setOnErrorListener { _, _, _ ->
                handler.post {
                    stop()
                    onError("Dosya çalınamadı")
                }
                true
            }
            media = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            media = null
            try { mp.release() } catch (_: Exception) { }
            fx?.release()
            fx = null
            onError("Dosya açılamadı: ${e.message}")
        }
    }

    fun apply(p: Profile) {
        fx?.apply(p)
    }

    fun stop() {
        running = false
        track?.let { t ->
            try { t.pause() } catch (_: Exception) { }
            try { t.flush() } catch (_: Exception) { }
        }
        try { thread?.join(1000) } catch (_: Exception) { }
        thread = null
        try { track?.release() } catch (_: Exception) { }
        track = null

        media?.let { m ->
            try { m.stop() } catch (_: Exception) { }
            try { m.release() } catch (_: Exception) { }
        }
        media = null

        fx?.release()
        fx = null
    }

    private fun attachEffects(session: Int, p: Profile) {
        val e = SessionEffects(session)
        effectOk = e.create()
        if (effectOk) {
            e.apply(p)
            fx = e
        } else {
            e.release()
        }
    }

    private fun attributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private fun buildPattern(sampleRate: Int): ShortArray {
        val freqs = intArrayOf(100, 1000, 6000)
        val toneLen = (sampleRate * 0.7).toInt()
        val gapLen = (sampleRate * 0.25).toInt()
        val fade = (sampleRate * 0.02).toInt()
        val amp = 0.3 * Short.MAX_VALUE
        val out = ShortArray(freqs.size * (toneLen + gapLen))
        var idx = 0
        for (f in freqs) {
            for (i in 0 until toneLen) {
                val env = when {
                    i < fade -> i / fade.toDouble()
                    i > toneLen - fade -> (toneLen - i) / fade.toDouble()
                    else -> 1.0
                }
                out[idx++] = (sin(2 * PI * f * i / sampleRate) * amp * env).toInt().toShort()
            }
            idx += gapLen
        }
        return out
    }

    companion object {
        private const val CHUNK = 4096
    }
}
