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
            val edges = Mat(); Imgproc.Canny(small, edges, 75.0, 200.0)
            Imgproc.dilate(edges, edges, Mat(), Point(-1.0, -1.0), 1)

            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

            val imgArea = small.width().toDouble() * small.height()
            var best: Array<Point>? = null
            var bestArea = imgArea * 0.25   // en az %25 alan
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

    private fun colorMode(src: Bitmap): Bitmap {
        val rgba = Mat(); Utils.bitmapToMat(src, rgba)
        val rgb = Mat(); Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)

        // CLAHE ile isik dengeleme (Lab uzayinda L kanali)
        val lab = Mat(); Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val chans = ArrayList<Mat>(); Core.split(lab, chans)
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        clahe.apply(chans[0], chans[0])
        Core.merge(chans, lab)
        Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB)

        // Beyaz dengesi: kagit (parlak) pikselleri notr yap -> renk lekesi gider
        val gray = Mat(); Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val mask = Mat()
        Imgproc.threshold(gray, mask, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        val paper = Core.mean(rgb, mask)
        val g = (paper.`val`[0] + paper.`val`[1] + paper.`val`[2]) / 3.0
        val sr = clamp(g / max(1.0, paper.`val`[0]), 0.7, 1.5)
        val sg = clamp(g / max(1.0, paper.`val`[1]), 0.7, 1.5)
        val sb = clamp(g / max(1.0, paper.`val`[2]), 0.7, 1.5)
        Core.multiply(rgb, Scalar(sr, sg, sb), rgb)

        // Unsharp mask (keskinlestir)
        val blur = Mat(); Imgproc.GaussianBlur(rgb, blur, Size(0.0, 0.0), 3.0)
        Core.addWeighted(rgb, 1.6, blur, -0.6, 0.0, rgb)

        val outRgba = Mat(); Imgproc.cvtColor(rgb, outRgba, Imgproc.COLOR_RGB2RGBA)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(outRgba, out)
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
