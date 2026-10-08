package com.eser.sesayar

import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import kotlin.math.roundToInt

/** Bir ses oturumuna (session) bagli bas/tiz/ses yukseltme efektleri. sessionId 0 = genel karisim. */
class SessionEffects(val sessionId: Int) {
    private var eq: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudness: LoudnessEnhancer? = null

    private val createNotes = ArrayList<String>()
    private val applyNotes = ArrayList<String>()

    /** En az bir efekt olusturulabildiyse true. */
    fun create(): Boolean {
        createNotes.clear()
        eq = tryCreate("Ekolayzer") { Equalizer(0, sessionId) }
        bassBoost = tryCreate("Bas güçlendirici") { BassBoost(0, sessionId) }
        loudness = tryCreate("Ses yükseltici") { LoudnessEnhancer(sessionId) }
        return eq != null || bassBoost != null || loudness != null
    }

    private fun <T : AudioEffect> tryCreate(name: String, factory: () -> T): T? {
        return try {
            val fx = factory()
            val r = fx.setEnabled(true)
            val state = if (fx.enabled) "açık" else "KAPALI"
            val control = if (fx.hasControl()) "kontrol bende" else "KONTROL BAŞKASINDA"
            createNotes.add("$name: oluştu, $state, $control (kod $r)")
            fx
        } catch (e: Exception) {
            createNotes.add("$name: OLUŞMADI (${e.javaClass.simpleName}: ${e.message})")
            null
        }
    }

    fun apply(p: Profile) {
        applyNotes.clear()
        val bassDb = (p.bass - 50) / 50f * MAX_EQ_DB
        val trebleDb = (p.treble - 50) / 50f * MAX_EQ_DB

        try {
            eq?.let { e ->
                val range = e.bandLevelRange
                val lo = range[0].toInt()
                val hi = range[1].toInt()
                val levels = ArrayList<Int>()
                for (band in 0 until e.numberOfBands) {
                    val hz = e.getCenterFreq(band.toShort()) / 1000
                    val db = bassWeight(hz) * bassDb + trebleWeight(hz) * trebleDb
                    val level = (db * 100).roundToInt().coerceIn(lo, hi)
                    e.setBandLevel(band.toShort(), level.toShort())
                    levels.add(e.getBandLevel(band.toShort()).toInt())
                }
                applyNotes.add("EQ bantları (mB): $levels")
            }
        } catch (e: Exception) {
            applyNotes.add("EQ ayarı HATA: ${e.javaClass.simpleName}: ${e.message}")
        }

        try {
            bassBoost?.let { b ->
                if (b.strengthSupported) {
                    val strength = ((p.bass - 50).coerceAtLeast(0) * 12).coerceIn(0, 1000)
                    b.setStrength(strength.toShort())
                    applyNotes.add("Bas güçlendirici: istenen $strength, okunan ${b.roundedStrength}")
                } else {
                    applyNotes.add("Bas güçlendirici bu cihazda desteklenmiyor")
                }
            }
        } catch (e: Exception) {
            applyNotes.add("Bas güçlendirici HATA: ${e.javaClass.simpleName}: ${e.message}")
        }

        try {
            loudness?.let { l ->
                val gain = p.boostDb.coerceIn(0, MAX_BOOST_DB) * 100
                l.setTargetGain(gain)
                applyNotes.add("Ses yükseltme: istenen $gain mB, okunan ${l.targetGain} mB")
            }
        } catch (e: Exception) {
            applyNotes.add("Ses yükseltme HATA: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Efektlerin gercekten ne durumda oldugunu gosteren metin (tani icin). */
    fun report(): String {
        val lines = ArrayList<String>()
        lines.add("Oturum $sessionId")
        lines.addAll(createNotes)
        lines.addAll(applyNotes)
        return lines.joinToString("\n")
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
