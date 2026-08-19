package com.kophas.battlecity.net

import com.kophas.battlecity.gameplay.PlayerProfile
import com.kophas.battlecity.gameplay.Tank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 로비에서 고르는 것. (계획서 §28, §29)
 *
 * 이름 세 글자, 탱크 세 종류, 색은 겹치지 않게. 이 규칙이 깨지면 게임 화면에서
 * 누가 누구인지 알 수 없게 된다.
 */
class LobbyProfileTest {

    private val lobby = LobbyState(paletteSize = 6)
    private var now = 1_000L

    private fun openRoom() = lobby.openAsHost("HST", 0, now)

    // --- 이름 -------------------------------------------------------------

    @Test
    fun `이름은 세 글자까지다`() {
        assertEquals("ABC", PlayerProfile.sanitize("ABCDEF", 0))
    }

    @Test
    fun `이름은 대문자로 맞춘다`() {
        // 비트맵 폰트에 소문자가 없다. 그대로 두면 글자가 통째로 빠져 보인다.
        assertEquals("ABC", PlayerProfile.sanitize("abc", 0))
    }

    @Test
    fun `이름을 비우면 자리 번호가 들어간다`() {
        for (slot in 0..3) {
            assertEquals("P${slot + 1}", PlayerProfile.sanitize("", slot))
            assertEquals("P${slot + 1}", PlayerProfile.sanitize("   ", slot))
        }
    }

    @Test
    fun `기본값은 P1 부터 P4 까지다`() {
        for (slot in 0..3) {
            assertEquals("P${slot + 1}", PlayerProfile.default(slot).name)
        }
    }

    @Test
    fun `글자와 숫자만 남긴다`() {
        assertEquals("AB1", PlayerProfile.sanitize("A-B!1@", 0))
    }

    // --- 색 ---------------------------------------------------------------

    @Test
    fun `방을 연 사람이 첫 색을 쥔다`() {
        val host = openRoom()
        assertEquals(0, host.colorIndex)
    }

    @Test
    fun `같은 색을 원해도 남은 색으로 바꿔 준다`() {
        openRoom() // 0번 색을 쓴다
        val guest = lobby.join("GST", 0, colorIndex = 0, nowMs = now)!!
        assertNotEquals("색이 겹치면 누가 누구인지 모른다", 0, guest.colorIndex)
    }

    @Test
    fun `원하는 색이 비어 있으면 그대로 준다`() {
        openRoom()
        val guest = lobby.join("GST", 0, colorIndex = 4, nowMs = now)!!
        assertEquals(4, guest.colorIndex)
    }

    @Test
    fun `네 명이 모두 다른 색을 쥔다`() {
        openRoom()
        repeat(3) { lobby.join("G$it", 0, colorIndex = 0, nowMs = now) }

        val colors = lobby.slots.filter { it.connected }.map { it.colorIndex }
        assertEquals(4, colors.size)
        assertEquals("색이 겹쳤다: $colors", 4, colors.distinct().size)
    }

    @Test
    fun `남이 쓰는 색으로는 바꿀 수 없다`() {
        openRoom()
        val guest = lobby.join("GST", 0, colorIndex = 3, nowMs = now)!!
        // 0번은 방장이 쓰고 있다. 조용히 되돌리고 쓰던 색을 지킨다.
        lobby.setReady(guest.index, ready = false, tankType = 0, colorIndex = 0, name = "GST", nowMs = now)
        assertEquals(3, guest.colorIndex)
    }

    @Test
    fun `빈 색으로는 바꿀 수 있다`() {
        openRoom()
        val guest = lobby.join("GST", 0, colorIndex = 3, nowMs = now)!!
        lobby.setReady(guest.index, ready = false, tankType = 0, colorIndex = 5, name = "GST", nowMs = now)
        assertEquals(5, guest.colorIndex)
    }

    @Test
    fun `자리가 비면 그 색을 다시 쓸 수 있다`() {
        openRoom()
        val guest = lobby.join("GST", 0, colorIndex = 2, nowMs = now)!!
        assertFalse(lobby.isColorFree(2, exceptSlot = 0))

        lobby.leave(guest.index)
        assertTrue(lobby.isColorFree(2, exceptSlot = 0))
    }

    @Test
    fun `팔레트 밖의 색은 받지 않는다`() {
        openRoom()
        assertFalse(lobby.isColorFree(-1, exceptSlot = 0))
        assertFalse(lobby.isColorFree(99, exceptSlot = 0))
    }

    // --- 탱크 종류 --------------------------------------------------------

    @Test
    fun `탱크 종류를 바꾸면 반영된다`() {
        openRoom()
        val guest = lobby.join("GST", 0, colorIndex = 2, nowMs = now)!!
        lobby.setReady(guest.index, ready = false, tankType = 2, colorIndex = 2, name = "GST", nowMs = now)
        assertEquals(Tank.Type.SPEED.ordinal, guest.tankType)
    }

    @Test
    fun `고른 것이 로비 현황에 실려 나간다`() {
        openRoom()
        val guest = lobby.join("ZZZ", 1, colorIndex = 4, nowMs = now)!!
        val snapshot = lobby.snapshot().slots[guest.index]

        assertEquals("ZZZ", snapshot.name)
        assertEquals(1, snapshot.tankType)
        assertEquals(4, snapshot.colorIndex)
    }
}
