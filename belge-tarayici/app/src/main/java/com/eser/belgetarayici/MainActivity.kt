package com.eser.belgetarayici

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Eser Lens ana ekrani (vFlat tarzi sade akis):
 *  1) Uygulama acilinca kamera tarayici hemen acilir (belgeyi bulup kendisi ceker).
 *  2) Taranan sayfalar alt alta gorunur; her sayfanin altinda Kirp / Dondur / Sil
 *     (iki sayfalik kitap cekiminde ayrica "Ikiye Bol").
 *  3) Altta: Gorunum (Orijinal / Net Belge / Siyah-Beyaz) ve
 *     + Sayfa / PDF Kaydet / Paylas.
 * Yapay zeka YOK: her islem ongorulebilir, sayfayi bozmaz.
 */
class MainActivity : AppCompatActivity() {

    /** base: kirp/dondur uygulanmis sayfa; shown: secili gorunum uygulanmis hali. */
    class Page(val base: File, val shown: File) {
        fun delete() { base.delete(); shown.delete() }
    }

    class PageHolder(val b: ItemPageBinding) : RecyclerView.ViewHolder(b.root)

    companion object {
        private const val WORK_DIM = 2000   // isleme/PDF cozunurlugu (uzun kenar)
        private const val EDIT_DIM = 3000   // dondurme/bolme sirasinda ust sinir
        private const val REQ_STORAGE = 42
        private const val PENDING_PDF = 1
        private const val PENDING_IMAGES = 2
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var scannerLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var galleryLauncher: ActivityResultLauncher<String>
    private lateinit var cropLauncher: ActivityResultLauncher<Intent>

    private val pages = mutableListOf<Page>()
    private val adapter = PageAdapter()
    private val worker = Executors.newSingleThreadExecutor()
    private val fileCounter = AtomicInteger(0)

    @Volatile private var filter = DocEnhancer.Mode.COLOR   // varsayilan: Net Belge
    private var addMode = false      // true: yeni sayfalar sona eklenir, false: yenileri eskilerin yerine
    private var cropIndex = -1
    private var unsaved = false
    private var pendingAction = 0

    private val outputDir: File
        get() = File(filesDir, "output").apply { mkdirs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // OpenCV'yi arka planda hazirla
        worker.execute { OpenCvProcessor.ensureInit() }

        scannerLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                val result = GmsDocumentScanningResult.fromActivityResultIntent(res.data)
                val uris = result?.pages?.map { it.imageUri }.orEmpty()
                if (uris.isNotEmpty()) addPages(uris) else toast(getString(R.string.error_no_result))
            }
        }

        galleryLauncher = registerForActivityResult(
            ActivityResultContracts.GetMultipleContents()
        ) { uris ->
            if (!uris.isNullOrEmpty()) addPages(uris)
        }

        cropLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { res ->
            val i = cropIndex
            cropIndex = -1
            if (res.resultCode == Activity.RESULT_OK && i in pages.indices) {
                val p = pages[i]
                runTask(getString(R.string.processing), { renderPage(p) }) {
                    adapter.notifyItemChanged(i)
                    unsaved = true
                }
            }
        }

        binding.toolbar.inflateMenu(R.menu.main_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_new -> { askNewScan(); true }
                R.id.action_text -> { openTextScreen(); true }
                R.id.action_gallery -> { saveImages(); true }
                else -> false
            }
        }

        binding.pageList.layoutManager = LinearLayoutManager(this)
        binding.pageList.adapter = adapter

        binding.btnScanBig.setOnClickListener { startScan(add = false) }
        binding.btnAddPage.setOnClickListener { startScan(add = true) }
        binding.btnSavePdf.setOnClickListener { savePdf() }
        binding.btnShare.setOnClickListener { sharePdf() }
        binding.filterOriginal.setOnClickListener { setFilter(DocEnhancer.Mode.ORIGINAL) }
        binding.filterClean.setOnClickListener { setFilter(DocEnhancer.Mode.COLOR) }
        binding.filterBw.setOnClickListener { setFilter(DocEnhancer.Mode.BW) }

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
    // Tarama
    // ----------------------------------------------------------------------
    private fun startScan(add: Boolean) {
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
                // Kamera tarayici yoksa (Play Hizmetleri sorunu vb.) galeriden secmeyi oner
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

    /** Taranan/secilen gorselleri sayfa yap. Tarayicinin kirpmasina DOKUNULMAZ. */
    private fun addPages(uris: List<Uri>) {
        val replace = !addMode
        addMode = false
        runTask(getString(R.string.processing), {
            val added = ArrayList<Page>()
            uris.forEachIndexed { i, uri ->
                if (uris.size > 1) progress(getString(R.string.progress, i + 1, uris.size))
                val p = newPage()
                if (copyUri(uri, p.base)) {
                    renderPage(p)
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

    private fun newPage(): Page {
        val n = fileCounter.incrementAndGet()
        return Page(File(outputDir, "sayfa${n}_ham.jpg"), File(outputDir, "sayfa$n.jpg"))
    }

    /** Sayfanin gosterilen halini secili gorunume gore yeniden uret. */
    private fun renderPage(p: Page) {
        val mode = filter
        if (mode == DocEnhancer.Mode.ORIGINAL) {
            p.base.copyTo(p.shown, overwrite = true)
            return
        }
        val src = ImageUtils.decodeScaled(p.base, WORK_DIM)
        val out = OpenCvProcessor.process(src, mode)
        ImageUtils.writeJpeg(out, p.shown, 92)
        if (out !== src) out.recycle()
        src.recycle()
    }

    // ----------------------------------------------------------------------
    // Gorunum (filtre)
    // ----------------------------------------------------------------------
    private fun setFilter(mode: DocEnhancer.Mode) {
        if (mode == filter) return
        filter = mode
        highlightFilter()
        if (pages.isEmpty()) return
        val snapshot = pages.toList()
        runTask(getString(R.string.processing), {
            snapshot.forEachIndexed { i, p ->
                if (snapshot.size > 1) progress(getString(R.string.progress, i + 1, snapshot.size))
                renderPage(p)
            }
        }) {
            adapter.notifyDataSetChanged()
            unsaved = true
        }
    }

    private fun highlightFilter() {
        val primary = ContextCompat.getColor(this, R.color.primary)
        val dark = ContextCompat.getColor(this, R.color.primary_dark)
        listOf(
            DocEnhancer.Mode.ORIGINAL to binding.filterOriginal,
            DocEnhancer.Mode.COLOR to binding.filterClean,
            DocEnhancer.Mode.BW to binding.filterBw
        ).forEach { (mode, btn) -> styleChoice(btn, mode == filter, primary, dark) }
    }

    private fun styleChoice(btn: MaterialButton, selected: Boolean, primary: Int, dark: Int) {
        btn.backgroundTintList = ColorStateList.valueOf(if (selected) primary else Color.TRANSPARENT)
        btn.setTextColor(if (selected) Color.WHITE else dark)
    }

    // ----------------------------------------------------------------------
    // Sayfa islemleri (her sayfanin kendi dugmeleri)
    // ----------------------------------------------------------------------
    private fun openCrop(index: Int) {
        val p = pages.getOrNull(index) ?: return
        cropIndex = index
        val intent = Intent(this, CropActivity::class.java)
        intent.putExtra(CropActivity.EXTRA_PATH, p.base.absolutePath)
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
            renderPage(p)
        }) {
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
            val pl = newPage()
            val pr = newPage()
            ImageUtils.writeJpeg(left, pl.base, 95)
            ImageUtils.writeJpeg(right, pr.base, 95)
            left.recycle(); right.recycle(); bmp.recycle()
            renderPage(pl)
            renderPage(pr)
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
        val paths = ArrayList(pages.map { it.shown.absolutePath })
        val intent = Intent(this, TextActivity::class.java)
        intent.putStringArrayListExtra(TextActivity.EXTRA_IMAGE_PATHS, paths)
        startActivity(intent)
    }

    // ----------------------------------------------------------------------
    // PDF / kaydet / paylas
    // ----------------------------------------------------------------------
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
        val files = pages.map { it.shown }
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
        val files = pages.map { it.shown }
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

    private fun saveImages() {
        if (pages.isEmpty()) return
        if (needsLegacyPerm()) { pendingAction = PENDING_IMAGES; requestLegacyPerm(); return }
        val files = pages.map { it.shown }
        runTask(getString(R.string.saving), {
            var ok = 0
            var err: String? = null
            files.forEachIndexed { i, f ->
                val e = saveImageToGallery(f, i + 1)
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

    /** Hata yoksa null, varsa sebep doner. */
    private fun saveImageToGallery(file: File, no: Int): String? {
        return try {
            val name = "EserLens_${stampName()}_$no.jpg"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/EserLens")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    ?: return "insert=null"
                contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: return "stream=null"
                cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, cv, null, null)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "EserLens"
                ).apply { mkdirs() }
                val dest = File(dir, name)
                file.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
                // Galeride hemen gorunsun
                MediaScannerConnection.scanFile(this, arrayOf(dest.absolutePath), arrayOf("image/jpeg"), null)
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
                PENDING_IMAGES -> saveImages()
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
            h.b.pageLabel.text = getString(R.string.page_label, position + 1, pages.size)
            val (w, ht) = ImageUtils.size(p.shown)
            // Genis cekim = buyuk ihtimalle iki sayfalik kitap -> "Ikiye Bol" goster
            h.b.btnSplit.visibility = if (w > ht * 1.15) View.VISIBLE else View.GONE
            val reqW = binding.pageList.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            h.b.pageImage.setImageBitmap(ImageUtils.decodePreview(p.shown, reqW))
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
        highlightFilter()
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
