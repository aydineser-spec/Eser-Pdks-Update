package com.eser.belgetarayici

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eser.belgetarayici.databinding.ActivityMainBinding
import com.eser.belgetarayici.databinding.ItemPageBinding
import com.eser.belgetarayici.databinding.SheetSaveBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Eser Lens ana ekrani (vFlat tarzi):
 *  1) Uygulama acilinca KENDI kameramiz acilir: telefonun tam cozunurlugunde ceker.
 *  2) Her cekimde kagidin kenarlari otomatik bulunur ve sayfa duzlestirilir
 *     (emin degilse fotograf oldugu gibi kalir). Goruntunun rengine dokunulmaz.
 *  3) Her sayfanin altinda Kirp / Dondur / Sil (kitapta "Ikiye Bol").
 *     Kirp, ORIJINAL fotografi acar: kesilen kisim kaybolmaz.
 *  4) Altta: + Sayfa / Kaydet (PDF, JPG, PNG) / Paylas.
 *  Menu: Galeriden ekle, Google tarayici ile tara, Yazi cikar.
 */
class MainActivity : AppCompatActivity() {

    /**
     * Bir sayfa.
     *  file: gosterilen/kaydedilen sayfa (otomatik iyilestirme uygulanmis)
     *  base: iyilestirme ONCESI sayfa (kirp/dondur sonucu); iyilestirme kapatilinca bu gosterilir
     *  raw: kamera/galeri fotografinin orijinali (Kirp icin; Google tarayicisinda yok)
     *  corners: raw uzerindeki koseler (0..1), rotation: sonradan yapilan ceyrek donusler
     *  enhance: iyilestirilebilir mi (Google tarayicisi zaten iyilestiriyor: false)
     */
    class Page(
        val file: File,
        val base: File,
        val raw: File? = null,
        var corners: FloatArray? = null,
        var rotation: Int = 0,
        val enhance: Boolean = true
    ) {
        fun delete() { file.delete(); base.delete(); raw?.delete() }
    }

    class PageHolder(val b: ItemPageBinding) : RecyclerView.ViewHolder(b.root)

    companion object {
        private const val EDIT_DIM = 4000   // isleme sirasinda ust sinir (uzun kenar)
        private const val ENH_DIM = 3200    // otomatik iyilestirme cozunurlugu (uzun kenar)
        private const val PREFS = "eserlens"
        private const val KEY_AUTO = "auto_enhance"
        private const val REQ_STORAGE = 42
        private const val PENDING_PDF = 1
        private const val PENDING_JPG = 2
        private const val PENDING_PNG = 3
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraLauncher: ActivityResultLauncher<Intent>
    private lateinit var scannerLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var galleryLauncher: ActivityResultLauncher<String>
    private lateinit var cropLauncher: ActivityResultLauncher<Intent>

    private val pages = mutableListOf<Page>()
    private val adapter = PageAdapter()
    private val worker = Executors.newSingleThreadExecutor()
    private val fileCounter = AtomicInteger(0)

    private var addMode = false      // true: yeni sayfalar sona eklenir, false: yenileri eskilerin yerine
    @Volatile private var autoEnhance = true
    private var cropIndex = -1
    private var unsaved = false
    private var pendingAction = 0

    private val outputDir: File
        get() = File(filesDir, "output").apply { mkdirs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Kenar bulucuyu arka planda hazirla
        worker.execute { EdgeDetector.init() }
        autoEnhance = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_AUTO, true)

        cameraLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { res ->
            val shots = res.data?.getStringArrayListExtra(CameraActivity.EXTRA_SHOTS)
            if (res.resultCode == Activity.RESULT_OK && !shots.isNullOrEmpty()) {
                processPhotos(shots.map { File(it) })
            } else if (res.data?.getBooleanExtra(CameraActivity.EXTRA_FALLBACK, false) == true) {
                startGoogleScan(addMode)
            }
        }

        scannerLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                val result = GmsDocumentScanningResult.fromActivityResultIntent(res.data)
                val uris = result?.pages?.map { it.imageUri }.orEmpty()
                if (uris.isNotEmpty()) addScannedPages(uris) else toast(getString(R.string.error_no_result))
            }
        }

        galleryLauncher = registerForActivityResult(
            ActivityResultContracts.GetMultipleContents()
        ) { uris ->
            if (!uris.isNullOrEmpty()) importGallery(uris)
        }

        cropLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { res ->
            val i = cropIndex
            cropIndex = -1
            if (res.resultCode == Activity.RESULT_OK && i in pages.indices) {
                val p = pages[i]
                val c = res.data?.getFloatArrayExtra(CropActivity.EXTRA_CORNERS)
                if (p.raw != null && c != null) p.corners = c
                // Kirp "temel" sayfayi degistirdi: gosterilen hali yeniden uret
                runTask(getString(R.string.processing), { render(p) }) {
                    adapter.notifyItemChanged(i)
                    unsaved = true
                }
            }
        }

        binding.toolbar.inflateMenu(R.menu.main_menu)
        binding.toolbar.menu.findItem(R.id.action_auto)?.isChecked = autoEnhance
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_new -> { askNewScan(); true }
                R.id.action_text -> { openTextScreen(); true }
                R.id.action_gallery_add -> { addMode = pages.isNotEmpty(); galleryLauncher.launch("image/*"); true }
                R.id.action_google -> { startGoogleScan(pages.isNotEmpty()); true }
                R.id.action_auto -> { toggleAutoEnhance(item.isChecked.not()); item.isChecked = autoEnhance; true }
                else -> false
            }
        }

        binding.pageList.layoutManager = LinearLayoutManager(this)
        binding.pageList.adapter = adapter

        binding.btnScanBig.setOnClickListener { startScan(add = false) }
        binding.btnGalleryBig.setOnClickListener { addMode = false; galleryLauncher.launch("image/*") }
        binding.btnAddPage.setOnClickListener { startScan(add = true) }
        binding.btnSave.setOnClickListener { askSave() }
        binding.btnShare.setOnClickListener { sharePdf() }

        // Geri tusu: kaydedilmemis sayfa varsa once sor
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pages.isNotEmpty() && unsaved) {
                    confirm(
                        getString(R.string.exit_title), getString(R.string.exit_msg),
                        getString(R.string.exit_yes)
                    ) { finish() }
                } else {
                    finish()
                }
            }
        })

        if (savedInstanceState == null) outputDir.listFiles()?.forEach { it.delete() }
        updateUi()

        // vFlat gibi: uygulama acilir acilmaz kamera
        if (savedInstanceState == null) startScan(add = false)
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    // ----------------------------------------------------------------------
    // Cekim
    // ----------------------------------------------------------------------
    /** Kendi kameramiz (tam cozunurluk). */
    private fun startScan(add: Boolean) {
        addMode = add
        cameraLauncher.launch(Intent(this, CameraActivity::class.java))
    }

    /** Yedek: Google'in tarayicisi (dusuk cozunurluk ama otomatik cekim + temizlik). */
    private fun startGoogleScan(add: Boolean) {
        addMode = add
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(30)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { e ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.scan_failed_title)
                    .setMessage(getString(R.string.scan_failed_msg, e.localizedMessage ?: ""))
                    .setPositiveButton(R.string.pick_gallery) { _, _ ->
                        galleryLauncher.launch("image/*")
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
    }

    /** Kamera fotograflari -> kenari bul, duzlestir, sayfa yap. */
    private fun processPhotos(raws: List<File>) {
        val replace = !addMode
        addMode = false
        runTask(getString(R.string.processing), { buildPhotoPages(raws) }) { added ->
            showAddedPages(added, replace)
        }
    }

    /** Galeriden secilen fotograflar: kamera fotografi gibi islenir (kenar bulma dahil). */
    private fun importGallery(uris: List<Uri>) {
        val replace = !addMode
        addMode = false
        runTask(getString(R.string.processing), {
            val raws = ArrayList<File>()
            for (uri in uris) {
                val raw = File(outputDir, "galeri_${System.nanoTime()}.jpg")
                if (copyUri(uri, raw)) raws.add(raw) else raw.delete()
            }
            buildPhotoPages(raws)
        }) { added -> showAddedPages(added, replace) }
    }

    /** Arka planda calisir. Her fotograf icin: koseleri bul -> duzlestir -> sayfa. */
    private fun buildPhotoPages(raws: List<File>): List<Page> {
        val added = ArrayList<Page>()
        raws.forEachIndexed { i, raw ->
            if (raws.size > 1) progress(getString(R.string.progress, i + 1, raws.size))
            try {
                val full = ImageUtils.decodeScaled(raw, EDIT_DIM)
                // Emin degilse null -> tum fotograf (hic kirpma yok)
                val corners = EdgeDetector.detect(full) ?: ImageUtils.FULL_FRAME.copyOf()
                val page = ImageUtils.warp(full, corners)
                full.recycle()
                val p = newPage(raw, corners)
                ImageUtils.writeJpeg(page, p.base, 95)
                page.recycle()
                render(p)
                added.add(p)
            } catch (e: Throwable) {
                raw.delete()
            }
        }
        return added
    }

    /**
     * Arka planda calisir: "temel" sayfadan gosterilen sayfayi uretir.
     * Otomatik iyilestirme aciksa vFlat gorunumu (kagit belgede beyazlatma), kapaliysa aynen.
     */
    private fun render(p: Page) {
        if (p.enhance && autoEnhance) {
            val src = ImageUtils.decodeScaled(p.base, ENH_DIM)
            val out = Enhancer.auto(src)
            ImageUtils.writeJpeg(out, p.file, 92)
            if (out !== src) out.recycle()
            src.recycle()
        } else {
            p.base.copyTo(p.file, overwrite = true)
        }
    }

    /** Menuden "Otomatik iyilestirme" ac/kapat: tum sayfalar yeniden uretilir. */
    private fun toggleAutoEnhance(on: Boolean) {
        autoEnhance = on
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_AUTO, on).apply()
        if (pages.isEmpty()) return
        val snapshot = pages.toList()
        runTask(getString(R.string.processing), {
            snapshot.forEachIndexed { i, p ->
                if (snapshot.size > 1) progress(getString(R.string.progress, i + 1, snapshot.size))
                render(p)
            }
        }) {
            adapter.notifyDataSetChanged()
            unsaved = true
        }
    }

    /** Google tarayicisindan gelen (zaten kirpilmis) sayfalar: oldugu gibi. */
    private fun addScannedPages(uris: List<Uri>) {
        val replace = !addMode
        addMode = false
        runTask(getString(R.string.processing), {
            val added = ArrayList<Page>()
            for (uri in uris) {
                // Google zaten iyilestirdi: ikinci kez dokunma (enhance = false)
                val p = newPage(enhance = false)
                if (copyUri(uri, p.base)) {
                    ImageUtils.normalizeOrientation(p.base)
                    render(p)
                    added.add(p)
                } else {
                    p.delete()
                }
            }
            added
        }) { added -> showAddedPages(added, replace) }
    }

    private fun showAddedPages(added: List<Page>, replace: Boolean) {
        if (added.isEmpty()) {
            toast(getString(R.string.error_no_result))
            return
        }
        if (replace) {
            pages.forEach { it.delete() }
            pages.clear()
        }
        val firstNew = pages.size
        pages.addAll(added)
        unsaved = true
        adapter.notifyDataSetChanged()
        updateUi()
        binding.pageList.scrollToPosition(firstNew)
    }

    private fun newPage(raw: File? = null, corners: FloatArray? = null, enhance: Boolean = true): Page {
        val n = fileCounter.incrementAndGet()
        return Page(
            File(outputDir, "sayfa$n.jpg"), File(outputDir, "sayfa${n}_temel.jpg"),
            raw, corners, 0, enhance
        )
    }

    // ----------------------------------------------------------------------
    // Sayfa islemleri (her sayfanin kendi dugmeleri)
    // ----------------------------------------------------------------------
    private fun openCrop(index: Int) {
        val p = pages.getOrNull(index) ?: return
        cropIndex = index
        val intent = Intent(this, CropActivity::class.java)
        val raw = p.raw
        if (raw != null && raw.exists()) {
            // Orijinal fotograf + mevcut koseler: kesilen kisim geri alinabilir
            intent.putExtra(CropActivity.EXTRA_PATH, raw.absolutePath)
            intent.putExtra(CropActivity.EXTRA_OUT, p.base.absolutePath)
            intent.putExtra(CropActivity.EXTRA_CORNERS, p.corners ?: ImageUtils.FULL_FRAME)
            intent.putExtra(CropActivity.EXTRA_ROTATION, p.rotation)
        } else {
            intent.putExtra(CropActivity.EXTRA_PATH, p.base.absolutePath)
        }
        cropLauncher.launch(intent)
    }

    private fun rotatePage(index: Int) {
        val p = pages.getOrNull(index) ?: return
        runTask(getString(R.string.rotating), {
            val bmp = ImageUtils.decodeScaled(p.base, EDIT_DIM)
            val rot = ImageUtils.rotate90(bmp)
            ImageUtils.writeJpeg(rot, p.base, 95)
            if (rot !== bmp) rot.recycle()
            bmp.recycle()
            render(p)
        }) {
            p.rotation = (p.rotation + 1) % 4
            adapter.notifyItemChanged(index)
            unsaved = true
        }
    }

    /** Iki sayfalik kitap cekimini cilt cizgisinden ikiye ayirir (sol sayfa once). */
    private fun splitPage(index: Int) {
        val p = pages.getOrNull(index) ?: return
        runTask(getString(R.string.splitting), {
            val bmp = ImageUtils.decodeScaled(p.base, EDIT_DIM)
            val cut = ImageUtils.findGutter(bmp)
            val left = Bitmap.createBitmap(bmp, 0, 0, cut, bmp.height)
            val right = Bitmap.createBitmap(bmp, cut, 0, bmp.width - cut, bmp.height)
            val pl = newPage(enhance = p.enhance)
            val pr = newPage(enhance = p.enhance)
            ImageUtils.writeJpeg(left, pl.base, 95)
            ImageUtils.writeJpeg(right, pr.base, 95)
            left.recycle(); right.recycle(); bmp.recycle()
            render(pl)
            render(pr)
            Pair(pl, pr)
        }) { (pl, pr) ->
            if (index in pages.indices) pages.removeAt(index).delete()
            val at = index.coerceIn(0, pages.size)
            pages.add(at, pl)
            pages.add(at + 1, pr)
            unsaved = true
            adapter.notifyDataSetChanged()
            updateUi()
        }
    }

    private fun askDeletePage(index: Int) {
        if (index !in pages.indices) return
        confirm(
            getString(R.string.delete_title), getString(R.string.delete_msg, index + 1),
            getString(R.string.delete)
        ) {
            if (index in pages.indices) {
                pages.removeAt(index).delete()
                adapter.notifyDataSetChanged()
                updateUi()
            }
        }
    }

    // ----------------------------------------------------------------------
    // Ust menu
    // ----------------------------------------------------------------------
    private fun askNewScan() {
        if (pages.isNotEmpty() && unsaved) {
            confirm(
                getString(R.string.new_title), getString(R.string.new_msg),
                getString(R.string.new_yes)
            ) { startScan(add = false) }
        } else {
            startScan(add = false)
        }
    }

    private fun openTextScreen() {
        if (pages.isEmpty()) return
        val paths = ArrayList(pages.map { it.file.absolutePath })
        val intent = Intent(this, TextActivity::class.java)
        intent.putStringArrayListExtra(TextActivity.EXTRA_IMAGE_PATHS, paths)
        startActivity(intent)
    }

    // ----------------------------------------------------------------------
    // Kaydet (PDF / JPG / PNG) ve paylas
    // ----------------------------------------------------------------------
    /** Alttan acilan pencere: PDF / JPG / PNG buyuk simgeli kutucuklar (vFlat gibi). */
    private fun askSave() {
        if (pages.isEmpty()) return
        val sheet = BottomSheetDialog(this)
        val sb = SheetSaveBinding.inflate(layoutInflater)
        sb.iconPdf.setup("PDF", ContextCompat.getColor(this, R.color.fmt_pdf))
        sb.iconJpg.setup("JPG", ContextCompat.getColor(this, R.color.fmt_jpg))
        sb.iconPng.setup("PNG", ContextCompat.getColor(this, R.color.fmt_png))
        sb.tilePdf.setOnClickListener { sheet.dismiss(); savePdf() }
        sb.tileJpg.setOnClickListener { sheet.dismiss(); saveImages(png = false) }
        sb.tilePng.setOnClickListener { sheet.dismiss(); saveImages(png = true) }
        sheet.setContentView(sb.root)
        sheet.show()
    }

    private fun buildPdf(files: List<File>): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val out = File(outputDir, "EserLens_$stamp.pdf")
        outputDir.listFiles()?.filter { it.name.endsWith(".pdf") }?.forEach { it.delete() }
        if (!JpegPdf.build(files, out)) throw IllegalStateException("PDF uretilemedi")
        return out
    }

    private fun savePdf() {
        if (pages.isEmpty()) return
        if (needsLegacyPerm()) { pendingAction = PENDING_PDF; requestLegacyPerm(); return }
        val files = pages.map { it.file }
        runTask(getString(R.string.making_pdf), { savePdfToDownloads(buildPdf(files)) }) { err ->
            if (err == null) {
                unsaved = false
                toast(getString(R.string.saved_pdf))
            } else {
                toast(getString(R.string.save_failed) + " ($err)")
            }
        }
    }

    private fun sharePdf() {
        if (pages.isEmpty()) return
        val files = pages.map { it.file }
        runTask(getString(R.string.making_pdf), { buildPdf(files) }) { pdf ->
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", pdf)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        }
    }

    private fun saveImages(png: Boolean) {
        if (pages.isEmpty()) return
        if (needsLegacyPerm()) {
            pendingAction = if (png) PENDING_PNG else PENDING_JPG
            requestLegacyPerm()
            return
        }
        val files = pages.map { it.file }
        runTask(getString(R.string.saving), {
            var ok = 0
            var err: String? = null
            for ((i, f) in files.withIndex()) {
                if (files.size > 1) progress(getString(R.string.saving_progress, i + 1, files.size))
                val e = saveImageToGallery(f, i + 1, png)
                if (e == null) ok++ else err = e
            }
            Pair(ok, err)
        }) { (ok, err) ->
            if (ok > 0) {
                unsaved = false
                toast(getString(R.string.saved_images, ok))
            } else {
                toast(getString(R.string.save_failed) + " (" + (err ?: "?") + ")")
            }
        }
    }

    private fun stampName(): String =
        SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

    /** Sayfayi akisa yaz: JPG oldugu gibi kopyalanir, PNG kayipsiz donusturulur. */
    private fun writeImage(file: File, out: OutputStream, png: Boolean) {
        if (png) {
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
                ?: throw IllegalStateException("Sayfa okunamadi")
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
        } else {
            file.inputStream().use { it.copyTo(out) }
        }
    }

    /** Hata yoksa null, varsa sebep doner. */
    private fun saveImageToGallery(file: File, no: Int, png: Boolean): String? {
        return try {
            val ext = if (png) "png" else "jpg"
            val mime = if (png) "image/png" else "image/jpeg"
            val name = "EserLens_${stampName()}_$no.$ext"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/EserLens")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    ?: return "insert=null"
                contentResolver.openOutputStream(uri)?.use { out -> writeImage(file, out, png) }
                    ?: return "stream=null"
                cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, cv, null, null)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "EserLens"
                ).apply { mkdirs() }
                val dest = File(dir, name)
                dest.outputStream().use { out -> writeImage(file, out, png) }
                // Galeride hemen gorunsun
                MediaScannerConnection.scanFile(this, arrayOf(dest.absolutePath), arrayOf(mime), null)
            }
            null
        } catch (e: Throwable) {
            e.javaClass.simpleName + ": " + (e.message ?: "")
        }
    }

    /** Hata yoksa null, varsa sebep doner. */
    private fun savePdfToDownloads(pdf: File): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, pdf.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/EserLens")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: return "insert=null"
                contentResolver.openOutputStream(uri)?.use { out ->
                    pdf.inputStream().use { it.copyTo(out) }
                } ?: return "stream=null"
                cv.clear(); cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, cv, null, null)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "EserLens"
                ).apply { mkdirs() }
                pdf.inputStream().use { input -> File(dir, pdf.name).outputStream().use { input.copyTo(it) } }
            }
            null
        } catch (e: Throwable) {
            e.javaClass.simpleName + ": " + (e.message ?: "")
        }
    }

    private fun needsLegacyPerm(): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED

    private fun requestLegacyPerm() {
        ActivityCompat.requestPermissions(
            this, arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_STORAGE) return
        val action = pendingAction
        pendingAction = 0
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            when (action) {
                PENDING_PDF -> savePdf()
                PENDING_JPG -> saveImages(png = false)
                PENDING_PNG -> saveImages(png = true)
            }
        } else {
            toast(getString(R.string.permission_denied))
        }
    }

    // ----------------------------------------------------------------------
    // Sayfa listesi
    // ----------------------------------------------------------------------
    inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {

        override fun getItemCount(): Int = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val h = PageHolder(ItemPageBinding.inflate(layoutInflater, parent, false))
            h.b.btnCrop.setOnClickListener { pos(h)?.let { openCrop(it) } }
            h.b.btnRotate.setOnClickListener { pos(h)?.let { rotatePage(it) } }
            h.b.btnSplit.setOnClickListener { pos(h)?.let { splitPage(it) } }
            h.b.btnDelete.setOnClickListener { pos(h)?.let { askDeletePage(it) } }
            return h
        }

        override fun onBindViewHolder(h: PageHolder, position: Int) {
            val p = pages[position]
            val (w, ht) = ImageUtils.size(p.file)
            // "Sayfa 1 / 3 · 8,4 MP": sayfanin kac megapiksel oldugu (netlik gostergesi)
            h.b.pageLabel.text = getString(R.string.page_label, position + 1, pages.size) +
                "  ·  " + getString(R.string.page_mp, w.toLong() * ht / 1_000_000.0)
            // Genis cekim = buyuk ihtimalle iki sayfalik kitap -> "Ikiye Bol" goster
            h.b.btnSplit.visibility = if (w > ht * 1.15) View.VISIBLE else View.GONE
            val reqW = binding.pageList.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            h.b.pageImage.setImageBitmap(ImageUtils.decodePreview(p.file, reqW))
        }

        private fun pos(h: PageHolder): Int? =
            h.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION && it < pages.size }
    }

    // ----------------------------------------------------------------------
    // Yardimcilar
    // ----------------------------------------------------------------------
    private fun updateUi() {
        val has = pages.isNotEmpty()
        binding.emptyState.visibility = if (has) View.GONE else View.VISIBLE
        binding.pageList.visibility = if (has) View.VISIBLE else View.GONE
        binding.bottomPanel.visibility = if (has) View.VISIBLE else View.GONE
        binding.toolbar.subtitle = if (has) getString(R.string.page_count, pages.size) else null
        binding.toolbar.menu.setGroupVisible(R.id.group_pages, has)
    }

    /**
     * Agir isi arka planda yap; bu sirada ekran kilitli (yanlislikla baska tusa basilmaz).
     * Hata olursa uygulama cokmez, kisa bir uyari cikar.
     */
    private fun <T> runTask(message: String, work: () -> T, done: (T) -> Unit) {
        setBusy(true, message)
        worker.execute {
            val result = runCatching { work() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setBusy(false, "")
                result.onSuccess { done(it) }
                    .onFailure { toast(getString(R.string.processing_failed)) }
            }
        }
    }

    /** Arka plandan ilerleme yazisini guncelle ("Hazirlaniyor… 3 / 10"). */
    private fun progress(msg: String) {
        runOnUiThread { binding.procText.text = msg }
    }

    private fun setBusy(busy: Boolean, msg: String) {
        binding.procOverlay.visibility = if (busy) View.VISIBLE else View.GONE
        binding.procText.text = msg
    }

    private fun confirm(title: String, message: String, yes: String, action: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(yes) { _, _ -> action() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

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

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
