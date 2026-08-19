package com.eser.belgetarayici

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer

/**
 * UVDoc AI dewarp: kivrik/buruk belgeyi cihazda duzlestirir.
 * ONNX Runtime ile modeli (assets/uvdoc.onnx) calistirir, cikan 2B grid'i
 * OpenCV remap ile tam cozunurluge uygular. (Python'da dogrulandi.)
 */
object DewarpAI {

    private const val IN_W = 488
    private const val IN_H = 712

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null

    fun ensure(context: Context): Boolean {
        if (session != null) return true
        return try {
            val bytes = context.assets.open("uvdoc.onnx").use { it.readBytes() }
            val e = OrtEnvironment.getEnvironment()
            val s = e.createSession(bytes, OrtSession.SessionOptions())
            env = e; session = s
            true
        } catch (e: Throwable) {
            false
        }
    }

    fun dewarp(context: Context, src: Bitmap): Bitmap {
        if (!ensure(context)) return src
        if (!OpenCvProcessor.ensureInit()) return src
        val e = env ?: return src
        val s = session ?: return src
        return try {
            val rgba = Mat(); Utils.bitmapToMat(src, rgba)
            val rgb = Mat(); Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            val imgW = rgb.width(); val imgH = rgb.height()

            // model girisi: 488x712 RGB, CHW, 0..1
            val small = Mat()
            Imgproc.resize(rgb, small, Size(IN_W.toDouble(), IN_H.toDouble()))
            val n = IN_W * IN_H
            val pix = ByteArray(n * 3)
            small.get(0, 0, pix)
            val chw = FloatArray(3 * n)
            var idx = 0
            for (i in 0 until n) {
                chw[i] = (pix[idx].toInt() and 0xFF) / 255f
                chw[n + i] = (pix[idx + 1].toInt() and 0xFF) / 255f
                chw[2 * n + i] = (pix[idx + 2].toInt() and 0xFF) / 255f
                idx += 3
            }
            val shape = longArrayOf(1, 3, IN_H.toLong(), IN_W.toLong())
            val tensor = OnnxTensor.createTensor(e, FloatBuffer.wrap(chw), shape)
            val result = s.run(java.util.Collections.singletonMap("img", tensor))

            @Suppress("UNCHECKED_CAST")
            val grid = result.get(0).value as Array<Array<Array<FloatArray>>>
            val gh = grid[0][0].size          // 45
            val gw = grid[0][0][0].size       // 31

            val gx = Mat(gh, gw, CvType.CV_32F)
            val gy = Mat(gh, gw, CvType.CV_32F)
            val rowx = FloatArray(gw); val rowy = FloatArray(gw)
            for (r in 0 until gh) {
                for (c in 0 until gw) { rowx[c] = grid[0][0][r][c]; rowy[c] = grid[0][1][r][c] }
                gx.put(r, 0, rowx); gy.put(r, 0, rowy)
            }

            // grid'i tam cozunurluge buyut, normalize [-1,1] -> piksel remap
            val gxB = Mat(); val gyB = Mat()
            Imgproc.resize(gx, gxB, Size(imgW.toDouble(), imgH.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            Imgproc.resize(gy, gyB, Size(imgW.toDouble(), imgH.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            Core.add(gxB, Scalar(1.0), gxB); Core.multiply(gxB, Scalar((imgW - 1) / 2.0), gxB)
            Core.add(gyB, Scalar(1.0), gyB); Core.multiply(gyB, Scalar((imgH - 1) / 2.0), gyB)

            val dst = Mat()
            Imgproc.remap(rgb, dst, gxB, gyB, Imgproc.INTER_LINEAR)

            val outRgba = Mat(); Imgproc.cvtColor(dst, outRgba, Imgproc.COLOR_RGB2RGBA)
            val out = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outRgba, out)

            tensor.close(); result.close()
            out
        } catch (e: Throwable) {
            src
        }
    }
}
