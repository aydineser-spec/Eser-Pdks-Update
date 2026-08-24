package com.eser.belgetarayici

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.min

/**
 * DocShadow AI ile belge golge silme + tarayici gorunumu (appearance enhancement).
 * Model (assets/docshadow.onnx) girisi: RGB /255, CHW; cikisi: temiz RGB.
 * (Python'da gercekci sert golgede dogrulandi: golge tamamen gitti, renk korundu.)
 */
object EnhanceAI {

    private const val MAXDIM = 1280   // isleme uzun kenar (hiz/kalite dengesi)

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null

    fun ensure(context: Context): Boolean {
        if (session != null) return true
        return try {
            val bytes = context.assets.open("docshadow.onnx").use { it.readBytes() }
            val e = OrtEnvironment.getEnvironment()
            val s = e.createSession(bytes, OrtSession.SessionOptions())
            env = e; session = s
            true
        } catch (e: Throwable) {
            false
        }
    }

    fun enhance(context: Context, src: Bitmap): Bitmap {
        if (!ensure(context)) return src
        if (!OpenCvProcessor.ensureInit()) return src
        val e = env ?: return src
        val s = session ?: return src
        return try {
            val rgba = Mat(); Utils.bitmapToMat(src, rgba)
            val rgb = Mat(); Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            val imgW = rgb.width(); val imgH = rgb.height()

            // Model boyutu: uzun kenar MAXDIM, 16'nin kati
            val sc = min(1.0, MAXDIM.toDouble() / maxOf(imgW, imgH))
            val rw = (((imgW * sc).toInt()) / 16 * 16).coerceAtLeast(16)
            val rh = (((imgH * sc).toInt()) / 16 * 16).coerceAtLeast(16)
            val small = Mat(); Imgproc.resize(rgb, small, Size(rw.toDouble(), rh.toDouble()))

            val n = rw * rh
            val pix = ByteArray(n * 3); small.get(0, 0, pix)
            val chw = FloatArray(3 * n)
            var idx = 0
            for (i in 0 until n) {
                chw[i] = (pix[idx].toInt() and 0xFF) / 255f
                chw[n + i] = (pix[idx + 1].toInt() and 0xFF) / 255f
                chw[2 * n + i] = (pix[idx + 2].toInt() and 0xFF) / 255f
                idx += 3
            }
            val tensor = OnnxTensor.createTensor(
                e, FloatBuffer.wrap(chw), longArrayOf(1, 3, rh.toLong(), rw.toLong())
            )
            val result = s.run(java.util.Collections.singletonMap("image", tensor))
            val outT = result.get(0) as OnnxTensor
            val fb = outT.floatBuffer   // [1,3,rh,rw]

            val outPix = ByteArray(n * 3)
            var p = 0
            for (i in 0 until n) {
                outPix[p] = clip(fb.get(i))
                outPix[p + 1] = clip(fb.get(n + i))
                outPix[p + 2] = clip(fb.get(2 * n + i))
                p += 3
            }
            val outSmall = Mat(rh, rw, CvType.CV_8UC3); outSmall.put(0, 0, outPix)
            val outFull = Mat(); Imgproc.resize(outSmall, outFull, Size(imgW.toDouble(), imgH.toDouble()))

            // Gren/kumlanmayi temizle + kagidi beyazlat (cizgi cizimde benekli gorunum gitsin)
            Imgproc.medianBlur(outFull, outFull, 3)
            val g = Mat(); Imgproc.cvtColor(outFull, g, Imgproc.COLOR_RGB2GRAY)
            val mask = Mat(); Imgproc.threshold(g, mask, 222.0, 255.0, Imgproc.THRESH_BINARY)
            outFull.setTo(Scalar(255.0, 255.0, 255.0), mask)

            val outRgba = Mat(); Imgproc.cvtColor(outFull, outRgba, Imgproc.COLOR_RGB2RGBA)
            val out = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outRgba, out)

            tensor.close(); result.close()
            out
        } catch (e: Throwable) {
            src
        }
    }

    private fun clip(v: Float): Byte {
        val x = (v * 255f).toInt()
        return (if (x < 0) 0 else if (x > 255) 255 else x).toByte()
    }
}
