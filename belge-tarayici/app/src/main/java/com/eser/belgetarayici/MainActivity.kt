package com.eser.belgetarayici

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.eser.belgetarayici.databinding.ActivityMainBinding
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var scannerLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var importLauncher: ActivityResultLauncher<String>

    // Ham (islenmemis) sayfalar ve o an gosterilen (islenmis) sayfalar
    private val originalImages = mutableListOf<File>()
    private val pageImages = mutableListOf<File>()
    private var pdfFile: File? = null
    private var currentMode = DocEnhancer.Mode.ORIGINAL

    private val outputDir: File
        get() = File(filesDir, "output").apply { mkdirs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        scannerLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { activityResult ->
            if (activityResult.resultCode == Activity.RESULT_OK) {
                val result = GmsDocumentScanningResult
                    .fromActivityResultIntent(activityResult.data)
                if (result != null) handleScanResult(result)
                else toast(getString(R.string.error_no_result))
            }
        }

        // Galeriden / dosyalardan gorsel sec (kamera olmadan)
        importLauncher = registerForActivityResult(
            ActivityResultContracts.GetMultipleContents()
        ) { uris ->
            if (!uris.isNullOrEmpty()) loadPages(uris)
            else toast(getString(R.string.no_image_picked))
        }

        binding.btnScan.setOnClickListener { startScan() }
        binding.btnImport.setOnClickListener { importLauncher.launch("image/*") }
        binding.btnSaveImages.setOnClickListener { saveImagesToGallery() }
        binding.btnSavePdf.setOnClickListener { savePdfToDownloads() }
        binding.btnShare.setOnClickListener { sharePdf() }
        binding.btnText.setOnClickListener { openTextScreen() }

        binding.modeOriginal.setOnClickListener { applyMode(DocEnhancer.Mode.ORIGINAL) }
        binding.modeColor.setOnClickListener { applyMode(DocEnhancer.Mode.COLOR) }
        binding.modeGray.setOnClickListener { applyMode(DocEnhancer.Mode.GRAY) }
        binding.modeBw.setOnClickListener { applyMode(DocEnhancer.Mode.BW) }

        showContent(false)
    }

    // ----------------------------------------------------------------------
    // Tarama baslat
    // ----------------------------------------------------------------------
    private fun startScan() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(30)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { e ->
                toast(getString(R.string.error_start, e.localizedMessage ?: ""))
            }
    }

    // ----------------------------------------------------------------------
    // Sonucu isle
    // ----------------------------------------------------------------------
    private fun handleScanResult(result: GmsDocumentScanningResult) {
        loadPages(result.pages?.map { it.imageUri } ?: emptyList())
    }

    // Taranan veya secilen gorselleri sayfa olarak yukle
    private fun loadPages(sources: List<Uri>) {
        outputDir.listFiles()?.forEach { it.delete() }
        originalImages.clear()
        pageImages.clear()
        pdfFile = null
        currentMode = DocEnhancer.Mode.ORIGINAL

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        sources.forEachIndexed { index, uri ->
            val orig = File(outputDir, "orig_${stamp}_${index + 1}.jpg")
            if (copyUri(uri, orig)) {
                originalImages.add(orig)
                // Baslangicta gosterilen = orijinal
                val disp = File(outputDir, "belge_${stamp}_${index + 1}.jpg")
                orig.copyTo(disp, overwrite = true)
                pageImages.add(disp)
            }
        }

        if (pageImages.isEmpty()) {
            showContent(false)
            return
        }
        rebuildPdf()
        renderPreview()
        showContent(true)
        binding.pageCount.text = getString(R.string.page_count, pageImages.size)
    }

    // ----------------------------------------------------------------------
    // Iyilestirme modunu uygula (arka planda)
    // ----------------------------------------------------------------------
    private fun applyMode(mode: DocEnhancer.Mode) {
        if (originalImages.isEmpty() || mode == currentMode) return
        setBusy(true, getString(R.string.processing))
        Thread {
            try {
                for (i in originalImages.indices) {
                    val src = decodeSampled(originalImages[i], 2400)
                    val outBmp = DocEnhancer.process(src, mode)
                    FileOutputStream(pageImages[i]).use { fos ->
                        outBmp.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                    }
                    if (outBmp !== src) outBmp.recycle()
                    src.recycle()
                }
                rebuildPdf()
                currentMode = mode
                runOnUiThread {
                    renderPreview()
                    setBusy(false, "")
                    highlightMode(mode)
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    setBusy(false, "")
                    toast(getString(R.string.processing_failed))
                }
            }
        }.start()
    }

    // ----------------------------------------------------------------------
    // PDF'i o anki sayfalardan yeniden uret
    // ----------------------------------------------------------------------
    private fun rebuildPdf() {
        try {
            val doc = PdfDocument()
            pageImages.forEachIndexed { index, f ->
                val bmp = decodeSampled(f, 2400)
                val info = PdfDocument.PageInfo
                    .Builder(bmp.width, bmp.height, index + 1).create()
                val page = doc.startPage(info)
                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                doc.finishPage(page)
                bmp.recycle()
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val out = File(outputDir, "belge_$stamp.pdf")
            FileOutputStream(out).use { doc.writeTo(it) }
            doc.close()
            pdfFile?.delete()
            pdfFile = out
        } catch (e: Throwable) {
            // PDF uretilemezse gorseller yine calisir
        }
    }

    private fun decodeSampled(file: File, maxDim: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        val big = max(bounds.outWidth, bounds.outHeight)
        while (big / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    private fun renderPreview() {
        binding.previewContainer.removeAllViews()
        val margin = (12 * resources.displayMetrics.density).toInt()
        for (image in pageImages) {
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = margin }
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(decodeSampled(image, 1200))
            }
            binding.previewContainer.addView(iv)
        }
    }

    private fun highlightMode(mode: DocEnhancer.Mode) {
        val map = mapOf(
            DocEnhancer.Mode.ORIGINAL to binding.modeOriginal,
            DocEnhancer.Mode.COLOR to binding.modeColor,
            DocEnhancer.Mode.GRAY to binding.modeGray,
            DocEnhancer.Mode.BW to binding.modeBw
        )
        map.forEach { (m, btn) -> btn.alpha = if (m == mode) 1f else 0.5f }
    }

    // ----------------------------------------------------------------------
    // Kaydet / paylas
    // ----------------------------------------------------------------------
    private fun saveImagesToGallery() {
        if (pageImages.isEmpty()) return
        var ok = 0
        for (image in pageImages) if (saveImageToGallery(image)) ok++
        toast(getString(R.string.saved_images, ok))
    }

    private fun saveImageToGallery(file: File): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/EserLens"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = contentResolver
            val uri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun savePdfToDownloads() {
        val pdf = pdfFile ?: return
        val saved = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, pdf.name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/EserLens"
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        pdf.inputStream().use { it.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    true
                } else false
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    ), "EserLens"
                ).apply { mkdirs() }
                val dest = File(dir, pdf.name)
                pdf.inputStream().use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                true
            }
        } catch (e: Exception) {
            false
        }
        toast(if (saved) getString(R.string.saved_pdf) else getString(R.string.save_failed))
    }

    private fun sharePdf() {
        val authority = "$packageName.fileprovider"
        val pdf = pdfFile
        if (pdf != null) {
            val uri = FileProvider.getUriForFile(this, authority, pdf)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        } else if (pageImages.isNotEmpty()) {
            val uris = ArrayList<Uri>()
            for (image in pageImages) uris.add(FileProvider.getUriForFile(this, authority, image))
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/jpeg"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        }
    }

    private fun openTextScreen() {
        if (pageImages.isEmpty()) return
        val paths = ArrayList(pageImages.map { it.absolutePath })
        val intent = Intent(this, TextActivity::class.java)
        intent.putStringArrayListExtra(TextActivity.EXTRA_IMAGE_PATHS, paths)
        startActivity(intent)
    }

    // ----------------------------------------------------------------------
    // Yardimcilar
    // ----------------------------------------------------------------------
    private fun copyUri(source: Uri, dest: File): Boolean {
        return try {
            contentResolver.openInputStream(source)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.exists() && dest.length() > 0
        } catch (e: Exception) {
            false
        }
    }

    private fun showContent(has: Boolean) {
        binding.emptyState.visibility = if (has) View.GONE else View.VISIBLE
        binding.pageCount.visibility = if (has) View.VISIBLE else View.GONE
        binding.modeBar.visibility = if (has) View.VISIBLE else View.GONE
        binding.btnSaveImages.isEnabled = has
        binding.btnSavePdf.isEnabled = has
        binding.btnShare.isEnabled = has
        binding.btnText.isEnabled = has
        if (has) highlightMode(currentMode)
    }

    private fun setBusy(busy: Boolean, msg: String) {
        binding.procOverlay.visibility = if (busy) View.VISIBLE else View.GONE
        binding.procText.text = msg
        binding.btnScan.isEnabled = !busy
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
