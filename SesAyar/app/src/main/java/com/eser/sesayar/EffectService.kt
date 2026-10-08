package com.eser.sesayar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class EffectService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val sessions = HashMap<Int, SessionEffects>()
    private var global: SessionEffects? = null
    private lateinit var audioManager: AudioManager

    private var profileKey = Settings.PROFILE_OTHER
    private var deviceName = ""

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            if (id <= 0) return
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> attach(id)
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> detach(id)
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refreshProfile()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refreshProfile()
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.post { refreshProfile() }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        startInForeground()

        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(sessionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(sessionReceiver, filter)
        }

        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)

        // Genel (tum sesler) efekti: bazi cihazlarda calisir, bazilarinda desteklenmez.
        val g = SessionEffects(0)
        if (g.create()) {
            global = g
        } else {
            g.release()
        }
        EffectState.globalOk = global != null
        EffectState.running = true

        refreshProfile()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        EffectState.running = false
        EffectState.sessionCount = 0
        EffectState.globalOk = false
        try { unregisterReceiver(sessionReceiver) } catch (e: Exception) { }
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        sessions.values.forEach { it.release() }
        sessions.clear()
        global?.release()
        global = null
        super.onDestroy()
    }

    private fun attach(id: Int) {
        if (sessions.containsKey(id)) return
        val fx = SessionEffects(id)
        if (fx.create()) {
            sessions[id] = fx
            fx.apply(Settings.profile(this, profileKey))
        } else {
            fx.release()
        }
        EffectState.sessionCount = sessions.size
    }

    private fun detach(id: Int) {
        sessions.remove(id)?.release()
        EffectState.sessionCount = sessions.size
    }

    /** Cikis cihazina gore profili sec ve tum efektlere uygula. */
    private fun refreshProfile() {
        val minorDevice = findMinorDevice()
        profileKey = when (Settings.mode(this)) {
            Settings.MODE_FORCE_MINOR -> Settings.PROFILE_MINOR
            Settings.MODE_FORCE_OTHER -> Settings.PROFILE_OTHER
            else -> if (minorDevice != null) Settings.PROFILE_MINOR else Settings.PROFILE_OTHER
        }
        deviceName = minorDevice ?: ""

        val p = Settings.profile(this, profileKey)
        global?.apply(p)
        sessions.values.forEach { it.apply(p) }

        EffectState.activeProfile = profileKey
        EffectState.deviceName = deviceName
        EffectState.sessionCount = sessions.size
        updateNotification()
    }

    /** Bagli cikis cihazlari arasinda adinda "minor" gecen Bluetooth cihazinin adini dondurur. */
    private fun findMinorDevice(): String? {
        val outs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (d in outs) {
            if (!isBluetooth(d.type)) continue
            val name = d.productName?.toString() ?: continue
            if (name.contains("minor", ignoreCase = true)) return name
        }
        return null
    }

    private fun isBluetooth(type: Int): Boolean {
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            return true
        }
        if (Build.VERSION.SDK_INT >= 31) {
            return type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
        return false
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ses Ayar", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotification("Başlatılıyor")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("Ses Ayar çalışıyor")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val label = if (profileKey == Settings.PROFILE_MINOR) "Minor IV profili" else "Diğer cihaz profili"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(label))
    }

    companion object {
        private const val CHANNEL_ID = "sesayar"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, EffectService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EffectService::class.java))
        }
    }
}
