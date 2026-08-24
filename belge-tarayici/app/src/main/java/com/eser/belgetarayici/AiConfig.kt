package com.eser.belgetarayici

import android.content.Context

/**
 * Tum AI ayarlarinin tek merkezi. API anahtarlari, aktif saglayici (Claude/Gemini),
 * model secimi ve tarama tercihleri burada saklanir (SharedPreferences "eserlens").
 * AiReadActivity ve SettingsActivity buradan okur/yazar.
 */
object AiConfig {

    const val PREFS = "eserlens"

    // Saglayicilar
    const val CLAUDE = "claude"
    const val GEMINI = "gemini"

    // Prefs anahtarlari
    private const val K_PROVIDER = "ai_provider"
    private const val K_KEY_CLAUDE = "key_claude"
    private const val K_KEY_GEMINI = "key_gemini"
    private const val K_CLAUDE_MODEL = "claude_model"   // "haiku" | "sonnet"
    private const val K_GEMINI_MODEL = "gemini_model"   // "flash" | "pro"
    private const val K_IMG_MAX = "img_max"             // uzun kenar px
    private const val K_AUTO_MAGIC = "auto_magic"       // cekince otomatik Sihirli Tara
    private const val K_SAVE_PDF = "save_pdf_default"   // varsayilan kayit PDF mi

    // Eski surumden tasima
    private const val OLD_KEY = "anthropic_key"
    private const val OLD_SONNET = "use_sonnet"

    // Model kimlikleri
    const val CLAUDE_HAIKU = "claude-haiku-4-5-20251001"
    const val CLAUDE_SONNET = "claude-sonnet-5"
    const val GEMINI_FLASH = "gemini-2.5-flash"
    const val GEMINI_FLASH_FALLBACK = "gemini-2.0-flash"
    const val GEMINI_PRO = "gemini-2.5-pro"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Eski tek-anahtar surumunden yeni yapiya bir kez tasi. */
    private fun migrate(c: Context) {
        val sp = p(c)
        if (sp.contains(OLD_KEY) && !sp.contains(K_KEY_CLAUDE)) {
            sp.edit()
                .putString(K_KEY_CLAUDE, sp.getString(OLD_KEY, "") ?: "")
                .putString(K_CLAUDE_MODEL, if (sp.getBoolean(OLD_SONNET, false)) "sonnet" else "haiku")
                .apply()
        }
    }

    fun claudeKey(c: Context): String { migrate(c); return p(c).getString(K_KEY_CLAUDE, "")!!.trim() }
    fun geminiKey(c: Context): String = p(c).getString(K_KEY_GEMINI, "")!!.trim()

    fun setClaudeKey(c: Context, v: String) = p(c).edit().putString(K_KEY_CLAUDE, v.trim()).apply()
    fun setGeminiKey(c: Context, v: String) = p(c).edit().putString(K_KEY_GEMINI, v.trim()).apply()

    fun hasClaude(c: Context) = claudeKey(c).isNotEmpty()
    fun hasGemini(c: Context) = geminiKey(c).isNotEmpty()
    fun anyKey(c: Context) = hasClaude(c) || hasGemini(c)

    /** Kullanicinin sectigi saglayici; anahtari yoksa mevcut olana dus. */
    fun provider(c: Context): String {
        migrate(c)
        val want = p(c).getString(K_PROVIDER, CLAUDE) ?: CLAUDE
        return when {
            want == GEMINI && hasGemini(c) -> GEMINI
            want == CLAUDE && hasClaude(c) -> CLAUDE
            hasGemini(c) -> GEMINI
            hasClaude(c) -> CLAUDE
            else -> want
        }
    }
    fun setProvider(c: Context, v: String) = p(c).edit().putString(K_PROVIDER, v).apply()

    fun claudeModel(c: Context): String =
        if (p(c).getString(K_CLAUDE_MODEL, "haiku") == "sonnet") CLAUDE_SONNET else CLAUDE_HAIKU
    fun setClaudeSonnet(c: Context, on: Boolean) =
        p(c).edit().putString(K_CLAUDE_MODEL, if (on) "sonnet" else "haiku").apply()
    fun isClaudeSonnet(c: Context) = p(c).getString(K_CLAUDE_MODEL, "haiku") == "sonnet"

    fun geminiModel(c: Context): String =
        if (p(c).getString(K_GEMINI_MODEL, "flash") == "pro") GEMINI_PRO else GEMINI_FLASH
    fun setGeminiPro(c: Context, on: Boolean) =
        p(c).edit().putString(K_GEMINI_MODEL, if (on) "pro" else "flash").apply()
    fun isGeminiPro(c: Context) = p(c).getString(K_GEMINI_MODEL, "flash") == "pro"

    fun imgMax(c: Context): Int = p(c).getInt(K_IMG_MAX, 1600)
    fun setImgMax(c: Context, v: Int) = p(c).edit().putInt(K_IMG_MAX, v).apply()

    fun autoMagic(c: Context): Boolean = p(c).getBoolean(K_AUTO_MAGIC, false)
    fun setAutoMagic(c: Context, v: Boolean) = p(c).edit().putBoolean(K_AUTO_MAGIC, v).apply()

    fun savePdfDefault(c: Context): Boolean = p(c).getBoolean(K_SAVE_PDF, true)
    fun setSavePdfDefault(c: Context, v: Boolean) = p(c).edit().putBoolean(K_SAVE_PDF, v).apply()

    /** Aktif saglayicinin okunabilir adi (baslikta gostermek icin). */
    fun activeLabel(c: Context): String = when (provider(c)) {
        GEMINI -> "Gemini" + if (isGeminiPro(c)) " Pro" else " Flash"
        CLAUDE -> "Claude" + if (isClaudeSonnet(c)) " Sonnet" else " Haiku"
        else -> "—"
    }
}
