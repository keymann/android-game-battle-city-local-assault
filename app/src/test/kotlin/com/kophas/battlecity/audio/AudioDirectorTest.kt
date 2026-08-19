package com.kophas.battlecity.audio

import com.kophas.battlecity.render.AssetSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 무엇을 언제 소리 낼지. (계획서 §34)
 *
 * 실제 소리를 내지 않고 **무엇을 시켰는지만** 적어 둔다. 기기도 스피커도 필요 없고,
 * 시간을 손으로 돌리므로 "60ms 안에는 다시 내지 않는다" 같은 규칙을 바로 확인한다.
 */
class AudioDirectorTest {

    private class Recorder : AudioDirector.Playback {
        val played = ArrayList<String>()
        val loopsStarted = ArrayList<String>()
        val loopsStopped = ArrayList<String>()
        val music = ArrayList<String>()
        val vibrations = ArrayList<Long>()
        var musicStopped = 0

        override fun play(clip: String, volume: Float) {
            played += clip
        }

        override fun startLoop(clip: String, volume: Float) {
            loopsStarted += clip
        }

        override fun stopLoop(clip: String) {
            loopsStopped += clip
        }

        override fun playMusic(clip: String, volume: Float) {
            music += clip
        }

        override fun stopMusic() {
            musicStopped++
        }

        override fun vibrate(milliseconds: Long, amplitude: Int) {
            vibrations += milliseconds
        }
    }

    private val assetsDir: File = sequenceOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).firstOrNull { it.isDirectory } ?: error("assets 디렉터리를 찾지 못했다")

    private val settings = AudioSettings.load(AssetSource.ofDirectory(assetsDir))
    private val playback = Recorder()
    private var now = 10_000L
    private val director = AudioDirector(settings, playback, clock = { now })

    // --- 설정 파일 --------------------------------------------------------

    @Test
    fun `계획서가 요구한 소리가 모두 정의돼 있다`() {
        // 계획서 §34 의 목록. 하나라도 빠지면 그 순간에 아무 소리도 안 난다.
        for (event in listOf(
            AudioDirector.Event.TANK_FIRE,
            AudioDirector.Event.BULLET_HIT_BRICK,
            AudioDirector.Event.BULLET_HIT_STEEL,
            AudioDirector.Event.BRICK_DESTROY,
            AudioDirector.Event.TANK_EXPLOSION,
            AudioDirector.Event.PLAYER_DEATH,
            AudioDirector.Event.ENEMY_SPAWN,
            AudioDirector.Event.BASE_DESTROY,
            AudioDirector.Event.VICTORY,
            AudioDirector.Event.GAME_OVER,
        )) {
            assertTrue("$event 소리가 없다", settings.event(event) != null)
        }
        assertTrue(settings.loop(AudioDirector.Loop.TANK_MOVE) != null)
        assertTrue(settings.loop(AudioDirector.Loop.BASE_WARNING) != null)
    }

    @Test
    fun `특수기 세 가지가 서로 다른 소리를 낸다`() {
        val clips = listOf(
            AudioDirector.Event.SPECIAL_PIERCING,
            AudioDirector.Event.SPECIAL_SHIELD,
            AudioDirector.Event.SPECIAL_DASH,
        ).map { settings.event(it)?.name }
        assertEquals(3, clips.filterNotNull().size)
        assertEquals("서로 다른 소리여야 무엇이 걸렸는지 안다", 3, clips.distinct().size)
    }

    // --- 겹침 막기 --------------------------------------------------------

    @Test
    fun `같은 소리가 너무 촘촘히 겹치지 않는다`() {
        // 벽 한 줄이 한꺼번에 무너지면 파괴음이 열 번 겹친다.
        repeat(10) { director.play(AudioDirector.Event.BRICK_DESTROY) }
        assertEquals(1, playback.played.size)
    }

    @Test
    fun `간격이 지나면 다시 난다`() {
        director.play(AudioDirector.Event.BRICK_DESTROY)
        now += settings.event(AudioDirector.Event.BRICK_DESTROY)!!.minIntervalMs + 1
        director.play(AudioDirector.Event.BRICK_DESTROY)
        assertEquals(2, playback.played.size)
    }

    @Test
    fun `다른 소리는 서로 막지 않는다`() {
        director.play(AudioDirector.Event.BRICK_DESTROY)
        director.play(AudioDirector.Event.TANK_FIRE)
        assertEquals(2, playback.played.size)
    }

    @Test
    fun `간격이 없는 소리는 언제든 난다`() {
        // 탱크가 터지는 소리는 겹쳐도 좋다. 그 순간이 중요하기 때문이다.
        repeat(3) { director.play(AudioDirector.Event.TANK_EXPLOSION) }
        assertEquals(3, playback.played.size)
    }

    @Test
    fun `없는 사건은 조용히 넘어간다`() {
        director.play("있을리없는사건")
        assertTrue(playback.played.isEmpty())
    }

    // --- 도는 소리 --------------------------------------------------------

    @Test
    fun `도는 소리는 한 번만 시작된다`() {
        repeat(5) { director.setLoop(AudioDirector.Loop.TANK_MOVE, true) }
        assertEquals(1, playback.loopsStarted.size)
    }

    @Test
    fun `조건이 사라지면 멈춘다`() {
        director.setLoop(AudioDirector.Loop.TANK_MOVE, true)
        director.setLoop(AudioDirector.Loop.TANK_MOVE, false)
        assertEquals(1, playback.loopsStopped.size)
    }

    @Test
    fun `멈춘 소리를 또 멈추지 않는다`() {
        director.setLoop(AudioDirector.Loop.TANK_MOVE, false)
        assertTrue(playback.loopsStopped.isEmpty())
    }

    // --- 배경음 -----------------------------------------------------------

    @Test
    fun `판이 끝나 갈 때 곡이 바뀐다`() {
        director.updateBattleMusic(enemiesRemaining = 80, totalEnemies = 80)
        assertEquals(AudioDirector.Track.BATTLE, director.track)

        director.updateBattleMusic(enemiesRemaining = 10, totalEnemies = 80)
        assertEquals(AudioDirector.Track.FINAL_WAVE, director.track)
        assertEquals(2, playback.music.size)
    }

    @Test
    fun `같은 곡을 다시 틀지 않는다`() {
        // 매 틱 부르는 자리라 그대로 두면 곡이 계속 처음부터 다시 시작한다.
        repeat(100) { director.updateBattleMusic(80, 80) }
        assertEquals(1, playback.music.size)
    }

    @Test
    fun `로비와 전투 곡이 다르다`() {
        director.setTrack(AudioDirector.Track.LOBBY)
        director.setTrack(AudioDirector.Track.BATTLE)
        assertEquals(2, playback.music.size)
        assertEquals(2, playback.music.distinct().size)
    }

    // --- 진동 -------------------------------------------------------------

    @Test
    fun `사건마다 진동 세기가 다르다`() {
        director.vibrate(AudioDirector.Haptic.TANK_FIRE)
        director.vibrate(AudioDirector.Haptic.BASE_DESTROY)
        assertEquals(2, playback.vibrations.size)
        assertTrue(
            "본진이 무너질 때가 더 길어야 한다",
            playback.vibrations[1] > playback.vibrations[0],
        )
    }

    @Test
    fun `쏘는 순간에는 소리와 진동이 함께 간다`() {
        director.playWithHaptic(AudioDirector.Event.TANK_FIRE, AudioDirector.Haptic.TANK_FIRE)
        assertEquals(1, playback.played.size)
        assertEquals(1, playback.vibrations.size)
    }

    // --- 음소거 -----------------------------------------------------------

    @Test
    fun `음소거하면 아무 소리도 나지 않는다`() {
        director.setLoop(AudioDirector.Loop.TANK_MOVE, true)
        director.setTrack(AudioDirector.Track.BATTLE)
        director.setMuted(true)

        director.play(AudioDirector.Event.TANK_FIRE)
        director.vibrate(AudioDirector.Haptic.TANK_FIRE)
        director.setTrack(AudioDirector.Track.FINAL_WAVE)

        assertEquals("돌던 소리도 멈춘다", 1, playback.loopsStopped.size)
        assertTrue(playback.musicStopped > 0)
        assertTrue(playback.played.isEmpty())
        assertTrue(playback.vibrations.isEmpty())
        assertNull(director.track)
    }
}
