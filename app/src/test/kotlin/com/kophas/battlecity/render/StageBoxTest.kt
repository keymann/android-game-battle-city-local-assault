package com.kophas.battlecity.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 어느 단말에서나 같은 화면을 만드는가. (계획서 §21, §22)
 *
 * 화면비가 제각각인 기기에서 UI 를 늘리면 단추 자리와 글자 크기가 기기마다 달라진다.
 * 16:9 한 조각으로 고정하고 남는 곳을 띠로 두면 그런 일이 없다.
 */
class StageBoxTest {

    private fun aspect(box: StageBox) = box.width / box.height

    @Test
    fun `16 대 9 화면은 남는 곳이 없다`() {
        val box = StageBox.fit(1920f, 1080f)
        assertEquals(0f, box.x, 0.01f)
        assertEquals(0f, box.y, 0.01f)
        assertEquals(1920f, box.width, 0.01f)
        assertEquals(1080f, box.height, 0.01f)
    }

    @Test
    fun `더 넓은 화면은 좌우를 남긴다`() {
        // 2856x1280 은 요즘 흔한 21:9 언저리다.
        val box = StageBox.fit(2856f, 1280f)
        assertEquals(1280f, box.height, 0.01f)
        assertEquals(1280f * StageBox.ASPECT, box.width, 0.01f)
        assertTrue("좌우에 띠가 생긴다", box.x > 0f)
        assertEquals(0f, box.y, 0.01f)
    }

    @Test
    fun `더 좁은 화면은 위아래를 남긴다`() {
        val box = StageBox.fit(1440f, 1080f)
        assertEquals(1440f, box.width, 0.01f)
        assertEquals(1440f / StageBox.ASPECT, box.height, 0.01f)
        assertTrue("위아래에 띠가 생긴다", box.y > 0f)
        assertEquals(0f, box.x, 0.01f)
    }

    @Test
    fun `어떤 화면에서도 비율은 16 대 9 다`() {
        val screens = listOf(
            1920f to 1080f,
            2856f to 1280f,
            2560f to 1080f,
            1440f to 1080f,
            2208f to 1768f,
            1024f to 768f,
        )
        for ((w, h) in screens) {
            val box = StageBox.fit(w, h)
            assertEquals("${w.toInt()}x${h.toInt()}", StageBox.ASPECT, aspect(box), 0.001f)
        }
    }

    @Test
    fun `화면 안에 들어간다`() {
        for ((w, h) in listOf(2856f to 1280f, 1440f to 1080f)) {
            val box = StageBox.fit(w, h)
            assertTrue(box.x >= 0f && box.y >= 0f)
            assertTrue(box.x + box.width <= w + 0.01f)
            assertTrue(box.y + box.height <= h + 0.01f)
        }
    }

    @Test
    fun `가운데에 놓는다`() {
        val box = StageBox.fit(2856f, 1280f)
        assertEquals(1428f, box.centerX, 0.01f)
        assertEquals(640f, box.centerY, 0.01f)
    }

    @Test
    fun `화면이 없으면 빈 자리다`() {
        assertEquals(StageBox.NONE, StageBox.fit(0f, 1080f))
        assertEquals(StageBox.NONE, StageBox.fit(1920f, 0f))
    }

    @Test
    fun `짧은 쪽은 높이다`() {
        val box = StageBox.fit(2856f, 1280f)
        assertEquals(box.height, box.shortSide, 0.01f)
    }
}
