package com.kophas.battlecity.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 방 설정과 기기 설정. (계획서 §44.2)
 *
 * 두 가지가 섞이기 쉬운 자리다. 방 규칙은 모두에게 같아야 하고 소리 크기는
 * 기기마다 달라야 한다. 여기서 그 경계를 못 박아 둔다.
 */
class RoomSettingsTest {

    @Test
    fun `맵 크기는 인원을 한 단계 좁히거나 넓힌다`() {
        assertEquals(2, RoomSettings(mapSize = RoomSettings.MapSize.COMPACT).gridPlayerCount(3))
        assertEquals(3, RoomSettings(mapSize = RoomSettings.MapSize.STANDARD).gridPlayerCount(3))
        assertEquals(4, RoomSettings(mapSize = RoomSettings.MapSize.LARGE).gridPlayerCount(3))
    }

    @Test
    fun `맵 크기는 생성기가 다룰 수 있는 범위를 벗어나지 않는다`() {
        // 두 명이 COMPACT 를 골라도 1인용 맵을 만들지 않는다. 그런 맵은 없다.
        assertEquals(2, RoomSettings(mapSize = RoomSettings.MapSize.COMPACT).gridPlayerCount(2))
        assertEquals(4, RoomSettings(mapSize = RoomSettings.MapSize.LARGE).gridPlayerCount(4))
    }

    @Test
    fun `소리 크기는 0에서 1 사이 배율로 바뀐다`() {
        assertEquals(0f, DeviceSettings(bgmVolume = 0).bgmScale, 0.001f)
        assertEquals(1f, DeviceSettings(sfxVolume = 100).sfxScale, 0.001f)
        assertEquals(0.7f, DeviceSettings(bgmVolume = 70).bgmScale, 0.001f)
    }

    @Test
    fun `슬라이더는 5 단위로 끊는다`() {
        assertEquals(0, SettingsScene.quantizeVolume(0f))
        assertEquals(100, SettingsScene.quantizeVolume(1f))
        assertEquals(50, SettingsScene.quantizeVolume(0.5f))
        // 52.3 은 50 으로 내려붙는다. 같은 자리를 다시 짚으면 같은 값이 나와야 한다.
        assertEquals(50, SettingsScene.quantizeVolume(0.523f))
        assertEquals(55, SettingsScene.quantizeVolume(0.54f))
    }

    @Test
    fun `슬라이더가 범위를 벗어나도 값은 안전하다`() {
        assertEquals(0, SettingsScene.quantizeVolume(-3f))
        assertEquals(100, SettingsScene.quantizeVolume(9f))
    }

    @Test
    fun `동시 COM 슬라이더는 AUTO 와 8에서 12 사이만 고른다`() {
        // 맨 왼쪽 한 칸은 AUTO 다. 손으로 정하고 나서 되돌릴 길이 있어야 한다.
        assertEquals(RoomSettings.ACTIVE_ENEMIES_AUTO, SettingsScene.activeEnemiesAt(0f))
        assertEquals(RoomSettings.MAX_ACTIVE_ENEMIES, SettingsScene.activeEnemiesAt(1f))
        // 계획서 §44.2 가 정한 폭 밖으로는 나가지 않는다.
        for (step in 0..20) {
            val value = SettingsScene.activeEnemiesAt(step / 20f)
            assertTrue(
                "범위 밖 값 $value",
                value == RoomSettings.ACTIVE_ENEMIES_AUTO ||
                    value in RoomSettings.MIN_ACTIVE_ENEMIES..RoomSettings.MAX_ACTIVE_ENEMIES,
            )
        }
    }

    @Test
    fun `슬라이더 자리와 값은 서로 되돌릴 수 있다`() {
        val values = listOf(RoomSettings.ACTIVE_ENEMIES_AUTO) +
            (RoomSettings.MIN_ACTIVE_ENEMIES..RoomSettings.MAX_ACTIVE_ENEMIES)
        for (value in values) {
            assertEquals(
                "자리로 옮겼다 돌아오면 같은 값이어야 한다",
                value,
                SettingsScene.activeEnemiesAt(SettingsScene.activeEnemiesFraction(value)),
            )
        }
    }

    @Test
    fun `PLAY AGAIN 은 방장만 누르고 남은 사람이 있어야 한다`() {
        assertTrue(ResultScene.playAgainAllowed(isHost = true, peersPresent = 1))
        // 방장이라도 다른 사람이 모두 결과 화면을 떠나면 못 누른다.
        assertFalse(ResultScene.playAgainAllowed(isHost = true, peersPresent = 0))
        // 방장이 아니면 사람이 남아 있어도 못 누른다.
        assertFalse(ResultScene.playAgainAllowed(isHost = false, peersPresent = 3))
    }
}
