package com.kophas.battlecity.render

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetManifestTest {

    private val assetsDir: File = sequenceOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).firstOrNull { it.isDirectory } ?: error("assets 디렉터리를 찾지 못했다")

    private val source = AssetSource.ofDirectory(assetsDir)

    private val manifest = AssetManifest.load(source)

    @Test
    fun `아틀라스 두 장을 서술한다`() {
        assertEquals(setOf("main", "tiny"), manifest.atlases.keys)

        val main = manifest.atlases.getValue("main")
        assertEquals("atlas/main.png", main.image)
        assertEquals("atlas/main.xml", main.descriptor)
        assertEquals(AssetManifest.AtlasSpec.TYPE_XML, main.type)
        assertEquals(2f, main.sourceScale, 1e-6f)

        val tiny = manifest.atlases.getValue("tiny")
        assertTrue(tiny.isGrid)
        assertEquals(16, tiny.tileSize)
        assertEquals(18, tiny.columns)
        assertEquals(11, tiny.rows)
        assertEquals(0, tiny.spacing)
    }

    @Test
    fun `월드 규격은 block 이 cell 의 두 배다`() {
        assertEquals(64f, manifest.world.blockPx, 1e-6f)
        assertEquals(32f, manifest.world.cellPx, 1e-6f)
        assertEquals(2, manifest.world.cellsPerBlock)
        assertEquals(
            manifest.world.blockPx,
            manifest.world.cellPx * manifest.world.cellsPerBlock,
            1e-6f,
        )
    }

    @Test
    fun `원작 TileType 8종이 모두 정의돼 있다`() {
        val required = listOf("EMPTY", "BRICK", "STEEL", "WATER", "FOREST", "ICE", "BASE", "SPAWN")
        required.forEach { assertNotNull("$it 타일이 없다", manifest.tile(it)) }
    }

    @Test
    fun `물은 탱크를 막고 포탄은 통과시킨다`() {
        val water = manifest.tile("WATER")!!
        assertEquals("block", water["collision"]?.asString)
        assertEquals(true, water["bulletPass"]?.asBoolean)
    }

    @Test
    fun `숲은 통행을 막지 않고 은폐만 한다`() {
        val forest = manifest.tile("FOREST")!!
        assertEquals("none", forest["collision"]?.asString)
        assertEquals(true, forest["conceal"]?.asBoolean)
        assertEquals("canopy", forest["renderLayer"]?.asString)
    }

    @Test
    fun `강철은 관통탄으로만 부술 수 있다`() {
        val steel = manifest.tile("STEEL")!!
        assertEquals("piercingOnly", steel["destructible"]?.asString)
    }

    @Test
    fun `환경 오브젝트 그룹이 11개 있다`() {
        assertEquals(11, manifest.propGroupIds.size)
        assertTrue(manifest.propGroupIds.containsAll(listOf("fuelDepot", "ordnance", "foliage")))
    }

    @Test
    fun `폭발성 프롭은 폭발 반경을 가진다`() {
        for (id in listOf("fuelDepot", "ordnance")) {
            val group = manifest.propGroup(id)!!
            assertEquals("explosive", group["kind"]?.asString)
            assertTrue("$id 폭발 반경이 없다", (group["blastCells"]?.asInt ?: 0) > 0)
        }
    }

    @Test
    fun `플레이어 슬롯 4개가 서로 다른 색이다`() {
        val slots = manifest.root["tanks"]?.get("playerSlots")?.asArray.orEmpty()
        assertEquals(4, slots.size)
        val colors = slots.mapNotNull { it["color"]?.asString }
        assertEquals(4, colors.toSet().size)
    }

    @Test
    fun `탱크 타입별 포신 등급이 정의돼 있다`() {
        val byType = manifest.root["tanks"]?.get("barrelByType")?.asObject.orEmpty()
        assertEquals(setOf("ATTACK", "DEFENSE", "SPEED"), byType.keys)
        assertEquals(3, byType["ATTACK"]?.asInt)
        assertEquals(1, byType["SPEED"]?.asInt)
    }

    @Test
    fun `HUD 숫자는 0부터 9까지 10개다`() {
        assertEquals(10, manifest.hudDigitIndices.size)
        assertEquals(180, manifest.hudDigitIndices.first())
        assertEquals(189, manifest.hudDigitIndices.last())
    }

    @Test
    fun `본진은 파괴 시 다른 스프라이트로 바뀐다`() {
        val base = manifest.tile("BASE")!!
        assertNotNull(base["intactIndex"]?.asInt)
        assertNotNull(base["destroyedIndex"]?.asInt)
        assertTrue(base["intactIndex"]?.asInt != base["destroyedIndex"]?.asInt)
    }
}
