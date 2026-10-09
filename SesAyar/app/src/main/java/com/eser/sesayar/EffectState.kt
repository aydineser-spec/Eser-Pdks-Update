package com.eser.sesayar

/** Servisin durumunu arayuze aktaran basit paylasilan durum. */
object EffectState {
    @Volatile var running = false
    @Volatile var activeProfile = Settings.PROFILE_OTHER
    @Volatile var deviceName = ""
    @Volatile var sessionCount = 0
    @Volatile var globalOk = false
    @Volatile var globalReport = ""
    @Volatile var btOutputs = "yok"
}
