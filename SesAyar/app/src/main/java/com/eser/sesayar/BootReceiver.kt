package com.eser.sesayar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Telefon acilinca, kullanici Ses Ayar'i acik birakmissa servisi tekrar baslatir. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Settings.enabled(context)) return
        try {
            EffectService.start(context)
        } catch (e: Exception) {
        }
    }
}
