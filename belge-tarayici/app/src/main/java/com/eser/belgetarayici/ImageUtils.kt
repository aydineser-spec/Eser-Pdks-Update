package com.eser.belgetarayici

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot
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
     * Fotografin EXIF yon bilgisi (derece: 0/90/180/270). Telefon kameralari
     * cogu zaman pikseli dondurmeden "90 derece cevir" notu yazar; okunmazsa
     * sayfa yan gorunur.
     */
    fun exifRotation(file: File): Int = try {
        when (ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }
    } catch (e: Throwable) {
        0
    }

    /**
     * Uzun kenari en fazla [maxDim] olacak sekilde cozer (EXIF yonu uygulanmis).
     * Once 2'nin katlariyla hedefin ALTINA dusmeden kucultur, sonra tam boyuta
     * olcekler (eski yontem 3000px'lik sayfayi 1500px'e dusurup yaziyi bulaniklastiriyordu).
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
        var bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: throw IllegalStateException("Goruntu okunamadi: ${file.name}")
        val cur = max(bmp.width, bmp.height)
        if (cur > maxDim) {
            val s = maxDim.toFloat() / cur
            val scaled = Bitmap.createScaledBitmap(
                bmp, (bmp.width * s).roundToInt().coerceAtLeast(1),
                (bmp.height * s).roundToInt().coerceAtLeast(1), true
            )
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        val deg = exifRotation(file)
        if (deg != 0) {
            val m = Matrix().apply { postRotate(deg.toFloat()) }
            val rot = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rot !== bmp) bmp.recycle()
            bmp = rot
        }
        return bmp
    }

    /**
     * Dosyada EXIF yon notu varsa pikselleri gercekten dondurup notsuz yeniden yazar.
     * (PDF'e JPEG oldugu gibi gomuldugu icin not okunmaz; sayfa yan cikardi.)
     */
    fun normalizeOrientation(file: File, maxDim: Int = 4000) {
        if (exifRotation(file) == 0) return
        val bmp = decodeScaled(file, maxDim)
        writeJpeg(bmp, file, 95)
        bmp.recycle()
    }

    /**
     * 4 koseye (0..1 normalize, TL,TR,BR,BL: x0,y0,...,x3,y3) gore perspektifi
     * duzeltip duz dikdortgen sayfa uretir.
     */
    fun warp(src: Bitmap, corners: FloatArray): Bitmap {
        val c = FloatArray(8) { i -> corners[i] * if (i % 2 == 0) src.width else src.height }
        val wTop = hypot((c[2] - c[0]).toDouble(), (c[3] - c[1]).toDouble())
        val wBot = hypot((c[4] - c[6]).toDouble(), (c[5] - c[7]).toDouble())
        val hLeft = hypot((c[6] - c[0]).toDouble(), (c[7] - c[1]).toDouble())
        val hRight = hypot((c[4] - c[2]).toDouble(), (c[5] - c[3]).toDouble())
        val ow = max(wTop, wBot).toInt().coerceIn(80, 6000)
        val oh = max(hLeft, hRight).toInt().coerceIn(80, 6000)
        val dst = floatArrayOf(0f, 0f, ow.toFloat(), 0f, ow.toFloat(), oh.toFloat(), 0f, oh.toFloat())
        val m = Matrix()
        if (!m.setPolyToPoly(c, 0, dst, 0, 4)) throw IllegalStateException("Koseler gecersiz")
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Tam kare (kirpma yok) koseleri. */
    val FULL_FRAME = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

    /** Saat yonunde [quarters] x 90 derece dondurur (0 ise ayni bitmap). */
    fun rotateQuarters(src: Bitmap, quarters: Int): Bitmap {
        val q = ((quarters % 4) + 4) % 4
        if (q == 0) return src
        val m = Matrix().apply { postRotate(90f * q) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
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
