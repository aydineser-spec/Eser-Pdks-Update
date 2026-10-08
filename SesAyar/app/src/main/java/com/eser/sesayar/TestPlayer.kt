package com.eser.sesayar

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Yazilimsal calar: sesi kendisi cozer, bas / tiz / ses kazancini sayisal olarak uygular ve
 * AudioTrack ile gonderir. Telefonun ses efekt sistemine (ve Samsung'un engellerine) bagli degildir;
 * Bluetooth'ta da ayni sekilde calisir.
 */
class TestPlayer(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())

    private var thread: Thread? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var running = false
    @Volatile private var profile = Profile(50, 50, 0)
    @Volatile private var version = 0
    @Volatile private var info = ""

    /** Yazilimsal isleme her zaman kullanilabilir. */
    val effectOk: Boolean get() = true

    /** Calma kendiliginden bittiginde ana is parcaciginda cagrilir. */
    var onStopped: (() -> Unit)? = null

    val isPlaying: Boolean get() = running

    fun apply(p: Profile) {
        profile = p
        version++
    }

    fun report(): String = if (info.isEmpty()) "Yazılım çalar: çalmıyor" else info

    /** Bas (100 Hz), orta (1 kHz) ve tiz (6 kHz) bolumlerinden olusan, tekrar eden test sesi. */
    fun playTone(p: Profile) {
        stop()
        profile = p
        version++
        start { runTone() }
    }

    fun playUri(uri: Uri, p: Profile, onError: (String) -> Unit) {
        stop()
        profile = p
        version++
        start { runDecode(uri, onError) }
    }

    fun stop() {
        running = false
        track?.let { t ->
            try { t.pause() } catch (_: Exception) { }
            try { t.flush() } catch (_: Exception) { }
        }
        try { thread?.join(1500) } catch (_: Exception) { }
        thread = null
    }

    private fun start(body: () -> Unit) {
        running = true
        thread = Thread {
            try {
                body()
            } finally {
                releaseTrack()
                val wasRunning = running
                running = false
                info = ""
                if (wasRunning) handler.post { onStopped?.invoke() }
            }
        }.also { it.start() }
    }

    private fun releaseTrack() {
        val t = track ?: return
        track = null
        try { t.stop() } catch (_: Exception) { }
        try { t.release() } catch (_: Exception) { }
    }

    private fun createTrack(sampleRate: Int, channels: Int): AudioTrack {
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(mask)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        return t
    }

    private fun describe(dsp: Dsp, sampleRate: Int, channels: Int) {
        info = String.format(
            "Yazılım işleme (telefon efektinden bağımsız)\nBas %+.1f dB, Tiz %+.1f dB, Ses +%d dB\n%d Hz, %d kanal",
            dsp.bassDb, dsp.trebleDb, dsp.boostDb, sampleRate, channels
        )
    }

    private fun runTone() {
        val sampleRate = 44100
        val dsp = Dsp(sampleRate, 1)
        var seen = -1
        val t = createTrack(sampleRate, 1)
        val pattern = buildPattern(sampleRate)
        val buf = ShortArray(CHUNK)
        var pos = 0
        while (running) {
            if (seen != version) {
                dsp.update(profile)
                seen = version
                describe(dsp, sampleRate, 1)
            }
            val n = minOf(CHUNK, pattern.size - pos)
            System.arraycopy(pattern, pos, buf, 0, n)
            dsp.process(buf, n)
            val written = t.write(buf, 0, n)
            if (written <= 0) break
            pos = (pos + n) % pattern.size
        }
    }

    private fun runDecode(uri: Uri, onError: (String) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var idx = -1
            var mime = ""
            for (i in 0 until extractor.trackCount) {
                val m = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    idx = i
                    mime = m
                    break
                }
            }
            if (idx < 0) {
                fail(onError, "Dosyada ses bulunamadı")
                return
            }
            extractor.selectTrack(idx)
            val format = extractor.getTrackFormat(idx)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var dsp: Dsp? = null
            var seen = -1
            var inputDone = false
            var outputDone = false
            val bufInfo = MediaCodec.BufferInfo()

            while (running && !outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufInfo, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT
                    ) {
                        fail(onError, "Bu ses biçimi desteklenmiyor")
                        return
                    }
                    dsp = null
                    releaseTrack()
                } else if (outIdx >= 0) {
                    if (bufInfo.size > 0) {
                        if (channels != 1 && channels != 2) {
                            fail(onError, "Desteklenmeyen kanal sayısı: $channels")
                            return
                        }
                        if (dsp == null) {
                            dsp = Dsp(sampleRate, channels)
                            seen = -1
                        }
                        val t = track ?: createTrack(sampleRate, channels)
                        if (seen != version) {
                            dsp.update(profile)
                            seen = version
                            describe(dsp, sampleRate, channels)
                        }
                        val ob = codec.getOutputBuffer(outIdx)!!
                        ob.position(bufInfo.offset)
                        ob.limit(bufInfo.offset + bufInfo.size)
                        val count = bufInfo.size / 2
                        val samples = ShortArray(count)
                        ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                        dsp.process(samples, count)
                        t.write(samples, 0, count)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } catch (e: Exception) {
            fail(onError, "Dosya çalınamadı: ${e.message}")
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            try { extractor.release() } catch (_: Exception) { }
        }
    }

    private fun fail(onError: (String) -> Unit, message: String) {
        handler.post { onError(message) }
    }

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
