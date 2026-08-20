package com.kophas.battlecity.audio

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * 실제로 소리를 내고 진동시킨다. (계획서 §34)
 *
 * 효과음은 `SoundPool`, 배경음은 `MediaPlayer` 다. 효과음은 짧고 여러 개가 한꺼번에
 * 겹치므로 미리 풀어 두고 즉시 내야 하고, 배경음은 몇십 초짜리라 통째로 메모리에
 * 올릴 이유가 없다. 둘의 성질이 달라 도구도 다르다.
 *
 * 어떤 소리를 언제 낼지는 [AudioDirector] 가 정한다. 여기서는 시키는 대로만 한다.
 */
class AndroidPlayback(
    context: Context,
    private val assets: AssetManager,
    private val settings: AudioSettings,
    maxStreams: Int = settings.maxStreams,
) : AudioDirector.Playback {

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(maxStreams)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val soundIds = HashMap<String, Int>()
    private val loopStreams = HashMap<String, Int>()
    private var music: MediaPlayer? = null

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }.getOrNull()?.takeIf { it.hasVibrator() }

    init {
        // 첫 발이 늦게 나지 않도록 미리 읽어 둔다. 총성이 반 박자 늦으면 티가 난다.
        for (clip in settings.allClipNames) {
            runCatching {
                assets.openFd("${settings.sfxPath}/$clip.ogg").use { descriptor ->
                    soundIds[clip] = pool.load(descriptor, 1)
                }
            }.onFailure { Log.w(TAG, "소리를 못 읽었다: $clip ($it)") }
        }
    }

    override fun play(clip: String, volume: Float) {
        val id = soundIds[clip] ?: return
        pool.play(id, volume, volume, PRIORITY, 0, 1f)
    }

    override fun startLoop(clip: String, volume: Float) {
        val id = soundIds[clip] ?: return
        val stream = pool.play(id, volume, volume, PRIORITY, LOOP_FOREVER, 1f)
        if (stream != 0) loopStreams[clip] = stream
    }

    override fun stopLoop(clip: String) {
        loopStreams.remove(clip)?.let { pool.stop(it) }
    }

    override fun playMusic(clip: String, volume: Float) {
        stopMusic()
        runCatching {
            assets.openFd("${settings.bgmPath}/$clip.ogg").use { descriptor ->
                music = MediaPlayer().apply {
                    setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                    isLooping = true
                    setVolume(volume, volume)
                    setOnPreparedListener { it.start() }
                    prepareAsync()
                }
            }
        }.onFailure { Log.w(TAG, "배경음을 못 읽었다: $clip ($it)") }
    }

    override fun stopMusic() {
        music?.let { player ->
            runCatching { player.stop() }
            player.release()
        }
        music = null
    }

    override fun vibrate(milliseconds: Long, amplitude: Int) {
        val device = vibrator ?: return
        runCatching {
            device.vibrate(
                VibrationEffect.createOneShot(milliseconds, amplitude.coerceIn(1, 255)),
            )
        }
    }

    fun release() {
        stopMusic()
        for (stream in loopStreams.values) pool.stop(stream)
        loopStreams.clear()
        pool.release()
    }

    private companion object {
        const val TAG = "BattleCity"
        const val PRIORITY = 1
        const val LOOP_FOREVER = -1
    }
}
