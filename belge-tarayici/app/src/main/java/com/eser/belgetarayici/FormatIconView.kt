package com.eser.belgetarayici

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Dosya turu simgesi: kosesi kivrik beyaz sayfa + ustunde renkli etiket ("PDF", "JPG", "PNG").
 * Kodla cizilir (resim dosyasi gerekmez).
 */
class FormatIconView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var label = "PDF"
    private var color = Color.parseColor("#D32F2F")
    private val dens = resources.displayMetrics.density

    private val pageFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val pageStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#90A4AE"); style = Paint.Style.STROKE; strokeWidth = 1.6f * dens
        strokeJoin = Paint.Join.ROUND
    }
    private val foldFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CFD8DC"); style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0BEC5"); strokeWidth = 1.6f * dens; strokeCap = Paint.Cap.ROUND
    }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }

    fun setup(label: String, color: Int) {
        this.label = label
        this.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val left = w * 0.20f; val right = w * 0.80f
        val top = h * 0.06f; val bottom = h * 0.94f
        val fold = (right - left) * 0.30f

        val page = Path().apply {
            moveTo(left, top); lineTo(right - fold, top); lineTo(right, top + fold)
            lineTo(right, bottom); lineTo(left, bottom); close()
        }
        canvas.drawPath(page, pageFill)
        canvas.drawPath(page, pageStroke)
        val foldPath = Path().apply {
            moveTo(right - fold, top); lineTo(right - fold, top + fold); lineTo(right, top + fold); close()
        }
        canvas.drawPath(foldPath, foldFill)
        canvas.drawPath(foldPath, pageStroke)

        // Sayfadaki "yazi" cizgileri
        val lx1 = left + (right - left) * 0.15f
        val lx2 = right - (right - left) * 0.15f
        canvas.drawLine(lx1, top + (bottom - top) * 0.22f, right - fold - (right - left) * 0.05f, top + (bottom - top) * 0.22f, linePaint)
        canvas.drawLine(lx1, top + (bottom - top) * 0.80f, lx2, top + (bottom - top) * 0.80f, linePaint)
        canvas.drawLine(lx1, top + (bottom - top) * 0.89f, lx2 - (right - left) * 0.2f, top + (bottom - top) * 0.89f, linePaint)

        // Renkli etiket bandi (sayfanin solundan disari tasar)
        bandPaint.color = color
        val band = RectF(w * 0.08f, top + (bottom - top) * 0.36f, w * 0.86f, top + (bottom - top) * 0.66f)
        canvas.drawRoundRect(band, 4f * dens, 4f * dens, bandPaint)
        textPaint.textSize = band.height() * 0.62f
        val ty = band.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, band.centerX(), ty, textPaint)
    }
}
