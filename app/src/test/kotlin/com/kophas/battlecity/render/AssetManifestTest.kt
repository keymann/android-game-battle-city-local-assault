package com.kophas.battlecity.render

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 매니페스트가 게임 의미를 리소스에 제대로 이어 주는지 본다.
 *
 * 값 하나하나를 외우는 테스트가 아니라, **그 값이 없으면 게임이 어떻게 깨지는지**
 * 를 기준으로 골랐다. 리소스를 갈아 끼워도 이 검사는 그대로 살아남아야 한다.
 */
class AssetManifestTest {

    private val assetsDir: File = sequenceOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).firstOrNull { it.isDirectory } ?: error("assets 디렉터리를 찾지 못했다")

    private val manifest = AssetManifest.load(AssetSource.ofDirectory(assetsDir))

    // --- 아틀라스 ---------------------------------------------------------

    @Test
    fun `아틀라스는 한 장이고 nearest 로 뽑는다`() {
        assertEquals(listOf("game"), manifest.atlases.keys.toList())
        val spec = manifest.atlases.getValue("game")
        assertEquals("atlas/game.png", spec.image)
        assertEquals("atlas/game.xml", spec.descriptor)
        // 탱크가 4방향으로 미리 그려져 있어 런타임 회전이 없다. 그래서 전부 NEAREST 다.
        assertEquals("nearest", spec.filter)
    }

    // --- 월드 규격 --------------------------------------------------------

    @Test
    fun `한 블록은 셀 두 칸이고 탱크 한 대다`() {
        val world = manifest.world
        assertEquals(64f, world.blockPx, 1e-3f)
        assertEquals(32f, world.cellPx, 1e-3f)
        assertEquals(2, world.cellsPerBlock)
        assertEquals("탱크 차체는 정확히 한 블록이다", world.blockPx, world.tankSizePx, 1e-3f)
    }

    @Test
    fun `탱크 스프라이트는 차체보다 넓다`() {
        // 포신이 차체 밖으로 뻗는다. 캔버스가 차체와 같으면 포신이 잘린다.
        val sprite = manifest.root["world"]?.get("tankSpritePx")?.asFloat
        assertNotNull("tankSpritePx 가 없다", sprite)
        assertTrue("$sprite <= ${manifest.world.tankSizePx}", sprite!! > manifest.world.tankSizePx)
    }

    // --- 타일 -------------------------------------------------------------

    @Test
    fun `부술 수 있는 벽과 없는 벽이 나뉜다`() {
        assertEquals(true, manifest.tile("BRICK")?.get("destructible")?.asBoolean)
        // 강철은 관통탄으로만 부순다. (계획서 §6.1)
        assertEquals("piercingOnly", manifest.tile("STEEL")?.get("destructible")?.asString)
    }

    @Test
    fun `벽은 여러 무늬를 섞어 쓴다`() {
        // 한 무늬만 쓰면 벽면이 장판처럼 보인다.
        assertTrue(manifest.tileSprites("BRICK").size >= 2)
        assertTrue(manifest.tileSprites("STEEL").size >= 2)
    }

    @Test
    fun `물은 프레임을 여러 장 가지고 넘실거린다`() {
        val frames = manifest.tileFrames("WATER")
        assertTrue("프레임이 $frames 뿐이다", frames.size >= 3)
        assertEquals(frames.size, frames.distinct().size)
        assertTrue((manifest.tile("WATER")?.get("animFps")?.asFloat ?: 0f) > 0f)
    }

    @Test
    fun `얼음은 전용 타일을 쓴다`() {
        // 예전에는 물 타일을 밝게 물들여 썼다. 이제 전용 그림이 있다.
        val ice = manifest.tileSprites("ICE")
        assertTrue("얼음 타일이 없다", ice.isNotEmpty())
        assertTrue("물 타일을 재탕하면 안 된다", ice.none { it.startsWith("water_") })
        assertTrue((manifest.tile("ICE")?.get("friction")?.asFloat ?: 0f) > 0f)
    }

    @Test
    fun `숲은 탱크를 가리는 캐노피다`() {
        assertEquals(true, manifest.tile("FOREST")?.get("conceal")?.asBoolean)
        assertEquals("canopy", manifest.tile("FOREST")?.get("renderLayer")?.asString)
        assertTrue(manifest.tileSprites("FOREST").isNotEmpty())
    }

    @Test
    fun `본진은 정상과 파괴 사이 단계를 가진다`() {
        val base = manifest.tile("BASE")
        assertNotNull(base)
        assertEquals("base_intact", base!!["intact"]?.asString)
        val frames = base["destroyFrames"]?.asStringList.orEmpty()
        assertTrue("파괴 단계가 $frames 뿐이다", frames.size >= 2)
        assertNotNull("잔해가 없으면 파괴 후 그릴 것이 없다", base["wreck"]?.asString)
        // 원작의 ㄷ자 벽돌 방벽.
        assertEquals("BRICK", base["wallTile"]?.asString)
        assertEquals("U", base["wallShape"]?.asString)
    }

    // --- 탱크 -------------------------------------------------------------

    @Test
    fun `탱크는 방향별 그림을 접두사로 찾는다`() {
        val directions = manifest.root["tanks"]?.get("directions")?.asStringList
        assertEquals(listOf("up", "right", "down", "left"), directions)

        for (section in listOf("player", "enemy")) {
            for (type in listOf("ATTACK", "DEFENSE", "SPEED")) {
                val prefix = manifest.root["tanks"]?.get(section)?.get(type)?.get("prefix")?.asString
                assertNotNull("$section.$type 접두사가 없다", prefix)
            }
        }
    }

    @Test
    fun `플레이어와 COM 은 서로 다른 그림을 쓴다`() {
        val player = listOf("ATTACK", "DEFENSE", "SPEED").map {
            manifest.root["tanks"]?.get("player")?.get(it)?.get("prefix")?.asString
        }
        val enemy = listOf("ATTACK", "DEFENSE", "SPEED").map {
            manifest.root["tanks"]?.get("enemy")?.get(it)?.get("prefix")?.asString
        }
        assertEquals("여섯 갈래가 모두 달라야 한다", 6, (player + enemy).distinct().size)
        assertTrue("적아 구분이 안 되면 게임이 성립하지 않는다", (player intersect enemy.toSet()).isEmpty())
    }

    @Test
    fun `슬롯 표식 색이 플레이어 수만큼 있다`() {
        // 탱크 색은 종류가 정한다. 같은 종류를 고른 두 사람은 이 표식으로만 구분된다.
        val colors = manifest.root["tanks"]?.get("slotMarker")?.get("colors")?.asStringList.orEmpty()
        assertEquals(4, colors.size)
        assertEquals("색이 겹치면 구분이 안 된다", 4, colors.distinct().size)
        assertTrue(colors.all { it.startsWith("#") })
    }

    // --- 포탄과 이펙트 ----------------------------------------------------

    @Test
    fun `포탄도 방향별로 그려져 있다`() {
        for (key in listOf("normal", "piercing", "muzzleFlash")) {
            val prefix = manifest.root["projectiles"]?.get(key)?.get("prefix")?.asString
            assertNotNull("projectiles.$key.prefix 가 없다", prefix)
        }
    }

    @Test
    fun `폭발은 여러 프레임으로 재생된다`() {
        val frames = manifest.effectFrames("tankExplosion")
        assertTrue("프레임이 $frames 뿐이다", frames.size >= 4)
        assertEquals(frames.size, frames.distinct().size)
    }

    @Test
    fun `특수기마다 연출이 있다`() {
        // 셋 다 없으면 무엇이 걸렸는지 화면으로 알 수 없다. (계획서 §6)
        for (id in listOf("shield", "dash", "spawn")) {
            assertTrue("$id 연출이 없다", manifest.effectFrames(id).isNotEmpty())
        }
    }

    // --- 프롭 -------------------------------------------------------------

    @Test
    fun `프롭 그룹은 종류를 밝힌다`() {
        val ids = manifest.propGroupIds
        assertTrue("프롭 그룹이 없다", ids.isNotEmpty())
        for (id in ids) {
            val group = manifest.propGroup(id)!!
            val kind = group["kind"]?.asString
            assertTrue("$id 의 kind 가 $kind 다", kind in listOf("explosive", "solid", "decor"))
            assertTrue("$id 에 스프라이트가 없다", group["sprites"]?.asStringList.orEmpty().isNotEmpty())
        }
    }

    @Test
    fun `폭발성 프롭은 폭발 반경을 가진다`() {
        val explosive = manifest.propGroupIds
            .map { it to manifest.propGroup(it)!! }
            .filter { (_, group) -> group["kind"]?.asString == "explosive" }
        assertTrue("연쇄 폭발할 프롭이 하나도 없다", explosive.isNotEmpty())
        for ((id, group) in explosive) {
            assertTrue("$id 에 blastCells 가 없다", (group["blastCells"]?.asInt ?: 0) > 0)
        }
    }

    // --- HUD --------------------------------------------------------------

    @Test
    fun `HUD 는 비트맵 폰트 접두사를 서술한다`() {
        assertEquals("ui_digit_", manifest.hudDigitPrefix)
        assertEquals("ui_char_", manifest.hudCharPrefix)
    }

    @Test
    fun `HUD 아이콘이 모두 정의돼 있다`() {
        for (key in listOf("life", "lifeEmpty", "skull", "kill", "panel", "dead")) {
            assertNotNull("hud.$key 가 없다", manifest.hud(key))
        }
    }

    @Test
    fun `조작 UI 아이콘을 미리 잡아 둔다`() {
        // Phase 7 에서 쓴다. 지금 그리지는 않지만 리소스는 이미 있다.
        val controls = manifest.root["hud"]?.get("controls")?.asObject.orEmpty()
        for (key in listOf("joystickBase", "joystickKnob", "fire", "special")) {
            assertNotNull("hud.controls.$key 가 없다", controls[key]?.asString)
        }
    }
}
