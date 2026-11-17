package com.eser.belgetarayici

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * OpenCV tabanli belge isleme:
 *  COLOR ("Net Belge"): sadece BELGEYSE golge siler + kagidi beyazlatir;
 *                       fotograf/ekran gibi belge olmayan goruntuye dokunmaz
 *  BW ("Siyah-Beyaz"): belgede esikleme; belge degilse duz siyah-beyaz fotograf
 * OpenCV hazir degilse ya da hata olursa goruntu oldugu gibi birakilir.
 */
object OpenCvProcessor {

    @Volatile private var ready = false

    fun isReady(): Boolean = ready

    // Belge turu (akilli mod secimi icin)
    enum class DocKind { COLORFUL, TEXT, LINEART, GRAY }

    // Belge turunu tahmin et (offline). Doygunluk SADECE icerik pikselinde olculur
    // -> renkli/soluk kase asla "cizim" sanilip Siyah-Beyaz'a gonderilmez (silinmez).
    fun classify(src: Bitmap): DocKind {
        if (!ensureInit()) return DocKind.TEXT
        return try {
            val rgba = Mat(); Utils.bitmapToMat(src, rgba)
            val rgb = Mat(); Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            val longEdge = max(rgb.width(), rgb.height())
            val sc = if (longEdge > 700) 700.0 / longEdge else 1.0
            val small = Mat()
            if (sc < 1.0) Imgproc.resize(rgb, small, Size(rgb.width() * sc, rgb.height() * sc))
            else rgb.copyTo(small)
            val hsv = Mat(); Imgproc.cvtColor(small, hsv, Imgproc.COLOR_RGB2HSV)
            val chans = ArrayList<Mat>(); Core.split(hsv, chans) // H, S, V
            val gray = Mat(); Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGB2GRAY)
            // Icerik maskesi: beyaz olmayan (murekkep/nesne/kase)
            val content = Mat()
            Imgproc.threshold(gray, content, 235.0, 255.0, Imgproc.THRESH_BINARY_INV)
            val total = (content.rows() * content.cols()).toDouble()
            val contentRatio = Core.countNonZero(content).toDouble() / total
            if (contentRatio < 0.004) return DocKind.TEXT
            val satContent = Core.mean(chans[1], content).`val`[0]  // icerigin doygunlugu
            val meanDark = Core.mean(gray, content).`val`[0]        // icerigin koyulugu
            when {
                satContent > 35.0 -> DocKind.COLORFUL                       // renkli/kase -> renk korunur
                contentRatio < 0.05 && meanDark < 100.0 -> DocKind.LINEART  // koyu seyrek cizim -> S/B
                else -> DocKind.TEXT                                        // yazi -> AI iyilestir
            }
        } catch (e: Throwable) {
            DocKind.TEXT
        }
    }

    fun ensureInit(): Boolean {
        if (!ready) {
            ready = try { OpenCVLoader.initLocal() } catch (e: Throwable) { false }
        }
        return ready
    }

    // ----------------------------------------------------------------------
    // Otomatik kenar tespiti + perspektif duzeltme
    // ----------------------------------------------------------------------
    fun autoCrop(src: Bitmap): Bitmap {
        if (!ensureInit()) return src
        return try {
            val rgba = Mat(); Utils.bitmapToMat(src, rgba)
            val gray = Mat(); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)

            val longEdge = max(gray.width(), gray.height())
            val scale = if (longEdge > 900) 900.0 / longEdge else 1.0
            val small = Mat()
            if (scale < 1.0) Imgproc.resize(gray, small, Size(gray.width() * scale, gray.height() * scale))
            else gray.copyTo(small)

            Imgproc.GaussianBlur(small, small, Size(5.0, 5.0), 0.0)
            val edges = Mat(); Imgproc.Canny(small, edges, 60.0, 180.0)
            Imgproc.dilate(edges, edges, Mat(), Point(-1.0, -1.0), 2)

            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

            val imgArea = small.width().toDouble() * small.height()
            var best: Array<Point>? = null
            var bestArea = imgArea * 0.15   // en az %15 alan
            for (c in contours) {
                val c2 = MatOfPoint2f(*c.toArray())
                val peri = Imgproc.arcLength(c2, true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(c2, approx, 0.02 * peri, true)
                if (approx.total() == 4L) {
                    val mp = MatOfPoint(*approx.toArray())
                    if (Imgproc.isContourConvex(mp)) {
                        val area = Imgproc.contourArea(approx)
                        if (area > bestArea) { bestArea = area; best = approx.toArray() }
                    }
                }
            }

            val quad = best ?: return src
            val ordered = orderCorners(quad.map { Point(it.x / scale, it.y / scale) })

            val wTop = dist(ordered[0], ordered[1]); val wBot = dist(ordered[3], ordered[2])
            val hL = dist(ordered[0], ordered[3]); val hR = dist(ordered[1], ordered[2])
            val ow = max(wTop, wBot).toInt().coerceAtLeast(80)
            val oh = max(hL, hR).toInt().coerceAtLeast(80)

            // Bozuk/dejenere kirpmayi onle: asiri oranli dikdortgeni reddet
            val ratio = ow.toDouble() / oh.toDouble()
            if (ratio > 6.0 || ratio < 1.0 / 6.0) return src

            val srcPts = MatOfPoint2f(ordered[0], ordered[1], ordered[2], ordered[3])
            val dstPts = MatOfPoint2f(
                Point(0.0, 0.0), Point(ow - 1.0, 0.0),
                Point(ow - 1.0, oh - 1.0), Point(0.0, oh - 1.0)
            )
            val m = Imgproc.getPerspectiveTransform(srcPts, dstPts)
            val warped = Mat()
            Imgproc.warpPerspective(rgba, warped, m, Size(ow.toDouble(), oh.toDouble()))

            val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(warped, out)
            out
        } catch (e: Throwable) {
            src
        }
    }

    private fun dist(a: Point, b: Point): Double {
        val dx = a.x - b.x; val dy = a.y - b.y
        return Math.hypot(dx, dy)
    }

    // Koseleri TL, TR, BR, BL sirasina diz
    private fun orderCorners(p: List<Point>): Array<Point> {
        val bySum = p.sortedBy { it.x + it.y }
        val tl = bySum.first(); val br = bySum.last()
        val byDiff = p.sortedBy { it.y - it.x }
        val tr = byDiff.first(); val bl = byDiff.last()
        return arrayOf(tl, tr, br, bl)
    }

    // ----------------------------------------------------------------------
    // Mod uygulama
    // ----------------------------------------------------------------------
    fun process(src: Bitmap, mode: DocEnhancer.Mode): Bitmap {
        if (mode == DocEnhancer.Mode.ORIGINAL) return src
        // OpenCV yoksa ya da hata olursa sayfaya DOKUNMA. (Eski saf-Kotlin yedek
        // DocEnhancer da fotografi/ekrani soldurup bozuyordu.)
        if (!ensureInit()) return src
        return try {
            when (mode) {
                DocEnhancer.Mode.COLOR -> colorMode(src)
                DocEnhancer.Mode.GRAY -> grayMode(src)
                DocEnhancer.Mode.BW -> bwMode(src)
                DocEnhancer.Mode.RECEIPT -> receiptMode(src)
                DocEnhancer.Mode.BOOK -> bookMode(src)
                else -> src
            }
        } catch (e: Throwable) {
            src
        }
    }

    // ----------------------------------------------------------------------
    // NET BELGE
    // ----------------------------------------------------------------------
    // Eski surum HER goruntuyu kagit sanip zorla beyazlatiyordu: TV ekrani,
    // fotograf gibi seyleri soldurup beneklere ceviriyordu (sert renk maskesi +
    // sinirsiz golge kazanci). Yeni kural:
    //  1) Goruntunun en az %45'i "kagit" (golgesi alininca parlak + renksiz)
    //     degilse BELGE DEGILDIR -> hic dokunulmaz.
    //  2) Belgede de sadece kagitla cevrili bolgeler (yazi, cizgi) temizlenir;
    //     sayfadaki fotograf/renkli alan oldugu gibi kalir. Gecisler yumusak.
    //  3) Kase/imza rengi yumusak geri konur: benek olusmaz.
    // Python/cv2 4.11 olcumu (uzun kenar 2000px, telefondaki ayarla birebir):
    //   astronot, kahve, kedi, roket, motor, TV ekrani: fark 0 (dokunulmadi)
    //   eski surum ayni goruntulerde: fark 60-156, gren 3-27 kat
    //   gercek taranmis sayfa: kagit 255, kose golgesi 222 (eski: koyu leke)
    //   sentetik belge: kagit 255, benek %0, yazi 14, mavi kase rengi korundu
    private const val DOC_MIN_PAPER = 0.45

    /** Seviye 70..230 + gamma 1.3 tek tabloda: kagit beyaz, yazi siyah, silik yazi korunur. */
    private val levelsGammaTable: ByteArray by lazy {
        ByteArray(256) { i ->
            val lv = ((i - 70.0) * 255.0 / 160.0).coerceIn(0.0, 255.0)
            Math.round(255.0 * Math.pow(lv / 255.0, 1.3)).toInt().coerceIn(0, 255).toByte()
        }
    }

    /** ff8: golgesi alinmis goruntu (8UC3), paper: kagit maskesi (8UC1), fraction: kagit orani. */
    private class PaperInfo(val ff8: Mat, val paper: Mat, val fraction: Double) {
        fun release() { ff8.release(); paper.release() }
    }

    /** Golgeyi sinirli kazancla al ve kagit piksellerini bul. rgb8: RGB 8UC3. */
    private fun analyzePaper(rgb8: Mat): PaperInfo {
        val w = rgb8.cols(); val h = rgb8.rows()
        val den = Mat(); Imgproc.medianBlur(rgb8, den, 3)
        val lum = Mat(); Imgproc.cvtColor(den, lum, Imgproc.COLOR_RGB2GRAY)

        // Isik haritasi (kagidin yerel parlakligi): 1/4 olcekte hesapla (hizli)
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

        // Kazanc = 250 / isik, 0.9..3.0 arasi: koyu alan (TV, fotograf) patlatilmaz
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
            f.convertTo(chans[i], CvType.CV_8U)   // yuvarlar ve 0..255'e kirpar
        }
        f.release(); gain.release()
        val ff8 = Mat(); Core.merge(chans, ff8)
        chans.forEach { it.release() }

        // Kagit = golgesi alininca parlak (>=215) ve renksiz (doygunluk <=44)
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

    /** Goruntu belge mi (kagit orani yeterli mi)? rgba: RGBA 8UC4. */
    private fun looksLikeDocument(rgba: Mat): Boolean {
        val rgb = Mat(); Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val info = analyzePaper(rgb)
        val ok = info.fraction >= DOC_MIN_PAPER
        info.release(); rgb.release()
        return ok
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
        Core.add(k, Scalar(3.0), k)        // 3 - 2t
        Core.multiply(t, t, t)             // t^2
        Core.multiply(t, k, t)             // t^2 (3 - 2t)
        k.release()
        return t
    }

    private fun colorMode(src: Bitmap): Bitmap {
        val rgba = Mat(); Utils.bitmapToMat(src, rgba)
        val rgb = Mat(); Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB); rgba.release()
        val w = rgb.cols(); val h = rgb.rows()

        val info = analyzePaper(rgb)
        if (info.fraction < DOC_MIN_PAPER) {
            // Belge degil (fotograf, ekran, manzara): DOKUNMA
            info.release(); rgb.release()
            return src
        }
        val ff8 = info.ff8
        val paper = info.paper

        // Yerel kagit yogunlugu (1/8 olcek, hizli): yazi kagitla cevrili -> yuksek,
        // sayfadaki fotograf/renkli alan -> dusuk. Belge bitisi agirligi buradan.
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

        // Beyaz dengesi: kagit pikselleri notr (renk lekesi gider)
        val m = Core.mean(ff8, paper)
        paper.release()
        val g = (m.`val`[0] + m.`val`[1] + m.`val`[2]) / 3.0
        Core.multiply(
            ff8,
            Scalar(
                clamp(g / max(1.0, m.`val`[0]), 0.95, 1.08),
                clamp(g / max(1.0, m.`val`[1]), 0.95, 1.08),
                clamp(g / max(1.0, m.`val`[2]), 0.95, 1.08)
            ),
            ff8
        )

        // Renkli icerik agirligi (kase/imza): yumusak, benek yapmaz
        val hsv = Mat(); Imgproc.cvtColor(ff8, hsv, Imgproc.COLOR_RGB2HSV)
        val hs = ArrayList<Mat>(); Core.split(hsv, hs); hsv.release()
        val satF = Mat(); hs[1].convertTo(satF, CvType.CV_32F); hs.forEach { it.release() }
        Imgproc.GaussianBlur(satF, satF, Size(0.0, 0.0), 1.5)
        val cw = smoothstep(satF, 50.0, 90.0); satF.release()

        // Belge bitisi (seviye + gamma) tek tabloyla
        val lut = Mat(1, 256, CvType.CV_8UC1); lut.put(0, 0, levelsGammaTable)
        val doc8 = Mat(); Core.LUT(ff8, lut, doc8); lut.release()
        val docCh = ArrayList<Mat>(); Core.split(doc8, docCh); doc8.release()
        val ffCh = ArrayList<Mat>(); Core.split(ff8, ffCh); ff8.release()
        val srcCh = ArrayList<Mat>(); Core.split(rgb, srcCh); rgb.release()

        val d = Mat(); val f = Mat(); val s = Mat(); val bl = Mat()
        val outCh = ArrayList<Mat>()
        for (c in 0 until 3) {
            docCh[c].convertTo(d, CvType.CV_32F); docCh[c].release()
            ffCh[c].convertTo(f, CvType.CV_32F); ffCh[c].release()
            // renkli icerigi geri koy: d = d + (f - d) * cw
            Core.subtract(f, d, f); Core.multiply(f, cw, f); Core.add(d, f, d)
            // hafif keskinlik
            Imgproc.GaussianBlur(d, bl, Size(0.0, 0.0), 1.5)
            Core.addWeighted(d, 1.2, bl, -0.2, 0.0, d)
            // harman: kagit bolgesi belge bitisi, kalan yer ORIJINAL: o = s + (d - s) * wdoc
            srcCh[c].convertTo(s, CvType.CV_32F); srcCh[c].release()
            Core.subtract(d, s, d); Core.multiply(d, wdoc, d); Core.add(s, d, d)
            val o = Mat(); d.convertTo(o, CvType.CV_8U); outCh.add(o)
        }
        d.release(); f.release(); s.release(); bl.release(); cw.release(); wdoc.release()

        val out3 = Mat(); Core.merge(outCh, out3); outCh.forEach { it.release() }
        val outRgba = Mat(); Imgproc.cvtColor(out3, outRgba, Imgproc.COLOR_RGB2RGBA); out3.release()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(outRgba, out)
        outRgba.release()
        return out
    }

    private fun grayMode(src: Bitmap): Bitmap {
        val rgba = Mat(); Utils.bitmapToMat(src, rgba)
        val gray = Mat(); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.createCLAHE(2.5, Size(8.0, 8.0)).apply(gray, gray)
        val blur = Mat(); Imgproc.GaussianBlur(gray, blur, Size(0.0, 0.0), 3.0)
        Core.addWeighted(gray, 1.5, blur, -0.5, 0.0, gray)
        val outRgba = Mat(); Imgproc.cvtColor(gray, outRgba, Imgproc.COLOR_GRAY2RGBA)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(outRgba, out)
        return out
    }

    private fun bwMode(src: Bitmap): Bitmap {
        val rgba = Mat(); Utils.bitmapToMat(src, rgba)
        val gray = Mat(); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        // Belge degilse (fotograf, ekran) esikleme her yeri beneklere cevirir:
        // onun yerine duz siyah-beyaz fotograf ver
        if (!looksLikeDocument(rgba)) {
            rgba.release()
            val grayRgba = Mat(); Imgproc.cvtColor(gray, grayRgba, Imgproc.COLOR_GRAY2RGBA)
            gray.release()
            val photo = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(grayRgba, photo)
            grayRgba.release()
            return photo
        }
        rgba.release()
        // Isik dengeleme + kenar koruyucu gren temizleme
        Imgproc.createCLAHE(2.0, Size(8.0, 8.0)).apply(gray, gray)
        val den = Mat(); Imgproc.bilateralFilter(gray, den, 5, 45.0, 45.0)
        // Adaptive Gaussian Threshold: her bolgeyi ayri degerlendirir (okunaklilik)
        val bw = Mat()
        Imgproc.adaptiveThreshold(
            den, bw, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 25, 12.0
        )
        // Morfoloji: tek piksel gureni temizle
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(2.0, 2.0))
        Imgproc.morphologyEx(bw, bw, Imgproc.MORPH_OPEN, k)
        val outRgba = Mat(); Imgproc.cvtColor(bw, outRgba, Imgproc.COLOR_GRAY2RGBA)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(outRgba, out)
        return out
    }

    // Fis: soluk termal baski icin guclu CLAHE + ayarli adaptive threshold
    private fun receiptMode(src: Bitmap): Bitmap {
        val rgba = Mat(); Utils.bitmapToMat(src, rgba)
        val gray = Mat(); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.createCLAHE(3.5, Size(8.0, 8.0)).apply(gray, gray)
        val den = Mat(); Imgproc.bilateralFilter(gray, den, 5, 45.0, 45.0)
        val bw = Mat()
        // daha kucuk blok + dusuk C: soluk yazilari yakalar
        Imgproc.adaptiveThreshold(
            den, bw, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 21, 9.0
        )
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(2.0, 2.0))
        Imgproc.morphologyEx(bw, bw, Imgproc.MORPH_OPEN, k)
        val outRgba = Mat(); Imgproc.cvtColor(bw, outRgba, Imgproc.COLOR_GRAY2RGBA)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(outRgba, out)
        return out
    }

    // Kitap: cilt golgesini alan guclu CLAHE + keskinlestirme (gri, dogal sayfa)
    private fun bookMode(src: Bitmap): Bitmap {
        val rgba = Mat(); Utils.bitmapToMat(src, rgba)
        val gray = Mat(); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.createCLAHE(3.5, Size(8.0, 8.0)).apply(gray, gray)
        // kagidi beyaza yaklastir
        Core.normalize(gray, gray, 0.0, 255.0, Core.NORM_MINMAX)
        val blur = Mat(); Imgproc.GaussianBlur(gray, blur, Size(0.0, 0.0), 3.0)
        Core.addWeighted(gray, 1.6, blur, -0.6, 0.0, gray)
        val outRgba = Mat(); Imgproc.cvtColor(gray, outRgba, Imgproc.COLOR_GRAY2RGBA)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(outRgba, out)
        return out
    }

    private fun clamp(v: Double, lo: Double, hi: Double): Double =
        if (v < lo) lo else if (v > hi) hi else v
}
