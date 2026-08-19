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
    fun `아틀라스는 두 장이고 필터가 서로 다르다`() {
        assertEquals(setOf("tiles", "units"), manifest.atlases.keys)

        val tiles = manifest.atlases.getValue("tiles")
        assertEquals("atlas/tiles.png", tiles.image)
        assertEquals("atlas/tiles.xml", tiles.descriptor)
        assertEquals(16, tiles.sourceTile)
        // 픽셀아트는 NEAREST 여야 도트가 산다.
        assertEquals("nearest", tiles.filter)

        val units = manifest.atlases.getValue("units")
        assertEquals("atlas/units.png", units.image)
        // 회전하는 벡터풍 스프라이트는 LINEAR 여야 계단이 지지 않는다.
        assertEquals("linear", units.filter)
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
    fun `나무는 20종 이상이다`() {
        assertTrue("숲이 단조로우면 안 된다", manifest.tileSprites("FOREST").size >= 20)
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
        assertTrue(
            manifest.propGroupIds.containsAll(
                listOf("explosiveBarrel", "woodFence", "ironFence", "haystack", "rubble"),
            ),
        )
    }

    @Test
    fun `폭발성 프롭은 폭발 반경을 가진다`() {
        for (id in listOf("explosiveBarrel", "fuelBarrel")) {
            val group = manifest.propGroup(id)!!
            assertEquals("explosive", group["kind"]?.asString)
            assertTrue("$id 폭발 반경이 없다", (group["blastCells"]?.asInt ?: 0) > 0)
        }
    }

    @Test
    fun `플레이어 슬롯 4개가 서로 다른 색과 몸체를 쓴다`() {
        val slots = manifest.root["tanks"]?.get("playerSlots")?.asArray.orEmpty()
        assertEquals(4, slots.size)
        assertEquals(4, slots.mapNotNull { it["color"]?.asString }.toSet().size)
        assertEquals(4, slots.mapNotNull { it["body"]?.asString }.toSet().size)
        assertEquals(4, slots.mapNotNull { it["barrelPrefix"]?.asString }.toSet().size)
    }

    @Test
    fun `탱크 타입별 포신 등급이 정의돼 있다`() {
        val byType = manifest.root["tanks"]?.get("barrelByType")?.asObject.orEmpty()
        assertEquals(setOf("ATTACK", "DEFENSE", "SPEED"), byType.keys)
        assertEquals("공격형이 가장 굵은 포신을 쓴다", 3, byType["ATTACK"]?.asInt)
        assertEquals(1, byType["SPEED"]?.asInt)
    }

    @Test
    fun `유닛 스프라이트는 위를 향하므로 회전 보정이 없다`() {
        assertEquals(0, manifest.root["tanks"]?.get("spriteRotationOffsetDegrees")?.asInt)
    }

    @Test
    fun `HUD 는 비트맵 폰트 접두사를 서술한다`() {
        assertEquals("ui_digit_", manifest.hudDigitPrefix)
        assertEquals("ui_char_", manifest.hudCharPrefix)
        assertEquals("battle_195", manifest.hudLifeSprite)
    }

    @Test
    fun `지형은 잔디 흙 자갈 기본 타일을 가진다`() {
        assertTrue(manifest.terrainBase("grass").isNotEmpty())
        assertTrue(manifest.terrainBase("dirt").isNotEmpty())
        assertTrue(manifest.terrainBase("gravel").isNotEmpty())
    }

    @Test
    fun `도로 오토타일은 16가지 비트마스크를 모두 채운다`() {
        val road = manifest.terrain("road")?.asObject.orEmpty()
        for (mask in 0..15) {
            assertNotNull("마스크 $mask 에 대응하는 도로 타일이 없다", road[mask.toString()]?.asString)
        }
    }

    @Test
    fun `흙 구역 나인슬라이스가 9조각 모두 있다`() {
        val patch = manifest.terrain("dirtPatch")?.asObject.orEmpty()
        for (key in listOf("NW", "N", "NE", "W", "C", "E", "SW", "S", "SE")) {
            assertNotNull("$key 조각이 없다", patch[key]?.asString)
        }
    }

    @Test
    fun `본진은 파괴 애니메이션 프레임을 가진다`() {
        val base = manifest.tile("BASE")!!
        assertEquals("base_0", base["intact"]?.asString)

        val frames = base["destroyFrames"]?.asStringList.orEmpty()
        assertEquals("손상 -> 심한 손상 -> 폭발 3프레임", 3, frames.size)
        assertEquals(frames.size, frames.toSet().size)
        assertTrue("재생 속도가 있어야 한다", (base["destroyFps"]?.asFloat ?: 0f) > 0f)

        // 폭발이 끝나면 잔해로 남는다. 정상 상태와 달라야 한다.
        assertNotNull(base["wreck"]?.asString)
        assertTrue(base["wreck"]?.asString != base["intact"]?.asString)
    }

    @Test
    fun `물과 얼음은 열린 수면 타일 하나를 쓴다`() {
        assertEquals("battle_037", manifest.tileSprite("WATER"))
        assertEquals("battle_037", manifest.tileSprite("ICE"))
    }
}
