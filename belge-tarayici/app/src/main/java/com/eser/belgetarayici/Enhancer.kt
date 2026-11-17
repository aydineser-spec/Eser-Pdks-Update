package com.eser.belgetarayici

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * Otomatik iyilestirme (vFlat gorunumu). Kullanicinin vFlat ciktisi OLCULDU ve
 * ayarlar ona gore secildi (Python/cv2 4.11 ile birebir ayni adimlar):
 *
 *  KAGIT BELGE (golgesi alininca en az %45'i parlak+renksiz kagit):
 *    golge silinir, kagit beyazlar, yazi koyulasir; kase/imza rengi korunur.
 *    (Daha once test edilen guvenli "Net Belge": benek yok, fotograf alanina dokunmaz.)
 *
 *  DIGER (kutu, dergi, fotograf, ekran):
 *    1) Ton araligi: siyah koyu, beyaz beyaz (yuzdelik germe, kazanc en fazla 1.6)
 *    2) Netlik: orta olcekli ayrinti guclendirilir (bant gecisli: ince kumlanma BUYUMEZ)
 *    3) Keskinlik: sadece goruntunun kendi kumlanmasinin 4 kati ustundeki kenarlar
 *    4) Renk doygunlugu hafif kisilir (germe zaten canlandiriyor)
 *  Olcum (Lexar kutusu, vFlat hedef / bizim ham / bizim yeni):
 *    siyah %1: 17 / 44 / 8   orta: 105 / 122 / 112   doygunluk: 157 / 131 / 164
 *    netlik: 48 / 17 / 40.  Karanlik TV ekraninda kumlanma 11 -> 15 (eski CLAHE: 122).
 *
 * Hata olursa ya da OpenCV yoksa goruntu OLDUGU GIBI dondurulur.
 */
object Enhancer {

    // Diger (fotograf/kutu) yolu ayarlari
    private const val LO_PCT = 0.2
    private const val HI_PCT = 98.5
    private const val MAX_GAIN = 1.6
    private const val GAMMA = 1.2
    private const val CLARITY = 2.0
    private const val SHARP = 2.5
    private const val SAT = 0.9

    // Belge yolu
    private const val DOC_MIN_PAPER = 0.45

    fun auto(src: Bitmap): Bitmap {
        if (!EdgeDetector.init()) return src
        return try {
            val rgba = Mat(); Utils.bitmapToMat(src, rgba)
            val rgb = Mat(); Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB); rgba.release()
            val outRgb = documentLook(rgb) ?: photoLook(rgb)
            rgb.release()
            val outRgba = Mat(); Imgproc.cvtColor(outRgb, outRgba, Imgproc.COLOR_RGB2RGBA); outRgb.release()
            val out = Bitmap.createBitmap(outRgba.cols(), outRgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outRgba, out)
            outRgba.release()
            out
        } catch (e: Throwable) {
            src
        }
    }

    // ======================================================================
    // DIGER: kutu, dergi, fotograf, ekran (vFlat gorunumu)
    // ======================================================================
    private fun photoLook(rgb: Mat): Mat {
        val w = rgb.cols(); val h = rgb.rows()
        val longEdge = max(w, h)

        // 1) Ton araligi (kucuk kopyanin parlaklik yuzdeliklerinden)
        val small = Mat()
        Imgproc.resize(
            rgb, small,
            Size(max(1, w * 400 / longEdge).toDouble(), max(1, h * 400 / longEdge).toDouble()),
            0.0, 0.0, Imgproc.INTER_AREA
        )
        val sg = Mat(); Imgproc.cvtColor(small, sg, Imgproc.COLOR_RGB2GRAY); small.release()
        val lut = levelsLut(sg); sg.release()
        val toned = Mat(); Core.LUT(rgb, lut, toned); lut.release()

        // 2) Netlik + 3) keskinlik: sadece parlaklik (Lab L) kanalinda, renge dokunmaz
        val lab = Mat(); Imgproc.cvtColor(toned, lab, Imgproc.COLOR_RGB2Lab); toned.release()
        val ch = ArrayList<Mat>(); Core.split(lab, ch); lab.release()
        val l = Mat(); ch[0].convertTo(l, CvType.CV_32F)

        // bant gecisli netlik: (orta olcek - genis olcek); ince kumlanma buyutulmez
        val mid = Mat(); Imgproc.GaussianBlur(l, mid, Size(0.0, 0.0), max(1.5, longEdge / 700.0))
        val big = Mat(); Imgproc.GaussianBlur(l, big, Size(0.0, 0.0), max(4.0, longEdge / 100.0))
        Core.subtract(mid, big, mid); big.release()
        Core.scaleAdd(mid, CLARITY, l, l); mid.release()

        // esikli keskinlik
        val s1 = (longEdge / 1600.0).coerceIn(1.0, 2.5)
        val d = Mat(); Imgproc.GaussianBlur(l, d, Size(0.0, 0.0), s1)
        Core.subtract(l, d, d)                                  // d = L - bulanik(L)
        val absd = Mat(); Core.absdiff(d, Scalar(0.0), absd)
        val noise = 1.4826 * sampledMedian(absd) + 0.5
        val t = 4.0 * noise
        Core.subtract(absd, Scalar(t), absd)                     // m = clip((|d| - t) / t, 0, 1)
        Core.multiply(absd, Scalar(1.0 / t), absd)
        Core.min(absd, Scalar(1.0), absd)
        Core.max(absd, Scalar(0.0), absd)
        Core.multiply(d, absd, d); absd.release()
        Core.scaleAdd(d, SHARP, l, l); d.release()

        l.convertTo(ch[0], CvType.CV_8U); l.release()            // yuvarlar + 0..255
        val lab2 = Mat(); Core.merge(ch, lab2); ch.forEach { it.release() }
        val out = Mat(); Imgproc.cvtColor(lab2, out, Imgproc.COLOR_Lab2RGB); lab2.release()

        // 4) Renk doygunlugu
        val hsv = Mat(); Imgproc.cvtColor(out, hsv, Imgproc.COLOR_RGB2HSV)
        val hs = ArrayList<Mat>(); Core.split(hsv, hs)
        hs[1].convertTo(hs[1], -1, SAT, 0.0)
        Core.merge(hs, hsv); hs.forEach { it.release() }
        Imgproc.cvtColor(hsv, out, Imgproc.COLOR_HSV2RGB); hsv.release()
        return out
    }

    /** Parlaklik yuzdeliklerinden germe + gamma tablosu (256'lik). */
    private fun levelsLut(gray: Mat): Mat {
        val n = gray.total().toInt()
        val b = ByteArray(n); gray.get(0, 0, b)
        val hist = IntArray(256)
        for (v in b) hist[v.toInt() and 0xFF]++
        fun pct(q: Double): Int {
            var cum = 0L
            for (i in 0..255) {
                cum += hist[i]
                if (cum.toDouble() / n >= q) return i
            }
            return 255
        }
        val lo = min(pct(LO_PCT / 100.0), 90)
        val hi = max(pct(HI_PCT / 100.0), 140)
        val gain = min(255.0 / max(1, hi - lo), MAX_GAIN)
        val table = ByteArray(256) { i ->
            val v = ((i - lo) * gain).coerceIn(0.0, 255.0)
            Math.round(255.0 * Math.pow(v / 255.0, GAMMA)).toInt().coerceIn(0, 255).toByte()
        }
        val lut = Mat(1, 256, CvType.CV_8UC1); lut.put(0, 0, table)
        return lut
    }

    /** Tek kanal float'in medyani (her 4. satir/sutundan ornekleyerek; hizli). */
    private fun sampledMedian(m: Mat): Double {
        val sub = Mat()
        Imgproc.resize(
            m, sub, Size(max(1, m.cols() / 4).toDouble(), max(1, m.rows() / 4).toDouble()),
            0.0, 0.0, Imgproc.INTER_NEAREST
        )
        val n = sub.total().toInt()
        if (n == 0) { sub.release(); return 0.0 }
        val arr = FloatArray(n); sub.get(0, 0, arr); sub.release()
        arr.sort()
        return if (n % 2 == 1) arr[n / 2].toDouble() else (arr[n / 2 - 1] + arr[n / 2]) / 2.0
    }

    // ======================================================================
    // KAGIT BELGE: golge sil + kagidi beyazlat (belge degilse null)
    // ======================================================================
    /** Seviye 70..230 + gamma 1.3: kagit beyaz, yazi siyah, silik yazi korunur. */
    private val docTable: ByteArray by lazy {
        ByteArray(256) { i ->
            val lv = ((i - 70.0) * 255.0 / 160.0).coerceIn(0.0, 255.0)
            Math.round(255.0 * Math.pow(lv / 255.0, 1.3)).toInt().coerceIn(0, 255).toByte()
        }
    }

    private class PaperInfo(val ff8: Mat, val paper: Mat, val fraction: Double) {
        fun release() { ff8.release(); paper.release() }
    }

    /** Golgeyi sinirli kazancla al ve kagit piksellerini bul. rgb8: RGB 8UC3. */
    private fun analyzePaper(rgb8: Mat): PaperInfo {
        val w = rgb8.cols(); val h = rgb8.rows()
        val den = Mat(); Imgproc.medianBlur(rgb8, den, 3)
        val lum = Mat(); Imgproc.cvtColor(den, lum, Imgproc.COLOR_RGB2GRAY)

        val small = Mat()
        Imgproc.resize(
            lum, small, Size(max(1, w / 4).toDouble(), max(1, h / 4).toDouble()),
            0.0, 0.0, Imgproc.INTER_AREA
        )
        lum.release()
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(13.0, 13.0))
        Imgproc.morphologyEx(small, small, Imgproc.MORPH_CLOSE, k)
        Imgproc.GaussianBlur(small, small, Size(0.0, 0.0), 6.25)
        val bg = Mat()
        Imgproc.resize(small, bg, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        small.release(); k.release()

        val gain = Mat(); bg.convertTo(gain, CvType.CV_32F); bg.release()
        Core.max(gain, Scalar(1.0), gain)
        Core.divide(250.0, gain, gain)
        Core.min(gain, Scalar(3.0), gain)
        Core.max(gain, Scalar(0.9), gain)

        val chans = ArrayList<Mat>(); Core.split(den, chans); den.release()
        val f = Mat()
        for (i in 0 until 3) {
            chans[i].convertTo(f, CvType.CV_32F)
            Core.multiply(f, gain, f)
            f.convertTo(chans[i], CvType.CV_8U)
        }
        f.release(); gain.release()
        val ff8 = Mat(); Core.merge(chans, ff8)
        chans.forEach { it.release() }

        val gray = Mat(); Imgproc.cvtColor(ff8, gray, Imgproc.COLOR_RGB2GRAY)
        val hsv = Mat(); Imgproc.cvtColor(ff8, hsv, Imgproc.COLOR_RGB2HSV)
        val hs = ArrayList<Mat>(); Core.split(hsv, hs); hsv.release()
        val bright = Mat(); Imgproc.threshold(gray, bright, 214.0, 255.0, Imgproc.THRESH_BINARY)
        val lowSat = Mat(); Imgproc.threshold(hs[1], lowSat, 44.0, 255.0, Imgproc.THRESH_BINARY_INV)
        val paper = Mat(); Core.bitwise_and(bright, lowSat, paper)
        gray.release(); bright.release(); lowSat.release(); hs.forEach { it.release() }

        val frac = Core.countNonZero(paper).toDouble() / (w.toDouble() * h.toDouble())
        return PaperInfo(ff8, paper, frac)
    }

    /** 0..1 arasi yumusak gecis (tek kanal float). */
    private fun smoothstep(x: Mat, a: Double, b: Double): Mat {
        val t = Mat()
        Core.subtract(x, Scalar(a), t)
        Core.multiply(t, Scalar(1.0 / (b - a)), t)
        Core.min(t, Scalar(1.0), t)
        Core.max(t, Scalar(0.0), t)
        val k = Mat()
        Core.multiply(t, Scalar(-2.0), k)
        Core.add(k, Scalar(3.0), k)
        Core.multiply(t, t, t)
        Core.multiply(t, k, t)
        k.release()
        return t
    }

    /** Kagit belgeyse temizlenmis RGB, degilse null. rgb: RGB 8UC3 (degistirilmez). */
    private fun documentLook(rgb: Mat): Mat? {
        val w = rgb.cols(); val h = rgb.rows()
        val info = analyzePaper(rgb)
        if (info.fraction < DOC_MIN_PAPER) {
            info.release()
            return null
        }
        val ff8 = info.ff8
        val paper = info.paper

        val pf = Mat(); paper.convertTo(pf, CvType.CV_32F, 1.0 / 255.0)
        val ps = Mat()
        Imgproc.resize(
            pf, ps, Size(max(1, w / 8).toDouble(), max(1, h / 8).toDouble()),
            0.0, 0.0, Imgproc.INTER_AREA
        )
        pf.release()
        Imgproc.GaussianBlur(ps, ps, Size(0.0, 0.0), 0.03 * max(w, h) / 8.0)
        val dens = Mat()
        Imgproc.resize(ps, dens, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        ps.release()
        val wdoc = smoothstep(dens, 0.40, 0.70); dens.release()

        val m = Core.mean(ff8, paper)
        paper.release()
        val g = (m.`val`[0] + m.`val`[1] + m.`val`[2]) / 3.0
        Core.multiply(
            ff8,
            Scalar(
                (g / max(1.0, m.`val`[0])).coerceIn(0.95, 1.08),
                (g / max(1.0, m.`val`[1])).coerceIn(0.95, 1.08),
                (g / max(1.0, m.`val`[2])).coerceIn(0.95, 1.08)
            ),
            ff8
        )

        val hsv = Mat(); Imgproc.cvtColor(ff8, hsv, Imgproc.COLOR_RGB2HSV)
        val hs = ArrayList<Mat>(); Core.split(hsv, hs); hsv.release()
        val satF = Mat(); hs[1].convertTo(satF, CvType.CV_32F); hs.forEach { it.release() }
        Imgproc.GaussianBlur(satF, satF, Size(0.0, 0.0), 1.5)
        val cw = smoothstep(satF, 50.0, 90.0); satF.release()

        val lut = Mat(1, 256, CvType.CV_8UC1); lut.put(0, 0, docTable)
        val doc8 = Mat(); Core.LUT(ff8, lut, doc8); lut.release()
        val docCh = ArrayList<Mat>(); Core.split(doc8, docCh); doc8.release()
        val ffCh = ArrayList<Mat>(); Core.split(ff8, ffCh); ff8.release()
        val srcCh = ArrayList<Mat>(); Core.split(rgb, srcCh)

        val d = Mat(); val f = Mat(); val s = Mat(); val bl = Mat()
        val outCh = ArrayList<Mat>()
        for (c in 0 until 3) {
            docCh[c].convertTo(d, CvType.CV_32F); docCh[c].release()
            ffCh[c].convertTo(f, CvType.CV_32F); ffCh[c].release()
            Core.subtract(f, d, f); Core.multiply(f, cw, f); Core.add(d, f, d)
            Imgproc.GaussianBlur(d, bl, Size(0.0, 0.0), 1.5)
            Core.addWeighted(d, 1.2, bl, -0.2, 0.0, d)
            srcCh[c].convertTo(s, CvType.CV_32F); srcCh[c].release()
            Core.subtract(d, s, d); Core.multiply(d, wdoc, d); Core.add(s, d, d)
            val o = Mat(); d.convertTo(o, CvType.CV_8U); outCh.add(o)
        }
        d.release(); f.release(); s.release(); bl.release(); cw.release(); wdoc.release()

        val out3 = Mat(); Core.merge(outCh, out3); outCh.forEach { it.release() }
        return out3
    }
}
