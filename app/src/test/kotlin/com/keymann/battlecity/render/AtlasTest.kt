package com.keymann.battlecity.render

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 실제 선별 리소스로 검증한다.
 * `app/src/main/assets` 를 테스트 리소스 경로로 붙여 두었기 때문에
 * 런타임과 완전히 같은 파일을 읽는다.
 */
class AtlasTest {

    private val assetsDir: File = sequenceOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).firstOrNull { it.isDirectory } ?: error("assets 디렉터리를 찾지 못했다")

    private val source = AssetSource.ofDirectory(assetsDir)

    @Test
    fun `main 아틀라스는 187개 스프라이트를 모두 읽는다`() {
        val atlas = TextureAtlas.parse(
            xml = source.readText("atlas/main.xml"),
            textureId = 1,
            textureWidth = 1124,
            textureHeight = 1128,
        )
        assertEquals(187, atlas.size)
        assertEquals("main.png", atlas.imagePath)
    }

    @Test
    fun `스프라이트 이름에서 png 확장자가 떨어진다`() {
        val atlas = mainAtlas()
        assertTrue(atlas.contains("tankBody_blue"))
        assertTrue(atlas.contains("tileGrass1"))
        assertTrue(atlas.contains("explosion1"))
        assertTrue("확장자가 남아 있으면 안 된다", !atlas.contains("tankBody_blue.png"))
    }

    @Test
    fun `UV 는 0~1 안에 들어오고 half-texel 인셋이 적용된다`() {
        val atlas = mainAtlas()
        for (name in atlas.names) {
            val r = atlas[name]
            assertTrue("$name u0", r.u0 in 0f..1f)
            assertTrue("$name v0", r.v0 in 0f..1f)
            assertTrue("$name u1", r.u1 in 0f..1f)
            assertTrue("$name v1", r.v1 in 0f..1f)
            assertTrue("$name 은 u0 < u1 이어야 한다", r.u0 < r.u1)
            assertTrue("$name 은 v0 < v1 이어야 한다", r.v0 < r.v1)
        }
    }

    @Test
    fun `없는 스프라이트를 찾으면 실패한다`() {
        val atlas = mainAtlas()
        assertEquals(null, atlas.find("존재하지_않는_스프라이트"))
        runCatching { atlas["존재하지_않는_스프라이트"] }
            .onSuccess { error("예외가 나야 한다") }
    }

    @Test
    fun `tiny 격자 아틀라스는 198 타일을 인덱스로 매핑한다`() {
        val tiny = tinyAtlas()
        assertEquals(198, tiny.tileCount)

        // 인덱스 0 = 좌상단
        val first = tiny[0]
        assertEquals(0, first.x)
        assertEquals(0, first.y)

        // 인덱스 18 = 두 번째 행 첫 칸
        val secondRow = tiny[18]
        assertEquals(0, secondRow.x)
        assertEquals(16, secondRow.y)

        // 인덱스 37 = 물 타일 (3행 2열)
        val water = tiny[37]
        assertEquals(16, water.x)
        assertEquals(32, water.y)
    }

    @Test
    fun `범위 밖 타일 인덱스는 거부한다`() {
        val tiny = tinyAtlas()
        runCatching { tiny[198] }.onSuccess { error("예외가 나야 한다") }
        runCatching { tiny[-1] }.onSuccess { error("예외가 나야 한다") }
    }

    @Test
    fun `같은 인덱스를 다시 요청하면 캐시된 객체를 준다`() {
        val tiny = tinyAtlas()
        assertTrue(tiny[37] === tiny[37])
    }

    @Test
    fun `매니페스트가 참조하는 모든 main 스프라이트가 아틀라스에 존재한다`() {
        val manifest = AssetManifest.load(source)
        val atlas = mainAtlas()

        val referenced = collectMainSpriteNames(manifest)
        assertTrue("참조 스프라이트를 하나도 못 찾았다", referenced.isNotEmpty())

        val missing = referenced.filterNot { atlas.contains(it) }
        assertEquals("아틀라스에 없는 스프라이트가 있다: $missing", emptyList<String>(), missing)
    }

    @Test
    fun `매니페스트가 참조하는 모든 tiny 인덱스가 범위 안이다`() {
        val manifest = AssetManifest.load(source)
        val tiny = tinyAtlas()

        val indices = buildList {
            addAll(manifest.tileIndices("WATER"))
            addAll(manifest.tileIndices("ICE"))
            add(manifest.hudLifeIndex)
            addAll(manifest.hudDigitIndices)
        }
        assertTrue(indices.isNotEmpty())
        indices.forEach { assertNotNull(tiny[it]) }
    }

    private fun mainAtlas() = TextureAtlas.parse(
        xml = source.readText("atlas/main.xml"),
        textureId = 1,
        textureWidth = 1124,
        textureHeight = 1128,
    )

    private fun tinyAtlas() = GridAtlas(
        textureId = 2,
        tileSize = 16,
        columns = 18,
        rows = 11,
        spacing = 0,
    )

    /** 매니페스트에서 main 아틀라스를 가리키는 스프라이트 이름을 모두 모은다. */
    private fun collectMainSpriteNames(manifest: AssetManifest): List<String> = buildList {
        for (type in listOf("BRICK", "STEEL", "FOREST")) {
            addAll(manifest.tileSprites(type))
        }
        addAll(manifest.tile("FOREST")?.get("litter")?.asStringList.orEmpty())

        for (groupId in manifest.propGroupIds) {
            addAll(manifest.propGroup(groupId)?.get("sprites")?.asStringList.orEmpty())
        }

        for (effect in listOf("tankExplosion", "bulletHit", "brickBreak", "spawnPuff")) {
            addAll(manifest.effectFrames(effect))
        }

        val terrain = manifest.root["terrain"]?.asObject.orEmpty()
        for ((_, group) in terrain) {
            addAll(group["base"]?.asStringList.orEmpty())
            for ((key, value) in group.asObject) {
                if (key == "atlas" || key == "base") continue
                value.asString?.let { add(it) }
            }
        }

        val slots = manifest.root["tanks"]?.get("playerSlots")?.asArray.orEmpty()
        for (slot in slots) {
            slot["body"]?.asString?.let { add(it) }
            slot["outline"]?.asString?.let { add(it) }
            slot["preview"]?.asString?.let { add(it) }
            slot["barrelPrefix"]?.asString?.let { prefix -> (1..3).forEach { add("$prefix$it") } }
            slot["bulletPrefix"]?.asString?.let { prefix -> (1..3).forEach { add("$prefix$it") } }
        }

        val enemies = manifest.root["tanks"]?.get("enemy")?.asObject.orEmpty()
        for ((_, enemy) in enemies) {
            listOf("body", "barrel", "bullet", "preview").forEach { key ->
                enemy[key]?.asString?.let { add(it) }
            }
        }
    }
}
