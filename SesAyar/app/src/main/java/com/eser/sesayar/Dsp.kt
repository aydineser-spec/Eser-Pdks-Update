package com.eser.sesayar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Tek kanal icin ikinci dereceden (biquad) suzgec. */
class Biquad {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }

    fun setLowShelf(sampleRate: Int, freq: Float, gainDb: Float) =
        setShelf(sampleRate, freq, gainDb, low = true)

    fun setHighShelf(sampleRate: Int, freq: Float, gainDb: Float) =
        setShelf(sampleRate, freq, gainDb, low = false)

    /** RBJ audio EQ cookbook, raf (shelf) suzgeci, S = 1. */
    private fun setShelf(sampleRate: Int, freq: Float, gainDb: Float, low: Boolean) {
        val a = 10.0.pow(gainDb / 40.0).toFloat()
        val w0 = (2.0 * PI * freq / sampleRate).toFloat()
        val cosW = cos(w0)
        val alpha = sin(w0) / sqrt(2f)
        val twoSqrtAAlpha = 2f * sqrt(a) * alpha

        val nb0: Float
        val nb1: Float
        val nb2: Float
        val na0: Float
        val na1: Float
        val na2: Float
        if (low) {
            nb0 = a * ((a + 1) - (a - 1) * cosW + twoSqrtAAlpha)
            nb1 = 2 * a * ((a - 1) - (a + 1) * cosW)
            nb2 = a * ((a + 1) - (a - 1) * cosW - twoSqrtAAlpha)
            na0 = (a + 1) + (a - 1) * cosW + twoSqrtAAlpha
            na1 = -2 * ((a - 1) + (a + 1) * cosW)
            na2 = (a + 1) + (a - 1) * cosW - twoSqrtAAlpha
        } else {
            nb0 = a * ((a + 1) + (a - 1) * cosW + twoSqrtAAlpha)
            nb1 = -2 * a * ((a - 1) + (a + 1) * cosW)
            nb2 = a * ((a + 1) + (a - 1) * cosW - twoSqrtAAlpha)
            na0 = (a + 1) - (a - 1) * cosW + twoSqrtAAlpha
            na1 = 2 * ((a - 1) - (a + 1) * cosW)
            na2 = (a + 1) - (a - 1) * cosW - twoSqrtAAlpha
        }
        b0 = nb0 / na0
        b1 = nb1 / na0
        b2 = nb2 / na0
        a1 = na1 / na0
        a2 = na2 / na0
    }
}

/**
 * Yazilimsal bas / tiz / ses kazanci. Telefonun ses efekt sistemine bagli degildir,
 * sesi dogrudan sayisal olarak isler. Araya serilmis 16-bit PCM veri uzerinde calisir.
 */
class Dsp(private val sampleRate: Int, private val channels: Int) {
    private val low = Array(channels) { Biquad() }
    private val high = Array(channels) { Biquad() }
    private var gain = 1f

    var bassDb = 0f
        private set
    var trebleDb = 0f
        private set
    var boostDb = 0

    fun update(p: Profile) {
        bassDb = (p.bass - 50) / 50f * BASS_RANGE_DB
        trebleDb = (p.treble - 50) / 50f * TREBLE_RANGE_DB
        boostDb = p.boostDb
        gain = 10.0.pow(p.boostDb / 20.0).toFloat()
        for (c in 0 until channels) {
            low[c].setLowShelf(sampleRate, LOW_FREQ, bassDb)
            high[c].setHighShelf(sampleRate, HIGH_FREQ, trebleDb)
        }
    }

    /** Araya serilmis (interleaved) 16-bit ornekleri yerinde isler. */
    fun process(samples: ShortArray, count: Int) {
        for (i in 0 until count) {
            val ch = i % channels
            var x = samples[i] / 32768f
            x = low[ch].process(x)
            x = high[ch].process(x)
            x *= gain
            x = softLimit(x)
            samples[i] = (x * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** Sert kirpilma (cizirti) yerine yumusak sinirlayici: esik ustu tanh ile CEILING degerine yaklastirilir. */
    private fun softLimit(x: Float): Float {
        val ax = abs(x)
        if (ax <= KNEE) return x
        return sign(x) * (KNEE + (CEILING - KNEE) * tanh((ax - KNEE) / (CEILING - KNEE)))
    }

    companion object {
        const val BASS_RANGE_DB = SessionEffects.MAX_EQ_DB
        const val TREBLE_RANGE_DB = SessionEffects.MAX_EQ_DB
        const val LOW_FREQ = 150f
        const val HIGH_FREQ = 4000f
        private const val KNEE = 0.6f

        /** Cikis tavani yaklasik -1 dBFS: Bluetooth kodeklemesi (SBC/AAC) icin pay birakir. */
        private const val CEILING = 0.89f
    }
}
