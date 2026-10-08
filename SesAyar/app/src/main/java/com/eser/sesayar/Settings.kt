package com.eser.sesayar

import android.content.Context

data class Profile(val bass: Int, val treble: Int, val boostDb: Int)

object Settings {
    const val PROFILE_MINOR = "minor"
    const val PROFILE_OTHER = "other"

    const val MODE_AUTO = 0
    const val MODE_FORCE_MINOR = 1
    const val MODE_FORCE_OTHER = 2

    const val PREFS = "sesayar"

    private val defaults = mapOf(
        PROFILE_MINOR to Profile(bass = 85, treble = 62, boostDb = 4),
        PROFILE_OTHER to Profile(bass = 50, treble = 50, boostDb = 0),
    )

    val presets = linkedMapOf(
        "Düz (efekt yok)" to Profile(50, 50, 0),
        "Dengeli ve net" to Profile(60, 65, 2),
        "Bas ağırlıklı" to Profile(100, 45, 3),
        "Minor IV için önerilen" to Profile(85, 62, 4),
        "Vokal / konuşma" to Profile(35, 75, 4),
    )

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)

    fun setEnabled(c: Context, v: Boolean) = prefs(c).edit().putBoolean("enabled", v).apply()

    fun mode(c: Context) = prefs(c).getInt("mode", MODE_AUTO)

    fun setMode(c: Context, v: Int) = prefs(c).edit().putInt("mode", v).apply()

    fun profile(c: Context, key: String): Profile {
        val d = defaults.getValue(key)
        val p = prefs(c)
        return Profile(
            bass = p.getInt("${key}_bass", d.bass),
            treble = p.getInt("${key}_treble", d.treble),
            boostDb = p.getInt("${key}_boost", d.boostDb),
        )
    }

    fun saveProfile(c: Context, key: String, v: Profile) {
        prefs(c).edit()
            .putInt("${key}_bass", v.bass)
            .putInt("${key}_treble", v.treble)
            .putInt("${key}_boost", v.boostDb)
            .apply()
    }
}
