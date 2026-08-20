package com.kophas.battlecity.render

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 실제 리소스로 검증한다.
 * `app/src/main/assets` 를 그대로 읽으므로 런타임과 같은 파일을 본다.
 */
class AtlasTest {

    private val assetsDir: File = sequenceOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).firstOrNull { it.isDirectory } ?: error("assets 디렉터리를 찾지 못했다")

    private val source = AssetSource.ofDirectory(assetsDir)

    private val manifest = AssetManifest.load(source)

    private val image = IndexedPngReader.read(File(assetsDir, "atlas/game.png"))

    private fun atlas() = TextureAtlas.parse(
        xml = source.readText("atlas/game.xml"),
        textureId = 1,
        textureWidth = image.width,
        textureHeight = image.height,
        insetTexels = 0.02f,
    )

    @Test
    fun `아틀라스는 한 장이다`() {
        // 지형·탱크·이펙트·HUD 가 모두 같은 팩에서 나오고 화풍이 같다. 나눌 이유가 없다.
        // 한 장이면 한 프레임 안에서 텍스처 교체가 한 번도 일어나지 않는다.
        assertEquals(listOf("game"), manifest.atlases.keys.toList())
        assertEquals("nearest", manifest.atlases.getValue("game").filter)
        assertEquals("game.png", atlas().imagePath)
    }

    @Test
    fun `에셋 팩의 모든 갈래가 들어 있다`() {
        val names = atlas().names
        fun count(prefix: String) = names.count { it.startsWith(prefix) }

        assertEquals("바닥 8종", 8, count("ground_"))
        assertEquals("벽돌 8종", 8, count("brick_"))
        assertEquals("강철 4종", 4, count("steel_"))
        assertEquals("물 4프레임", 4, count("water_frame_"))
        assertEquals("숲 4종", 4, count("forest_"))
        assertEquals("얼음 4종", 4, count("ice_"))
        assertEquals("본진 8상태", 8, names.count { it.startsWith("base_") && it != "base_warning_pulse" })

        assertEquals("플레이어 3종 x 4방향", 12, count("player_"))
        assertEquals("COM 3종 x 4방향", 12, count("enemy_"))

        assertEquals("포탄 2종 x 4방향", 8, count("shell_"))
        assertEquals("총구 화염 4방향", 4, count("muzzle_flash_"))
        assertEquals("폭발 8프레임", 8, count("explosion_"))
        assertEquals("방어막 3프레임", 3, names.count { it.matches(Regex("shield_\\d")) })
        assertEquals("대시 3프레임", 3, count("dash_trail_"))

        // 쿨타임 고리 둘은 뺐다. 쿨타임은 SPECIAL 버튼 자체로 표현한다.
        assertEquals("HUD 아이콘 14종", 14, count("hud_"))
        assertEquals("로비 아이콘 16종", 16, count("lobby_"))
    }

    @Test
    fun `탱크는 네 방향이 모두 그려져 있다`() {
        // 방향별 그림이 있어야 런타임 회전이 필요 없다. 하나라도 빠지면 그 방향에서 터진다.
        val atlas = atlas()
        for (faction in listOf("player", "enemy")) {
            for (type in listOf("attack", "defense", "speed")) {
                for (direction in listOf("up", "right", "down", "left")) {
                    val name = "${faction}_${type}_$direction"
                    assertTrue("$name 이 없다", atlas.contains(name))
                }
            }
        }
    }

    @Test
    fun `비트맵 폰트 글리프가 모두 들어 있다`() {
        // 새 에셋 팩에는 글자가 없다. 기존 아틀라스에서 옮겨 온 것이라 더 쉽게 빠진다.
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
        assertTrue(atlas().names.none { it.endsWith(".png") })
    }

    @Test
    fun `UV 는 0~1 안에 들어오고 뒤집히지 않는다`() {
        val atlas = atlas()
        for (name in atlas.names) {
            val region = atlas[name]
            assertTrue("$name u0", region.u0 in 0f..1f)
            assertTrue("$name v0", region.v0 in 0f..1f)
            assertTrue("$name u1", region.u1 in 0f..1f)
            assertTrue("$name v1", region.v1 in 0f..1f)
            assertTrue("$name 가로 뒤집힘", region.u1 > region.u0)
            assertTrue("$name 세로 뒤집힘", region.v1 > region.v0)
        }
    }

    @Test
    fun `이어 붙는 타일은 정확히 한 블록 정사각이다`() {
        // 한 픽셀이라도 어긋나면 화면 전체에 격자 무늬가 생긴다.
        val atlas = atlas()
        val seamless = atlas.names.filter {
            it.startsWith("ground_") || it.startsWith("water_") || it.startsWith("ice_")
        }
        assertTrue(seamless.isNotEmpty())
        for (name in seamless) {
            val region = atlas[name]
            assertEquals("$name 가로", 64, region.width)
            assertEquals("$name 세로", 64, region.height)
        }
    }

    @Test
    fun `없는 스프라이트를 찾으면 실패한다`() {
        assertNull(atlas().find("있을리없는이름"))
    }

    @Test
    fun `블록 사분면으로 쪼개면 원본을 4등분한다`() {
        val region = atlas()["brick_intact_a"]
        val quadrant = region.sub(1, 0, 2, 2)
        assertEquals(region.width / 2, quadrant.width)
        assertEquals(region.height / 2, quadrant.height)
        assertTrue(quadrant.u0 > region.u0)
        assertEquals(region.u1, quadrant.u1, 1e-6f)
    }

    @Test
    fun `아래에서 차오르는 띠는 위쪽을 잘라낸다`() {
        // SPECIAL 버튼이 이 방식으로 차오른다.
        val region = atlas()["hud_special_button_normal"]
        val half = region.bottomBand(0.5f)
        assertTrue("아래 끝은 그대로다", half.v1 == region.v1)
        assertTrue("위쪽이 잘렸다", half.v0 > region.v0)
        assertEquals(region.height / 2, half.height)
        assertEquals(region, region.bottomBand(1f))
    }

    @Test
    fun `매니페스트가 참조하는 모든 스프라이트가 아틀라스 안에 있다`() {
        val atlas = atlas()
        val missing = referencedSprites().filterNot { atlas.contains(it) }
        assertTrue("아틀라스에 없는 스프라이트: $missing", missing.isEmpty())
    }

    /** 매니페스트에서 스프라이트 이름으로 보이는 문자열을 모두 긁는다. */
    private fun referencedSprites(): List<String> {
        val names = ArrayList<String>()
        val directions = listOf("up", "right", "down", "left")

        fun add(name: String?) {
            if (name != null && name.isNotBlank()) names += name
        }

        for (type in listOf("BRICK", "STEEL", "FOREST", "ICE")) {
            names += manifest.tileSprites(type)
        }
        names += manifest.tileFrames("WATER")
        val base = manifest.tile("BASE")
        add(base?.get("intact")?.asString)
        add(base?.get("shielded")?.asString)
        add(base?.get("wreck")?.asString)
        names += base?.get("destroyFrames")?.asStringList.orEmpty()
        names += base?.get("warnFrames")?.asStringList.orEmpty()

        for (group in listOf("grass", "dirt", "gravel")) {
            names += manifest.terrainBase(group)
        }
        for (id in manifest.propGroupIds) {
            names += manifest.propGroup(id)?.get("sprites")?.asStringList.orEmpty()
        }
        for (id in (manifest.root["effects"]?.asObject ?: emptyMap()).keys) {
            names += manifest.effectFrames(id)
        }

        // 방향이 붙는 것들은 조합해서 확인한다.
        for (section in listOf("player", "enemy")) {
            for (type in listOf("ATTACK", "DEFENSE", "SPEED")) {
                val prefix = manifest.root["tanks"]?.get(section)?.get(type)?.get("prefix")?.asString
                if (prefix != null) names += directions.map { "${prefix}_$it" }
            }
        }
        for (key in listOf("normal", "piercing", "muzzleFlash")) {
            val prefix = manifest.root["projectiles"]?.get(key)?.get("prefix")?.asString
            if (prefix != null) names += directions.map { "${prefix}_$it" }
        }

        for (key in listOf("life", "lifeEmpty", "skull", "kill", "panel", "dead")) {
            add(manifest.hud(key))
        }
        for (group in listOf("cooldown", "network", "controls")) {
            for ((key, value) in manifest.root["hud"]?.get(group)?.asObject ?: emptyMap()) {
                if (key != "comment") add(value.asString)
            }
        }
        add(manifest.root["tanks"]?.get("slotMarker")?.get("sprite")?.asString)

        assertTrue("참조 스프라이트를 하나도 못 찾았다", names.size > 60)
        return names.distinct()
    }
}
