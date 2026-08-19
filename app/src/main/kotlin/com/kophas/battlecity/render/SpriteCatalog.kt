package com.kophas.battlecity.render

import com.kophas.battlecity.gameplay.Explosion
import com.kophas.battlecity.gameplay.Tank

/**
 * 매니페스트의 스프라이트 이름을 실제 [TextureRegion] 으로 한 번만 풀어 둔다.
 *
 * 렌더 루프에서 문자열 조회가 일어나지 않도록 시작할 때 전부 해석한다.
 * 리소스를 바꿔도 여기만 다시 읽으면 되고 그리는 코드는 그대로다.
 */
class SpriteCatalog(private val assets: GameAssets) {

    private val manifest = assets.manifest

    // --- 타일 -------------------------------------------------------------

    val water: TextureRegion = assets[manifest.tileSprite("WATER") ?: "battle_037"]

    val waterAnimFps: Float = manifest.tile("WATER")?.get("animFps")?.asFloat ?: 0.5f

    /**
     * 물결 세기.
     *
     * 잔디가 섞이지 않은 열린 수면 타일이 하나뿐이라 프레임 교대로는 물결을
     * 만들 수 없다. 대신 밝기를 셀 위치에 따라 흔들어 잔물결처럼 보이게 한다.
     */
    val waterShimmer: Float = manifest.tile("WATER")?.get("shimmer")?.asFloat ?: 0.1f

    val ice: TextureRegion = assets[manifest.tileSprite("ICE") ?: "battle_037"]

    val iceTint: Int = parseColor(manifest.tile("ICE")?.get("tint")?.asString, 0xDFF6FFCC.toInt())

    // 본진 건물. 파괴 애니메이션 프레임을 함께 들고 있다.
    val baseIntact: TextureRegion = assets[manifest.tile("BASE")?.get("intact")?.asString ?: "base_0"]

    val baseDestroyFrames: List<TextureRegion> =
        (manifest.tile("BASE")?.get("destroyFrames")?.asStringList ?: emptyList())
            .map { assets[it] }
            .ifEmpty { listOf(baseIntact) }

    val baseDestroyFps: Float = manifest.tile("BASE")?.get("destroyFps")?.asFloat ?: 5f

    /** 폭발이 끝난 뒤 남는 잔해. */
    val baseWreck: TextureRegion = assets[manifest.tile("BASE")?.get("wreck")?.asString ?: "base_2"]

    // --- 탱크 -------------------------------------------------------------
    //
    // 몸체와 포신을 분리해 그린다. 4방향 게임이라 둘의 방향은 늘 같지만,
    // 포신 굵기 1->2->3 이 스피드/방어/공격 서열을 그대로 보여 준다.

    /** 유닛 스프라이트가 위를 향해 그려져 있으면 0 이다. */
    val tankRotationOffset: Float =
        (manifest.root["tanks"]?.get("spriteRotationOffsetDegrees")?.asFloat ?: 0f) *
            (Math.PI.toFloat() / 180f)

    /** 탱크 뒤에 까는 어두운 실루엣. 어떤 지형 위에서도 형태가 읽히게 한다. */
    val silhouetteColor: Int =
        parseColor(manifest.root["tanks"]?.get("silhouette")?.get("tint")?.asString, 0x0D0D12)

    val silhouetteScale: Float =
        manifest.root["tanks"]?.get("silhouette")?.get("scale")?.asFloat ?: 1.05f

    val silhouetteAlpha: Float =
        manifest.root["tanks"]?.get("silhouette")?.get("alpha")?.asFloat ?: 0.45f

    private val barrelByType: Map<String, Int> =
        (manifest.root["tanks"]?.get("barrelByType")?.asObject ?: emptyMap())
            .mapNotNull { (key, value) -> value.asInt?.let { key to it } }
            .toMap()

    private val bulletByType: Map<String, Int> =
        (manifest.root["tanks"]?.get("bulletByType")?.asObject ?: emptyMap())
            .mapNotNull { (key, value) -> value.asInt?.let { key to it } }
            .toMap()

    private class TankArt(
        val body: TextureRegion,
        val outline: TextureRegion?,
        val barrels: Map<Tank.Type, TextureRegion>,
        val bullets: Map<Tank.Type, TextureRegion>,
        val flag: TextureRegion?,
    )

    private val playerArt: List<TankArt> =
        (manifest.root["tanks"]?.get("playerSlots")?.asArray ?: emptyList()).map { slot ->
            val barrelPrefix = slot["barrelPrefix"]?.asString ?: "tankBlue_barrel"
            val bulletPrefix = slot["bulletPrefix"]?.asString ?: "bulletBlue"
            TankArt(
                body = assets[slot["body"]?.asString ?: "tankBody_blue"],
                outline = slot["outline"]?.asString?.let { assets.find(it) },
                barrels = Tank.Type.entries.associateWith { type ->
                    assets["$barrelPrefix${barrelByType[type.name] ?: 2}"]
                },
                bullets = Tank.Type.entries.associateWith { type ->
                    assets["$bulletPrefix${bulletByType[type.name] ?: 2}"]
                },
                flag = slot["flag"]?.asString?.let { assets.find(it) },
            )
        }

    private val enemyArt: Map<Tank.Type, TankArt> =
        Tank.Type.entries.associateWith { type ->
            val node = manifest.root["tanks"]?.get("enemy")?.get(type.name)
            val barrel = assets[node?.get("barrel")?.asString ?: "tankDark_barrel1"]
            val bullet = assets[node?.get("bullet")?.asString ?: "bulletDark1"]
            TankArt(
                body = assets[node?.get("body")?.asString ?: "tankBody_dark"],
                outline = node?.get("outline")?.asString?.let { assets.find(it) },
                barrels = Tank.Type.entries.associateWith { barrel },
                bullets = Tank.Type.entries.associateWith { bullet },
                flag = null,
            )
        }

    private fun artOf(tank: Tank): TankArt = when (tank.faction) {
        Tank.Faction.PLAYER -> playerArt[tank.colorSlot.coerceIn(0, playerArt.lastIndex)]
        Tank.Faction.ENEMY -> enemyArt.getValue(tank.type)
    }

    fun bodyOf(tank: Tank): TextureRegion = artOf(tank).body

    fun outlineOf(tank: Tank): TextureRegion? = artOf(tank).outline

    fun barrelOf(tank: Tank): TextureRegion =
        artOf(tank).barrels[tank.type] ?: artOf(tank).body

    fun bulletOf(tank: Tank): TextureRegion =
        artOf(tank).bullets[tank.type] ?: piercingBullet

    fun playerFlag(slot: Int): TextureRegion =
        playerArt[slot.coerceIn(0, playerArt.lastIndex)].flag ?: heart

    val piercingBullet: TextureRegion =
        assets[manifest.root["projectiles"]?.get("piercing")?.get("sprite")?.asString ?: "shotLarge"]

    // --- 특수기 연출 (계획서 §6) ------------------------------------------

    /** 방어막. 탱크 외곽선을 키우고 청색으로 물들여 맥동시킨다. */
    class ShieldFx(val tint: Int, val alpha: Float, val scale: Float, val pulseHz: Float)

    /** 대시. 진행 반대 방향으로 잔상을 남긴다. */
    class DashFx(val tint: Int, val alpha: Float, val afterImages: Int, val spacingPx: Float)

    private val specialFx = manifest.root["tanks"]?.get("specialFx")

    val shieldFx = ShieldFx(
        tint = parseColor(specialFx?.get("shield")?.get("tint")?.asString, 0x66CCFF),
        alpha = specialFx?.get("shield")?.get("alpha")?.asFloat ?: 0.55f,
        scale = specialFx?.get("shield")?.get("scale")?.asFloat ?: 1.28f,
        pulseHz = specialFx?.get("shield")?.get("pulseHz")?.asFloat ?: 3f,
    )

    val dashFx = DashFx(
        tint = parseColor(specialFx?.get("dash")?.get("tint")?.asString, 0xFFFFFF),
        alpha = specialFx?.get("dash")?.get("alpha")?.asFloat ?: 0.30f,
        afterImages = specialFx?.get("dash")?.get("afterImages")?.asInt ?: 3,
        spacingPx = specialFx?.get("dash")?.get("spacingPx")?.asFloat ?: 13f,
    )

    val piercingTrail: TextureRegion =
        assets[specialFx?.get("piercing")?.get("sprite")?.asString ?: "shotRed"]

    val piercingTint: Int = parseColor(specialFx?.get("piercing")?.get("tint")?.asString, 0xFFD24A)

    val piercingTrailAlpha: Float = specialFx?.get("piercing")?.get("alpha")?.asFloat ?: 0.7f

    val trackDecals: List<TextureRegion> =
        (manifest.root["tanks"]?.get("trackDecal")?.get("sprites")?.asStringList ?: emptyList())
            .map { assets[it] }

    // --- HUD --------------------------------------------------------------

    val heart: TextureRegion = assets[manifest.hudLifeSprite]

    val skull: TextureRegion = assets[manifest.hud("skull") ?: "ui_054"]

    val ammo: TextureRegion = assets[manifest.hud("ammo") ?: "ui_060"]

    val locked: TextureRegion = assets[manifest.hud("locked") ?: "ui_076"]

    private val digits: List<TextureRegion> =
        (0..9).map { assets["${manifest.hudDigitPrefix}$it"] }

    private val chars: Map<Char, TextureRegion> =
        ('A'..'Z').associateWith { assets["${manifest.hudCharPrefix}$it"] }

    fun digit(value: Int): TextureRegion = digits[value.coerceIn(0, 9)]

    /** 특수기 쿨타임 게이지. 가운데 조각을 가로로 늘여 쓴다. */
    val gaugeFill: TextureRegion =
        assets[manifest.root["hud"]?.get("gauge")?.get("fill")?.asString ?: "ui_140"]

    val gaugeTrack: TextureRegion =
        assets[manifest.root["hud"]?.get("gauge")?.get("track")?.asString ?: "ui_158"]

    val gaugeHeightRatio: Float =
        manifest.root["hud"]?.get("gauge")?.get("heightRatio")?.asFloat ?: 0.34f

    /** 비트맵 폰트 글리프. 지원하지 않는 문자는 null 이라 공백으로 넘어간다. */
    fun glyph(char: Char): TextureRegion? = when (char) {
        in '0'..'9' -> digits[char - '0']
        in 'A'..'Z' -> chars[char]
        in 'a'..'z' -> chars[char.uppercaseChar()]
        else -> null
    }

    // --- 이펙트 -----------------------------------------------------------

    class EffectArt(val frames: List<TextureRegion>, val fps: Float)

    private val effects: Map<Explosion.Kind, EffectArt> = mapOf(
        Explosion.Kind.TANK to effect("tankExplosion", "explosion"),
        Explosion.Kind.BULLET_HIT to effect("bulletHit", "explosionSmoke"),
        Explosion.Kind.BRICK_BREAK to effect("brickBreak", "explosionSmoke"),
        Explosion.Kind.SPAWN to effect("spawnPuff", "explosionSmoke"),
    )

    fun effectOf(kind: Explosion.Kind): EffectArt = effects.getValue(kind)

    private fun effect(id: String, fallbackPrefix: String): EffectArt {
        val node = manifest.root["effects"]?.get(id)
        val names = node?.get("frames")?.asStringList.orEmpty()
            .ifEmpty { (1..3).map { "$fallbackPrefix$it" } }
        return EffectArt(
            frames = names.map { assets[it] },
            fps = node?.get("fps")?.asFloat ?: 18f,
        )
    }

    // --- 스테이지 스프라이트 ----------------------------------------------

    /** [names] 를 그대로 해석한다. 없는 이름은 예외로 바로 드러난다. */
    fun resolveAll(names: Array<String>): Array<TextureRegion> =
        Array(names.size) { assets[names[it]] }

    private fun parseColor(text: String?, fallback: Int): Int {
        if (text == null || !text.startsWith("#")) return fallback
        return runCatching { text.substring(1).toLong(16).toInt() }.getOrDefault(fallback)
    }
}
