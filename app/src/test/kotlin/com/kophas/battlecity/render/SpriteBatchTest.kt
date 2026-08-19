package com.kophas.battlecity.render

import com.kophas.battlecity.core.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpriteBatchTest {

    private fun region(textureId: Int, name: String = "r") =
        TextureRegion.of(name, textureId, 0, 0, 16, 16, 256, 256)

    @Test
    fun `빈 배치는 스프라이트도 run 도 없다`() {
        val batch = SpriteBatch(16)
        batch.begin()
        batch.end()

        assertEquals(0, batch.spriteCount)
        assertEquals(0, batch.runCount)
    }

    @Test
    fun `같은 텍스처는 하나의 run 으로 묶인다`() {
        val batch = SpriteBatch(16)
        batch.begin()
        repeat(5) { batch.draw(region(1), 0f, 0f, 8f, 8f) }
        batch.end()

        assertEquals(5, batch.spriteCount)
        assertEquals(1, batch.runCount)
        assertEquals(1 to 5, batch.runAt(0))
    }

    @Test
    fun `텍스처가 섞여 들어와도 정렬 후 run 은 텍스처당 하나다`() {
        val batch = SpriteBatch(16)
        batch.begin()
        batch.draw(region(2), 0f, 0f, 8f, 8f)
        batch.draw(region(1), 0f, 0f, 8f, 8f)
        batch.draw(region(2), 0f, 0f, 8f, 8f)
        batch.draw(region(1), 0f, 0f, 8f, 8f)
        batch.end()

        assertEquals(4, batch.spriteCount)
        assertEquals(2, batch.runCount)
        assertEquals(1 to 2, batch.runAt(0))
        assertEquals(2 to 2, batch.runAt(1))
    }

    @Test
    fun `레이어가 낮은 스프라이트가 먼저 나온다`() {
        val batch = SpriteBatch(16)
        batch.begin()
        batch.draw(region(1), 10f, 0f, 8f, 8f, layer = Constants.Layer.HUD)
        batch.draw(region(1), 20f, 0f, 8f, 8f, layer = Constants.Layer.GROUND)
        batch.end()

        // 0번 슬롯이 GROUND(x=20), 1번이 HUD(x=10)
        assertEquals(20f, batch.spriteFloatAt(0, 0), 1e-6f)
        assertEquals(10f, batch.spriteFloatAt(1, 0), 1e-6f)
    }

    @Test
    fun `레이어가 텍스처보다 우선한다`() {
        val batch = SpriteBatch(16)
        batch.begin()
        batch.draw(region(1), 0f, 0f, 8f, 8f, layer = Constants.Layer.CANOPY)
        batch.draw(region(2), 0f, 0f, 8f, 8f, layer = Constants.Layer.GROUND)
        batch.end()

        // CANOPY 가 뒤로 가야 하므로 첫 run 은 텍스처 2다.
        assertEquals(2 to 1, batch.runAt(0))
        assertEquals(1 to 1, batch.runAt(1))
    }

    @Test
    fun `같은 레이어 같은 텍스처 안에서는 삽입 순서가 유지된다`() {
        val batch = SpriteBatch(16)
        batch.begin()
        repeat(4) { batch.draw(region(3), it.toFloat(), 0f, 8f, 8f) }
        batch.end()

        for (i in 0 until 4) {
            assertEquals(i.toFloat(), batch.spriteFloatAt(i, 0), 1e-6f)
        }
    }

    @Test
    fun `capacity 를 넘으면 버리고 개수를 기록한다`() {
        val batch = SpriteBatch(2)
        batch.begin()
        repeat(5) { batch.draw(region(1), 0f, 0f, 8f, 8f) }
        batch.end()

        assertEquals(2, batch.spriteCount)
        assertEquals(3, batch.droppedSprites)
    }

    @Test
    fun `스프라이트 필드가 네이티브 레이아웃 순서대로 기록된다`() {
        val batch = SpriteBatch(4)
        val r = region(7)
        batch.begin()
        batch.draw(
            region = r,
            x = 1f, y = 2f, width = 3f, height = 4f,
            rotation = 0.5f, originX = 0.25f, originY = 0.75f,
            red = 0.1f, green = 0.2f, blue = 0.3f, alpha = 0.4f,
        )
        batch.end()

        assertEquals(1f, batch.spriteFloatAt(0, 0), 1e-6f)
        assertEquals(2f, batch.spriteFloatAt(0, 1), 1e-6f)
        assertEquals(3f, batch.spriteFloatAt(0, 2), 1e-6f)
        assertEquals(4f, batch.spriteFloatAt(0, 3), 1e-6f)
        assertEquals(r.u0, batch.spriteFloatAt(0, 4), 1e-6f)
        assertEquals(r.v0, batch.spriteFloatAt(0, 5), 1e-6f)
        assertEquals(r.u1, batch.spriteFloatAt(0, 6), 1e-6f)
        assertEquals(r.v1, batch.spriteFloatAt(0, 7), 1e-6f)
        assertEquals(0.25f, batch.spriteFloatAt(0, 8), 1e-6f)
        assertEquals(0.75f, batch.spriteFloatAt(0, 9), 1e-6f)
        assertEquals(0.5f, batch.spriteFloatAt(0, 10), 1e-6f)
        assertEquals(0.1f, batch.spriteFloatAt(0, 12), 1e-6f)
        assertEquals(0.4f, batch.spriteFloatAt(0, 15), 1e-6f)
    }

    @Test
    fun `begin 은 이전 프레임 상태를 지운다`() {
        val batch = SpriteBatch(4)
        batch.begin()
        batch.draw(region(1), 0f, 0f, 8f, 8f)
        batch.end()

        batch.begin()
        batch.end()
        assertEquals(0, batch.spriteCount)
        assertEquals(0, batch.runCount)
    }

    @Test
    fun `direct 버퍼를 써야 JNI 복사가 없다`() {
        val batch = SpriteBatch(4)
        assertTrue(batch.spriteBuffer.isDirect)
        assertTrue(batch.runBuffer.isDirect)
    }
}
