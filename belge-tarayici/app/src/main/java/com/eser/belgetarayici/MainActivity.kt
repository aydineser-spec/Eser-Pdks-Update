package com.eser.belgetarayici

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var scannerLauncher: ActivityResultLauncher<IntentSenderRequest>

    // Taranan sonuc, uygulamanin kendi klasorune kopyalanmis olarak tutulur.
    private val pageImages = mutableListOf<File>()
    private var pdfFile: File? = null

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
                if (result != null) {
                    handleScanResult(result)
                } else {
                    toast(getString(R.string.error_no_result))
                }
            }
        }

        binding.btnScan.setOnClickListener { startScan() }
        binding.btnSaveImages.setOnClickListener { saveImagesToGallery() }
        binding.btnSavePdf.setOnClickListener { savePdfToDownloads() }
        binding.btnShare.setOnClickListener { sharePdf() }

        updateActionsEnabled(false)
    }

    // ----------------------------------------------------------------------
    // Tarama baslatma
    // ----------------------------------------------------------------------
    private fun startScan() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(30)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            // FULL mod: otomatik yakalama, kenar bulma, perspektif duzeltme,
            // golge/leke/parmak temizleme ve gelismis filtreler.
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(
                    IntentSenderRequest.Builder(intentSender).build()
                )
            }
            .addOnFailureListener { e ->
                toast(getString(R.string.error_start, e.localizedMessage ?: ""))
            }
    }

    // ----------------------------------------------------------------------
    // Sonucu isle: dosyalari uygulama klasorune kopyala ve onizle
    // ----------------------------------------------------------------------
    private fun handleScanResult(result: GmsDocumentScanningResult) {
        // Onceki taramayi temizle.
        outputDir.listFiles()?.forEach { it.delete() }
        pageImages.clear()
        pdfFile = null

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        result.pages?.forEachIndexed { index, page ->
            val dest = File(outputDir, "belge_${stamp}_${index + 1}.jpg")
            if (copyUri(page.imageUri, dest)) {
                pageImages.add(dest)
            }
        }

        result.pdf?.let { pdf ->
            val dest = File(outputDir, "belge_$stamp.pdf")
            if (copyUri(pdf.uri, dest)) {
                pdfFile = dest
            }
        }

        renderPreview()
        val hasContent = pageImages.isNotEmpty()
        updateActionsEnabled(hasContent)
        binding.emptyState.visibility = if (hasContent) View.GONE else View.VISIBLE
        if (hasContent) {
            binding.pageCount.visibility = View.VISIBLE
            binding.pageCount.text = getString(R.string.page_count, pageImages.size)
        }
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
                setImageURI(Uri.fromFile(image))
            }
            binding.previewContainer.addView(iv)
        }
    }

    // ----------------------------------------------------------------------
    // Gorselleri galeriye kaydet
    // ----------------------------------------------------------------------
    private fun saveImagesToGallery() {
        if (pageImages.isEmpty()) return
        var ok = 0
        for (image in pageImages) {
            if (saveImageToGallery(image)) ok++
        }
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
                        Environment.DIRECTORY_PICTURES + "/BelgeTarayici"
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

    // ----------------------------------------------------------------------
    // PDF'i Indirilenler klasorune kaydet
    // ----------------------------------------------------------------------
    private fun savePdfToDownloads() {
        val pdf = pdfFile ?: return
        val saved = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, pdf.name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/BelgeTarayici"
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
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
                val downloads = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    ),
                    "BelgeTarayici"
                ).apply { mkdirs() }
                val dest = File(downloads, pdf.name)
                pdf.inputStream().use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                true
            }
        } catch (e: Exception) {
            false
        }
        toast(
            if (saved) getString(R.string.saved_pdf)
            else getString(R.string.save_failed)
        )
    }

    // ----------------------------------------------------------------------
    // Paylas (PDF varsa PDF, yoksa gorseller)
    // ----------------------------------------------------------------------
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
            for (image in pageImages) {
                uris.add(FileProvider.getUriForFile(this, authority, image))
            }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/jpeg"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        }
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

    private fun updateActionsEnabled(enabled: Boolean) {
        binding.btnSaveImages.isEnabled = enabled
        binding.btnSavePdf.isEnabled = enabled
        binding.btnShare.isEnabled = enabled
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
