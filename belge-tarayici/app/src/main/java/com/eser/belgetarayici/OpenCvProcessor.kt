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
 * OpenCV tabanli belge isleme zinciri (Adobe Scan / MS Lens tarzi):
 *  autoCrop: Canny + kontur ile belgeyi otomatik bul -> warpPerspective (duz A4)
 *  COLOR : CLAHE (isik dengeleme) + beyaz dengesi + unsharp (keskin, renkli)
 *  GRAY  : CLAHE gri + keskinlestirme
 *  BW    : CLAHE + bilateral + Adaptive Gaussian Threshold + morphology (OCR icin)
 * OpenCV hazir degilse saf-Kotlin DocEnhancer'a duser.
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
        if (!ensureInit()) return DocEnhancer.process(src, mode)
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
            DocEnhancer.process(src, mode)
        }
    }

    // Golge Temizle: isik haritasina bolerek (flat-field) golgeyi siler,
    // kagidi beyazlatir, RENGI ve yaziyi korur (siyah-beyaz yapmaz).
    private fun colorMode(src: Bitmap): Bitmap {
        val rgba = Mat(); Utils.bitmapToMat(src, rgba)
        val rgb = Mat(); Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)

        // TEK luminans arka plani ile bol (renk oranlari korunur -> mor/pembe
        // kayma OLMAZ). Per-kanal bolme renk lekesi yapiyordu.
        // Foto grenini once yumusat (benekleşmeyi engeller)
        Imgproc.medianBlur(rgb, rgb, 3)

        val chans = ArrayList<Mat>(); Core.split(rgb, chans)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(51.0, 51.0))
        val lum = Mat(); Imgproc.cvtColor(rgb, lum, Imgproc.COLOR_RGB2GRAY)
        val bg = Mat()
        Imgproc.morphologyEx(lum, bg, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.GaussianBlur(bg, bg, Size(0.0, 0.0), 25.0)
        val bgF = Mat(); bg.convertTo(bgF, CvType.CV_32F)
        Core.add(bgF, Scalar(1.0), bgF)
        for (i in 0 until 3) {
            val chF = Mat(); chans[i].convertTo(chF, CvType.CV_32F)
            Core.divide(chF, bgF, chF)
            Core.multiply(chF, Scalar(255.0), chF)
            chF.convertTo(chans[i], CvType.CV_8U)
        }
        Core.merge(chans, rgb)

        // Beyaz dengesi: parlak (kagit) pikselleri notr yap -> renk lekesi gider
        val gray = Mat(); Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val mask = Mat(); Imgproc.threshold(gray, mask, 200.0, 255.0, Imgproc.THRESH_BINARY)
        val m = Core.mean(rgb, mask)
        val g = (m.`val`[0] + m.`val`[1] + m.`val`[2]) / 3.0
        if (g > 1.0) {
            Core.multiply(
                rgb,
                Scalar(
                    clamp(g / max(1.0, m.`val`[0]), 0.95, 1.08),
                    clamp(g / max(1.0, m.`val`[1]), 0.95, 1.08),
                    clamp(g / max(1.0, m.`val`[2]), 0.95, 1.08)
                ),
                rgb
            )
        }

        // Arka plani bembeyaz temizle + kontrast (puruzsuz seviye ayari, benek yok)
        // Kagit -> beyaz, yazi -> siyah, kase/imza rengi korunur
        whitenPaper(rgb)

        // Hafif keskinlik (yazi 'dolma kalem' gibi kalinlasmasin - dusuk tutuldu)
        val blur = Mat(); Imgproc.GaussianBlur(rgb, blur, Size(0.0, 0.0), 1.5)
        Core.addWeighted(rgb, 1.2, blur, -0.2, 0.0, rgb)

        val outRgba = Mat(); Imgproc.cvtColor(rgb, outRgba, Imgproc.COLOR_RGB2RGBA)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(outRgba, out)
        return out
    }

    /**
     * Kagit arka planini tertemiz beyaza ceker (vFlat/CamScanner gorunumu).
     * BENEK YAPMAZ: yerel esikleme yerine PURUZSUZ global seviye ayari kullanir.
     *  - medianBlur: foto grenini yumusatir (ince cizgiyi bozmaz)
     *  - seviye (levels): siyah nokta 70, beyaz nokta 205 -> kagit bembeyaz, yazi siyah,
     *    tek bir affine + kirpma oldugu icin karabiber benek OLUSMAZ
     *  - renkli icerik (mavi kase, imza): orijinal renginde geri konur (bozulmaz)
     * rgb: RGB, 8UC3 (yerinde degistirilir).
     */
    fun whitenPaper(rgb: Mat) {
        // Renkli icerigi sonradan geri koymak icin orijinali sakla
        val orig = rgb.clone()

        // Foto grenini yumusat (benekleri kaynaginda azaltir)
        Imgproc.medianBlur(rgb, rgb, 3)

        // Seviye ayari: out = (in - bp) * 255/(wp - bp)  (bp=70, wp=205)
        // Kagit (~235) -> 255 beyaz, yazi (~80) -> ~0 siyah; PURUZSUZ, benek yok
        val bp = 70.0; val wp = 205.0
        val scale = 255.0 / (wp - bp)
        rgb.convertTo(rgb, -1, scale, -bp * scale)

        // Renkli (doygun) icerigi orijinalden geri koy: kase/imza rengi birebir kalir
        val hsv = Mat(); Imgproc.cvtColor(orig, hsv, Imgproc.COLOR_RGB2HSV)
        val ch = ArrayList<Mat>(); Core.split(hsv, ch)
        val satMask = Mat(); Imgproc.threshold(ch[1], satMask, 45.0, 255.0, Imgproc.THRESH_BINARY)
        // Maskeyi biraz genislet: renkli kenarlar da korunsun
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        Imgproc.dilate(satMask, satMask, k)
        orig.copyTo(rgb, satMask)
        orig.release()
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
