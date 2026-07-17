package com.eser.belgetarayici

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot
import kotlin.math.max

/**
 * Yamuk belgeyi elle secilen 4 koseye gore duz dikdortgene (A4 gibi) cevirir.
 * Perspektif donusum icin Android'in Matrix.setPolyToPoly'si kullanilir.
 */
class CropActivity : AppCompatActivity() {

    companion object { const val EXTRA_PATH = "path" }

    private lateinit var cropView: CropView
    private var path: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = intent.getStringExtra(EXTRA_PATH)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111111"))
        }

        val title = TextView(this).apply {
            text = getString(R.string.crop_hint)
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(32, 28, 32, 20)
        }
        root.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        cropView = CropView(this)
        root.addView(cropView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 20, 24, 32)
        }
        val cancel = Button(this).apply {
            text = getString(R.string.cancel)
            setOnClickListener { finish() }
        }
        val apply = Button(this).apply {
            text = getString(R.string.do_straighten)
            setOnClickListener { applyWarp() }
        }
        bar.addView(cancel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = 12 })
        bar.addView(apply, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)

        setContentView(root)

        val p = path
        if (p != null) {
            val bmp = decode(File(p))
            if (bmp != null) cropView.setBitmap(bmp)
            else finish()
        } else finish()
    }

    private fun decode(f: File): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, b)
        var s = 1
        while (max(b.outWidth, b.outHeight) / s > 1600) s *= 2
        return BitmapFactory.decodeFile(
            f.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = s }
        )
    }

    private fun applyWarp() {
        val p = path ?: run { finish(); return }
        val src = decode(File(p)) ?: run { finish(); return }
        val c = cropView.cornersInBitmap() // TL,TR,BR,BL (bitmap px)

        val wTop = hypot((c[2] - c[0]).toDouble(), (c[3] - c[1]).toDouble())
        val wBot = hypot((c[4] - c[6]).toDouble(), (c[5] - c[7]).toDouble())
        val hLeft = hypot((c[6] - c[0]).toDouble(), (c[7] - c[1]).toDouble())
        val hRight = hypot((c[4] - c[2]).toDouble(), (c[5] - c[3]).toDouble())
        val ow = max(wTop, wBot).toInt().coerceAtLeast(80)
        val oh = max(hLeft, hRight).toInt().coerceAtLeast(80)

        val dst = floatArrayOf(
            0f, 0f,
            ow.toFloat(), 0f,
            ow.toFloat(), oh.toFloat(),
            0f, oh.toFloat()
        )
        val m = Matrix()
        m.setPolyToPoly(c, 0, dst, 0, 4)

        try {
            val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            FileOutputStream(File(p)).use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            out.recycle()
            src.recycle()
            setResult(Activity.RESULT_OK)
            Toast.makeText(this, getString(R.string.straighten_done), Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            // basarisizsa dokunma
        }
        finish()
    }
}
