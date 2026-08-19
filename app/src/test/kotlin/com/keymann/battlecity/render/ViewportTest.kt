package com.keymann.battlecity.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportTest {

    private fun viewport() = Viewport(832f, 832f)

    @Test
    fun `가로가 넓은 화면에서는 세로에 맞추고 좌우를 레터박스로 남긴다`() {
        val vp = viewport()
        vp.update(2400, 1080)

        assertEquals(1080f / 832f, vp.scale, 1e-5f)
        assertEquals(0f, vp.offsetY, 1e-3f)
        assertEquals((2400f - vp.contentWidth) / 2f, vp.offsetX, 1e-3f)
    }

    @Test
    fun `세로가 긴 화면에서는 가로에 맞춘다`() {
        val vp = viewport()
        vp.update(1080, 2400)

        assertEquals(1080f / 832f, vp.scale, 1e-5f)
        assertEquals(0f, vp.offsetX, 1e-3f)
        assertEquals((2400f - vp.contentHeight) / 2f, vp.offsetY, 1e-3f)
    }

    @Test
    fun `정사각 화면에서는 여백이 없다`() {
        val vp = viewport()
        vp.update(832, 832)

        assertEquals(1f, vp.scale, 1e-6f)
        assertEquals(0f, vp.offsetX, 1e-6f)
        assertEquals(0f, vp.offsetY, 1e-6f)
    }

    @Test
    fun `월드에서 화면으로 갔다가 돌아오면 원래 좌표다`() {
        val vp = viewport()
        vp.update(2400, 1080)

        val worldX = 123.5f
        val worldY = 700f
        assertEquals(worldX, vp.screenToWorldX(vp.worldToScreenX(worldX)), 1e-2f)
        assertEquals(worldY, vp.screenToWorldY(vp.worldToScreenY(worldY)), 1e-2f)
    }

    @Test
    fun `컷아웃 인셋만큼 안쪽으로 배치한다`() {
        val vp = viewport()
        vp.update(2400, 1080, Viewport.Insets(left = 100, right = 60))

        val available = 2400 - 100 - 60
        assertEquals(minOf(available / 832f, 1080f / 832f), vp.scale, 1e-5f)
        assertTrue("좌측 인셋 안쪽이어야 한다", vp.offsetX >= 100f)
    }

    @Test
    fun `폴더블이 펼쳐져도 논리 해상도는 유지된다`() {
        val vp = viewport()
        vp.update(1080, 1080)
        val folded = vp.scale

        vp.update(2200, 1600)
        assertEquals(832f, vp.logicalWidth, 1e-6f)
        assertEquals(832f, vp.logicalHeight, 1e-6f)
        assertTrue("펼치면 더 크게 그려져야 한다", vp.scale > folded)
    }

    @Test
    fun `맵 크기가 바뀌어도 화면 크기 정보는 유지된다`() {
        val vp = viewport()
        vp.update(2400, 1080)
        vp.resizeWorld(1216f, 1216f)

        assertEquals(2400, vp.screenWidth)
        assertEquals(1080f / 1216f, vp.scale, 1e-5f)
    }

    @Test
    fun `화면 크기가 0이면 안전한 기본값을 쓴다`() {
        val vp = viewport()
        vp.update(0, 0)
        assertEquals(1f, vp.scale, 1e-6f)
    }

    @Test
    fun `화면 밖 오브젝트는 보이지 않는다고 판단한다`() {
        val vp = viewport()
        vp.update(832, 832)

        assertTrue(vp.isVisible(0f, 0f, 64f, 64f))
        assertFalse(vp.isVisible(-200f, 0f, 64f, 64f))
        assertFalse(vp.isVisible(0f, 2000f, 64f, 64f))
    }
}
