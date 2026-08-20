package com.kophas.battlecity.input

import com.kophas.battlecity.core.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 조작 UI. (계획서 §18, §19)
 *
 * 안드로이드 없이 손가락만 흉내 낸다. 화면도 기기도 필요 없다.
 */
class TouchControlsTest {

    private val controls = TouchControls().apply { resize(2400, 1080) }

    private fun stickCenter() = 300f to 800f

    // --- 조이스틱 ---------------------------------------------------------

    @Test
    fun `짚은 자리가 조이스틱 중심이 된다`() {
        // 고정된 자리에 두면 화면을 보지 않고는 정확히 짚기 어렵다.
        controls.onDown(0, 250f, 900f)
        assertTrue(controls.state.stickActive)
        assertEquals(250f, controls.state.stickOriginX, 0.01f)
        assertEquals(900f, controls.state.stickOriginY, 0.01f)
    }

    @Test
    fun `아날로그로 밀면 가장 가까운 네 방향으로 접힌다`() {
        val (x, y) = stickCenter()
        val push = controls.stickRadius()

        val cases = listOf(
            Triple(0f, -push, Direction.UP),
            Triple(push, 0f, Direction.RIGHT),
            Triple(0f, push, Direction.DOWN),
            Triple(-push, 0f, Direction.LEFT),
            // 비스듬히 밀어도 더 많이 민 쪽으로 접힌다. (계획서 §19)
            Triple(push, -push * 0.4f, Direction.RIGHT),
            Triple(push * 0.4f, -push, Direction.UP),
        )
        for ((dx, dy, expected) in cases) {
            controls.onDown(0, x, y)
            controls.onMove(0, x + dx, y + dy)
            assertEquals("($dx,$dy)", expected, controls.state.direction)
            assertTrue(controls.state.moving)
            controls.onUp(0)
        }
    }

    @Test
    fun `가운데 조금은 움직이지 않는다`() {
        // 손가락을 얹어만 두어도 굴러가면 조작이 안 된다.
        val (x, y) = stickCenter()
        controls.onDown(0, x, y)
        controls.onMove(0, x + controls.stickRadius() * 0.2f, y)

        assertNull(controls.state.direction)
        assertFalse(controls.state.moving)
    }

    @Test
    fun `손잡이는 테두리 밖으로 나가지 않는다`() {
        val (x, y) = stickCenter()
        val radius = controls.stickRadius()
        controls.onDown(0, x, y)
        controls.onMove(0, x + radius * 10f, y)

        assertEquals(x + radius, controls.state.stickKnobX, 0.5f)
        assertEquals(y, controls.state.stickKnobY, 0.5f)
    }

    @Test
    fun `손을 떼면 멈춘다`() {
        val (x, y) = stickCenter()
        controls.onDown(0, x, y)
        controls.onMove(0, x, y - controls.stickRadius())
        controls.onUp(0)

        assertFalse(controls.state.moving)
        assertNull(controls.state.direction)
        assertFalse(controls.state.stickActive)
    }

    @Test
    fun `오른쪽 절반을 짚어도 조이스틱이 생기지 않는다`() {
        // 거기는 버튼 자리다. 조이스틱이 생기면 버튼을 누를 때마다 탱크가 움직인다.
        controls.onDown(0, 2000f, 900f)
        assertFalse(controls.state.stickActive)
    }

    // --- 버튼 -------------------------------------------------------------

    @Test
    fun `FIRE 를 누르면 한 번만 나간다`() {
        controls.onDown(0, controls.fireCenterX(), controls.fireCenterY())
        assertTrue("누른 틱에 나간다", controls.consume().fire)
        assertFalse("누르고 있어도 다시 나오지 않는다", controls.consume().fire)
    }

    @Test
    fun `SPECIAL 도 한 번만 나간다`() {
        controls.onDown(1, controls.specialCenterX(), controls.specialCenterY())
        assertTrue(controls.consume().special)
        assertFalse(controls.consume().special)
    }

    @Test
    fun `떼었다 누르면 다시 나간다`() {
        val x = controls.fireCenterX()
        val y = controls.fireCenterY()
        controls.onDown(0, x, y)
        controls.consume()
        controls.onUp(0)
        controls.onDown(0, x, y)
        assertTrue(controls.consume().fire)
    }

    @Test
    fun `버튼을 누르는 동안 눌린 상태가 유지된다`() {
        // 그림을 눌린 모양으로 바꾸려면 이 값이 필요하다.
        controls.onDown(0, controls.fireCenterX(), controls.fireCenterY())
        controls.consume()
        assertTrue(controls.state.firePressed)
        controls.onUp(0)
        assertFalse(controls.state.firePressed)
    }

    @Test
    fun `버튼 판정이 그림보다 조금 넉넉하다`() {
        // 손가락 끝은 정확하지 않다. 딱 맞게 잡으면 자꾸 헛눌린다.
        val radius = controls.buttonRadius()
        controls.onDown(0, controls.fireCenterX() + radius * 1.1f, controls.fireCenterY())
        assertTrue(controls.consume().fire)
    }

    // --- 여러 손가락 ------------------------------------------------------

    @Test
    fun `움직이면서 쏠 수 있다`() {
        val (x, y) = stickCenter()
        controls.onDown(0, x, y)
        controls.onMove(0, x, y - controls.stickRadius())
        controls.onDown(1, controls.fireCenterX(), controls.fireCenterY())

        val state = controls.consume()
        assertEquals(Direction.UP, state.direction)
        assertTrue(state.moving)
        assertTrue(state.fire)
    }

    @Test
    fun `버튼에서 손을 떼도 조이스틱은 그대로다`() {
        val (x, y) = stickCenter()
        controls.onDown(0, x, y)
        controls.onMove(0, x + controls.stickRadius(), y)
        controls.onDown(1, controls.fireCenterX(), controls.fireCenterY())
        controls.onUp(1)

        assertTrue(controls.state.stickActive)
        assertEquals(Direction.RIGHT, controls.state.direction)
    }

    @Test
    fun `창을 벗어나면 모두 놓는다`() {
        val (x, y) = stickCenter()
        controls.onDown(0, x, y)
        controls.onMove(0, x, y - controls.stickRadius())
        controls.onDown(1, controls.fireCenterX(), controls.fireCenterY())
        controls.onCancel()

        assertFalse(controls.state.stickActive)
        assertFalse(controls.state.moving)
        assertFalse(controls.state.firePressed)
    }

    // --- 화면 크기 --------------------------------------------------------

    @Test
    fun `버튼 크기가 화면에 따라 함께 커진다`() {
        // 픽셀로 고정하면 태블릿에서 손톱만 해진다.
        val phone = TouchControls().apply { resize(2400, 1080) }
        val tablet = TouchControls().apply { resize(3200, 2000) }
        assertTrue(tablet.buttonRadius() > phone.buttonRadius())
        assertTrue(tablet.stickRadius() > phone.stickRadius())
    }

    @Test
    fun `버튼은 오른쪽 아래에 조이스틱은 왼쪽에 있다`() {
        assertTrue(controls.fireCenterX() > 2400f * 0.5f)
        assertTrue(controls.specialCenterY() < controls.fireCenterY())
        assertTrue(controls.restingStickX() < 2400f * 0.5f)
    }
}
