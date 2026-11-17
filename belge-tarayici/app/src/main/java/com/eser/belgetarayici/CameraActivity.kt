package com.eser.belgetarayici

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
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
import java.io.File
import java.util.concurrent.Executors

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

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else fallback(R.string.camera_permission_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.btnShutter.setOnClickListener { takePhoto() }
        binding.btnDone.setOnClickListener { finishWithShots() }
        binding.btnFlash.setOnClickListener { toggleFlash() }
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
     * Canli kenar bulma: ~6 kare/sn, kucuk kare uzerinde EdgeDetector.
     * Bulunan belge mavi alanla gosterilir; 3 kare ust uste bulunamazsa alan gizlenir.
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
            val q = EdgeDetector.detect(upright)
            upright.recycle()
            if (q == null) missCount++ else missCount = 0
            val show = if (q == null && missCount < 3) return else q
            runOnUiThread {
                binding.overlay.imageAspect = aspect
                binding.overlay.setQuad(show)
            }
        } catch (e: Throwable) {
            // canli isaretleme olmazsa cekim yine calisir
        } finally {
            proxy.close()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
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
