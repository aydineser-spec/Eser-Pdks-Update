package com.eser.belgetarayici

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Goruntu dosyasi yardimcilari (bellek dostu, cozunurluk kaybetmeden). */
object ImageUtils {

    /** Dosyanin piksel boyutu (genislik, yukseklik); okunamazsa (0, 0). */
    fun size(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        return Pair(max(o.outWidth, 0), max(o.outHeight, 0))
    }

    /**
     * Uzun kenari en fazla [maxDim] olacak sekilde cozer. Once 2'nin katlariyla
     * hedefin ALTINA dusmeden kucultur, sonra tam boyuta olcekler
     * (eski yontem 3000px'lik sayfayi 1500px'e dusurup yaziyi bulaniklastiriyordu).
     */
    fun decodeScaled(file: File, maxDim: Int): Bitmap {
        val (w, h) = size(file)
        val big = max(w, h)
        var sample = 1
        while (big / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: throw IllegalStateException("Goruntu okunamadi: ${file.name}")
        val cur = max(bmp.width, bmp.height)
        if (cur <= maxDim) return bmp
        val s = maxDim.toFloat() / cur
        val out = Bitmap.createScaledBitmap(
            bmp, (bmp.width * s).roundToInt().coerceAtLeast(1),
            (bmp.height * s).roundToInt().coerceAtLeast(1), true
        )
        if (out !== bmp) bmp.recycle()
        return out
    }

    /** Ekranda gostermek icin hafif (RGB_565) ve ekran genisligine yakin cozum. */
    fun decodePreview(file: File, reqWidth: Int): Bitmap? {
        val (w, _) = size(file)
        if (w <= 0) return null
        var sample = 1
        while (w / (sample * 2) >= reqWidth) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
    }

    fun writeJpeg(bmp: Bitmap, file: File, quality: Int = 95) {
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    /** Saat yonunde 90 derece dondurur. */
    fun rotate90(src: Bitmap): Bitmap {
        val m = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /**
     * Iki sayfalik kitap cekiminde ortadaki cilt cizgisini bulur (x konumu).
     * Ortanin %38-%62 araliginda, iki yanindan belirgin koyu dar seridi arar
     * (cilt golgesi); belirgin bir golge yoksa tam ortadan boler.
     */
    fun findGutter(bmp: Bitmap): Int {
        val w = bmp.width
        val h = bmp.height
        val center = w / 2
        val x = try {
            val sw = 400
            val sh = (h * sw.toFloat() / w).roundToInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
            val px = IntArray(sw * sh)
            small.getPixels(px, 0, sw, 0, 0, sw, sh)
            if (small !== bmp) small.recycle()

            val col = DoubleArray(sw)
            for (y in 0 until sh) {
                val row = y * sw
                for (cx in 0 until sw) {
                    val p = px[row + cx]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    col[cx] += (r * 299 + g * 587 + b * 114) / 1000.0
                }
            }
            // Cilt = iki yanindan da (%5 genislik otede) belirgin koyu DAR serit.
            // Sadece "en koyu kolon"a bakmak, golge yokken yazi satirlarini
            // cilt sanip sayfayi yazinin ortasindan kesiyordu.
            fun win(c: Int): Double {
                var v = 0.0
                for (k in -2..2) v += col[(c + k).coerceIn(0, sw - 1)]
                return v
            }
            val from = (sw * 0.38).toInt()
            val to = (sw * 0.62).toInt()
            val d = (sw * 0.05).toInt()
            var best = -1
            var bestScore = 0.0
            for (cx in from..to) {
                val v = win(cx)
                val side = minOf(win(cx - d), win(cx + d))
                if (v < side * 0.93 && side - v > bestScore) { bestScore = side - v; best = cx }
            }
            if (best >= 0) (best.toLong() * w / sw).toInt() else center
        } catch (e: Throwable) {
            center
        }
        return x.coerceIn(w / 4, w * 3 / 4)
    }
}
