package com.kophas.battlecity.game

import android.content.Context

/**
 * 이 기기에만 남는 설정. (계획서 §44.2 볼륨 설정)
 *
 * 방 규칙은 방장이 정해 START 로 내려오므로 저장할 것이 없다. 남는 것은 소리
 * 크기와 이름처럼 **기기를 쓰는 사람의 것**뿐이다.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun loadDevice(): DeviceSettings {
        val fallback = DeviceSettings()
        return DeviceSettings(
            bgmVolume = prefs.getInt(KEY_BGM, fallback.bgmVolume).coerceIn(0, DeviceSettings.MAX_VOLUME),
            sfxVolume = prefs.getInt(KEY_SFX, fallback.sfxVolume).coerceIn(0, DeviceSettings.MAX_VOLUME),
        )
    }

    fun saveDevice(settings: DeviceSettings) {
        prefs.edit()
            .putInt(KEY_BGM, settings.bgmVolume)
            .putInt(KEY_SFX, settings.sfxVolume)
            .apply()
    }

    /** 마지막으로 쓴 이름. 판마다 다시 치게 하지 않는다. */
    fun loadName(fallback: String): String = prefs.getString(KEY_NAME, null).orEmpty().ifBlank { fallback }

    fun saveName(name: String) {
        prefs.edit().putString(KEY_NAME, name).apply()
    }

    private companion object {
        const val NAME = "battlecity"
        const val KEY_BGM = "bgmVolume"
        const val KEY_SFX = "sfxVolume"
        const val KEY_NAME = "playerName"
    }
}
