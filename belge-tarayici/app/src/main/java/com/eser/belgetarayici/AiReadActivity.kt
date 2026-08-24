package com.eser.belgetarayici

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
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
 * Claude Haiku ile belgeyi oku/anla: tur + alanlar (tutar/tarih/no) + ozet + ceviri.
 * Kullanicinin kendi Anthropic API anahtari (cihazda saklanir). Internet gerekir.
 */
class AiReadActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        private const val MODEL = "claude-haiku-4-5-20251001"
        private const val PREFS = "eserlens"
        private const val KEY = "anthropic_key"
    }

    private lateinit var keyInput: EditText
    private lateinit var askInput: EditText
    private lateinit var result: TextView
    private lateinit var progress: ProgressBar
    private lateinit var runBtn: Button
    private var path: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = intent.getStringExtra(EXTRA_PATH)
        val d = resources.displayMetrics.density
        val p = (16 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p, p, p)
        }

        root.addView(TextView(this).apply {
            text = "🤖 AI ile Oku (Claude Haiku)"
            textSize = 18f
            setTextColor(Color.parseColor("#0D47A1"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        // API anahtari
        root.addView(TextView(this).apply {
            text = "Anthropic API anahtarın (console.anthropic.com):"
            setPadding(0, p, 0, 4)
            setTextColor(Color.parseColor("#5A6675"))
        })
        keyInput = EditText(this).apply {
            hint = "sk-ant-..."
            inputType = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(prefs().getString(KEY, ""))
        }
        root.addView(keyInput)

        // Serbest soru (opsiyonel)
        askInput = EditText(this).apply {
            hint = "Soru sor (boş bırak: oku + alanları çıkar + özetle)"
        }
        root.addView(askInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = p })

        runBtn = Button(this).apply {
            text = "🤖 OKU"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#00B8D4"))
            setOnClickListener { runRead() }
        }
        root.addView(runBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = p })

        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = p })

        result = TextView(this).apply {
            setTextColor(Color.parseColor("#1A1A1A"))
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(0, p, 0, 0)
        }
        root.addView(result)

        val copy = Button(this).apply {
            text = "📋 Kopyala"
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("ai", result.text))
                toast("Kopyalandı")
            }
        }
        root.addView(copy, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = p })

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun runRead() {
        val key = keyInput.text.toString().trim()
        if (key.isEmpty()) { toast("Önce API anahtarını gir"); return }
        prefs().edit().putString(KEY, key).apply()
        val p = path
        if (p == null || !File(p).exists()) { toast("Belge yok"); return }

        val ask = askInput.text.toString().trim()
        val prompt = if (ask.isNotEmpty())
            "$ask\nSadece belgedeki bilgiyi kullan, uydurma. Türkçe yanıt ver."
        else
            "Sen bir belge okuma yardımcısısın. Ekteki belgeyi incele ve SADECE görüntüde " +
            "yazan bilgiyi kullan, hiçbir şey uydurma. Türkçe yanıt ver:\n" +
            "1) Belge türü\n" +
            "2) Önemli alanlar (varsa: tutar, KDV, tarih, belge/fatura no, taraflar)\n" +
            "3) 2-3 cümle özet\n" +
            "Belge yabancı dilse ayrıca Türkçe çevir. Okunamayan yeri '(okunamadı)' yaz."

        progress.visibility = View.VISIBLE
        runBtn.isEnabled = false
        result.text = ""
        Thread {
            val out = try { callClaude(key, imageB64(File(p)), prompt) }
            catch (e: Throwable) { "Hata: " + (e.message ?: e.toString()) }
            runOnUiThread {
                progress.visibility = View.GONE
                runBtn.isEnabled = true
                result.text = out
            }
        }.start()
    }

    // Belgeyi kucult + JPEG + base64
    private fun imageB64(f: File): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var s = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / s > 1500) s *= 2
        val bmp = BitmapFactory.decodeFile(
            f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s }
        )
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        bmp.recycle()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun callClaude(key: String, imgB64: String, prompt: String): String {
        val conn = URL("https://api.anthropic.com/v1/messages")
            .openConnection() as HttpsURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("x-api-key", key)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.setRequestProperty("content-type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 90000

        val content = JSONArray()
        content.put(JSONObject().apply {
            put("type", "image")
            put("source", JSONObject().apply {
                put("type", "base64"); put("media_type", "image/jpeg"); put("data", imgB64)
            })
        })
        content.put(JSONObject().apply { put("type", "text"); put("text", prompt) })
        val body = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", 1500)
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
