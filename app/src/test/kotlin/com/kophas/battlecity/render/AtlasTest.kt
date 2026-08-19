package com.kophas.battlecity.render

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 실제 선별 리소스로 검증한다.
 * `app/src/main/assets` 를 그대로 읽으므로 런타임과 같은 파일을 본다.
 */
class AtlasTest {

    private val assetsDir: File = sequenceOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).firstOrNull { it.isDirectory } ?: error("assets 디렉터리를 찾지 못했다")

    private val source = AssetSource.ofDirectory(assetsDir)

    private val manifest = AssetManifest.load(source)

    private fun atlas() = TextureAtlas.parse(
        xml = source.readText("atlas/tiles.xml"),
        textureId = 1,
        textureWidth = ATLAS_WIDTH,
        textureHeight = ATLAS_HEIGHT,
        insetTexels = 0.02f,
    )

    private fun units() = TextureAtlas.parse(
        xml = source.readText("atlas/units.xml"),
        textureId = 2,
        textureWidth = UNITS_WIDTH,
        textureHeight = UNITS_HEIGHT,
    )

    @Test
    fun `통합 아틀라스는 네 팩의 스프라이트를 모두 담는다`() {
        val atlas = atlas()
        assertEquals(700, atlas.size)
        assertEquals("tiles.png", atlas.imagePath)

        // 팩별 개수: Tiny Battle 198 / Tiny Town 132 / Tiny Dungeon 132 / Desert Shooter 198 + 40
        assertEquals(198, atlas.names.count { it.startsWith("battle_") })
        assertEquals(132, atlas.names.count { it.startsWith("town_") })
        assertEquals(132, atlas.names.count { it.startsWith("dungeon_") })
        assertEquals(40, atlas.names.count { it.startsWith("fx_") })
    }

    @Test
    fun `비트맵 폰트 글리프가 모두 들어 있다`() {
        val atlas = atlas()
        for (digit in 0..9) {
            assertTrue("숫자 $digit 글리프가 없다", atlas.contains("ui_digit_$digit"))
        }
        for (char in 'A'..'Z') {
            assertTrue("문자 $char 글리프가 없다", atlas.contains("ui_char_$char"))
        }
    }

    @Test
    fun `스프라이트 이름에서 png 확장자가 떨어진다`() {
        val atlas = atlas()
        assertTrue(atlas.contains("town_052"))
        assertTrue("확장자가 남아 있으면 안 된다", !atlas.contains("town_052.png"))
    }

    @Test
    fun `UV 는 0~1 안에 들어오고 뒤집히지 않는다`() {
        val atlas = atlas()
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
    fun `16px 타일과 24px 이펙트가 원본 크기 그대로다`() {
        val atlas = atlas()
        assertEquals(16, atlas["battle_037"].width)
        assertEquals(16, atlas["town_000"].width)
        assertEquals(16, atlas["dungeon_040"].width)
        assertEquals(16, atlas["ui_char_A"].width)
        assertEquals(24, atlas["fx_025"].width)
    }

    @Test
    fun `없는 스프라이트를 찾으면 실패한다`() {
        val atlas = atlas()
        assertNull(atlas.find("존재하지_않는_스프라이트"))
        runCatching { atlas["존재하지_않는_스프라이트"] }
            .onSuccess { error("예외가 나야 한다") }
    }

    @Test
    fun `블록 사분면으로 쪼개면 원본을 4등분한다`() {
        val region = atlas()["town_052"]
        val topLeft = region.sub(0, 0, 2, 2)
        val bottomRight = region.sub(1, 1, 2, 2)

        assertEquals(8, topLeft.width)
        assertEquals(8, topLeft.height)
        assertEquals(region.u0, topLeft.u0, 1e-6f)
        assertEquals(region.u1, bottomRight.u1, 1e-6f)
        assertTrue(topLeft.u1 <= bottomRight.u0 + 1e-6f)
    }

    @Test
    fun `유닛 아틀라스는 몸체와 포신이 분리돼 있다`() {
        val units = units()
        assertEquals(187, units.size)
        for (color in listOf("blue", "green", "red", "sand")) {
            assertTrue("tankBody_$color 가 없다", units.contains("tankBody_$color"))
            assertTrue("tankBody_${color}_outline 이 없다", units.contains("tankBody_${color}_outline"))
        }
        for (prefix in listOf("tankBlue", "tankGreen", "tankRed", "tankSand", "tankDark")) {
            for (level in 1..3) {
                assertTrue("${prefix}_barrel$level 이 없다", units.contains("${prefix}_barrel$level"))
            }
        }
    }

    @Test
    fun `매니페스트가 참조하는 모든 스프라이트가 두 아틀라스 안에 있다`() {
        val tiles = atlas()
        val units = units()
        val referenced = collectSpriteNames(manifest)
        assertTrue("참조 스프라이트를 하나도 못 찾았다", referenced.size > 100)

        val missing = referenced.filterNot { tiles.contains(it) || units.contains(it) }
        assertEquals("어느 아틀라스에도 없는 스프라이트가 있다: $missing", emptyList<String>(), missing)
    }

    /**
     * 매니페스트 전체를 훑어 스프라이트 이름을 모은다.
     *
     * 포신/포탄은 접두사 + 등급(1~3)으로 조회하므로 펼쳐서 확인한다.
     * 폰트 접두사(`ui_char_`)는 글자를 붙여 쓰는 것이라 여기서 제외한다.
     */
    private fun collectSpriteNames(manifest: AssetManifest): List<String> {
        val result = LinkedHashSet<String>()

        fun walk(key: String?, node: com.kophas.battlecity.util.JsonValue) {
            when (node) {
                is com.kophas.battlecity.util.JsonValue.Text -> {
                    val value = node.value
                    if (key == "barrelPrefix" || key == "bulletPrefix") {
                        (1..3).forEach { result += "$value$it" }
                    } else if (SPRITE_NAME.matches(value)) {
                        result += value
                    }
                }
                is com.kophas.battlecity.util.JsonValue.Arr -> node.items.forEach { walk(key, it) }
                is com.kophas.battlecity.util.JsonValue.Obj ->
                    node.fields.forEach { (childKey, child) -> walk(childKey, child) }
                else -> Unit
            }
        }
        walk(null, manifest.root)
        return result.toList()
    }

    private companion object {
        const val ATLAS_WIDTH = 352
        const val ATLAS_HEIGHT = 552
        const val UNITS_WIDTH = 1124
        const val UNITS_HEIGHT = 1128

        /** 픽셀 팩은 접두사 + 번호, 유닛 팩은 Kenney 원본 이름을 그대로 쓴다. */
        val SPRITE_NAME = Regex(
            """(battle|town|dungeon|fx)_\d{3}""" +
                """|ui_(char|digit)_[A-Z0-9]""" +
                """|(tankBody|tank|bullet|shot|explosion|explosionSmoke|tracks|oilSpill)[A-Za-z0-9_]*""",
        )
    }
}
