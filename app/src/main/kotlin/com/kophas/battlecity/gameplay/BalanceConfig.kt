package com.kophas.battlecity.gameplay

import com.kophas.battlecity.render.AssetSource
import com.kophas.battlecity.util.JsonValue
import kotlin.math.floor
import kotlin.math.max

/**
 * `assets/manifest/balance.json` 리더.
 *
 * 능력치와 게임 규칙을 코드에 하드코딩하지 않는다. 밸런스 조정은 이 파일만 고친다.
 * (계획서 §7 중요 구현 원칙, §41-19)
 *
 * 계획서의 능력치는 1~4 등급값이다. 실제 단위(px/s, 초)로 바꾸는 계수는
 * [Units] 에 따로 두어, 등급표는 그대로 두고 감각만 조정할 수 있게 한다.
 */
class BalanceConfig(val root: JsonValue) {

    val version: Int = root["version"]?.asInt ?: 0

    // -----------------------------------------------------------------------

    data class Rules(
        val livesPerPlayer: Int,
        val maxHp: Int,
        /**
         * 본진이 견디는 발수. 보호막(방 설정)은 그 위에 한 발을 더 막는다.
         *
         * 한 발에 판이 끝나면 COM 이 본진에 닿는 순간 손 쓸 도리가 없다.
         */
        val baseHits: Int,
        val respawnDelaySeconds: Float,
        val spawnGuardSeconds: Float,
        val defenseCoefficient: Float,
        val minDamage: Int,
        /** 데미지 1당 깎이는 HP. 계획서의 정수 데미지를 HP 스케일로 옮긴다. */
        val damageUnitHp: Int,
        val enemiesPerPlayer: Int,
        val enemySpawnIntervalSeconds: Float,
        val killScore: Int,
        val friendlyKillScore: Int,
        private val maxActiveEnemiesByPlayers: Map<Int, Int>,
    ) {
        fun totalEnemies(playerCount: Int): Int = playerCount * enemiesPerPlayer

        fun maxActiveEnemies(playerCount: Int): Int =
            maxActiveEnemiesByPlayers[playerCount] ?: DEFAULT_MAX_ACTIVE

        private companion object {
            const val DEFAULT_MAX_ACTIVE = 8
        }
    }

    val rules: Rules = root["rules"].let { node ->
        Rules(
            livesPerPlayer = node?.get("livesPerPlayer")?.asInt ?: 3,
            maxHp = node?.get("maxHp")?.asInt ?: 100,
            baseHits = (node?.get("baseHits")?.asInt ?: 1).coerceAtLeast(1),
            respawnDelaySeconds = node?.get("respawnDelaySeconds")?.asFloat ?: 2f,
            spawnGuardSeconds = node?.get("spawnGuardSeconds")?.asFloat ?: 1.5f,
            defenseCoefficient = node?.get("defenseCoefficient")?.asFloat ?: 0.5f,
            minDamage = node?.get("minDamage")?.asInt ?: 1,
            damageUnitHp = node?.get("damageUnitHp")?.asInt ?: 25,
            enemiesPerPlayer = node?.get("enemiesPerPlayer")?.asInt ?: 20,
            enemySpawnIntervalSeconds = node?.get("enemySpawnIntervalSeconds")?.asFloat ?: 2.5f,
            killScore = node?.get("killScore")?.asInt ?: 1,
            friendlyKillScore = node?.get("friendlyKillScore")?.asInt ?: 0,
            maxActiveEnemiesByPlayers = (node?.get("maxActiveEnemies")?.asObject ?: emptyMap())
                .mapNotNull { (key, value) ->
                    val players = key.toIntOrNull()
                    val limit = value.asInt
                    if (players != null && limit != null) players to limit else null
                }
                .toMap(),
        )
    }

    // -----------------------------------------------------------------------

    data class Units(
        val moveSpeedBase: Float,
        val moveSpeedPerRank: Float,
        val fireCooldownBase: Float,
        val fireCooldownPerRank: Float,
        val fireCooldownMin: Float,
        val projectileSpeed: Float,
        val piercingProjectileSpeed: Float,
        /**
         * COM 에만 곱하는 계수. 등급표와 사람 탱크는 그대로 두고 COM 만 무디게 한다.
         *
         * 등급을 내리는 방법도 있지만 한 등급이 너무 크다 — 이동은 48px/s, 쿨타임은
         * 0.18초씩 움직인다. 살짝 무디게 하려면 계수 쪽이 맞다.
         */
        val enemyMoveSpeedScale: Float,
        val enemyFireCooldownScale: Float,
    ) {
        /** 이동속도 등급(1~4) -> 논리 px/초. [scale] 이 작으면 느려진다. */
        fun moveSpeedOf(rank: Int, scale: Float = 1f): Float =
            (moveSpeedBase + moveSpeedPerRank * rank) * scale

        /**
         * 연사속도 등급(1~4) -> 발사 쿨타임(초). 등급이 높을수록 짧아진다.
         *
         * [scale] 이 1 보다 크면 쿨타임이 길어져 연사가 느려진다. 계수를 먼저 곱하고
         * 하한을 본다. 하한을 먼저 보면 계수가 묻힌다.
         */
        fun fireCooldownOf(rank: Int, scale: Float = 1f): Float =
            max(fireCooldownMin, (fireCooldownBase - fireCooldownPerRank * rank) * scale)
    }

    val units: Units = root["units"].let { node ->
        Units(
            moveSpeedBase = node?.get("moveSpeedBasePxPerSecond")?.asFloat ?: 32f,
            moveSpeedPerRank = node?.get("moveSpeedPerRankPxPerSecond")?.asFloat ?: 48f,
            fireCooldownBase = node?.get("fireCooldownBaseSeconds")?.asFloat ?: 0.9f,
            fireCooldownPerRank = node?.get("fireCooldownPerRankSeconds")?.asFloat ?: 0.18f,
            fireCooldownMin = node?.get("fireCooldownMinSeconds")?.asFloat ?: 0.15f,
            projectileSpeed = node?.get("projectileSpeedPxPerSecond")?.asFloat ?: 448f,
            piercingProjectileSpeed = node?.get("piercingProjectileSpeedPxPerSecond")?.asFloat ?: 560f,
            enemyMoveSpeedScale = node?.get("enemyMoveSpeedScale")?.asFloat ?: 1f,
            enemyFireCooldownScale = node?.get("enemyFireCooldownScale")?.asFloat ?: 1f,
        )
    }

    // -----------------------------------------------------------------------

    enum class Special { NONE, PIERCING, SHIELD, DASH }

    /** 계획서 §7 밸런스 표를 그대로 담는다. 등급값이라 단위가 없다. */
    data class TankStats(
        val type: Tank.Type,
        val label: String,
        val attackPower: Int,
        val defensePower: Int,
        val moveSpeedRank: Int,
        val fireRateRank: Int,
        val special: Special,
        val specialCooldownSeconds: Float,
        val specialDurationSeconds: Float,
        /** 방어형 방어막의 피해 감소율. */
        val damageReduction: Float,
        /** 스피드형 대시의 속도 배수. */
        val speedMultiplier: Float,
    )

    val playerTanks: Map<Tank.Type, TankStats> = parseTanks(root["playerTanks"])

    val enemyTanks: Map<Tank.Type, TankStats> = parseTanks(root["enemyTanks"])

    fun statsFor(faction: Tank.Faction, type: Tank.Type): TankStats {
        val table = if (faction == Tank.Faction.PLAYER) playerTanks else enemyTanks
        return table[type] ?: table.values.first()
    }

    fun moveSpeedFor(faction: Tank.Faction, type: Tank.Type): Float =
        units.moveSpeedOf(statsFor(faction, type).moveSpeedRank, moveScaleOf(faction))

    fun fireCooldownFor(faction: Tank.Faction, type: Tank.Type): Float =
        units.fireCooldownOf(statsFor(faction, type).fireRateRank, fireScaleOf(faction))

    /** COM 만 무디게 하는 계수. 사람 탱크는 1 이다. */
    private fun moveScaleOf(faction: Tank.Faction): Float =
        if (faction == Tank.Faction.ENEMY) units.enemyMoveSpeedScale else 1f

    private fun fireScaleOf(faction: Tank.Faction): Float =
        if (faction == Tank.Faction.ENEMY) units.enemyFireCooldownScale else 1f

    private fun parseTanks(node: JsonValue?): Map<Tank.Type, TankStats> =
        (node?.asObject ?: emptyMap()).mapNotNull { (key, value) ->
            val type = runCatching { Tank.Type.valueOf(key) }.getOrNull() ?: return@mapNotNull null
            type to TankStats(
                type = type,
                label = value["label"]?.asString ?: key,
                attackPower = value["attackPower"]?.asInt ?: 1,
                defensePower = value["defensePower"]?.asInt ?: 1,
                moveSpeedRank = value["moveSpeed"]?.asInt ?: 1,
                fireRateRank = value["fireRate"]?.asInt ?: 1,
                special = runCatching {
                    Special.valueOf(value["special"]?.asString ?: "NONE")
                }.getOrDefault(Special.NONE),
                specialCooldownSeconds = value["specialCooldownSeconds"]?.asFloat ?: 0f,
                specialDurationSeconds = value["specialDurationSeconds"]?.asFloat ?: 0f,
                damageReduction = value["damageReduction"]?.asFloat ?: 0f,
                speedMultiplier = value["speedMultiplier"]?.asFloat ?: 1f,
            )
        }.toMap()

    // -----------------------------------------------------------------------

    /** COM 타입 출현 가중치. 합이 100 이 아니어도 정규화한다. */
    val enemyMix: Map<Tank.Type, Int> =
        (root["enemyMix"]?.asObject ?: emptyMap()).mapNotNull { (key, value) ->
            val type = runCatching { Tank.Type.valueOf(key) }.getOrNull() ?: return@mapNotNull null
            val weight = value.asInt ?: return@mapNotNull null
            type to weight
        }.toMap()

    /** 동점 처리 우선순위. (계획서 §16.2) */
    val tieBreakOrder: List<String> =
        root["tieBreak"]?.get("order")?.asStringList ?: listOf("KILLS", "LIVES", "HP", "SHARED")

    // -----------------------------------------------------------------------

    /**
     * 피해량. (계획서 §13)
     *
     * ```
     * Damage = max(minDamage, floor(공격력 - 방어력 x 계수))
     * ```
     * 계획서의 예시(3 vs 1 -> 2, 2 vs 3 -> 1)와 맞으려면 반올림이 아니라
     * 내림이어야 한다. 정수 단위로 떨어지므로 밸런싱도 예측하기 쉽다.
     * 결과는 HP 단위로 환산해 돌려준다.
     */
    fun damageOf(attackPower: Int, defensePower: Int): Int {
        val raw = attackPower - defensePower * rules.defenseCoefficient
        val steps = max(rules.minDamage, floor(raw).toInt())
        return steps * rules.damageUnitHp
    }

    companion object {
        const val DEFAULT_PATH = "manifest/balance.json"

        fun load(source: AssetSource, path: String = DEFAULT_PATH): BalanceConfig =
            BalanceConfig(JsonValue.parse(source.readText(path)))
    }
}
