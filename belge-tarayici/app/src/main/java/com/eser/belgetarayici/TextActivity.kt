package com.eser.belgetarayici

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.eser.belgetarayici.databinding.ActivityTextBinding
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

class TextActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_PATHS = "image_paths"
    }

    private lateinit var binding: ActivityTextBinding

    // Ceviri hedef dilleri (gorunen ad -> ML Kit dil kodu)
    private val languages = listOf(
        "Türkçe" to TranslateLanguage.TURKISH,
        "İngilizce" to TranslateLanguage.ENGLISH,
        "Almanca" to TranslateLanguage.GERMAN,
        "Fransızca" to TranslateLanguage.FRENCH,
        "İspanyolca" to TranslateLanguage.SPANISH,
        "İtalyanca" to TranslateLanguage.ITALIAN,
        "Rusça" to TranslateLanguage.RUSSIAN,
        "Arapça" to TranslateLanguage.ARABIC,
        "Farsça" to TranslateLanguage.PERSIAN,
        "Hollandaca" to TranslateLanguage.DUTCH,
        "Portekizce" to TranslateLanguage.PORTUGUESE,
        "Yunanca" to TranslateLanguage.GREEK,
        "Bulgarca" to TranslateLanguage.BULGARIAN,
        "Rumence" to TranslateLanguage.ROMANIAN,
        "Lehçe" to TranslateLanguage.POLISH,
        "Ukraynaca" to TranslateLanguage.UKRAINIAN,
        "Çince" to TranslateLanguage.CHINESE,
        "Japonca" to TranslateLanguage.JAPANESE,
        "Korece" to TranslateLanguage.KOREAN,
        "Hintçe" to TranslateLanguage.HINDI,
        "Endonezce" to TranslateLanguage.INDONESIAN,
        "İsveççe" to TranslateLanguage.SWEDISH,
        "Çekçe" to TranslateLanguage.CZECH,
        "Macarca" to TranslateLanguage.HUNGARIAN
    ).filter { it.second.isNotEmpty() }

    private var recognizedText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTextBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        // Hedef dil listesi
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            languages.map { it.first }
        )
        binding.spinnerLang.adapter = adapter
        binding.spinnerLang.setSelection(1) // varsayilan: Ingilizce

        binding.btnCopyText.setOnClickListener {
            copyToClipboard(binding.txtRecognized.text.toString())
        }
        binding.btnTranslate.setOnClickListener { translate() }
        binding.btnCopyTranslation.setOnClickListener {
            copyToClipboard(binding.txtTranslation.text.toString())
        }

        val paths = intent.getStringArrayListExtra(EXTRA_IMAGE_PATHS) ?: arrayListOf()
        runOcr(paths)
    }

    // ----------------------------------------------------------------------
    // OCR: tum sayfalardaki yaziyi cikar
    // ----------------------------------------------------------------------
    private fun runOcr(paths: List<String>) {
        if (paths.isEmpty()) {
            binding.progress.visibility = View.GONE
            binding.txtRecognized.setText("")
            return
        }
        binding.progress.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.ocr_running)

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val builder = StringBuilder()

        fun processNext(index: Int) {
            if (index >= paths.size) {
                recognizedText = builder.toString().trim()
                binding.txtRecognized.setText(recognizedText)
                binding.progress.visibility = View.GONE
                binding.statusText.text =
                    if (recognizedText.isEmpty()) getString(R.string.ocr_empty)
                    else getString(R.string.ocr_done)
                return
            }
            val uri = Uri.fromFile(File(paths[index]))
            try {
                val image = InputImage.fromFilePath(this, uri)
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        if (result.text.isNotBlank()) {
                            if (builder.isNotEmpty()) builder.append("\n\n")
                            builder.append(result.text)
                        }
                        processNext(index + 1)
                    }
                    .addOnFailureListener {
                        processNext(index + 1)
                    }
            } catch (e: Exception) {
                processNext(index + 1)
            }
        }
        processNext(0)
    }

    // ----------------------------------------------------------------------
    // Ceviri: kaynak dili otomatik tani, hedef dile cevir
    // ----------------------------------------------------------------------
    private fun translate() {
        val source = binding.txtRecognized.text.toString().trim()
        if (source.isEmpty()) {
            toast(getString(R.string.no_text_to_translate))
            return
        }
        val targetCode = languages[binding.spinnerLang.selectedItemPosition].second

        binding.progress.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.detecting_language)

        LanguageIdentification.getClient().identifyLanguage(source)
            .addOnSuccessListener { langTag ->
                val srcCode = if (langTag == "und") TranslateLanguage.ENGLISH
                else TranslateLanguage.fromLanguageTag(langTag) ?: TranslateLanguage.ENGLISH
                doTranslate(source, srcCode, targetCode)
            }
            .addOnFailureListener {
                doTranslate(source, TranslateLanguage.ENGLISH, targetCode)
            }
    }

    private fun doTranslate(text: String, srcCode: String, targetCode: String) {
        if (srcCode == targetCode) {
            binding.txtTranslation.setText(text)
            binding.progress.visibility = View.GONE
            binding.statusText.text = getString(R.string.translate_done)
            return
        }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(srcCode)
            .setTargetLanguage(targetCode)
            .build()
        val translator = Translation.getClient(options)
        binding.statusText.text = getString(R.string.model_downloading)

        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { translated ->
                        binding.txtTranslation.setText(translated)
                        binding.progress.visibility = View.GONE
                        binding.statusText.text = getString(R.string.translate_done)
                        translator.close()
                    }
                    .addOnFailureListener { e ->
                        binding.progress.visibility = View.GONE
                        binding.statusText.text = getString(R.string.translate_failed)
                        toast(e.localizedMessage ?: "")
                        translator.close()
                    }
            }
            .addOnFailureListener { e ->
                binding.progress.visibility = View.GONE
                binding.statusText.text = getString(R.string.model_download_failed)
                toast(e.localizedMessage ?: "")
                translator.close()
            }
    }

    private fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Eser Lens", text))
        toast(getString(R.string.copied))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
