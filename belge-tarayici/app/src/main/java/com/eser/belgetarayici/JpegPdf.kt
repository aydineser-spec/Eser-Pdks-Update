package com.eser.belgetarayici

import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * JPEG'leri dogrudan PDF icine gomerek (DCTDecode) PDF uretir.
 * Bitmap coz-yeniden ciz yapmaz -> bellek kullanmaz, cokmesi imkansiz,
 * her cihazda calisir. PdfDocument'in cihaza gore takilma sorununu bitirir.
 */
object JpegPdf {

    fun build(images: List<File>, out: File): Boolean {
        if (images.isEmpty()) return false
        return try {
            val fos = FileOutputStream(out)
            var pos = 0L
            val offsets = HashMap<Int, Long>()

            fun w(s: String) {
                val b = s.toByteArray(Charsets.ISO_8859_1); fos.write(b); pos += b.size
            }
            fun wb(b: ByteArray) { fos.write(b); pos += b.size }
            fun obj(n: Int) { offsets[n] = pos; w("$n 0 obj\n") }

            w("%PDF-1.4\n")

            val n = images.size
            val pageObjs = IntArray(n)
            val imgObjs = IntArray(n)
            val contentObjs = IntArray(n)
            var next = 3
            for (i in 0 until n) { pageObjs[i] = next++; imgObjs[i] = next++; contentObjs[i] = next++ }
            val maxObj = next - 1

            // 1: Catalog
            obj(1); w("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
            // 2: Pages
            obj(2)
            val kids = StringBuilder()
            for (i in 0 until n) kids.append("${pageObjs[i]} 0 R ")
            w("<< /Type /Pages /Kids [ $kids] /Count $n >>\nendobj\n")

            for (i in 0 until n) {
                val jpeg = images[i].readBytes()
                val (iw, ih) = jpegSize(images[i])

                obj(pageObjs[i])
                w(
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $iw $ih] " +
                        "/Resources << /XObject << /Im0 ${imgObjs[i]} 0 R >> >> " +
                        "/Contents ${contentObjs[i]} 0 R >>\nendobj\n"
                )

                obj(imgObjs[i])
                w(
                    "<< /Type /XObject /Subtype /Image /Width $iw /Height $ih " +
                        "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode " +
                        "/Length ${jpeg.size} >>\nstream\n"
                )
                wb(jpeg)
                w("\nendstream\nendobj\n")

                val content = "q $iw 0 0 $ih 0 0 cm /Im0 Do Q\n"
                obj(contentObjs[i])
                w("<< /Length ${content.length} >>\nstream\n")
                w(content)
                w("endstream\nendobj\n")
            }

            val xrefPos = pos
            w("xref\n0 ${maxObj + 1}\n")
            w("0000000000 65535 f \n")
            for (o in 1..maxObj) {
                w(String.format(Locale.US, "%010d 00000 n \n", offsets[o] ?: 0L))
            }
            w("trailer\n<< /Size ${maxObj + 1} /Root 1 0 R >>\nstartxref\n$xrefPos\n%%EOF\n")

            fos.flush(); fos.close()
            out.exists() && out.length() > 0L
        } catch (e: Throwable) {
            false
        }
    }

    private fun jpegSize(f: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        val w = if (o.outWidth > 0) o.outWidth else 1000
        val h = if (o.outHeight > 0) o.outHeight else 1400
        return Pair(w, h)
    }
}
