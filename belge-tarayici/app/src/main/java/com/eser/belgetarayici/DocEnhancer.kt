package com.eser.belgetarayici

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Belge iyilestirme motoru.
 *
 * Yontem: kagit aydinlatma haritasini (arka plan) tahmin edip isigi
 * duzlestirir (flat-field) -> golge/parlama silinir; sonra keskinlestirme
 * (unsharp mask) ve kontrast ile yazi netlestirilir (tarayici etkisi).
 *  - COLOR: golge temizle + kagit beyazlat + keskinlestir
 *  - GRAY : normalize gri + keskinlestir
 *  - BW   : Otsu ile temiz siyah-beyaz (metin/eski evrak icin)
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

        // Parlaklik (orijinalden - keskinligi korumak icin on-blur YOK)
        val lum = IntArray(n)
        for (i in 0 until n) {
            val p = px[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            lum[i] = (r * 299 + g * 587 + b * 114) / 1000
        }

        // Kagit aydinlatma haritasi
        val bg = estimateBackground(lum, w, h)

        return when (mode) {
            Mode.COLOR -> {
                // Beyaz dengesi: kagidin gercek rengini bulup renk lekesini
                // (pembe/sari/mavi tonu) giderir -> kagit bembeyaz olur
                val hist = IntArray(256)
                for (v in lum) hist[v.coerceIn(0, 255)]++
                val target = (n * 0.75).toInt()
                var cum = 0; var thr = 200
                for (t in 0..255) { cum += hist[t]; if (cum >= target) { thr = t; break } }
                var sr = 0L; var sg = 0L; var sb = 0L; var cnt = 0L
                for (i in 0 until n) {
                    if (lum[i] >= thr) {
                        val p = px[i]
                        sr += ((p ushr 16) and 0xFF).toLong()
                        sg += ((p ushr 8) and 0xFF).toLong()
                        sb += (p and 0xFF).toLong()
                        cnt++
                    }
                }
                val c = if (cnt > 0L) cnt else 1L
                val pr = sr.toDouble() / c; val pg = sg.toDouble() / c; val pb = sb.toDouble() / c
                val grayp = (pr + pg + pb) / 3.0
                val wr = clampD(grayp / Math.max(1.0, pr), 0.6, 1.6)
                val wgc = clampD(grayp / Math.max(1.0, pg), 0.6, 1.6)
                val wbc = clampD(grayp / Math.max(1.0, pb), 0.6, 1.6)

                val enh = IntArray(n)
                for (i in 0 until n) {
                    val b0 = max(1, bg[i])
                    // Dusuk kazanc: koyu/renkli bolgeleri fazla parlatmaz
                    val gain = min(1.9, 255.0 / b0)
                    val p = px[i]
                    var r = min(255, (((p ushr 16) and 0xFF) * wr * gain).toInt())
                    var g = min(255, (((p ushr 8) and 0xFF) * wgc * gain).toInt())
                    var b = min(255, ((p and 0xFF) * wbc * gain).toInt())
                    // Kagit beyazlatma: parlak + dusuk doygunluk -> beyaz
                    val mx = max(r, max(g, b))
                    val mn = min(r, min(g, b))
                    val sat = mx - mn
                    val lo = (r * 299 + g * 587 + b * 114) / 1000
                    val wm = clamp01((lo - 185) / 50.0) * clamp01((35 - sat) / 35.0)
                    if (wm > 0.0) {
                        r = (r * (1 - wm) + 255.0 * wm).toInt()
                        g = (g * (1 - wm) + 255.0 * wm).toInt()
                        b = (b * (1 - wm) + 255.0 * wm).toInt()
                    }
                    enh[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                toBitmap(applyLut(multiScaleSharpen(enh, w, h), lut()), w, h)
            }
            Mode.GRAY -> {
                val enh = IntArray(n)
                for (i in 0 until n) {
                    val b0 = max(1, bg[i])
                    var v = lum[i] * 255 / b0
                    if (v > 255) v = 255
                    v = stretch(v)
                    enh[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
                toBitmap(applyLut(multiScaleSharpen(enh, w, h), lut()), w, h)
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
                val out = IntArray(n)
                for (i in 0 until n) {
                    val v = if (norm[i] >= t) 255 else 0
                    out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
                toBitmap(out, w, h)
            }
            else -> src
        }
    }

    // Cok-olcekli unsharp mask: ince + genis olcek -> yazi cok daha net
    private fun multiScaleSharpen(a: IntArray, w: Int, h: Int): IntArray {
        val b1 = boxBlurPixels(a, w, h, 1)   // ince detay
        val b2 = boxBlurPixels(a, w, h, 3)   // genis kenar
        val out = IntArray(a.size)
        for (i in a.indices) {
            val p = a[i]; val q1 = b1[i]; val q2 = b2[i]
            val r = sh((p ushr 16) and 0xFF, (q1 ushr 16) and 0xFF, (q2 ushr 16) and 0xFF)
            val g = sh((p ushr 8) and 0xFF, (q1 ushr 8) and 0xFF, (q2 ushr 8) and 0xFF)
            val b = sh(p and 0xFF, q1 and 0xFF, q2 and 0xFF)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    private fun sh(v: Int, b1: Int, b2: Int): Int {
        val r = (v + 0.8 * (v - b1) + 0.5 * (v - b2)).toInt()
        return if (r < 0) 0 else if (r > 255) 255 else r
    }

    // Kontrast + yazi koyulastirma (gamma) tek LUT'ta
    private fun lut(): IntArray {
        val t = IntArray(256)
        for (v in 0..255) {
            var c = (v - 10) * 1.18 + 5
            if (c < 0.0) c = 0.0 else if (c > 255.0) c = 255.0
            val g = 255.0 * Math.pow(c / 255.0, 1.05)
            t[v] = if (g < 0.0) 0 else if (g > 255.0) 255 else g.toInt()
        }
        return t
    }

    private fun applyLut(a: IntArray, t: IntArray): IntArray {
        for (i in a.indices) {
            val p = a[i]
            val r = t[(p ushr 16) and 0xFF]
            val g = t[(p ushr 8) and 0xFF]
            val b = t[p and 0xFF]
            a[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return a
    }

    private fun toBitmap(px: IntArray, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun clamp01(v: Double): Double = if (v < 0) 0.0 else if (v > 1) 1.0 else v

    private fun clampD(v: Double, lo: Double, hi: Double): Double =
        if (v < lo) lo else if (v > hi) hi else v

    private fun stretch(v: Int): Int {
        val lo = 40
        val hi = 210
        if (v <= lo) return 0
        if (v >= hi) return 255
        return (v - lo) * 255 / (hi - lo)
    }

    private fun estimateBackground(lum: IntArray, w: Int, h: Int): IntArray {
        // Ince olcek: kirisik/buruk golgelerini de takip edip siler
        val step = max(1, min(w, h) / 90)
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
        val b1 = boxBlur(small, sw, sh, 1)
        val b2 = boxBlur(b1, sw, sh, 1)
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
                var s = 0; var c = 0; var k = -r
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
                var s = 0; var c = 0; var k = -r
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

    private fun boxBlurPixels(a: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tr = IntArray(a.size); val tg = IntArray(a.size); val tb = IntArray(a.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var sr = 0; var sg = 0; var sb = 0; var c = 0; var k = -r
                while (k <= r) {
                    val xx = x + k
                    if (xx in 0 until w) {
                        val p = a[row + xx]
                        sr += (p ushr 16) and 0xFF; sg += (p ushr 8) and 0xFF; sb += p and 0xFF; c++
                    }
                    k++
                }
                val idx = row + x
                tr[idx] = sr / c; tg[idx] = sg / c; tb[idx] = sb / c
            }
        }
        val out = IntArray(a.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sr = 0; var sg = 0; var sb = 0; var c = 0; var k = -r
                while (k <= r) {
                    val yy = y + k
                    if (yy in 0 until h) {
                        val idx = yy * w + x
                        sr += tr[idx]; sg += tg[idx]; sb += tb[idx]; c++
                    }
                    k++
                }
                out[y * w + x] = (0xFF shl 24) or ((sr / c) shl 16) or ((sg / c) shl 8) or (sb / c)
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
