package com.kophas.battlecity.render

import com.kophas.battlecity.core.Direction
import com.kophas.battlecity.gameplay.Explosion
import com.kophas.battlecity.gameplay.Tank

/**
 * 매니페스트의 스프라이트 이름을 실제 [TextureRegion] 으로 한 번만 풀어 둔다.
 *
 * 렌더 루프에서 문자열 조회가 일어나지 않도록 시작할 때 전부 해석한다.
 * 리소스를 바꿔도 여기만 다시 읽으면 되고 그리는 코드는 그대로다.
 *
 * 탱크와 포탄은 **방향별 그림이 따로** 있다. 그래서 회전값이 없다.
 * 픽셀아트를 돌리면 가장자리가 뭉개지는데 그 문제가 통째로 사라진다.
 */
class SpriteCatalog(private val assets: GameAssets) {

    private val manifest = assets.manifest

    private fun node(vararg path: String) = path.fold(manifest.root as com.kophas.battlecity.util.JsonValue?) { acc, key ->
        acc?.get(key)
    }

    private fun regions(vararg path: String): List<TextureRegion> =
        (node(*path)?.asStringList ?: emptyList()).map { assets[it] }

    // --- 타일 -------------------------------------------------------------

    /** 물결 프레임. 전용 그림이 4장이라 프레임 교대만으로 넘실거린다. */
    val waterFrames: List<TextureRegion> =
        manifest.tileFrames("WATER").map { assets[it] }.ifEmpty { listOf(assets["water_frame_1"]) }

    val waterAnimFps: Float = manifest.tile("WATER")?.get("animFps")?.asFloat ?: 4f

    /** 얼음. 전용 타일이 있어 물을 물들여 쓰지 않는다. */
    val iceTiles: List<TextureRegion> =
        manifest.tileSprites("ICE").map { assets[it] }.ifEmpty { listOf(assets["ice_clean_a"]) }

    // --- 본진 -------------------------------------------------------------

    private val base = node("tiles", "BASE")

    val baseIntact: TextureRegion = assets[base?.get("intact")?.asString ?: "base_intact"]

    /** 파괴 애니메이션. 손상 -> 심한 손상 -> 파괴 순으로 재생한다. */
    val baseDestroyFrames: List<TextureRegion> =
        regions("tiles", "BASE", "destroyFrames").ifEmpty { listOf(baseIntact) }

    val baseDestroyFps: Float = base?.get("destroyFps")?.asFloat ?: 6f

    /** 폭발이 끝난 뒤 남는 잔해. */
    val baseWreck: TextureRegion = assets[base?.get("wreck")?.asString ?: "base_rubble"]

    // --- 탱크 -------------------------------------------------------------

    /** 스프라이트 캔버스가 차체보다 넓다. 포신이 밖으로 뻗기 때문이다. */
    val tankSpritePx: Float = node("world", "tankSpritePx")?.asFloat ?: 96f

    private val directionSuffix: List<String> =
        node("tanks", "directions")?.asStringList ?: listOf("up", "right", "down", "left")

    /** [faction]/[type] 별 4방향 그림. Direction.ordinal 로 바로 찾는다. */
    private val tankArt: Map<Pair<Tank.Faction, Tank.Type>, Array<TextureRegion>> =
        Tank.Faction.entries.flatMap { faction ->
            val section = if (faction == Tank.Faction.PLAYER) "player" else "enemy"
            Tank.Type.entries.map { type ->
                val prefix = node("tanks", section, type.name, "prefix")?.asString
                    ?: "${section}_${type.name.lowercase()}"
                (faction to type) to Array(Direction.VALUES.size) { i ->
                    assets["${prefix}_${directionSuffix.getOrElse(i) { "up" }}"]
                }
            }
        }.toMap()

    fun tankSprite(tank: Tank): TextureRegion =
        tankArt.getValue(tank.faction to tank.type)[tank.direction.ordinal]

    /** HUD 에 쓰는 정면 초상. 어떤 탱크를 골랐는지 한눈에 보인다. */
    fun playerPortrait(type: Tank.Type): TextureRegion =
        tankArt.getValue(Tank.Faction.PLAYER to type)[Direction.UP.ordinal]

    /**
     * 플레이어 머리 위 표식.
     *
     * 탱크 색은 **종류**가 정하므로 같은 종류를 고른 두 사람이 구별되지 않는다.
     * 슬롯 색 표식을 띄워 누가 누구인지 알 수 있게 한다.
     */
    class SlotMarker(
        val region: TextureRegion,
        val colors: IntArray,
        val sizeRatio: Float,
        val offsetRatio: Float,
    ) {
        fun colorOf(slot: Int): Int =
            if (colors.isEmpty()) 0xFFFFFF else colors[slot.coerceIn(0, colors.lastIndex)]
    }

    val slotMarker = SlotMarker(
        region = assets[node("tanks", "slotMarker", "sprite")?.asString ?: "hud_heart_full"],
        colors = (node("tanks", "slotMarker", "colors")?.asStringList ?: emptyList())
            .map { parseColor(it, 0xFFFFFF) }.toIntArray(),
        sizeRatio = node("tanks", "slotMarker", "sizeRatio")?.asFloat ?: 0.34f,
        offsetRatio = node("tanks", "slotMarker", "offsetRatio")?.asFloat ?: -0.62f,
    )

    // --- 포탄 -------------------------------------------------------------

    private fun directional(prefix: String): Array<TextureRegion> =
        Array(Direction.VALUES.size) { i ->
            assets["${prefix}_${directionSuffix.getOrElse(i) { "up" }}"]
        }

    private val normalShell = directional(
        node("projectiles", "normal", "prefix")?.asString ?: "shell_normal",
    )

    private val piercingShell = directional(
        node("projectiles", "piercing", "prefix")?.asString ?: "shell_piercing",
    )

    private val muzzleFlash = directional(
        node("projectiles", "muzzleFlash", "prefix")?.asString ?: "muzzle_flash",
    )

    fun shellSprite(direction: Direction, piercing: Boolean): TextureRegion =
        (if (piercing) piercingShell else normalShell)[direction.ordinal]

    fun muzzleFlash(direction: Direction): TextureRegion = muzzleFlash[direction.ordinal]

    // --- 특수기 연출 (계획서 §6) ------------------------------------------

    class FrameArt(val frames: List<TextureRegion>, val fps: Float) {
        fun at(timeSeconds: Float): TextureRegion =
            frames[((timeSeconds * fps).toInt().coerceAtLeast(0)) % frames.size]

        /** 0~1 진행도에 맞춰 한 번만 재생한다. */
        fun atProgress(progress: Float): TextureRegion =
            frames[(progress.coerceIn(0f, 1f) * (frames.size - 1)).toInt()]
    }

    private fun frameArt(id: String, fallback: String): FrameArt {
        val names = (node("effects", id, "frames")?.asStringList ?: emptyList())
            .ifEmpty { listOf(fallback) }
        return FrameArt(names.map { assets[it] }, node("effects", id, "fps")?.asFloat ?: 12f)
    }

    /** 방어막. 지속되는 동안 프레임을 돌린다. */
    val shieldFx: FrameArt = frameArt("shield", "shield_2")

    /** 대시. 진행 반대쪽에 잔상을 남긴다. */
    val dashFx: FrameArt = frameArt("dash", "dash_trail_2")

    val baseWarningFx: FrameArt = frameArt("baseWarning", "base_warning_pulse")

    // --- HUD --------------------------------------------------------------

    val heart: TextureRegion = assets[manifest.hud("life") ?: "hud_heart_full"]

    val heartEmpty: TextureRegion = assets[manifest.hud("lifeEmpty") ?: "hud_heart_empty"]

    val skull: TextureRegion = assets[manifest.hud("skull") ?: "hud_enemy_counter"]

    val killIcon: TextureRegion = assets[manifest.hud("kill") ?: "hud_kill_explosion"]

    val panel: TextureRegion = assets[manifest.hud("panel") ?: "hud_status_panel"]

    val deadIcon: TextureRegion = assets[manifest.hud("dead") ?: "hud_player_dead"]

    /**
     * 특수기 쿨타임 고리.
     *
     * 남은 양에 따라 그림을 바꿔 끼우지 않는다. 한 장을 어둡게 깔고 차오른 만큼만
     * 밝게 덧그린다. 그림을 바꾸면 반쯤 찼을 때 아이콘이 툭 바뀌어 눈에 거슬린다.
     */
    val cooldownRing: TextureRegion =
        assets[node("hud", "cooldown", "ring")?.asString ?: "hud_cooldown_75"]

    private val digits: List<TextureRegion> =
        (0..9).map { assets["${manifest.hudDigitPrefix}$it"] }

    private val chars: Map<Char, TextureRegion> =
        ('A'..'Z').associateWith { assets["${manifest.hudCharPrefix}$it"] }

    fun digit(value: Int): TextureRegion = digits[value.coerceIn(0, 9)]

    /** 비트맵 폰트 글리프. 지원하지 않는 문자는 null 이라 공백으로 넘어간다. */
    fun glyph(char: Char): TextureRegion? = when (char) {
        in '0'..'9' -> digits[char - '0']
        in 'A'..'Z' -> chars[char]
        in 'a'..'z' -> chars[char.uppercaseChar()]
        else -> null
    }

    // --- 이펙트 -----------------------------------------------------------

    private val effects: Map<Explosion.Kind, FrameArt> = mapOf(
        Explosion.Kind.TANK to frameArt("tankExplosion", "explosion_4"),
        Explosion.Kind.BULLET_HIT to frameArt("bulletHit", "impact_small"),
        Explosion.Kind.BRICK_BREAK to frameArt("brickBreak", "debris_brick"),
        Explosion.Kind.SPAWN to frameArt("spawn", "spawn_glow"),
    )

    fun effectOf(kind: Explosion.Kind): FrameArt = effects.getValue(kind)

    // --- 스테이지 스프라이트 ----------------------------------------------

    /** [names] 를 그대로 해석한다. 없는 이름은 예외로 바로 드러난다. */
    fun resolveAll(names: Array<String>): Array<TextureRegion> =
        Array(names.size) { assets[names[it]] }

    private fun parseColor(text: String?, fallback: Int): Int {
        if (text == null || !text.startsWith("#")) return fallback
        return runCatching { text.substring(1).toLong(16).toInt() }.getOrDefault(fallback)
    }
}
