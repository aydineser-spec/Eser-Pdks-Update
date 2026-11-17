package com.eser.belgetarayici

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Kamera goruntusunun ustunde bulunan belgeyi mavi alanla gosterir
 * (Google tarayicisindaki mavi isaretleme gibi).
 * Koseler 0..1 normalize (TL,TR,BR,BL); kamera goruntusu 3:4 ve ekrana
 * "fitCenter" yerlestirildigi icin ayni dikdortgene oturtulur.
 * Titremesin diye yeni koseler eskileriyle yumusakca harmanlanir.
 */
class QuadOverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Kamera goruntusunun en/boy orani (dikey: 3/4). */
    var imageAspect: Float = 3f / 4f

    private var quad: FloatArray? = null
    private val dens = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4400B8D4"); style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00B8D4"); style = Paint.Style.STROKE
        strokeWidth = 3f * dens; strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }

    /** null: belge yok (alan gizlenir). */
    fun setQuad(q: FloatArray?) {
        val old = quad
        quad = if (q == null || old == null) q?.copyOf() else FloatArray(8) { i -> old[i] * 0.4f + q[i] * 0.6f }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val q = quad ?: return
        val vw = width.toFloat(); val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        // fitCenter dikdortgeni
        val cw: Float; val ch: Float
        if (vw / vh > imageAspect) { ch = vh; cw = vh * imageAspect } else { cw = vw; ch = vw / imageAspect }
        val left = (vw - cw) / 2f; val top = (vh - ch) / 2f
        val path = Path()
        for (i in 0..3) {
            val x = left + q[i * 2] * cw
            val y = top + q[i * 2 + 1] * ch
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
        for (i in 0..3) {
            canvas.drawCircle(left + q[i * 2] * cw, top + q[i * 2 + 1] * ch, 5f * dens, dot)
        }
    }
}
