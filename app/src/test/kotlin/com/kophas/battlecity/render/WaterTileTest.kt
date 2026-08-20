package com.kophas.battlecity.render

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 물 타일에 잔디가 섞이지 않았는지 픽셀로 확인한다.
 *
 * 이전에 쓰던 타일셋은 물 타일 대부분이 **해안 전이 타일**이라, 눈으로는 열린 수면처럼
 * 보여도 잔디가 몇 픽셀 남아 연못 안쪽에 조각이 박히는 일이 두 번 있었다.
 * 지금은 전용 시트에서 잘라 쓰지만, 잘라 내는 좌표가 한 칸만 밀려도 옆 타일의
 * 잔디가 딸려 오므로 검사는 그대로 둔다.
 *
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
    fun `물 프레임에 잔디 픽셀이 없다`() {
        val frames = manifest.tileFrames("WATER")
        assertTrue("WATER 프레임이 비어 있다", frames.isNotEmpty())
        for (frame in frames) {
            assertEquals("$frame 에 잔디색 픽셀이 있다", 0, grassPixels(frame))
        }
    }

    @Test
    fun `얼음이 쓰는 타일에도 잔디 픽셀이 없다`() {
        val sprite = manifest.tileSprite("ICE")
        assertTrue("ICE 스프라이트가 없다", sprite != null)
        assertEquals("$sprite 에 잔디색 픽셀이 있다", 0, grassPixels(sprite!!))
    }

    @Test
    fun `잔디 타일은 검사에 확실히 걸린다`() {
        // 이 테스트가 통과한다는 것은 위의 검사가 실제로 판별력이 있다는 뜻이다.
        assertTrue("잔디 타일은 잔디색으로 가득해야 한다", grassPixels("env_ground_grass") > 500)
    }

    @Test
    fun `물 프레임은 서로 다른 그림이다`() {
        // 같은 그림이 세 장이면 애니메이션이 멈춘 것처럼 보인다.
        val frames = manifest.tileFrames("WATER")
        val signatures = frames.map { name ->
            val region = atlas[name]
            (0 until region.height).sumOf { y ->
                (0 until region.width).sumOf { x ->
                    atlasImage.argbAt(region.x + x, region.y + y).toLong()
                }
            }
        }
        assertEquals(frames.size, signatures.toSet().size)
    }

    /** 초록이 확실히 우세한 픽셀 수. 잔디가 섞였는지 보는 지표다. */
    private fun grassPixels(spriteName: String): Int {
        val region = atlas[spriteName]
        var count = 0
        for (y in 0 until region.height) {
            for (x in 0 until region.width) {
                val argb = atlasImage.argbAt(region.x + x, region.y + y)
                if (((argb ushr 24) and 0xFF) < 250) continue
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                if (g > r + 20 && g > b + 20) count++
            }
        }
        return count
    }
}
