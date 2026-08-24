package com.eser.belgetarayici

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Belgeyi AI ile oku/anla. Saglayici (Gemini/Claude), model ve anahtar Ayarlar'dan gelir.
 * Presetler (fatura/tapu/ceviri) + serbest soru. Sadece belgedeki bilgi kullanilir.
 */
class AiReadActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"

        private const val P_READ =
            "Sen bir belge okuma yardımcısısın. Ekteki belgeyi incele ve SADECE görüntüde " +
            "yazan bilgiyi kullan, hiçbir şey uydurma. Türkçe yanıt ver:\n" +
            "1) Belge türü\n2) Önemli alanlar\n3) 2-3 cümle özet.\n" +
            "Belge yabancı dilse ayrıca Türkçe çevir. Okunamayan yeri '(okunamadı)' yaz."
        private const val P_FATURA =
            "Bu bir fatura/fiş. SADECE görüntüdeki bilgiyle şu alanları Türkçe listele " +
            "(yoksa '(yok)'): Firma, Vergi Dairesi/VKN, Fatura/Fiş No, Tarih, " +
            "Kalemler (ürün + adet + birim fiyat), Ara Toplam, KDV, Genel/Ödenecek Toplam. " +
            "Rakamları ve tarihi aynen yaz, uydurma."
        private const val P_TAPU =
            "Bu bir tapu/imar/kadastro/numarataj belgesi. SADECE görüntüdeki bilgiyle şu alanları " +
            "Türkçe listele (yoksa '(yok)'): İl/İlçe, Mahalle/Köy, Ada, Parsel, Pafta, Blok, Kat, " +
            "Bağımsız Bölüm, Nitelik, Yüzölçümü, Malik(ler), Sokak/Cadde, Kapı No, Tarih, Yevmiye, " +
            "Cilt/Sayfa, Belge No. Ada/parsel gibi '/' içeren numaraları AYNEN yaz. Uydurma."
        private const val P_CEVIR =
            "Bu belgedeki tüm metni oku ve akıcı Türkçe'ye çevir. Sadece belgedeki metni kullan, " +
            "uydurma. Okunamayan yeri '(okunamadı)' yaz."
    }

    private lateinit var askInput: EditText
    private lateinit var result: TextView
    private lateinit var progress: ProgressBar
    private lateinit var activeLabel: TextView
    private val actionButtons = ArrayList<Button>()
    private var path: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = intent.getStringExtra(EXTRA_PATH)
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "🤖 AI ile Oku"
            textSize = 18f
            setTextColor(Color.parseColor("#0D47A1"))
            setTypeface(typeface, Typeface.BOLD)
        })

        // Aktif AI + Ayarlar
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        activeLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#37474F"))
        }
        topRow.addView(activeLabel, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topRow.addView(Button(this).apply {
            text = "⚙ Ayarlar"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@AiReadActivity, SettingsActivity::class.java)) }
        })
        root.addView(topRow, lp().apply { topMargin = (8 * dp).toInt() })

        // Hazir presetler
        root.addView(TextView(this).apply {
            text = "Hızlı işlem:"
            setPadding(0, pad, 0, 4)
            setTextColor(Color.parseColor("#5A6675"))
        })
        root.addView(presetRow("📄 Oku+Özetle" to P_READ, "🧾 Fatura" to P_FATURA))
        root.addView(presetRow("🏛️ Tapu/İmar" to P_TAPU, "🌍 Çevir" to P_CEVIR))

        askInput = EditText(this).apply {
            hint = "Kendi sorunu yaz (ör. 'ödenecek tutar ne?')"
        }
        root.addView(askInput, lp().apply { topMargin = pad })

        val askBtn = Button(this).apply {
            text = "🤖 SORUYU SOR"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#00B8D4"))
            setOnClickListener {
                val q = askInput.text.toString().trim()
                if (q.isEmpty()) toast("Ya bir işlem seç ya da soru yaz")
                else run("$q\nSadece belgedeki bilgiyi kullan, uydurma. Türkçe yanıt ver.")
            }
        }
        root.addView(askBtn, lp().apply { topMargin = (8 * dp).toInt() })
        actionButtons.add(askBtn)

        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = pad })

        result = TextView(this).apply {
            setTextColor(Color.parseColor("#1A1A1A"))
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(0, pad, 0, 0)
        }
        root.addView(result)

        root.addView(Button(this).apply {
            text = "📋 Kopyala"
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("ai", result.text))
                toast("Kopyalandı")
            }
        }, lp().apply { topMargin = pad })

        val scroll = ScrollView(this); scroll.addView(root); setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        activeLabel.text = if (AiConfig.anyKey(this))
            "Aktif: " + AiConfig.activeLabel(this)
        else "Aktif: yok — Ayarlar'dan anahtar gir"
    }

    private fun presetRow(vararg items: Pair<String, String>): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val dp = resources.displayMetrics.density
        items.forEachIndexed { i, (label, prompt) ->
            val b = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 13f
                setTextColor(Color.parseColor("#0D47A1"))
                setOnClickListener { run(prompt) }
            }
            actionButtons.add(b)
            row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (i == 0) marginEnd = (6 * dp).toInt() })
        }
        return row
    }

    private fun lp() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun run(prompt: String) {
        if (!AiConfig.anyKey(this)) {
            toast("Önce Ayarlar'dan bir AI anahtarı gir")
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val p = path
        if (p == null || !File(p).exists()) { toast("Belge yok"); return }
        val provider = AiConfig.provider(this)

        progress.visibility = View.VISIBLE
        setButtons(false)
        result.text = ""
        Thread {
            val out = try {
                val img = imageB64(File(p))
                if (provider == AiConfig.GEMINI)
                    callGemini(AiConfig.geminiKey(this), AiConfig.geminiModel(this), img, prompt)
                else
                    callClaude(AiConfig.claudeKey(this), AiConfig.claudeModel(this), img, prompt)
            } catch (e: Throwable) { "Hata: " + (e.message ?: e.toString()) }
            runOnUiThread {
                progress.visibility = View.GONE
                setButtons(true)
                result.text = out
            }
        }.start()
    }

    private fun setButtons(on: Boolean) { actionButtons.forEach { it.isEnabled = on } }

    private fun imageB64(f: File): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        val target = AiConfig.imgMax(this)
        var s = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / s > target) s *= 2
        val bmp = BitmapFactory.decodeFile(
            f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s }
        )
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, baos)
        bmp.recycle()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    // ---------------- Gemini ----------------
    private fun callGemini(key: String, model: String, imgB64: String, prompt: String): String {
        val first = geminiOnce(key, model, imgB64, prompt)
        // Model adi bulunamadiysa (404) yedek flash modeliyle tekrar dene
        if (first.startsWith("Hata (404") && model != AiConfig.GEMINI_FLASH_FALLBACK)
            return geminiOnce(key, AiConfig.GEMINI_FLASH_FALLBACK, imgB64, prompt)
        return first
    }

    private fun geminiOnce(key: String, model: String, imgB64: String, prompt: String): String {
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpsURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("x-goog-api-key", key)
        conn.setRequestProperty("content-type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 120000

        val parts = JSONArray()
        parts.put(JSONObject().apply {
            put("inline_data", JSONObject().apply {
                put("mime_type", "image/jpeg"); put("data", imgB64)
            })
        })
        parts.put(JSONObject().apply { put("text", prompt) })
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", parts)
            }))
            put("generationConfig", JSONObject().apply { put("maxOutputTokens", 2000) })
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) {
            val msg = try { JSONObject(resp).getJSONObject("error").getString("message") }
            catch (e: Throwable) { resp.take(300) }
            return "Hata ($code): $msg"
        }
        val obj = JSONObject(resp)
        val cands = obj.optJSONArray("candidates")
        if (cands == null || cands.length() == 0) {
            val block = obj.optJSONObject("promptFeedback")?.optString("blockReason")
            return if (!block.isNullOrEmpty()) "Engellendi: $block" else "(boş yanıt)"
        }
        val sb = StringBuilder()
        val cparts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
        if (cparts != null) for (i in 0 until cparts.length())
            sb.append(cparts.getJSONObject(i).optString("text"))
        return sb.toString().ifBlank { "(boş yanıt)" }
    }

    // ---------------- Claude ----------------
    private fun callClaude(key: String, model: String, imgB64: String, prompt: String): String {
        val conn = URL("https://api.anthropic.com/v1/messages")
            .openConnection() as HttpsURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("x-api-key", key)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.setRequestProperty("content-type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 120000

        val content = JSONArray()
        content.put(JSONObject().apply {
            put("type", "image")
            put("source", JSONObject().apply {
                put("type", "base64"); put("media_type", "image/jpeg"); put("data", imgB64)
            })
        })
        content.put(JSONObject().apply { put("type", "text"); put("text", prompt) })
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 2000)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user"); put("content", content)
            }))
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) {
            val msg = try { JSONObject(resp).getJSONObject("error").getString("message") }
            catch (e: Throwable) { resp.take(300) }
            return "Hata ($code): $msg"
        }
        val arr = JSONObject(resp).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("type") == "text") sb.append(o.optString("text"))
        }
        return sb.toString().ifBlank { "(boş yanıt)" }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
