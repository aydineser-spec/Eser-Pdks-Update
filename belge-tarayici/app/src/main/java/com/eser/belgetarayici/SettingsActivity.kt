package com.eser.belgetarayici

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Ayarlar: AI anahtarlari (Gemini/Claude), hangi AI kullanilacak, model secimi,
 * tarama ve gorsel kalite tercihleri. Hangi AI'in anahtarini girersen listede o cikar.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var geminiKey: EditText
    private lateinit var claudeKey: EditText
    private lateinit var providerGroup: RadioGroup
    private lateinit var providerHint: TextView
    private var dp = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Ayarlar"
        dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(header("⚙ Ayarlar", 20f))

        // ---------- AI Oku ----------
        root.addView(sectionTitle("🤖 AI Oku — Yapay Zeka"))
        root.addView(note(
            "Belgeyi \"AI Oku\" ile okutmak için bir yapay zeka anahtarı gir. " +
            "Sadece anahtarını girdiğin AI aşağıdaki listede çıkar."
        ))

        // Gemini (bedava)
        root.addView(label("Gemini API anahtarı  (BEDAVA — aistudio.google.com):"))
        geminiKey = keyField("AIza...", AiConfig.geminiKey(this))
        root.addView(geminiKey)
        val geminiPro = CheckBox(this).apply {
            text = "Güçlü model (Gemini Pro) — daha isabetli, günlük limiti azdır"
            isChecked = AiConfig.isGeminiPro(this@SettingsActivity)
        }
        root.addView(geminiPro)

        // Claude (ucretli)
        root.addView(label("Claude API anahtarı  (ücretli — console.anthropic.com):"))
        claudeKey = keyField("sk-ant-...", AiConfig.claudeKey(this))
        root.addView(claudeKey)
        val claudeSonnet = CheckBox(this).apply {
            text = "Güçlü model (Claude Sonnet) — zor belgelerde daha isabetli, pahalı"
            isChecked = AiConfig.isClaudeSonnet(this@SettingsActivity)
        }
        root.addView(claudeSonnet)

        // Kullanilacak AI (dinamik)
        root.addView(label("Kullanılacak AI:"))
        providerHint = note("Önce yukarıdan bir anahtar gir.")
        root.addView(providerHint)
        providerGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        root.addView(providerGroup)

        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = rebuildProviders()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        geminiKey.addTextChangedListener(watcher)
        claudeKey.addTextChangedListener(watcher)
        rebuildProviders()

        // ---------- Tarama ----------
        root.addView(sectionTitle("📷 Tarama"))
        val autoMagic = CheckBox(this).apply {
            text = "Çekince otomatik \"Sihirli Tara\" uygula (gölge sil + düzelt)"
            isChecked = AiConfig.autoMagic(this@SettingsActivity)
        }
        root.addView(autoMagic)

        // ---------- Gorsel kalite ----------
        root.addView(sectionTitle("🔎 AI Oku görüntü kalitesi"))
        root.addView(note("Yüksek kalite küçük yazıları daha iyi okur ama biraz daha yavaş/pahalıdır."))
        val qGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val qNormal = RadioButton(this).apply { text = "Normal"; id = 1600 }
        val qHigh = RadioButton(this).apply { text = "Yüksek"; id = 2200 }
        qGroup.addView(qNormal); qGroup.addView(qHigh)
        qGroup.check(if (AiConfig.imgMax(this) >= 2000) 2200 else 1600)
        root.addView(qGroup)

        // ---------- Kaydet ----------
        val save = Button(this).apply {
            text = "💾 KAYDET"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1565C0"))
            setOnClickListener {
                AiConfig.setGeminiKey(this@SettingsActivity, geminiKey.text.toString())
                AiConfig.setClaudeKey(this@SettingsActivity, claudeKey.text.toString())
                AiConfig.setGeminiPro(this@SettingsActivity, geminiPro.isChecked)
                AiConfig.setClaudeSonnet(this@SettingsActivity, claudeSonnet.isChecked)
                AiConfig.setAutoMagic(this@SettingsActivity, autoMagic.isChecked)
                AiConfig.setImgMax(this@SettingsActivity, if (qGroup.checkedRadioButtonId == 2200) 2200 else 1600)
                when (providerGroup.checkedRadioButtonId) {
                    1 -> AiConfig.setProvider(this@SettingsActivity, AiConfig.GEMINI)
                    2 -> AiConfig.setProvider(this@SettingsActivity, AiConfig.CLAUDE)
                }
                Toast.makeText(this@SettingsActivity, "Ayarlar kaydedildi", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        root.addView(save, lp().apply { topMargin = (20 * dp).toInt() })

        root.addView(note(
            "Gemini bedava anahtar: aistudio.google.com → Get API key.\n" +
            "Claude anahtar: console.anthropic.com → API Keys (kredi gerekir)."
        ))

        val scroll = ScrollView(this); scroll.addView(root); setContentView(scroll)
    }

    /** Sadece anahtari girilmis saglayicilar icin radio goster. */
    private fun rebuildProviders() {
        val hasG = geminiKey.text.toString().trim().isNotEmpty()
        val hasC = claudeKey.text.toString().trim().isNotEmpty()
        val prev = providerGroup.checkedRadioButtonId
        providerGroup.removeAllViews()
        if (hasG) providerGroup.addView(RadioButton(this).apply { text = "Gemini (bedava)"; id = 1 })
        if (hasC) providerGroup.addView(RadioButton(this).apply { text = "Claude"; id = 2 })
        providerHint.visibility = if (hasG || hasC) TextView.GONE else TextView.VISIBLE

        // Onceki secim korunur; yoksa kayitli tercih; yoksa ilk mevcut.
        val saved = AiConfig.provider(this)
        val target = when {
            prev == 1 && hasG -> 1
            prev == 2 && hasC -> 2
            saved == AiConfig.GEMINI && hasG -> 1
            saved == AiConfig.CLAUDE && hasC -> 2
            hasG -> 1
            hasC -> 2
            else -> -1
        }
        if (target != -1) providerGroup.check(target)
    }

    private fun lp() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun header(t: String, size: Float) = TextView(this).apply {
        text = t; textSize = size
        setTextColor(Color.parseColor("#0D47A1"))
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t; textSize = 16f
        setTextColor(Color.parseColor("#1565C0"))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, (20 * dp).toInt(), 0, (6 * dp).toInt())
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.parseColor("#37474F"))
        setPadding(0, (12 * dp).toInt(), 0, (2 * dp).toInt())
    }

    private fun note(t: String) = TextView(this).apply {
        text = t; textSize = 13f
        setTextColor(Color.parseColor("#78909C"))
        setPadding(0, (2 * dp).toInt(), 0, (2 * dp).toInt())
    }

    private fun keyField(hintText: String, value: String) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        setText(value)
    }
}
