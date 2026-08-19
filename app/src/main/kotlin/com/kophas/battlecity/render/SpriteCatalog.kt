package com.kophas.battlecity.render

import com.kophas.battlecity.gameplay.Explosion
import com.kophas.battlecity.gameplay.Tank

/**
 * 매니페스트의 이름/인덱스를 실제 [TextureRegion] 으로 한 번만 풀어 둔다.
 *
 * 렌더 루프에서 문자열 조회가 일어나지 않도록 시작할 때 전부 해석한다.
 * 리소스를 바꿔도 여기만 다시 읽으면 되고 그리는 코드는 그대로다.
 */
class SpriteCatalog(private val assets: GameAssets) {

    private val manifest = assets.manifest

    // --- 타일 -------------------------------------------------------------

    val waterFrames: List<TextureRegion> =
        manifest.tileIndices("WATER").ifEmpty { listOf(37) }.map { assets.tiny[it] }

    val waterAnimFps: Float = manifest.tile("WATER")?.get("animFps")?.asFloat ?: 2f

    val iceFrame: TextureRegion =
        assets.tiny[manifest.tileIndices("ICE").firstOrNull() ?: 37]

    /** 얼음은 물 타일에 틴트를 얹어 표현한다. (docs/ASSET_SELECTION.md §4) */
    val iceTint: Int = parseColor(manifest.tile("ICE")?.get("tint")?.asString, 0xDFF6FFCC.toInt())

    val baseIntact: TextureRegion =
        assets.tiny[manifest.tile("BASE")?.get("intactIndex")?.asInt ?: 70]

    val baseDestroyed: TextureRegion =
        assets.tiny[manifest.tile("BASE")?.get("destroyedIndex")?.asInt ?: 194]

    /**
     * 지형 틴트. (곱연산)
     *
     * 배경인 지형과 전경인 탱크가 같은 색 계열이면(잔디 vs 초록 탱크,
     * 모래 vs 샌드 탱크) 서로 묻혀 버린다. 지형만 어둡게 눌러 명도로 갈라 놓는다.
     */
    val grassTint: Int = parseColor(manifest.root["terrain"]?.get("tint")?.get("grass")?.asString, 0x5C7350)
    val sandTint: Int = parseColor(manifest.root["terrain"]?.get("tint")?.get("sand")?.asString, 0x9E8F78)
    val decorTint: Int = parseColor(manifest.root["terrain"]?.get("tint")?.get("decor")?.asString, 0xB4B4B4)

    /** 탱크 뒤에 까는 어두운 실루엣. 어떤 지형 위에서도 형태가 읽히게 한다. */
    val silhouetteColor: Int =
        parseColor(manifest.root["overlays"]?.get("uses")?.get("silhouette")?.get("tint")?.asString, 0x0D0D12)

    val silhouetteScale: Float =
        manifest.root["overlays"]?.get("uses")?.get("silhouette")?.get("scale")?.asFloat ?: 1.08f

    /** 스프라이트 이름으로 어느 바이옴 타일인지 판별해 틴트를 고른다. MIXED 맵도 정확하다. */
    fun groundTintFor(spriteName: String): Int =
        if (spriteName.startsWith("tileSand")) sandTint else grassTint

    fun outlineOf(region: TextureRegion): TextureRegion? =
        assets.main.find(region.name + OUTLINE_SUFFIX)

    // --- 탱크 -------------------------------------------------------------

    private val barrelByType: Map<String, Int> =
        (manifest.root["tanks"]?.get("barrelByType")?.asObject ?: emptyMap())
            .mapNotNull { (key, value) -> value.asInt?.let { key to it } }
            .toMap()

    private class TankArt(
        val body: TextureRegion,
        val barrels: Map<Tank.Type, TextureRegion>,
        val bullet: TextureRegion,
    )

    private val playerArt: List<TankArt> =
        (manifest.root["tanks"]?.get("playerSlots")?.asArray ?: emptyList()).map { slot ->
            val barrelPrefix = slot["barrelPrefix"]?.asString ?: "tankBlue_barrel"
            val bulletPrefix = slot["bulletPrefix"]?.asString ?: "bulletBlue"
            TankArt(
                body = assets.main[slot["body"]?.asString ?: "tankBody_blue"],
                barrels = Tank.Type.entries.associateWith { type ->
                    assets.main["$barrelPrefix${barrelByType[type.name] ?: 2}"]
                },
                bullet = assets.main["${bulletPrefix}2"],
            )
        }

    private val enemyArt: Map<Tank.Type, TankArt> =
        Tank.Type.entries.associateWith { type ->
            val node = manifest.root["tanks"]?.get("enemy")?.get(type.name)
            TankArt(
                body = assets.main[node?.get("body")?.asString ?: "tankBody_dark"],
                barrels = mapOf(),
                bullet = assets.main[node?.get("bullet")?.asString ?: "bulletDark1"],
            ).let { art ->
                TankArt(
                    body = art.body,
                    barrels = Tank.Type.entries.associateWith {
                        assets.main[node?.get("barrel")?.asString ?: "tankDark_barrel1"]
                    },
                    bullet = art.bullet,
                )
            }
        }

    fun bodyOf(tank: Tank): TextureRegion = artOf(tank).body

    fun barrelOf(tank: Tank): TextureRegion =
        artOf(tank).barrels[tank.type] ?: artOf(tank).body

    fun bulletOf(tank: Tank): TextureRegion = artOf(tank).bullet

    private fun artOf(tank: Tank): TankArt = when (tank.faction) {
        Tank.Faction.PLAYER -> playerArt[tank.colorSlot.coerceIn(0, playerArt.lastIndex)]
        Tank.Faction.ENEMY -> enemyArt.getValue(tank.type)
    }

    // --- 이펙트 -----------------------------------------------------------

    private val explosionFrames: Map<Explosion.Kind, List<TextureRegion>> = mapOf(
        Explosion.Kind.TANK to frames("tankExplosion", "explosion"),
        Explosion.Kind.BULLET_HIT to frames("bulletHit", "explosionSmoke"),
        Explosion.Kind.BRICK_BREAK to frames("brickBreak", "explosionSmoke"),
        Explosion.Kind.SPAWN to frames("spawnPuff", "explosionSmoke"),
    )

    fun explosionFrame(kind: Explosion.Kind, index: Int): TextureRegion {
        val list = explosionFrames.getValue(kind)
        return list[index.coerceIn(0, list.lastIndex)]
    }

    fun explosionFrameCount(kind: Explosion.Kind): Int = explosionFrames.getValue(kind).size

    private fun frames(effectId: String, fallbackPrefix: String): List<TextureRegion> {
        val names = manifest.effectFrames(effectId).ifEmpty { (1..3).map { "$fallbackPrefix$it" } }
        return names.map { assets.main[it] }
    }

    // --- 스테이지 스프라이트 ----------------------------------------------

    /** [names] 를 그대로 해석한다. 없는 이름은 예외로 바로 드러난다. */
    fun resolveAll(names: Array<String>): Array<TextureRegion> =
        Array(names.size) { assets.main[names[it]] }

    private companion object {
        const val OUTLINE_SUFFIX = "_outline"
    }

    private fun parseColor(text: String?, fallback: Int): Int {
        if (text == null || !text.startsWith("#")) return fallback
        return runCatching { text.substring(1).toLong(16).toInt() }.getOrDefault(fallback)
    }
}
