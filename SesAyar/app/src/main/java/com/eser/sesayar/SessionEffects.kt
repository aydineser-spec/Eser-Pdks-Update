package com.eser.sesayar

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import kotlin.math.roundToInt

/** Bir ses oturumuna (session) bagli bas/tiz/ses yukseltme efektleri. sessionId 0 = genel karisim. */
class SessionEffects(val sessionId: Int) {
    private var eq: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudness: LoudnessEnhancer? = null

    /** En az bir efekt olusturulabildiyse true. */
    fun create(): Boolean {
        eq = try {
            Equalizer(0, sessionId).also { it.setEnabled(true) }
        } catch (e: Exception) {
            null
        }
        bassBoost = try {
            BassBoost(0, sessionId).also { it.setEnabled(true) }
        } catch (e: Exception) {
            null
        }
        loudness = try {
            LoudnessEnhancer(sessionId).also { it.setEnabled(true) }
        } catch (e: Exception) {
            null
        }
        return eq != null || bassBoost != null || loudness != null
    }

    fun apply(p: Profile) {
        val bassDb = (p.bass - 50) / 50f * MAX_EQ_DB
        val trebleDb = (p.treble - 50) / 50f * MAX_EQ_DB

        try {
            eq?.let { e ->
                val range = e.bandLevelRange
                val lo = range[0].toInt()
                val hi = range[1].toInt()
                for (band in 0 until e.numberOfBands) {
                    val hz = e.getCenterFreq(band.toShort()) / 1000
                    val db = bassWeight(hz) * bassDb + trebleWeight(hz) * trebleDb
                    val level = (db * 100).roundToInt().coerceIn(lo, hi)
                    e.setBandLevel(band.toShort(), level.toShort())
                }
            }
        } catch (e: Exception) {
        }

        try {
            bassBoost?.let { b ->
                if (b.strengthSupported) {
                    val strength = ((p.bass - 50).coerceAtLeast(0) * 12).coerceIn(0, 1000)
                    b.setStrength(strength.toShort())
                }
            }
        } catch (e: Exception) {
        }

        try {
            loudness?.setTargetGain(p.boostDb.coerceIn(0, MAX_BOOST_DB) * 100)
        } catch (e: Exception) {
        }
    }

    fun release() {
        try { eq?.release() } catch (e: Exception) { }
        try { bassBoost?.release() } catch (e: Exception) { }
        try { loudness?.release() } catch (e: Exception) { }
        eq = null
        bassBoost = null
        loudness = null
    }

    private fun bassWeight(hz: Int): Float = when {
        hz <= 250 -> 1f
        hz < 1000 -> 0.25f
        else -> 0f
    }

    private fun trebleWeight(hz: Int): Float = when {
        hz >= 3000 -> 1f
        hz >= 1000 -> 0.3f
        else -> 0f
    }

    companion object {
        const val MAX_EQ_DB = 10f
        const val MAX_BOOST_DB = 10
    }
}
