package com.eser.belgetarayici

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
    private lateinit var cropLauncher: ActivityResultLauncher<Intent>

    // Ham (islenmemis) sayfalar ve o an gosterilen (islenmis) sayfalar
    private val rawImages = mutableListOf<File>()       // hic dokunulmamis kopya (Sifirla icin)
    private val originalImages = mutableListOf<File>()   // isleme tabani (kirp/dewarp buraya yazilir)
    private val pageImages = mutableListOf<File>()
    private var pdfFile: File? = null
    private var currentMode = DocEnhancer.Mode.ORIGINAL
    private var pendingSave = 0  // 1=gorseller, 2=pdf (izin sonrasi devam icin)

    private val outputDir: File
        get() = File(filesDir, "output").apply { mkdirs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // OpenCV'yi arka planda hazirla (native kutuphaneyi yukle)
        Thread { OpenCvProcessor.ensureInit() }.start()

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

        // Yamuk kagidi duz A4'e cevir (perspektif duzeltme)
        cropLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { res ->
            if (res.resultCode == Activity.RESULT_OK) refreshAfterCrop()
        }

        binding.btnScan.setOnClickListener { startScan() }
        binding.btnImport.setOnClickListener { importLauncher.launch("image/*") }
        binding.btnCrop.setOnClickListener { openCrop() }
        binding.btnMagic.setOnClickListener { runMagic() }
        binding.btnDewarp.setOnClickListener { runDewarp() }
        binding.btnEnhanceAi.setOnClickListener { runEnhanceAI() }
        binding.btnRotate.setOnClickListener { rotatePages() }
        binding.btnReset.setOnClickListener { resetToOriginal() }
        binding.btnSaveImages.setOnClickListener { saveImagesToGallery() }
        binding.btnSavePdf.setOnClickListener { savePdfToDownloads() }
        binding.btnShare.setOnClickListener { sharePdf() }
        binding.btnText.setOnClickListener { openTextScreen() }

        binding.modeOriginal.setOnClickListener { applyMode(DocEnhancer.Mode.ORIGINAL) }
        binding.modeColor.setOnClickListener { applyMode(DocEnhancer.Mode.COLOR) }
        binding.modeGray.setOnClickListener { applyMode(DocEnhancer.Mode.GRAY) }
        binding.modeBw.setOnClickListener { applyMode(DocEnhancer.Mode.BW) }
        binding.modeReceipt.setOnClickListener { applyMode(DocEnhancer.Mode.RECEIPT) }
        binding.modeBook.setOnClickListener { applyMode(DocEnhancer.Mode.BOOK) }

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
        rawImages.clear()
        originalImages.clear()
        pageImages.clear()
        pdfFile = null
        currentMode = DocEnhancer.Mode.ORIGINAL

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        sources.forEachIndexed { index, uri ->
            val orig = File(outputDir, "orig_${stamp}_${index + 1}.jpg")
            if (copyUri(uri, orig)) {
                originalImages.add(orig)
                // Hic dokunulmamis kopya (Sifirla ile geri donmek icin)
                val ham = File(outputDir, "ham_${stamp}_${index + 1}.jpg")
                orig.copyTo(ham, overwrite = true)
                rawImages.add(ham)
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
        // Otomatik: belge kenarlarini bul + perspektif duzelt + iyilestir
        autoPrepare()
    }

    // Otomatik belge yakalama (yandaki evraklari disla) + COLOR iyilestirme.
    // autoCrop yalniz belgeyi guvenle bulunca kirpar; bulamazsa dokunmaz.
    private fun autoPrepare() {
        if (originalImages.isEmpty()) return
        setBusy(true, getString(R.string.processing))
        Thread {
            try {
                for (i in originalImages.indices) {
                    val raw = decodeSampled(originalImages[i], 1600)
                    // Ana belgeyi otomatik kirp (yandaki evraklar/clutter cikar)
                    val cropped = OpenCvProcessor.autoCrop(raw)
                    if (cropped !== raw) {
                        FileOutputStream(originalImages[i]).use {
                            cropped.compress(Bitmap.CompressFormat.JPEG, 95, it)
                        }
                    }
                    val enh = OpenCvProcessor.process(cropped, DocEnhancer.Mode.COLOR)
                    FileOutputStream(pageImages[i]).use {
                        enh.compress(Bitmap.CompressFormat.JPEG, 92, it)
                    }
                    if (enh !== cropped) enh.recycle()
                    if (cropped !== raw) cropped.recycle()
                    raw.recycle()
                }
                rebuildPdf()
                currentMode = DocEnhancer.Mode.COLOR
                runOnUiThread {
                    renderPreview(); setBusy(false, "")
                    highlightMode(DocEnhancer.Mode.COLOR); updatePageInfo()
                }
            } catch (e: Throwable) {
                runOnUiThread { setBusy(false, ""); toast(getString(R.string.processing_failed)) }
            }
        }.start()
    }

    // Sayfa sayisi + motor durumu (OpenCV yuklendi mi) goster
    private fun updatePageInfo() {
        val eng = if (OpenCvProcessor.isReady()) "HD" else "temel"
        binding.pageCount.text = getString(R.string.page_count, pageImages.size) + "  ·  " + eng
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
                    val src = decodeSampled(originalImages[i], 1600)
                    val outBmp = OpenCvProcessor.process(src, mode)
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
                    updatePageInfo()
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
    // PDF uret; basarili ise null, hata varsa sebep dondurur.
    // JPEG'i dogrudan PDF'e gomer (JpegPdf) -> bellek kullanmaz, cokmez.
    private fun rebuildPdf(): String? {
        if (pageImages.isEmpty()) return "sayfa yok"
        return try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val out = File(outputDir, "belge_$stamp.pdf")
            if (!JpegPdf.build(pageImages, out)) return "pdf uretilemedi"
            pdfFile?.delete()
            pdfFile = out
            null
        } catch (e: Throwable) {
            e.javaClass.simpleName + ": " + (e.message ?: "")
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
            DocEnhancer.Mode.BW to binding.modeBw,
            DocEnhancer.Mode.RECEIPT to binding.modeReceipt,
            DocEnhancer.Mode.BOOK to binding.modeBook
        )
        map.forEach { (m, btn) -> btn.alpha = if (m == mode) 1f else 0.5f }
    }

    // ----------------------------------------------------------------------
    // Kaydet / paylas
    // ----------------------------------------------------------------------
    private fun saveImagesToGallery() {
        if (pageImages.isEmpty()) return
        if (needsLegacyPerm()) { pendingSave = 1; requestLegacyPerm(); return }
        var ok = 0
        var err: String? = null
        for (image in pageImages) {
            val e = saveOneImage(image)
            if (e == null) ok++ else err = e
        }
        if (ok > 0) toast(getString(R.string.saved_images, ok))
        else toast(getString(R.string.save_failed) + " (" + (err ?: "?") + ")")
    }

    private fun saveOneImage(file: File): String? {
        return try {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, uniqueName(file.name))
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/EserLens"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv
            ) ?: return "insert=null"
            contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: return "stream=null"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, cv, null, null)
            }
            null
        } catch (e: Throwable) {
            e.javaClass.simpleName + ": " + (e.message ?: "")
        }
    }

    private fun savePdfToDownloads() {
        var pdf = pdfFile
        if (pdf == null || !pdf.exists() || pdf.length() == 0L) {
            // Son bir deneme: PDF'i yeniden uret; olmazsa gercek sebebi goster
            val e = rebuildPdf()
            pdf = pdfFile
            if (pdf == null || !pdf.exists() || pdf.length() == 0L) {
                toast(getString(R.string.save_failed) + " (PDF: " + (e ?: "yok") + ")"); return
            }
        }
        if (needsLegacyPerm()) { pendingSave = 2; requestLegacyPerm(); return }
        val err = savePdfInternal(pdf)
        if (err == null) toast(getString(R.string.saved_pdf))
        else toast(getString(R.string.save_failed) + " (" + err + ")")
    }

    private fun savePdfInternal(pdf: File): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName(pdf.name))
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/EserLens"
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv
                ) ?: return "insert=null"
                contentResolver.openOutputStream(uri)?.use { out ->
                    pdf.inputStream().use { it.copyTo(out) }
                } ?: return "stream=null"
                cv.clear(); cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, cv, null, null)
                null
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    ), "EserLens"
                ).apply { mkdirs() }
                val dest = File(dir, uniqueName(pdf.name))
                pdf.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
                null
            }
        } catch (e: Throwable) {
            e.javaClass.simpleName + ": " + (e.message ?: "")
        }
    }

    private fun uniqueName(base: String): String {
        val stamp = System.currentTimeMillis().toString().takeLast(5)
        val dot = base.lastIndexOf('.')
        return if (dot > 0) base.substring(0, dot) + "_" + stamp + base.substring(dot)
        else base + "_" + stamp
    }

    private fun needsLegacyPerm(): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED

    private fun requestLegacyPerm() {
        ActivityCompat.requestPermissions(
            this, arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 42
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            when (pendingSave) { 1 -> saveImagesToGallery(); 2 -> savePdfToDownloads() }
        } else if (requestCode == 42) {
            toast(getString(R.string.save_failed) + " (izin verilmedi)")
        }
        pendingSave = 0
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

    // Yamuk belgeyi duzeltmek icin kirpma ekranini ac (ilk sayfa)
    private fun openCrop() {
        if (originalImages.isEmpty()) return
        val intent = Intent(this, CropActivity::class.java)
        intent.putExtra(CropActivity.EXTRA_PATH, originalImages[0].absolutePath)
        cropLauncher.launch(intent)
    }

    // DocShadow AI ile golge sil + tarayici gorunumu (tum sayfalar)
    private fun runEnhanceAI() {
        if (originalImages.isEmpty()) return
        setBusy(true, getString(R.string.enhancing_ai))
        Thread {
            try {
                for (i in originalImages.indices) {
                    val raw = decodeSampled(originalImages[i], 1600)
                    val clean = EnhanceAI.enhance(this, raw)
                    FileOutputStream(originalImages[i]).use {
                        clean.compress(Bitmap.CompressFormat.JPEG, 95, it)
                    }
                    FileOutputStream(pageImages[i]).use {
                        clean.compress(Bitmap.CompressFormat.JPEG, 92, it)
                    }
                    if (clean !== raw) clean.recycle()
                    raw.recycle()
                }
                rebuildPdf()
                currentMode = DocEnhancer.Mode.ORIGINAL
                runOnUiThread {
                    renderPreview(); setBusy(false, "")
                    highlightMode(DocEnhancer.Mode.ORIGINAL); updatePageInfo()
                }
            } catch (e: Throwable) {
                runOnUiThread { setBusy(false, ""); toast(getString(R.string.processing_failed)) }
            }
        }.start()
    }

    // SIHIRLI TARA: tek tuşta tüm zincir -> oto-kırp + AI düzleştir + AI gölge sil
    private fun runMagic() {
        if (originalImages.isEmpty()) return
        setBusy(true, getString(R.string.magic_busy))
        Thread {
            try {
                for (i in originalImages.indices) {
                    var bmp = decodeSampled(originalImages[i], 1600)
                    // 1) Belgeyi otomatik bul + kırp + eğikliği düzelt
                    val cropped = OpenCvProcessor.autoCrop(bmp)
                    if (cropped !== bmp) { bmp.recycle(); bmp = cropped }
                    // 2) AI ile kıvrık/buruk düzleştir (UVDoc)
                    val flat = DewarpAI.dewarp(this, bmp)
                    if (flat !== bmp) { bmp.recycle(); bmp = flat }
                    // 3) AI ile gölge sil + tarayıcı görünümü (DocShadow)
                    val enh = EnhanceAI.enhance(this, bmp)
                    if (enh !== bmp) { bmp.recycle(); bmp = enh }
                    // Sonucu hem işleme tabanına hem gösterime yaz
                    FileOutputStream(originalImages[i]).use {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)
                    }
                    FileOutputStream(pageImages[i]).use {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)
                    }
                    bmp.recycle()
                }
                rebuildPdf()
                currentMode = DocEnhancer.Mode.ORIGINAL
                runOnUiThread {
                    renderPreview(); setBusy(false, "")
                    highlightMode(DocEnhancer.Mode.ORIGINAL); updatePageInfo()
                }
            } catch (e: Throwable) {
                runOnUiThread { setBusy(false, ""); toast(getString(R.string.processing_failed)) }
            }
        }.start()
    }

    // Tum sayfalari 90 derece dondur (yan/donuk belgeyi dik yap)
    private fun rotatePages() {
        if (originalImages.isEmpty()) return
        setBusy(true, getString(R.string.rotating))
        Thread {
            try {
                for (i in originalImages.indices) {
                    val bmp = decodeSampled(originalImages[i], 2000)
                    val m = Matrix().apply { postRotate(90f) }
                    val rot = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    FileOutputStream(originalImages[i]).use {
                        rot.compress(Bitmap.CompressFormat.JPEG, 95, it)
                    }
                    if (rot !== bmp) rot.recycle()
                    bmp.recycle()
                }
                val keep = if (currentMode == DocEnhancer.Mode.ORIGINAL)
                    DocEnhancer.Mode.COLOR else currentMode
                currentMode = DocEnhancer.Mode.ORIGINAL
                runOnUiThread { setBusy(false, ""); applyMode(keep) }
            } catch (e: Throwable) {
                runOnUiThread { setBusy(false, ""); toast(getString(R.string.processing_failed)) }
            }
        }.start()
    }

    // Tum islemleri geri al: ham (dokunulmamis) kopyalardan basla
    private fun resetToOriginal() {
        if (rawImages.isEmpty()) return
        for (i in originalImages.indices) {
            if (i < rawImages.size) rawImages[i].copyTo(originalImages[i], overwrite = true)
        }
        currentMode = DocEnhancer.Mode.ORIGINAL
        applyMode(DocEnhancer.Mode.COLOR)
    }

    // AI ile kivrik/buruk belgeyi duzlestir (tum sayfalar), sonra tekrar iyilestir
    private fun runDewarp() {
        if (originalImages.isEmpty()) return
        setBusy(true, getString(R.string.dewarping))
        Thread {
            try {
                for (i in originalImages.indices) {
                    val raw = decodeSampled(originalImages[i], 1600)
                    // UVDoc kadraji dolu belge bekler: once otomatik kirp, sonra duzlestir
                    val cropped = OpenCvProcessor.autoCrop(raw)
                    val flat = DewarpAI.dewarp(this, cropped)
                    FileOutputStream(originalImages[i]).use {
                        flat.compress(Bitmap.CompressFormat.JPEG, 95, it)
                    }
                    if (flat !== cropped) flat.recycle()
                    if (cropped !== raw) cropped.recycle()
                    raw.recycle()
                }
                // Duzlestirilmis orijinali mevcut modla yeniden isle
                val keep = if (currentMode == DocEnhancer.Mode.ORIGINAL)
                    DocEnhancer.Mode.COLOR else currentMode
                currentMode = DocEnhancer.Mode.ORIGINAL
                runOnUiThread { setBusy(false, ""); applyMode(keep) }
            } catch (e: Throwable) {
                runOnUiThread { setBusy(false, ""); toast(getString(R.string.processing_failed)) }
            }
        }.start()
    }

    // Duzeltme sonrasi: duzeltilmis orijinali tekrar isle ve goster
    private fun refreshAfterCrop() {
        if (originalImages.isEmpty()) return
        originalImages[0].copyTo(pageImages[0], overwrite = true)
        currentMode = DocEnhancer.Mode.ORIGINAL
        rebuildPdf()
        renderPreview()
        applyMode(DocEnhancer.Mode.COLOR)
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
        binding.btnCrop.isEnabled = has
        binding.btnMagic.isEnabled = has
        binding.btnDewarp.isEnabled = has
        binding.btnEnhanceAi.isEnabled = has
        binding.btnRotate.isEnabled = has
        binding.btnReset.isEnabled = has
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
