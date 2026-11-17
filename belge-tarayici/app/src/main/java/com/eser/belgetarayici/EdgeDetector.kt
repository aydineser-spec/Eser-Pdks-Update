package com.eser.belgetarayici

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Fotografta belgenin (kagit, kutu, kitap) 4 kosesini bulur.
 * SADECE KOSE BULUR: goruntunun rengine/tonuna hic dokunmaz.
 *
 * Yontem (Python/cv2 4.11 ile 30 sahnede test edildi: tugla, cim, cakil,
 * koyu ve acik masa; egik/perspektifli belge ve renkli kutu):
 *  1) 500px'e kucult, yazi/desen yumusat (kapama + medyan)
 *  2) Aday dortgenler: Canny (3 esik) + kontur + 4 koseli cokgen; Otsu bolgeleri.
 *     Hem parlaklik hem renk doygunlugu kanalinda.
 *  3) Elemeler: cok kucuk/buyuk alan, fotograf cercevesine yapisan dortgen
 *  4) GUVEN TESTI: dort kenarin HER BIRINDE iceri/disari renk farki belirgin
 *     olmali (kagit kenari gercekten orada mi?). Gecemezse null -> hic kirpilmaz.
 * Sonuc: 21/30 tam isabet (<%2), kalanlarin cogu %2-5 (Kirp ile duzeltilir),
 * uçuk yanlis kesim yok.
 */
object EdgeDetector {

    private const val WORK = 500.0
    private const val MIN_SIDE_CONTRAST = 15.0

    @Volatile private var ready = false

    fun init(): Boolean {
        if (!ready) ready = try { OpenCVLoader.initLocal() } catch (e: Throwable) { false }
        return ready
    }

    /**
     * Belgenin koseleri, 0..1 normalize [x0,y0, x1,y1, x2,y2, x3,y3] (TL,TR,BR,BL).
     * Emin degilse null (cagiran taraf kirpmaz, fotograf oldugu gibi kalir).
     */
    fun detect(src: Bitmap): FloatArray? {
        if (!init()) return null
        return try { detectInternal(src) } catch (e: Throwable) { null }
    }

    private fun detectInternal(src: Bitmap): FloatArray? {
        val s = WORK / max(src.width, src.height)
        val sw = max(1, (src.width * s).toInt())
        val sh = max(1, (src.height * s).toInt())
        val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
        val rgba = Mat(); Utils.bitmapToMat(scaled, rgba)
        if (scaled !== src) scaled.recycle()
        val rgb = Mat(); Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB); rgba.release()
        val w = rgb.cols()
        val h = rgb.rows()
        val areaImg = w.toDouble() * h

        // Kenar karsilastirmasi icin yumusatilmis renkli goruntu (piksel dizisi)
        val col = Mat(); Imgproc.medianBlur(rgb, col, 7)
        val colBytes = ByteArray(w * h * 3); col.get(0, 0, colBytes); col.release()

        // Parlaklik kanali: yazi ve zemin deseni yumusatilir
        val gray = Mat(); Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val g = Mat()
        val k9 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        Imgproc.morphologyEx(gray, g, Imgproc.MORPH_CLOSE, k9)
        Imgproc.medianBlur(g, g, 7)
        gray.release(); k9.release()

        // Renk doygunlugu kanali: renkli kutu / gri zemin ayrimi
        val hsv = Mat(); Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV); rgb.release()
        val hs = ArrayList<Mat>(); Core.split(hsv, hs); hsv.release()
        val sat = Mat(); Imgproc.medianBlur(hs[1], sat, 7)
        hs.forEach { it.release() }

        var best: Array<Point>? = null
        var bestScore = 0.0
        for (ch in listOf(g, sat)) {
            for (pts in candidates(ch, w, h)) {
                if (pts.size != 4) continue
                val area = Imgproc.contourArea(MatOfPoint2f(*pts))
                if (area < areaImg * 0.12 || area > areaImg * 0.92) continue
                if (borderStuck(pts, w, h) >= 3) continue
                val ord = order(pts)
                val sc = sideContrast(colBytes, w, h, ord)
                if (sc < MIN_SIDE_CONTRAST) continue
                val score = area * min(sc, 80.0)
                if (score > bestScore) { bestScore = score; best = ord }
            }
        }
        g.release(); sat.release()

        val b = best ?: return null
        return FloatArray(8) { i ->
            val p = b[i / 2]
            if (i % 2 == 0) (p.x / w).toFloat().coerceIn(0f, 1f) else (p.y / h).toFloat().coerceIn(0f, 1f)
        }
    }

    /** Aday dortgenler: Canny konturlari (3 esik) + Otsu bolgeleri. */
    private fun candidates(ch: Mat, w: Int, h: Int): List<Array<Point>> {
        val out = ArrayList<Array<Point>>()
        val med = median(ch)
        val pairs = listOf(
            Pair(max(10.0, 0.66 * med), min(255.0, 1.33 * med)),
            Pair(20.0, 60.0),
            Pair(40.0, 120.0)
        )
        val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val edges = Mat()
        for ((t1, t2) in pairs) {
            Imgproc.Canny(ch, edges, t1, t2)
            Imgproc.dilate(edges, edges, k3)
            val cnts = ArrayList<MatOfPoint>()
            val hier = Mat()
            Imgproc.findContours(edges, cnts, hier, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            hier.release()
            for (c in cnts) {
                val c2 = MatOfPoint2f(*c.toArray())
                val peri = Imgproc.arcLength(c2, true)
                if (peri >= 0.25 * (w + h)) {
                    for (eps in doubleArrayOf(0.02, 0.04)) {
                        val ap = MatOfPoint2f()
                        Imgproc.approxPolyDP(c2, ap, eps * peri, true)
                        val arr = ap.toArray()
                        ap.release()
                        if (arr.size == 4 && Imgproc.isContourConvex(MatOfPoint(*arr))) {
                            out.add(arr)
                            break
                        }
                    }
                }
                c2.release(); c.release()
            }
        }
        edges.release(); k3.release()

        // Parlaklik/renk bolgesi (Otsu): duz zeminde kenar zayifsa
        val th = Mat()
        Imgproc.threshold(ch, th, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        val inv = Mat(); Core.bitwise_not(th, inv)
        val k7 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        for (m in listOf(th, inv)) {
            val m2 = Mat(); Imgproc.morphologyEx(m, m2, Imgproc.MORPH_OPEN, k7)
            val cnts = ArrayList<MatOfPoint>()
            val hier = Mat()
            Imgproc.findContours(m2, cnts, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            hier.release(); m2.release()
            for (c in cnts) {
                val c2 = MatOfPoint2f(*c.toArray())
                val peri = Imgproc.arcLength(c2, true)
                val ap = MatOfPoint2f()
                Imgproc.approxPolyDP(c2, ap, 0.02 * peri, true)
                val arr = ap.toArray()
                ap.release()
                if (arr.size == 4) {
                    out.add(arr)
                } else if (c.total() >= 5) {
                    val rr = Imgproc.minAreaRect(c2)
                    val p = Array(4) { Point() }
                    rr.points(p)
                    out.add(p)
                }
                c2.release(); c.release()
            }
        }
        th.release(); inv.release(); k7.release()
        return out
    }

    /** 8 bit tek kanalin medyani (Canny otomatik esigi icin). */
    private fun median(m: Mat): Double {
        val n = m.total().toInt()
        if (n == 0) return 128.0
        val b = ByteArray(n); m.get(0, 0, b)
        val hist = IntArray(256)
        for (v in b) hist[v.toInt() and 0xFF]++
        val half = (n + 1) / 2
        var cum = 0
        for (i in 0..255) {
            cum += hist[i]
            if (cum >= half) return i.toDouble()
        }
        return 128.0
    }

    /** Fotograf cercevesine yapisan kose sayisi (cercevenin kendisini belge sanmasin). */
    private fun borderStuck(pts: Array<Point>, w: Int, h: Int): Int {
        val m = 0.015 * max(w, h)
        return pts.count { it.x < m || it.y < m || it.x > w - 1 - m || it.y > h - 1 - m }
    }

    /** Koseleri TL, TR, BR, BL sirasina diz. */
    private fun order(p: Array<Point>): Array<Point> {
        val tl = p.minByOrNull { it.x + it.y }!!
        val br = p.maxByOrNull { it.x + it.y }!!
        val tr = p.minByOrNull { it.y - it.x }!!
        val bl = p.maxByOrNull { it.y - it.x }!!
        return arrayOf(tl, tr, br, bl)
    }

    /**
     * Guven testi: her kenar boyunca 25 noktada, 6px iceri ve 6px disari renk farki.
     * Kenarin skoru farklarin medyani; dortgenin skoru EN ZAYIF kenar.
     * Kadraja tasan kenar (ornek alinamayan) notr kabul edilir.
     */
    private fun sideContrast(px: ByteArray, w: Int, h: Int, q: Array<Point>): Double {
        val cx = q.sumOf { it.x } / 4.0
        val cy = q.sumOf { it.y } / 4.0
        var minScore = Double.MAX_VALUE
        for (i in 0 until 4) {
            val p = q[i]
            val r = q[(i + 1) % 4]
            val vx = r.x - p.x
            val vy = r.y - p.y
            val len = hypot(vx, vy)
            if (len < 10.0) return 0.0
            var nx = -vy / len
            var ny = vx / len
            val mx = (p.x + r.x) / 2.0
            val my = (p.y + r.y) / 2.0
            if ((cx - mx) * nx + (cy - my) * ny < 0) { nx = -nx; ny = -ny }
            val d = 6.0
            val diffs = ArrayList<Double>()
            for (k in 0 until 25) {
                val t = 0.1 + 0.8 * k / 24.0
                val bx = p.x + vx * t
                val by = p.y + vy * t
                val xi = (bx + nx * d).roundToInt(); val yi = (by + ny * d).roundToInt()
                val xo = (bx - nx * d).roundToInt(); val yo = (by - ny * d).roundToInt()
                if (xi !in 0 until w || yi !in 0 until h || xo !in 0 until w || yo !in 0 until h) continue
                val a = (yi * w + xi) * 3
                val o = (yo * w + xo) * 3
                var s2 = 0.0
                for (c in 0 until 3) {
                    val dv = (px[a + c].toInt() and 0xFF) - (px[o + c].toInt() and 0xFF)
                    s2 += (dv * dv).toDouble()
                }
                diffs.add(sqrt(s2))
            }
            val score = if (diffs.size < 8) {
                255.0
            } else {
                diffs.sort()
                val n = diffs.size
                if (n % 2 == 1) diffs[n / 2] else (diffs[n / 2 - 1] + diffs[n / 2]) / 2.0
            }
            minScore = min(minScore, score)
        }
        return minScore
    }
}
