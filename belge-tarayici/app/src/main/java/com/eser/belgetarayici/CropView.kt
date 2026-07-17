package com.eser.belgetarayici

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Belgenin 4 kosesini elle secmek icin gorunum. Kullanici yesil noktalari
 * belgenin koselerine surukler; cornersInBitmap() bu koseleri bitmap piksel
 * koordinatlarinda verir (perspektif duzeltme icin).
 */
class CropView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private val corners = Array(4) { PointF() } // TL, TR, BR, BL (view koordinati)
    private var drawRect = RectF()
    private var scale = 1f
    private var dragIndex = -1
    private val touchR = 70f

    private val paintImg = Paint(Paint.FILTER_BITMAP_FLAG)
    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E7D32"); strokeWidth = 6f; style = Paint.Style.STROKE
    }
    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#332E7D32"); style = Paint.Style.FILL
    }
    private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E7D32"); style = Paint.Style.FILL
    }
    private val paintDotIn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }

    fun setBitmap(bmp: Bitmap) {
        bitmap = bmp
        post { computeRect(); initCorners(); invalidate() }
    }

    private fun computeRect() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        val s = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        scale = s
        val dw = bmp.width * s; val dh = bmp.height * s
        val left = (width - dw) / 2f; val top = (height - dh) / 2f
        drawRect = RectF(left, top, left + dw, top + dh)
    }

    private fun initCorners() {
        val r = drawRect
        val ix = r.width() * 0.08f; val iy = r.height() * 0.08f
        corners[0].set(r.left + ix, r.top + iy)
        corners[1].set(r.right - ix, r.top + iy)
        corners[2].set(r.right - ix, r.bottom - iy)
        corners[3].set(r.left + ix, r.bottom - iy)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (bitmap != null) { computeRect(); initCorners(); invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, null, drawRect, paintImg)
        val path = Path()
        path.moveTo(corners[0].x, corners[0].y)
        for (i in 1..3) path.lineTo(corners[i].x, corners[i].y)
        path.close()
        canvas.drawPath(path, paintFill)
        canvas.drawPath(path, paintLine)
        for (c in corners) {
            canvas.drawCircle(c.x, c.y, 30f, paintDot)
            canvas.drawCircle(c.x, c.y, 13f, paintDotIn)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                dragIndex = -1; var best = touchR
                for (i in 0..3) {
                    val d = hypot(x - corners[i].x, y - corners[i].y)
                    if (d < best) { best = d; dragIndex = i }
                }
                return dragIndex != -1
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragIndex != -1) {
                    corners[dragIndex].set(
                        x.coerceIn(drawRect.left, drawRect.right),
                        y.coerceIn(drawRect.top, drawRect.bottom)
                    )
                    invalidate(); return true
                }
            }
            MotionEvent.ACTION_UP -> dragIndex = -1
        }
        return super.onTouchEvent(e)
    }

    // Koseleri bitmap piksel koordinatinda dondur: [x0,y0, x1,y1, x2,y2, x3,y3]
    fun cornersInBitmap(): FloatArray {
        val out = FloatArray(8)
        for (i in 0..3) {
            out[i * 2] = (corners[i].x - drawRect.left) / scale
            out[i * 2 + 1] = (corners[i].y - drawRect.top) / scale
        }
        return out
    }
}
