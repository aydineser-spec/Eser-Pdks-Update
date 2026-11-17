package com.eser.belgetarayici

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.io.File

/**
 * Sayfanin 4 kosesini elle ayarlama ekrani.
 *  - EXTRA_PATH: gosterilecek kaynak (kamera sayfasinda ORIJINAL fotograf: kesilen
 *    kisim kaybolmaz, koseler istendigi kadar yeniden ayarlanir)
 *  - EXTRA_OUT: sonucun yazilacagi dosya (yoksa kaynagin ustune)
 *  - EXTRA_CORNERS: baslangic koseleri (0..1); EXTRA_ROTATION: sonuca uygulanacak
 *    ceyrek donus sayisi (sayfa onceden dondurulduysa)
 * Sonuc: RESULT_OK + EXTRA_CORNERS (secilen koseler).
 * Duzlestirme tam cozunurlukte ve arka planda yapilir (ekran donmaz).
 */
class CropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_OUT = "out"
        const val EXTRA_CORNERS = "corners"
        const val EXTRA_ROTATION = "rotation"
        private const val VIEW_DIM = 2000   // ekranda gosterim
        private const val OUT_DIM = 4000    // sonuc kalitesi
    }

    private lateinit var cropView: CropView
    private lateinit var title: TextView
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnApply: MaterialButton
    private var srcPath: String? = null
    private var outPath: String? = null
    private var rotation = 0
    @Volatile private var working = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        srcPath = intent.getStringExtra(EXTRA_PATH)
        outPath = intent.getStringExtra(EXTRA_OUT) ?: srcPath
        rotation = intent.getIntExtra(EXTRA_ROTATION, 0)
        val initial = intent.getFloatArrayExtra(EXTRA_CORNERS)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111111"))
        }

        val d = resources.displayMetrics.density
        title = TextView(this).apply {
            text = getString(R.string.crop_hint)
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
        }
        root.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        cropView = CropView(this)
        root.addView(cropView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Iki buyuk tus: Vazgec (gri) | Tamam (mavi)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (16 * d).toInt())
        }
        btnCancel = MaterialButton(this).apply {
            text = getString(R.string.cancel)
            isAllCaps = false
            textSize = 17f
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#424242"))
            setTextColor(Color.WHITE)
            setOnClickListener { if (!working) finish() }
        }
        btnApply = MaterialButton(this).apply {
            text = getString(R.string.do_straighten)
            isAllCaps = false
            textSize = 17f
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.primary))
            setTextColor(Color.WHITE)
            setOnClickListener { applyWarp() }
        }
        val btnH = (58 * d).toInt()
        bar.addView(btnCancel, LinearLayout.LayoutParams(0, btnH, 1f)
            .apply { marginEnd = (8 * d).toInt() })
        bar.addView(btnApply, LinearLayout.LayoutParams(0, btnH, 1f))
        root.addView(bar)

        setContentView(root)

        val p = srcPath
        val bmp = if (p != null) {
            try { ImageUtils.decodeScaled(File(p), VIEW_DIM) } catch (e: Throwable) { null }
        } else null
        if (bmp == null) { finish(); return }
        cropView.setBitmap(bmp, initial)
    }

    private fun applyWarp() {
        if (working) return
        val src = srcPath ?: run { finish(); return }
        val out = outPath ?: src
        val corners = cropView.cornersNormalized()
        working = true
        btnApply.isEnabled = false
        btnCancel.isEnabled = false
        title.text = getString(R.string.cropping)
        Thread {
            val ok = try {
                val full = ImageUtils.decodeScaled(File(src), OUT_DIM)
                val warped = ImageUtils.warp(full, corners)
                full.recycle()
                val rotated = ImageUtils.rotateQuarters(warped, rotation)
                if (rotated !== warped) warped.recycle()
                ImageUtils.writeJpeg(rotated, File(out), 92)
                rotated.recycle()
                true
            } catch (e: Throwable) {
                false
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok) {
                    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_CORNERS, corners))
                    Toast.makeText(this, getString(R.string.straighten_done), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.processing_failed), Toast.LENGTH_LONG).show()
                }
                finish()
            }
        }.start()
    }
}
