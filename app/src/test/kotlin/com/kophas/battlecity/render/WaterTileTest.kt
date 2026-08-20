package com.kophas.battlecity.render

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * asset2 의 물 타일 대부분은 잔디·모래가 붙은 **해안 전이 타일**이다.
 * 연못 안쪽에 그런 타일을 쓰면 물 한가운데 잔디 띠가 생긴다.
 *
 * 눈으로 골라내면 또 틀리므로 픽셀을 직접 검사한다.
 * 안드로이드 단위 테스트에는 `javax.imageio` 가 없어 [IndexedPngReader] 로 읽는다.
 */
class WaterTileTest {

    private val assetsDir: File = sequenceOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).firstOrNull { it.isDirectory } ?: error("assets 디렉터리를 찾지 못했다")

    private val manifest = AssetManifest.load(AssetSource.ofDirectory(assetsDir))

    private val atlasImage = IndexedPngReader.read(File(assetsDir, "atlas/tiles.png"))

    private val atlas = TextureAtlas.parse(
        xml = AssetSource.ofDirectory(assetsDir).readText("atlas/tiles.xml"),
        textureId = 1,
        textureWidth = atlasImage.width,
        textureHeight = atlasImage.height,
    )

    @Test
    fun `WATER 로 쓰는 타일에는 물이 아닌 픽셀이 없다`() {
        val sprite = manifest.tileSprite("WATER")
        assertTrue("WATER 스프라이트가 없다", sprite != null)
        assertEquals("$sprite 에 물이 아닌 픽셀이 있다", 0, nonWaterPixels(sprite!!))
    }

    @Test
    fun `ICE 로 쓰는 타일에도 물이 아닌 픽셀이 없다`() {
        val sprite = manifest.tileSprite("ICE")
        assertTrue("ICE 스프라이트가 없다", sprite != null)
        assertEquals("$sprite 에 물이 아닌 픽셀이 있다", 0, nonWaterPixels(sprite!!))
    }

    @Test
    fun `해안 타일은 검사에 확실히 걸린다`() {
        // 이 테스트가 통과한다는 것은 위의 검사가 실제로 판별력이 있다는 뜻이다.
        // 눈으로는 열린 수면처럼 보이지만 잔디 조각이 남아 있는 타일들이다.
        //   038 오른쪽 잔디 띠 / 091 우상단 해안 조각 / 092 우하단 잔디 8픽셀
        assertTrue("battle_038 은 해안 타일로 걸려야 한다", nonWaterPixels("battle_038") > 0)
        assertTrue("battle_091 은 해안 타일로 걸려야 한다", nonWaterPixels("battle_091") > 0)
        assertTrue("battle_092 는 해안 타일로 걸려야 한다", nonWaterPixels("battle_092") > 0)
    }

    /** 파랑이 우세하지도, 흰색 포말도 아닌 픽셀 수. */
    private fun nonWaterPixels(spriteName: String): Int {
        val region = atlas[spriteName]

        var count = 0
        for (y in 0 until region.height) {
            for (x in 0 until region.width) {
                val argb = atlasImage.argbAt(region.x + x, region.y + y)
                val a = (argb ushr 24) and 0xFF
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                if (a < 250) {
                    count++
                    continue
                }
                val blueDominant = b > r + 25 && b > g + 5
                val foam = r > 190 && g > 215 && b > 225
                if (!blueDominant && !foam) count++
            }
        }
        return count
    }
}
