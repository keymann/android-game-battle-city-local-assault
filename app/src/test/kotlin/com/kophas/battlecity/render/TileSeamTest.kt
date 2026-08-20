package com.kophas.battlecity.render

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 이어 붙는 타일이 실제로 이어지는지 픽셀로 확인한다.
 *
 * 에셋 팩의 타일은 **스티커** 모양이다. 모서리가 둥글고 바깥에 윤곽선이 둘려 있다.
 * 바닥·물·얼음처럼 빈틈없이 깔리는 타일은 그 테두리를 깎아 넣는데
 * (`tools/atlaslib.py` 의 `tile_inset`), 값이 한 번 어긋나면 화면 전체가
 * 격자로 덮인다. 벽돌과 강철은 반대로 테두리가 곧 생김새라 그대로 둔다.
 *
 * 밝기로 "테두리가 남았는지" 를 재 보려 했으나 이 그림들에는 통하지 않았다.
 * 그을린 바닥은 가운데가 원래 어두워서 가장자리와 크게 다르고, 자갈은 결이 거칠어
 * 이웃 픽셀 차이가 어디서나 크다. 그래서 **깨질 때 실제로 달라지는 것**,
 * 곧 둥근 모서리가 남았는지(=비치는 픽셀이 있는지)로 판정한다.
 *
 * 안드로이드 단위 테스트에는 `javax.imageio` 가 없어 [IndexedPngReader] 로 읽는다.
 */
class TileSeamTest {

    private val assetsDir: File = sequenceOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).firstOrNull { it.isDirectory } ?: error("assets 디렉터리를 찾지 못했다")

    private val source = AssetSource.ofDirectory(assetsDir)

    private val manifest = AssetManifest.load(source)

    private val image = IndexedPngReader.read(File(assetsDir, "atlas/game.png"))

    private val atlas = TextureAtlas.parse(
        xml = source.readText("atlas/game.xml"),
        textureId = 1,
        textureWidth = image.width,
        textureHeight = image.height,
    )

    /** 빈틈없이 깔리는 타일. */
    private fun seamless(): List<String> =
        manifest.terrainBase("grass") + manifest.terrainBase("dirt") +
            manifest.terrainBase("gravel") + manifest.tileFrames("WATER") +
            manifest.tileSprites("ICE")

    /** 한 칸을 통째로 차지하는 물건. 테두리가 곧 생김새다. */
    private fun blocky(): List<String> =
        manifest.tileSprites("BRICK") + manifest.tileSprites("STEEL")

    @Test
    fun `이어 붙는 타일에는 비치는 픽셀이 하나도 없다`() {
        // 둥근 모서리가 남아 있으면 칸마다 구멍이 뚫려 격자 무늬가 생긴다.
        val names = seamless()
        assertTrue("검사 대상이 비어 있다", names.isNotEmpty())
        for (name in names) {
            assertEquals("$name 에 비치는 픽셀이 있다", 0, translucentPixels(name))
        }
    }

    @Test
    fun `이어 붙는 타일은 정확히 한 블록 정사각이다`() {
        for (name in seamless()) {
            val region = atlas[name]
            assertEquals("$name 가로", 64, region.width)
            assertEquals("$name 세로", 64, region.height)
        }
    }

    @Test
    fun `벽은 둥근 모서리를 그대로 둔다`() {
        // 위의 검사를 실수로 벽까지 넓히면 벽이 장판처럼 보인다. 그때 여기서 걸린다.
        val names = blocky()
        assertTrue("검사 대상이 비어 있다", names.isNotEmpty())
        assertTrue(
            "벽에는 둥근 모서리가 남아 있어야 한다",
            names.all { translucentPixels(it) > 0 },
        )
    }

    @Test
    fun `물 프레임은 서로 다른 그림이다`() {
        // 같은 그림이 여러 장이면 애니메이션이 멈춘 것처럼 보인다.
        val frames = manifest.tileFrames("WATER")
        assertTrue(frames.size >= 3)
        assertEquals(frames.size, frames.map { signature(it) }.toSet().size)
    }

    @Test
    fun `얼음은 물과 다른 그림이다`() {
        // 예전에는 물 타일을 밝게 물들여 얼음으로 썼다. 이제 전용 그림이 있다.
        val water = manifest.tileFrames("WATER").map { signature(it) }.toSet()
        for (name in manifest.tileSprites("ICE")) {
            assertTrue("$name 이 물 프레임과 같은 그림이다", signature(name) !in water)
        }
    }

    private fun translucentPixels(name: String): Int {
        val region = atlas[name]
        var count = 0
        for (y in 0 until region.height) {
            for (x in 0 until region.width) {
                if (((image.argbAt(region.x + x, region.y + y) ushr 24) and 0xFF) < 250) count++
            }
        }
        return count
    }

    private fun signature(name: String): Long {
        val region = atlas[name]
        var sum = 0L
        for (y in 0 until region.height) {
            for (x in 0 until region.width) {
                sum += image.argbAt(region.x + x, region.y + y).toLong()
            }
        }
        return sum
    }
}
