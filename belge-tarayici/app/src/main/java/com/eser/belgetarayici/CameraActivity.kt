package com.eser.belgetarayici

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Patterns
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.eser.belgetarayici.databinding.ActivityCameraBinding
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Eser Lens'in kendi kamerasi (vFlat gibi): telefonun TAM cozunurlugunde ceker.
 * (Google'in tarayicisi sayfalari ~1 megapiksele dusuruyordu: kucuk yazi bulaniklasiyordu.)
 *  - Art arda istedigin kadar sayfa cek, "Bitti" ile don
 *  - Ekrana dokun: o noktaya netleme
 *  - Kenar bulma ve duzlestirme cekimden SONRA ana ekranda yapilir
 * Kamera acilamazsa / izin verilmezse Google'in tarayicisina gecilir (EXTRA_FALLBACK).
 */
class CameraActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SHOTS = "shots"
        const val EXTRA_FALLBACK = "fallback"
    }

    private lateinit var binding: ActivityCameraBinding
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private val shots = ArrayList<String>()
    private var flashOn = false
    private var capturing = false
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var lastAnalysis = 0L
    @Volatile private var missCount = 0

    // QR kod tanima (ML Kit, cihazda)
    private var qrScanner: BarcodeScanner? = null
    @Volatile private var lastQrCheck = 0L
    private var lastQrSeen = 0L
    private var currentQr: String? = null
    private var dismissedQr: String? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else fallback(R.string.camera_permission_denied)
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (!uris.isNullOrEmpty()) importFromGallery(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        qrScanner = try {
            BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            )
        } catch (e: Throwable) {
            null
        }

        binding.btnShutter.setOnClickListener { takePhoto() }
        binding.btnDone.setOnClickListener { finishWithShots() }
        binding.btnFlash.setOnClickListener { toggleFlash() }
        binding.btnGallery.setOnClickListener { galleryLauncher.launch("image/*") }
        binding.btnQrOpen.setOnClickListener { openQr() }
        binding.btnQrCopy.setOnClickListener { copyQr() }
        binding.btnQrShare.setOnClickListener { shareQr() }
        binding.btnQrClose.setOnClickListener { closeQr() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finishWithShots() }
        })
        updateDone()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                // Onizleme ve cekim ayni 4:3 oranda: ekranda gorulen = cekilen
                val previewSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
                // Cekim: ~12 MP'ye en yakin (yazi keskin, cekim hizli, dosya makul)
                val captureSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(4000, 3000),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
                val preview = Preview.Builder()
                    .setResolutionSelector(previewSelector)
                    .build()
                preview.setSurfaceProvider(binding.previewView.surfaceProvider)
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(captureSelector)
                    .setJpegQuality(95)
                    .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                    .build()
                // Canli kenar bulma icin kucuk (~640x480) kareler
                val analysisSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(analysisSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy -> analyzeFrame(proxy) }

                provider.unbindAll()
                camera = try {
                    provider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis
                    )
                } catch (e: Throwable) {
                    // Bazi telefonlar ucunu birden desteklemez: mavi alan olmadan devam
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture
                    )
                }
                imageCapture = capture
                showCaptureResolution(capture)
                setupTapToFocus()
            } catch (e: Throwable) {
                fallback(R.string.camera_failed)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Ust yaziya cekim cozunurlugunu ekle (ornek: "Cekim: 4000×3000 (12 MP)"). */
    private fun showCaptureResolution(capture: ImageCapture) {
        val res = try { capture.resolutionInfo?.resolution } catch (e: Throwable) { null } ?: return
        val mp = res.width.toLong() * res.height / 1_000_000.0
        binding.hint.text = getString(R.string.camera_hint) + "\n" +
            getString(R.string.camera_resolution, res.width, res.height, mp)
    }

    /**
     * Canli inceleme (~6 kare/sn, kucuk kare):
     *  - QR kod (~2.5 kez/sn): bulunursa ustte QR karti
     *  - Kenar bulma: belge mavi alanla gosterilir; 3 kare ust uste bulunamazsa gizlenir
     */
    private fun analyzeFrame(proxy: ImageProxy) {
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastAnalysis < 150) return
            lastAnalysis = now
            val bmp = proxy.toBitmap()
            val upright = ImageUtils.rotateQuarters(bmp, proxy.imageInfo.rotationDegrees / 90)
            if (upright !== bmp) bmp.recycle()
            val aspect = upright.width.toFloat() / upright.height

            if (now - lastQrCheck >= 400) {
                lastQrCheck = now
                val qr = detectQr(upright)
                if (qr != null) runOnUiThread { showQr(qr) }
            }

            val q = EdgeDetector.detect(upright)
            upright.recycle()
            if (q == null) missCount++ else missCount = 0
            if (q != null || missCount >= 3) {
                runOnUiThread {
                    binding.overlay.imageAspect = aspect
                    binding.overlay.setQuad(q)
                }
            }
        } catch (e: Throwable) {
            // canli inceleme olmazsa cekim yine calisir
        } finally {
            proxy.close()
        }
    }

    /** Karede QR kod varsa icerigi (ML Kit, cihazda; internet gerekmez). */
    private fun detectQr(bmp: Bitmap): String? {
        val s = qrScanner ?: return null
        return try {
            val codes = Tasks.await(s.process(InputImage.fromBitmap(bmp, 0)), 1500, TimeUnit.MILLISECONDS)
            codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
        } catch (e: Throwable) {
            null
        }
    }

    // ----------------------------------------------------------------------
    // QR karti
    // ----------------------------------------------------------------------
    private fun showQr(value: String) {
        lastQrSeen = SystemClock.uptimeMillis()
        if (value == dismissedQr) return                       // kapatildiysa ayni kod tekrar acilmasin
        if (value == currentQr && binding.qrCard.visibility == View.VISIBLE) return
        currentQr = value
        binding.qrText.text = value
        binding.btnQrOpen.visibility = if (looksLikeUrl(value)) View.VISIBLE else View.GONE
        binding.qrCard.visibility = View.VISIBLE
        binding.qrCard.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        binding.qrCard.removeCallbacks(hideQrIfStale)
        binding.qrCard.postDelayed(hideQrIfStale, 1000)
    }

    /** QR 6 sn gorulmezse kart kendiliginden kapanir. */
    private val hideQrIfStale: Runnable = object : Runnable {
        override fun run() {
            if (SystemClock.uptimeMillis() - lastQrSeen > 6000) {
                binding.qrCard.visibility = View.GONE
                currentQr = null
            } else {
                binding.qrCard.postDelayed(this, 1000)
            }
        }
    }

    private fun looksLikeUrl(v: String): Boolean {
        val t = v.trim()
        return t.startsWith("http://", true) || t.startsWith("https://", true) ||
            (!t.contains(' ') && Patterns.WEB_URL.matcher(t).matches())
    }

    private fun openQr() {
        val v = currentQr?.trim() ?: return
        val url = if (v.startsWith("http://", true) || v.startsWith("https://", true)) v else "https://$v"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Throwable) {
            Toast.makeText(this, getString(R.string.qr_open_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyQr() {
        val v = currentQr ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("QR", v))
        Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    private fun shareQr() {
        val v = currentQr ?: return
        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, v) }
        startActivity(Intent.createChooser(i, getString(R.string.share)))
    }

    private fun closeQr() {
        dismissedQr = currentQr
        currentQr = null
        binding.qrCard.visibility = View.GONE
    }

    // ----------------------------------------------------------------------
    // Galeri: secilen fotograflar kamera cekimi gibi islenir (kenar bulma dahil),
    // sonra ana ekranda "Kirp" ile istedigin gibi duzeltilir
    // ----------------------------------------------------------------------
    private fun importFromGallery(uris: List<Uri>) {
        binding.btnGallery.isEnabled = false
        Thread {
            val dir = File(filesDir, "output").apply { mkdirs() }
            val added = ArrayList<String>()
            for (u in uris) {
                val f = File(dir, "galeri_${System.nanoTime()}.jpg")
                try {
                    contentResolver.openInputStream(u)?.use { input ->
                        f.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (f.exists() && f.length() > 0) added.add(f.absolutePath) else f.delete()
                } catch (e: Throwable) {
                    f.delete()
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.btnGallery.isEnabled = true
                shots.addAll(added)
                updateDone()
                if (added.isNotEmpty()) finishWithShots()   // hemen sayfalara don
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        try { qrScanner?.close() } catch (e: Throwable) { }
    }

    /** Ekrana dokununca o noktaya netle (yakin cekimde yazi keskin olsun). */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapToFocus() {
        binding.previewView.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                try {
                    val point = binding.previewView.meteringPointFactory.createPoint(ev.x, ev.y)
                    camera?.cameraControl?.startFocusAndMetering(
                        FocusMeteringAction.Builder(point).build()
                    )
                } catch (e: Throwable) {
                    // netleme desteklenmiyorsa sessizce gec
                }
                v.performClick()
            }
            true
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (capturing) return
        capturing = true
        binding.btnShutter.isEnabled = false
        val dir = File(filesDir, "output").apply { mkdirs() }
        val file = File(dir, "cekim_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        flashEffect()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    shots.add(file.absolutePath)
                    capturing = false
                    binding.btnShutter.isEnabled = true
                    updateDone()
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    binding.btnShutter.isEnabled = true
                    file.delete()
                    Toast.makeText(
                        this@CameraActivity, getString(R.string.capture_failed), Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    private fun flashEffect() {
        val v = binding.shutterFlash
        v.animate().cancel()
        v.alpha = 0.7f
        v.visibility = View.VISIBLE
        v.animate().alpha(0f).setDuration(180).withEndAction { v.visibility = View.GONE }.start()
    }

    private fun toggleFlash() {
        flashOn = !flashOn
        imageCapture?.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        binding.btnFlash.text = getString(if (flashOn) R.string.flash_on else R.string.flash_off)
    }

    private fun updateDone() {
        binding.btnDone.text = if (shots.isEmpty()) getString(R.string.close)
        else getString(R.string.done_count, shots.size)
    }

    private fun finishWithShots() {
        if (capturing) return   // son cekim kaydedilirken bekle
        if (shots.isEmpty()) {
            setResult(Activity.RESULT_CANCELED)
        } else {
            setResult(Activity.RESULT_OK, Intent().putStringArrayListExtra(EXTRA_SHOTS, shots))
        }
        finish()
    }

    /** Kamera kullanilamiyor: Google'in tarayicisina gecilsin. */
    private fun fallback(msgRes: Int) {
        Toast.makeText(this, getString(msgRes), Toast.LENGTH_LONG).show()
        if (shots.isNotEmpty()) {
            setResult(Activity.RESULT_OK, Intent().putStringArrayListExtra(EXTRA_SHOTS, shots))
        } else {
            setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_FALLBACK, true))
        }
        finish()
    }
}
