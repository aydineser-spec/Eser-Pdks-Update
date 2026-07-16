package com.eser.belgetarayici

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Belge iyilestirme: isik/golge duzlestirme (flat-field), gri ton ve
 * siyah-beyaz (Otsu) modlari. Amac: dengesiz isikta cekilmis fotografi
 * temiz bir taramaya cevirmek - golgeleri ve parlamalari silmek.
 */
object DocEnhancer {

    enum class Mode { ORIGINAL, COLOR, GRAY, BW }

    fun process(src: Bitmap, mode: Mode): Bitmap {
        if (mode == Mode.ORIGINAL) return src

        val w = src.width
        val h = src.height
        val n = w * h
        val px = IntArray(n)
        src.getPixels(px, 0, w, 0, 0, w, h)

        // Parlaklik (luminance)
        val lum = IntArray(n)
        for (i in 0 until n) {
            val p = px[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            lum[i] = (r * 299 + g * 587 + b * 114) / 1000
        }

        // Arka plan (kagit) aydinlatma haritasi
        val bg = estimateBackground(lum, w, h)

        val out = IntArray(n)
        when (mode) {
            Mode.COLOR -> {
                for (i in 0 until n) {
                    val b0 = max(1, bg[i])
                    val gain = min(4.0, 255.0 / b0)
                    val p = px[i]
                    val r = min(255, (((p ushr 16) and 0xFF) * gain).toInt())
                    val g = min(255, (((p ushr 8) and 0xFF) * gain).toInt())
                    val b = min(255, ((p and 0xFF) * gain).toInt())
                    out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            Mode.GRAY -> {
                for (i in 0 until n) {
                    val b0 = max(1, bg[i])
                    var v = lum[i] * 255 / b0
                    if (v > 255) v = 255
                    v = stretch(v)
                    out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
            }
            Mode.BW -> {
                val norm = IntArray(n)
                for (i in 0 until n) {
                    val b0 = max(1, bg[i])
                    var v = lum[i] * 255 / b0
                    if (v > 255) v = 255
                    norm[i] = v
                }
                val t = otsu(norm)
                for (i in 0 until n) {
                    val v = if (norm[i] >= t) 255 else 0
                    out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
            }
            else -> {}
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    // Kagidi beyazlatmak icin hafif kontrast
    private fun stretch(v: Int): Int {
        val lo = 40
        val hi = 210
        if (v <= lo) return 0
        if (v >= hi) return 255
        return (v - lo) * 255 / (hi - lo)
    }

    // Blok-max + bulaniklastirma ile aydinlatma haritasi
    private fun estimateBackground(lum: IntArray, w: Int, h: Int): IntArray {
        val step = max(1, min(w, h) / 40)
        val sw = (w + step - 1) / step
        val sh = (h + step - 1) / step
        val small = IntArray(sw * sh)
        for (by in 0 until sh) {
            val y0 = by * step
            val y1 = min(h, y0 + step)
            for (bx in 0 until sw) {
                val x0 = bx * step
                val x1 = min(w, x0 + step)
                var mx = 0
                var yy = y0
                while (yy < y1) {
                    val base = yy * w
                    var xx = x0
                    while (xx < x1) {
                        val v = lum[base + xx]
                        if (v > mx) mx = v
                        xx++
                    }
                    yy++
                }
                small[by * sw + bx] = mx
            }
        }
        val b1 = boxBlur(small, sw, sh, 2)
        val b2 = boxBlur(b1, sw, sh, 2)
        val bg = IntArray(w * h)
        for (y in 0 until h) {
            val sy = min(sh - 1, y / step)
            val rowS = sy * sw
            val rowB = y * w
            for (x in 0 until w) {
                val sx = min(sw - 1, x / step)
                bg[rowB + x] = b2[rowS + sx]
            }
        }
        return bg
    }

    private fun boxBlur(a: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(a.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var s = 0
                var c = 0
                var k = -r
                while (k <= r) {
                    val xx = x + k
                    if (xx in 0 until w) { s += a[row + xx]; c++ }
                    k++
                }
                tmp[row + x] = s / c
            }
        }
        val out = IntArray(a.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0
                var c = 0
                var k = -r
                while (k <= r) {
                    val yy = y + k
                    if (yy in 0 until h) { s += tmp[yy * w + x]; c++ }
                    k++
                }
                out[y * w + x] = s / c
            }
        }
        return out
    }

    private fun otsu(v: IntArray): Int {
        val hist = IntArray(256)
        for (x in v) hist[x.coerceIn(0, 255)]++
        val total = v.size
        var sum = 0.0
        for (t in 0..255) sum += (t * hist[t]).toDouble()
        var sumB = 0.0
        var wB = 0
        var maxVar = 0.0
        var thr = 150
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += (t * hist[t]).toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)
            if (between > maxVar) { maxVar = between; thr = t }
        }
        return thr
    }
}
