package com.kophas.battlecity.audio

/**
 * 게임에서 일어난 일을 소리와 진동으로 옮긴다. (계획서 §34)
 *
 * 안드로이드를 모른다. 실제로 소리를 내는 것은 [Playback] 이 하고, 여기서는
 * **무엇을 언제 낼지**만 정한다. 그래야 기기 없이 "벽돌 열 장이 한꺼번에 부서져도
 * 소리가 열 번 겹치지 않는가" 같은 것을 확인할 수 있다.
 */
class AudioDirector(
    private val settings: AudioSettings,
    private val playback: Playback,
    private val clock: () -> Long,
    /**
     * 배경음을 틀지. 저사양 기기에서는 끈다. (계획서 §24)
     *
     * 배경음은 몇십 초짜리를 계속 디코드하므로 효과음보다 훨씬 무겁다. 줄여야 할 때
     * 가장 먼저 내려놓을 것이다. 효과음은 무엇이 일어났는지 알려 주므로 남긴다.
     */
    private val musicEnabled: Boolean = true,
) {
    /** 실제로 소리를 내는 쪽. 테스트에서는 무엇을 시켰는지 적어 두기만 한다. */
    interface Playback {
        fun play(clip: String, volume: Float)
        fun startLoop(clip: String, volume: Float)
        fun stopLoop(clip: String)
        fun playMusic(clip: String, volume: Float)
        fun stopMusic()
        fun vibrate(milliseconds: Long, amplitude: Int)
    }

    object Event {
        const val TANK_FIRE = "TANK_FIRE"
        const val SPECIAL_PIERCING = "SPECIAL_PIERCING"
        const val SPECIAL_SHIELD = "SPECIAL_SHIELD"
        const val SPECIAL_DASH = "SPECIAL_DASH"
        const val BULLET_HIT_BRICK = "BULLET_HIT_BRICK"
        const val BULLET_HIT_STEEL = "BULLET_HIT_STEEL"
        const val BRICK_DESTROY = "BRICK_DESTROY"
        const val TANK_EXPLOSION = "TANK_EXPLOSION"
        const val PLAYER_DEATH = "PLAYER_DEATH"
        const val ENEMY_SPAWN = "ENEMY_SPAWN"
        const val BASE_DESTROY = "BASE_DESTROY"
        const val VICTORY = "VICTORY"
        const val GAME_OVER = "GAME_OVER"
        const val UI_READY = "UI_READY"
        const val COUNTDOWN_TICK = "COUNTDOWN_TICK"
        const val COUNTDOWN_GO = "COUNTDOWN_GO"
        const val NETWORK_LOST = "NETWORK_LOST"
    }

    object Loop {
        const val TANK_MOVE = "TANK_MOVE"
        const val BASE_WARNING = "BASE_WARNING"
    }

    object Haptic {
        const val TANK_FIRE = "TANK_FIRE"
        const val SPECIAL = "SPECIAL"
        const val TAKE_DAMAGE = "TAKE_DAMAGE"
        const val PLAYER_DEATH = "PLAYER_DEATH"
        const val BASE_DESTROY = "BASE_DESTROY"
        const val UI_TAP = "UI_TAP"
    }

    object Track {
        const val LOBBY = "lobby"
        const val BATTLE = "battle"
        const val FINAL_WAVE = "finalWave"
        const val MENU = "menu"
    }

    var muted: Boolean = false
        private set

    private val lastPlayedMs = HashMap<String, Long>()
    private val activeLoops = HashSet<String>()
    private var currentTrack: String? = null

    fun setMuted(value: Boolean) {
        muted = value
        if (!value) return
        stopAllLoops()
        playback.stopMusic()
        currentTrack = null
    }

    /**
     * 한 번 나고 마는 소리.
     *
     * 같은 소리가 너무 촘촘히 겹치면 시끄럽기만 하고 무슨 일이 났는지 알 수 없다.
     * 벽 한 줄이 한꺼번에 무너지면 파괴음이 열 번 겹친다. 그래서 소리마다
     * 최소 간격을 두고 그 안에 들어온 것은 버린다.
     */
    fun play(event: String) {
        if (muted) return
        val clip = settings.event(event) ?: return
        val now = clock()
        val last = lastPlayedMs[event]
        if (last != null && now - last < clip.minIntervalMs) return
        lastPlayedMs[event] = now
        playback.play(clip.name, clip.volume)
    }

    /** 조건이 이어지는 동안 도는 소리. 이미 돌고 있으면 다시 시작하지 않는다. */
    fun setLoop(id: String, active: Boolean) {
        val clip = settings.loop(id) ?: return
        val running = id in activeLoops
        if (active && !running && !muted) {
            activeLoops += id
            playback.startLoop(clip.name, clip.volume)
        } else if (!active && running) {
            activeLoops -= id
            playback.stopLoop(clip.name)
        }
    }

    fun stopAllLoops() {
        for (id in activeLoops.toList()) setLoop(id, false)
    }

    fun vibrate(id: String) {
        if (muted) return
        val haptic = settings.haptic(id) ?: return
        if (haptic.milliseconds <= 0) return
        playback.vibrate(haptic.milliseconds, haptic.amplitude)
    }

    /** 소리와 진동을 함께 내는 사건. 쏘는 순간처럼 둘이 같이 가야 하는 것들이다. */
    fun playWithHaptic(event: String, haptic: String) {
        play(event)
        vibrate(haptic)
    }

    // --- 배경음 -----------------------------------------------------------

    /**
     * 판이 얼마나 남았는지에 따라 곡을 고른다.
     *
     * 남은 COM 이 얼마 안 되면 마지막 곡으로 넘어간다. 같은 곡을 계속 틀면
     * 판이 끝나 가는지 알 수 없다.
     */
    fun updateBattleMusic(enemiesRemaining: Int, totalEnemies: Int) {
        if (totalEnemies <= 0) return
        val ratio = enemiesRemaining.toFloat() / totalEnemies
        setTrack(if (ratio <= settings.finalWaveRatio) Track.FINAL_WAVE else Track.BATTLE)
    }

    fun setTrack(id: String?) {
        if (muted || !musicEnabled) return
        if (id == currentTrack) return
        currentTrack = id
        if (id == null) {
            playback.stopMusic()
            return
        }
        val clip = settings.track(id) ?: return
        playback.playMusic(clip, settings.bgmVolume)
    }

    val track: String? get() = currentTrack
}
