package com.eser.sesayar

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var bassLabel: TextView
    private lateinit var trebleLabel: TextView
    private lateinit var boostLabel: TextView
    private lateinit var bassBar: SeekBar
    private lateinit var trebleBar: SeekBar
    private lateinit var boostBar: SeekBar
    private lateinit var presetSpinner: Spinner
    private lateinit var toneButton: Button
    private lateinit var playerText: TextView
    private lateinit var diagText: TextView
    private lateinit var player: TestPlayer

    private var editing = Settings.PROFILE_MINOR
    private var loading = false

    private val statusTick = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = TestPlayer(this)
        buildUi()
        player.onStopped = {
            toneButton.text = "Test sesini çal"
            updatePlayerText()
        }
        loadProfile()
        requestPermissionsIfNeeded()
        if (Settings.enabled(this) && !EffectState.running) {
            startServiceSafely()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(statusTick)
    }

    override fun onPause() {
        handler.removeCallbacks(statusTick)
        super.onPause()
    }

    override fun onDestroy() {
        player.stop()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQ_PICK_AUDIO && resultCode == RESULT_OK && uri != null) {
            player.playUri(uri, currentProfile()) { msg -> playerText.text = msg }
            toneButton.text = "Test sesini çal"
            updatePlayerText()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(32))
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        root.addView(text("Ses Ayar", 26f, bold = true))
        root.addView(text("Bas, tiz ve ses yükseltme. Marshall uygulamasındaki ayarlara dokunmaz.", 14f))

        val power = Switch(this).apply {
            text = "Ses Ayar çalışsın"
            textSize = 18f
            isChecked = Settings.enabled(this@MainActivity)
            setPadding(0, dp(16), 0, dp(8))
            setOnCheckedChangeListener { _, on ->
                Settings.setEnabled(this@MainActivity, on)
                if (on) startServiceSafely() else EffectService.stop(this@MainActivity)
            }
        }
        root.addView(power)

        statusText = text("", 14f)
        root.addView(statusText)

        root.addView(heading("Profil seçimi"))
        val modeSpinner = Spinner(this)
        modeSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf(
                "Otomatik (Minor IV bağlıysa Minor profili)",
                "Her zaman Minor IV profili",
                "Her zaman Diğer cihaz profili",
            )
        )
        modeSpinner.setSelection(Settings.mode(this))
        modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (Settings.mode(this@MainActivity) != pos) Settings.setMode(this@MainActivity, pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        root.addView(modeSpinner)

        root.addView(heading("Düzenlediğin profil"))
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbMinor = RadioButton(this).apply { text = "Minor IV"; id = View.generateViewId() }
        val rbOther = RadioButton(this).apply { text = "Diğer cihazlar"; id = View.generateViewId() }
        group.addView(rbMinor)
        group.addView(rbOther)
        group.check(rbMinor.id)
        group.setOnCheckedChangeListener { _, checked ->
            editing = if (checked == rbMinor.id) Settings.PROFILE_MINOR else Settings.PROFILE_OTHER
            loadProfile()
        }
        root.addView(group)

        root.addView(heading("Hazır ayar"))
        val names = listOf("Seç…") + Settings.presets.keys
        presetSpinner = Spinner(this)
        presetSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == 0 || loading) return
                val preset = Settings.presets.values.elementAt(pos - 1)
                Settings.saveProfile(this@MainActivity, editing, preset)
                loadProfile()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        root.addView(presetSpinner)

        bassLabel = text("", 16f).also { it.setPadding(0, dp(20), 0, 0) }
        bassBar = bar(100)
        trebleLabel = text("", 16f).also { it.setPadding(0, dp(12), 0, 0) }
        trebleBar = bar(100)
        boostLabel = text("", 16f).also { it.setPadding(0, dp(12), 0, 0) }
        boostBar = bar(SessionEffects.MAX_BOOST_DB)
        root.addView(bassLabel); root.addView(bassBar)
        root.addView(trebleLabel); root.addView(trebleBar)
        root.addView(boostLabel); root.addView(boostBar)

        root.addView(heading("Ses Ayar çalar (yazılımsal bas / tiz / ses artırma)"))
        root.addView(
            text(
                "Bu çalar sesi kendisi işler, telefonun ses efektlerine bağlı değildir. Kulaklıkta da çalışır. " +
                    "Bir müzik dosyası seç ve çalarken kaydırıcıları oynat. Önce düşük sesle başla.",
                13f
            )
        )
        toneButton = Button(this).apply {
            text = "Test sesini çal"
            setOnClickListener {
                if (player.isPlaying) {
                    player.stop()
                    text = "Test sesini çal"
                } else {
                    player.playTone(currentProfile())
                    text = "Durdur"
                }
                updatePlayerText()
            }
        }
        val fileButton = Button(this).apply {
            text = "Müzik dosyası seç ve çal"
            setOnClickListener {
                toneButton.text = "Test sesini çal"
                player.stop()
                val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "audio/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivityForResult(pick, REQ_PICK_AUDIO)
            }
        }
        val stopButton = Button(this).apply {
            text = "Çaları durdur"
            setOnClickListener {
                player.stop()
                toneButton.text = "Test sesini çal"
                updatePlayerText()
            }
        }
        playerText = text("", 13f)
        root.addView(toneButton)
        root.addView(fileButton)
        root.addView(stopButton)
        root.addView(playerText)

        root.addView(heading("Tanı (sorun olursa bunun ekran görüntüsünü gönder)"))
        diagText = text("", 11f)
        root.addView(diagText)

        root.addView(heading("Bilmen gerekenler"))
        root.addView(
            text(
                "• Efekt, ses oturumunu bildiren müzik uygulamalarında (Spotify, YouTube Music, Samsung Music vb.) çalışır. " +
                    "Önce Ses Ayar'ı aç, sonra müziği başlat veya durdurup tekrar oynat.\n" +
                    "• Telefonun Dolby Atmos / Ekolayzer / Adapt sound ayarları ve Marshall EQ'su da açıksa üst üste biner. " +
                    "Birini kapatıp dene.\n" +
                    "• Yüksek ses ve ses yükseltme kulağa zarar verebilir ve sesi bozabilir. Düşük başla, yavaş artır.",
                13f
            )
        )
    }

    private fun bar(max: Int) = SeekBar(this).apply {
        this.max = max
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
                if (fromUser && !loading) saveFromBars()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun loadProfile() {
        loading = true
        val p = Settings.profile(this, editing)
        bassBar.progress = p.bass
        trebleBar.progress = p.treble
        boostBar.progress = p.boostDb
        presetSpinner.setSelection(0)
        updateLabels()
        player.apply(p)
        loading = false
    }

    private fun currentProfile() = Profile(bassBar.progress, trebleBar.progress, boostBar.progress)

    private fun saveFromBars() {
        val p = currentProfile()
        Settings.saveProfile(this, editing, p)
        player.apply(p)
    }

    private fun updatePlayerText() {
        playerText.text = when {
            !player.isPlaying -> ""
            else -> "Çalıyor. Bas, tiz ve ses kaydırıcılarını oynat, ses hemen değişmeli."
        }
    }

    private fun updateLabels() {
        bassLabel.text = "Bas: ${bassBar.progress}  (${dbText(bassBar.progress)})"
        trebleLabel.text = "Tiz: ${trebleBar.progress}  (${dbText(trebleBar.progress)})"
        boostLabel.text = "Ses yükseltme: +${boostBar.progress} dB"
    }

    private fun dbText(v: Int): String {
        val db = (v - 50) / 50f * SessionEffects.MAX_EQ_DB
        return String.format("%+.0f dB", db)
    }

    private fun updateStatus() {
        val globalDiag = if (EffectState.running) {
            "GENEL EFEKT:\n" + EffectState.globalReport
        } else {
            "GENEL EFEKT: servis kapalı"
        }
        diagText.text = "YAZILIM ÇALAR:\n" + player.report() + "\n\n" + globalDiag
        statusText.text = if (!EffectState.running) {
            "Durum: kapalı"
        } else {
            val prof = if (EffectState.activeProfile == Settings.PROFILE_MINOR) "Minor IV" else "Diğer cihaz"
            val dev = if (EffectState.deviceName.isNotEmpty()) " (${EffectState.deviceName})" else ""
            val global = if (EffectState.globalOk) "açık" else "bu telefonda desteklenmiyor"
            "Durum: çalışıyor\nAktif profil: $prof$dev\nBluetooth çıkışları: ${EffectState.btOutputs}\n" +
                "Bağlanan müzik uygulaması: ${EffectState.sessionCount}\nGenel efekt: $global"
        }
    }

    private fun startServiceSafely() {
        try {
            EffectService.start(this)
        } catch (e: Exception) {
            statusText.text = "Servis başlatılamadı: ${e.message}"
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        val missing = needed.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private fun text(t: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun heading(t: String) = text(t, 15f, bold = true).also { it.setPadding(0, dp(20), 0, dp(4)) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_PICK_AUDIO = 2
    }
}
