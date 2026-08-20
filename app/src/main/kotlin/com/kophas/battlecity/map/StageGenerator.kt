package com.kophas.battlecity.map

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.render.AssetManifest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 룰 기반 랜덤 스테이지 생성기. (assets/RANDOM_MAP_ASSET_GUIDE.md)
 *
 * 가이드의 §8 생성 순서를 그대로 따른다. 가장 중요한 원칙은 **경로를 장애물보다
 * 먼저 만든다** 는 것이다. 장애물을 무작위로 뿌린 뒤 빈 곳을 찾는 방식은 막힌 스폰과
 * 불공정한 본진 경로를 쉽게 만든다.
 *
 * ```
 * 1 예약     본진 / 플레이어 스폰 / 적 스폰과 그 주변
 * 2 경로     본진 -> 중앙 -> 각 적 스폰. 폭 2. 되돌아오는 순환로까지
 * 3 지형     물 -> 강철 -> 벽돌 -> 숲 -> 얼음 순으로 군집 배치
 * 4 소품     모래주머니 / 상자 / 드럼통 / 바위
 * 5 검증     연결성 / 본진 출구 / 공정성 -> 점수
 * 6 수선     실패하면 고치고 다시 검증. 8번 넘으면 seed 를 버린다
 * 7 그림     좌표 해시로 variation 선택
 * ```
 *
 * ### 좌표계
 *
 * 가이드가 말하는 "타일" 은 이 게임의 **block(64px)** 이다. 지형 한 장이 탱크 한 대
 * 크기라는 원작 규칙과 같다. 파괴는 그보다 잘게 **cell(32px)** 단위로 일어나므로,
 * 완성 단계에서 블록 하나를 셀 2x2 로 펼쳐 [StageData] 에 담는다.
 *
 * 연결성 검증만은 셀 격자에서 **탱크 발자국(2x2 셀)** 기준으로 한다. 블록 단위로
 * 재면 실제로는 못 지나가는 틈을 통과할 수 있다고 착각한다. AI 가 쓰는
 * [com.kophas.battlecity.ai.NavGrid] 와 같은 잣대다.
 */
class StageGenerator(
    private val manifest: AssetManifest,
    private val profile: MapGenProfile,
) {

    fun generate(seed: Long, playerCount: Int, stageIndex: Int = 0): StageData {
        var currentSeed = seed
        repeat(MAX_SEEDS) {
            val attempt = build(currentSeed, playerCount, stageIndex)
            if (attempt != null) return attempt
            currentSeed = Rng.advanceSeed(currentSeed)
        }
        // 여기까지 오면 규칙이 서로 모순이다. 마지막은 검증을 건너뛰고라도 돌려준다.
        return build(currentSeed, playerCount, stageIndex, force = true)!!
    }

    fun blocksXFor(playerCount: Int): Int = profile.presetFor(playerCount).blocksX

    fun blocksYFor(playerCount: Int): Int = profile.presetFor(playerCount).blocksY

    // -----------------------------------------------------------------------

    private fun build(
        seed: Long,
        playerCount: Int,
        stageIndex: Int,
        force: Boolean = false,
    ): StageData? {
        val preset = profile.presetFor(playerCount)
        val rng = Rng(seed + stageIndex * STAGE_SALT)
        val theme = StageTheme.roll(rng, manifest.propGroupIds)
        val builder = Builder(manifest, profile, rng, preset.blocksX, preset.blocksY)

        builder.reserve(playerCount)
        builder.carvePaths()
        builder.placeTerrain(theme)
        builder.placeObjects(theme)

        var report = builder.validate()
        var attempts = 0
        val maxRepairs = profile.ruleInt("maximumRepairAttempts", 8)
        while (!report.passed && attempts < maxRepairs) {
            builder.repair(report)
            report = builder.validate()
            attempts++
        }
        if (!report.passed && !force) return null

        builder.paintGround(theme)
        return builder.finish(theme, seed, report)
    }

    /** 검증 결과. 로그로 남기고 테스트가 그대로 읽는다. (가이드 §14, §15) */
    data class Report(
        val hardConnectedRatio: Float,
        val baseExitCount: Int,
        val fairnessScore: Float,
        val score: Int,
        val passed: Boolean,
        val failures: List<String>,
    )

    // =======================================================================

    internal class Builder(
        private val manifest: AssetManifest,
        private val profile: MapGenProfile,
        private val rng: Rng,
        val blocksX: Int,
        val blocksY: Int,
    ) {
        private val blocks = blocksX * blocksY

        /** 통과를 막는 지형. */
        private val obstacle = ByteArray(blocks) { NONE }

        /** 탱크 위에 그려 가리는 지형. 통과는 된다. */
        private val cover = ByteArray(blocks) { NONE }

        /** 바닥 의미 타입. 충돌에 관여하지 않는다. */
        private val ground = ByteArray(blocks) { GROUND_DIRT }

        /** 예약 정보. 여기 표시된 칸에는 지형을 놓지 않는다. */
        private val logic = ByteArray(blocks) { NONE }

        /** 셀 단위 소품. 블록보다 작아서 따로 든다. */
        private val props = HashMap<Int, Prop>()

        private var baseBlock = 0
        private var playerSpawns = IntArray(0)
        private var enemySpawns = IntArray(0)

        private val spriteIds = LinkedHashMap<String, Short>()
        private val groundIds = LinkedHashMap<String, Short>()

        private class Prop(val sprite: String, val type: TileType, val explosive: Boolean)

        private fun index(bx: Int, by: Int) = by * blocksX + bx

        private fun inBounds(bx: Int, by: Int) = bx in 0 until blocksX && by in 0 until blocksY

        private fun spriteId(name: String): Short =
            spriteIds.getOrPut(name) { spriteIds.size.toShort() }

        private fun groundId(name: String): Short =
            groundIds.getOrPut(name) { groundIds.size.toShort() }

        // -------------------------------------------------------------------
        // [1] 예약 (가이드 §6)
        // -------------------------------------------------------------------

        fun reserve(playerCount: Int) {
            val clear = profile.zone("spawnClearRadius", 1)

            // 본진은 하단 중앙. 맵 외곽과 최소 한 칸 띄운다.
            baseBlock = index(blocksX / 2, blocksY - 2)
            logic[baseBlock] = BASE_ZONE
            markZone(baseBlock, profile.zone("baseZoneRadius", 1), BASE_ZONE)

            // 플레이어는 본진 좌우로 대칭. 서로 최소 맨해튼 3 만큼 벌린다.
            val separation = profile.zone("minPlayerSpawnSeparation", 3)
            val centerX = blocksX / 2
            val row = blocksY - 1
            val offsets = ArrayList<Int>()
            for (i in 0 until playerCount) {
                val step = (i / 2 + 1) * separation
                offsets += if (i % 2 == 0) -step else step
            }
            playerSpawns = IntArray(playerCount) { i ->
                index((centerX + offsets[i]).coerceIn(1, blocksX - 2), row)
            }
            for (spawn in playerSpawns) {
                markZone(spawn, clear, PLAYER_SPAWN)
            }

            // 적은 상단 좌 / 중앙 / 우. 서로 충분히 떨어뜨린다.
            // 좌우 대칭으로 잡는다. 한쪽만 흔들면 그쪽 플레이어가 늘 불리해진다.
            // 가이드 §10 의 거리 편차 18% 는 비대칭 배치로는 좀처럼 지켜지지 않는다.
            val count = profile.zone("enemySpawnCount", 3)
            val minGap = (blocksX * profile.zoneRatio("minEnemySpawnSeparationRatio", 0.25f)).toInt()
            val edge = (1 + rng.nextInt(2)).coerceAtMost(blocksX / 2 - 1)
            val columns = ArrayList<Int>()
            for (i in 0 until count) {
                val axis = blocksX / 2
                val column = when {
                    count == 1 -> axis
                    i == 0 -> axis - (axis - edge)
                    i == count - 1 -> axis + (axis - edge)
                    else -> axis + (i - count / 2) * max(minGap, 1)
                }
                columns += column.coerceIn(1, blocksX - 2)
            }
            enemySpawns = IntArray(count) { index(columns[it], 0) }
            for (spawn in enemySpawns) {
                markZone(spawn, clear, ENEMY_SPAWN)
            }
        }

        private fun markZone(block: Int, radius: Int, tag: Byte) {
            val bx = block % blocksX
            val by = block / blocksX
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    val x = bx + dx
                    val y = by + dy
                    if (!inBounds(x, y)) continue
                    val i = index(x, y)
                    obstacle[i] = NONE
                    cover[i] = NONE
                    if (logic[i] == NONE || (dx == 0 && dy == 0)) logic[i] = tag
                }
            }
        }

        // -------------------------------------------------------------------
        // [2] 경로 먼저 (가이드 §8)
        // -------------------------------------------------------------------

        fun carvePaths() {
            val width = profile.zone("mainPathWidth", 2)
            val center = index(blocksX / 2, blocksY / 2)

            corridor(baseBlock, center, width)
            for (spawn in enemySpawns) corridor(center, spawn, width)
            for (spawn in playerSpawns) corridor(spawn, center, width)

            // 플레이어들이 늘어선 두 줄은 통째로 열어 둔다.
            //
            // 안쪽 두 명은 본진 방벽에 붙어 있고 바깥 두 명은 개활지에 선다. 그대로
            // 두면 시작 이동 면적이 1.3배까지 벌어져 공정성 검증에서 걸린다.
            // (가이드 §10 은 1.25배까지만 허용한다)
            if (playerSpawns.isNotEmpty()) {
                val row = playerSpawns.max() / blocksX
                val from = playerSpawns.minOf { it % blocksX }
                val to = playerSpawns.maxOf { it % blocksX }
                for (bx in from..to) {
                    for (dy in 0 until 2) {
                        val y = row - dy
                        if (y < 0) continue
                        val i = index(bx, y)
                        if (logic[i] == BASE_ZONE) continue
                        obstacle[i] = NONE
                        if (logic[i] == NONE) logic[i] = RESERVED_PATH
                    }
                }
            }

            // 순환로. 한 갈래가 막혀도 돌아갈 길이 남는다.
            // 주요 통로만 폭 2 로 판다(가이드 §9). 보조로까지 넓히면 예약 구역이
            // 맵을 다 먹어 지형을 놓을 자리가 남지 않는다.
            val left = index(2, blocksY / 2)
            val right = index(blocksX - 3, blocksY / 2)
            corridor(left, right, 1)
            corridor(index(2, 1), left, 1)
            corridor(index(blocksX - 3, 1), right, 1)
        }

        /** 두 블록을 잇는 ㄱ자 통로. [width] 만큼 두껍게 판다. */
        private fun corridor(from: Int, to: Int, width: Int) {
            var x = from % blocksX
            var y = from / blocksX
            val tx = to % blocksX
            val ty = to / blocksX
            val horizontalFirst = rng.nextBoolean()

            fun carve(bx: Int, by: Int) {
                for (dy in 0 until width) {
                    for (dx in 0 until width) {
                        val cx = (bx + dx).coerceIn(0, blocksX - 1)
                        val cy = (by + dy).coerceIn(0, blocksY - 1)
                        val i = index(cx, cy)
                        obstacle[i] = NONE
                        if (logic[i] == NONE) logic[i] = RESERVED_PATH
                    }
                }
            }

            if (horizontalFirst) {
                while (x != tx) { carve(x, y); x += if (tx > x) 1 else -1 }
                while (y != ty) { carve(x, y); y += if (ty > y) 1 else -1 }
            } else {
                while (y != ty) { carve(x, y); y += if (ty > y) 1 else -1 }
                while (x != tx) { carve(x, y); x += if (tx > x) 1 else -1 }
            }
            carve(tx, ty)
        }

        // -------------------------------------------------------------------
        // [3] 지형 군집 (가이드 §7)
        // -------------------------------------------------------------------

        fun placeTerrain(theme: StageTheme) {
            // 영구 지형부터. 나중에 놓으면 이미 벽돌이 차지해 자리가 없다.
            growAll("water", target(theme.waterWeight, "water")) { i -> obstacle[i] = WATER }
            growAll("steel", target(0.5f, "permanentObstacle")) { i -> obstacle[i] = STEEL }
            growAll("brick", target(theme.structureDensity, "brick")) { i -> obstacle[i] = BRICK }
            growAll("forest", target(theme.forestWeight, "cover")) { i -> cover[i] = FOREST }
            growAll("ice", target(theme.iceWeight, "ice")) { i -> ground[i] = GROUND_ICE }

            if (theme.mirrorX) mirrorLeftToRight()
            enforceDensities()
        }

        /**
         * 프로필이 정한 밀도 범위(가이드 §7)를 실제로 맞춘다.
         *
         * 군집을 키우다 보면 자리가 없어 목표를 못 채우고, 좌우로 접으면 반대로
         * 넘친다. 둘 다 프로필 위반이므로 마지막에 한 번 모자란 것은 채우고 넘친
         * 것은 덜어 낸다.
         */
        private fun enforceDensities() {
            for (spec in DENSITY_SPECS) {
                topUp(spec)
                trim(spec)
            }
        }

        /**
         * 왼쪽 절반을 오른쪽에 접어 넣는다.
         *
         * 완전 대칭 맵이 필요한 것은 아니지만(가이드 §10), 지형을 비대칭으로 두면
         * 한쪽 플레이어만 물이나 강철에 갇혀 거리 편차 18% 를 넘기기 쉽다.
         * 예약 구역은 건드리지 않으므로 스폰과 본진 주변은 그대로 남는다.
         */
        private fun mirrorLeftToRight() {
            // 접는 축은 본진이 선 열이다. 축을 맵 한가운데(blocksX-1)/2 로 잡으면
            // 칸 수가 짝수일 때 본진·스폰과 반 칸 어긋나서 대칭이 깨진다.
            val axis = blocksX / 2
            for (by in 0 until blocksY) {
                for (bx in 0 until axis) {
                    val mirrored = axis * 2 - bx
                    if (mirrored !in 0 until blocksX) continue
                    val source = index(bx, by)
                    val target = index(mirrored, by)
                    if (logic[target] != NONE || logic[source] != NONE) continue
                    obstacle[target] = obstacle[source]
                    cover[target] = cover[source]
                    ground[target] = ground[source]
                }
            }
        }

        /** 프로필의 [min, max] 범위 안에서 [weight] 위치의 목표 블록 수. */
        private fun target(weight: Float, key: String): Int {
            val range = profile.density(key)
            val ratio = range.start + (range.endInclusive - range.start) * weight.coerceIn(0f, 1f)
            return (blocks * ratio).toInt()
        }

        /** 목표 칸 수를 채울 때까지 군집을 하나씩 키운다. */
        private fun growAll(key: String, targetCells: Int, apply: (Int) -> Unit) {
            val size = profile.cluster(key)
            var placed = 0
            var guard = 0
            while (placed < targetCells && guard < MAX_CLUSTER_TRIES) {
                guard++
                val seed = freeBlock() ?: break
                // 남은 몫보다 크게 키우지 않는다. 마지막 군집이 목표를 넘기면
                // 프로필이 정한 밀도 상한을 벗어난다.
                val want = min(rng.nextInt(size.first, size.last + 1), targetCells - placed)
                if (want <= 0) break
                placed += growOne(seed, want, apply)
            }
        }

        /** 한 군집. 씨앗에서 이웃으로 번져 나간다. 예약 칸은 건너뛴다. */
        private fun growOne(seed: Int, want: Int, apply: (Int) -> Unit): Int {
            val frontier = ArrayDeque<Int>()
            frontier += seed
            var grown = 0

            while (frontier.isNotEmpty() && grown < want) {
                val current = frontier.removeFirst()
                if (!isFree(current)) continue
                apply(current)
                grown++

                val bx = current % blocksX
                val by = current / blocksX
                for (d in rng.shuffled(NEIGHBOURS)) {
                    val nx = bx + d[0]
                    val ny = by + d[1]
                    if (inBounds(nx, ny) && isFree(index(nx, ny))) frontier += index(nx, ny)
                }
            }
            return grown
        }

        /** 지형을 놓아도 되는 칸인가. 예약 구역과 이미 찬 칸은 안 된다. */
        private fun isFree(i: Int): Boolean =
            logic[i] == NONE && obstacle[i] == NONE && cover[i] == NONE && ground[i] != GROUND_ICE

        private fun freeBlock(): Int? {
            repeat(FREE_BLOCK_TRIES) {
                val i = rng.nextInt(blocks)
                if (isFree(i)) return i
            }
            return (0 until blocks).firstOrNull { isFree(it) }
        }

        private fun countOf(spec: DensitySpec): Int = (0 until blocks).count { has(spec, it) }

        private fun has(spec: DensitySpec, i: Int): Boolean = when (spec.layer) {
            LAYER_OBSTACLE -> obstacle[i] == spec.value
            LAYER_COVER -> cover[i] == spec.value
            else -> ground[i] == spec.value
        }

        private fun set(spec: DensitySpec, i: Int, on: Boolean) {
            when (spec.layer) {
                LAYER_OBSTACLE -> obstacle[i] = if (on) spec.value else NONE
                LAYER_COVER -> cover[i] = if (on) spec.value else NONE
                else -> ground[i] = if (on) spec.value else GROUND_DIRT
            }
        }

        /**
         * 최소 밀도를 못 채웠으면 기존 군집 옆에 덧붙인다.
         *
         * 경로와 예약 구역이 자리를 많이 먹으면 목표를 못 채우고 끝난다. 그때 아무
         * 데나 뿌리면 소금·후추가 되므로 이미 있는 덩어리 옆으로만 붙인다.
         */
        private fun topUp(spec: DensitySpec) {
            // 올림해야 한다. 내림하면 23.04 칸이 23 칸이 되어 하한을 아슬아슬하게 못 넘는다.
            val minimum = kotlin.math.ceil(blocks * profile.density(spec.key).start).toInt()
            var count = countOf(spec)
            var guard = 0
            while (count < minimum && guard < MAX_CLUSTER_TRIES) {
                guard++
                val seed = (0 until blocks).firstOrNull { i ->
                    isFree(i) && NEIGHBOURS.any { d ->
                        val x = i % blocksX + d[0]
                        val y = i / blocksX + d[1]
                        inBounds(x, y) && has(spec, index(x, y))
                    }
                } ?: freeBlock() ?: break
                set(spec, seed, true)
                count++
            }
        }

        /**
         * 상한을 넘겼으면 덜어 낸다. 좌우로 접으면 왼쪽 지형이 오른쪽에 더해져 넘친다.
         *
         * 덩어리 가장자리(같은 지형 이웃이 적은 칸)부터 지운다. 가운데를 파면
         * 군집이 도넛처럼 뚫려 어색해진다.
         */
        private fun trim(spec: DensitySpec) {
            val maximum = (blocks * profile.density(spec.key).endInclusive).toInt()
            var count = countOf(spec)
            if (count <= maximum) return

            val ranked = (0 until blocks).filter { has(spec, it) }.sortedBy { i ->
                NEIGHBOURS.count { d ->
                    val x = i % blocksX + d[0]
                    val y = i / blocksX + d[1]
                    inBounds(x, y) && has(spec, index(x, y))
                }
            }
            for (i in ranked) {
                if (count <= maximum) break
                set(spec, i, false)
                count--
            }
        }

        // -------------------------------------------------------------------
        // [4] 소품 (가이드 §4.8)
        // -------------------------------------------------------------------

        fun placeObjects(theme: StageTheme) {
            val budget = target(0.5f, "environmentObject")
            val groups = theme.propPalette.ifEmpty { manifest.propGroupIds }
            if (groups.isEmpty()) return

            var placed = 0
            var guard = 0
            while (placed < budget && guard < MAX_CLUSTER_TRIES) {
                guard++
                val group = manifest.propGroup(rng.pick(groups)) ?: continue
                // 파괴 상태 그림은 처음부터 깔지 않는다. (가이드 §13)
                // 매니페스트가 실수로 넣더라도 여기서 걸러 규칙이 한 곳에만 있게 한다.
                val sprites = group["sprites"]?.asStringList.orEmpty()
                    .filterNot { it in profile.runtimeStateOnly }
                if (sprites.isEmpty()) continue

                val kind = group["kind"]?.asString ?: "decor"
                val block = freeBlock() ?: break
                if (!allowedForKind(kind, block)) continue

                val run = if (group["layout"]?.asString == "line") {
                    rng.nextInt(profile.cluster("object").first, profile.cluster("object").last + 1)
                } else {
                    1
                }
                placed += placeProp(block, sprites, kind, run)
            }
        }

        /** 폭발물은 본진과 스폰에서 떨어뜨린다. (가이드 §11) */
        private fun allowedForKind(kind: String, block: Int): Boolean {
            if (kind != "explosive") return true
            val minBase = profile.root["objects"]?.get("fuelBarrel")
                ?.get("minDistanceFromBase")?.asInt ?: 4
            val minSpawn = profile.root["objects"]?.get("fuelBarrel")
                ?.get("minDistanceFromSpawn")?.asInt ?: 3
            if (manhattan(block, baseBlock) < minBase) return false
            return (playerSpawns + enemySpawns).none { manhattan(block, it) < minSpawn }
        }

        private fun placeProp(block: Int, sprites: List<String>, kind: String, run: Int): Int {
            val bx = block % blocksX
            val by = block / blocksX
            val horizontal = rng.nextBoolean()
            var placed = 0

            for (step in 0 until run) {
                val tx = if (horizontal) bx + step else bx
                val ty = if (horizontal) by else by + step
                if (!inBounds(tx, ty)) break
                val i = index(tx, ty)
                if (!isFree(i)) break

                // 소품은 블록보다 작다. 블록 안 한 칸에만 놓는다.
                val cellX = tx * Constants.CELLS_PER_BLOCK + rng.nextInt(2)
                val cellY = ty * Constants.CELLS_PER_BLOCK + rng.nextInt(2)
                val type = when (kind) {
                    "decor" -> TileType.EMPTY
                    "explosive" -> TileType.BRICK
                    else -> if (kind == "permanent") TileType.STEEL else TileType.BRICK
                }
                props[cellY * blocksX * Constants.CELLS_PER_BLOCK + cellX] =
                    Prop(rng.pick(sprites), type, kind == "explosive")
                // 소품이 놓인 블록은 다른 지형이 덮지 않도록 표시만 해 둔다.
                logic[i] = RESERVED_PATH
                placed++
            }
            return placed
        }

        // -------------------------------------------------------------------
        // [5] 검증 (가이드 §9, §10, §15)
        // -------------------------------------------------------------------

        fun validate(): Report {
            val failures = ArrayList<String>()
            val nav = footprintGraph()

            val ratio = largestComponentRatio(nav)
            val minRatio = profile.rule("minimumHardConnectedRatio", 0.90f)
            if (ratio < minRatio) failures += "연결 비율 $ratio < $minRatio"

            val exits = baseExitCount(nav)
            val minExits = profile.ruleInt("minimumBaseExits", 3)
            if (exits < minExits) failures += "본진 출구 $exits < $minExits"

            for (spawn in playerSpawns + enemySpawns) {
                if (!reachesCenter(nav, spawn)) failures += "스폰 $spawn 이 중앙과 끊겼다"
            }

            val fairness = fairness(nav, failures)

            val score = score(ratio, exits, fairness, failures)
            val passed = failures.isEmpty() && score >= profile.ruleInt("minimumAcceptScore", 85)
            return Report(ratio, exits, fairness, score, passed, failures)
        }

        /**
         * 탱크가 실제로 설 수 있는 자리 격자.
         *
         * 블록이 아니라 셀 격자에서 **2x2 발자국**으로 잰다. 블록 단위로 재면
         * 실제로는 못 지나가는 한 칸짜리 틈을 지나갈 수 있다고 착각한다.
         */
        private fun footprintGraph(): BooleanArray {
            val cellsX = blocksX * Constants.CELLS_PER_BLOCK
            val cellsY = blocksY * Constants.CELLS_PER_BLOCK
            val solid = BooleanArray(cellsX * cellsY)

            for (by in 0 until blocksY) {
                for (bx in 0 until blocksX) {
                    val i = index(bx, by)
                    val blocked = obstacle[i] != NONE || i == baseBlock
                    if (!blocked) continue
                    for (dy in 0 until Constants.CELLS_PER_BLOCK) {
                        for (dx in 0 until Constants.CELLS_PER_BLOCK) {
                            solid[(by * 2 + dy) * cellsX + bx * 2 + dx] = true
                        }
                    }
                }
            }
            for ((cell, prop) in props) {
                if (prop.type != TileType.EMPTY) solid[cell] = true
            }

            val width = cellsX - 1
            val height = cellsY - 1
            val nav = BooleanArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    nav[y * width + x] = !solid[y * cellsX + x] &&
                        !solid[y * cellsX + x + 1] &&
                        !solid[(y + 1) * cellsX + x] &&
                        !solid[(y + 1) * cellsX + x + 1]
                }
            }
            return nav
        }

        private fun navWidth() = blocksX * Constants.CELLS_PER_BLOCK - 1

        private fun navHeight() = blocksY * Constants.CELLS_PER_BLOCK - 1

        private fun navNodeOf(block: Int): Int {
            val bx = (block % blocksX) * Constants.CELLS_PER_BLOCK
            val by = (block / blocksX) * Constants.CELLS_PER_BLOCK
            return by.coerceAtMost(navHeight() - 1) * navWidth() + bx.coerceAtMost(navWidth() - 1)
        }

        /**
         * [start] 노드에서 닿는 칸까지의 거리. 못 닿으면 -1.
         *
         * [start] 는 **노드 번호**다. 블록 번호를 그대로 넘기면 전혀 다른 자리에서
         * 거리를 재게 된다. 블록에서 노드로는 [navNodeOf] 로 바꾼다.
         */
        private fun distances(nav: BooleanArray, start: Int): IntArray {
            val width = navWidth()
            val height = navHeight()
            val dist = IntArray(nav.size) { -1 }
            if (start !in nav.indices) return dist

            val queue = ArrayDeque<Int>()
            // 시작 칸이 막혀 있으면 가장 가까운 열린 칸에서 출발한다.
            val from = if (nav[start]) start else nearestOpen(nav, start) ?: return dist
            dist[from] = 0
            queue += from

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                val cx = current % width
                val cy = current / width
                for (d in NEIGHBOURS) {
                    val nx = cx + d[0]
                    val ny = cy + d[1]
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                    val next = ny * width + nx
                    if (!nav[next] || dist[next] >= 0) continue
                    dist[next] = dist[current] + 1
                    queue += next
                }
            }
            return dist
        }

        private fun nearestOpen(nav: BooleanArray, from: Int): Int? {
            val width = navWidth()
            val fx = from % width
            val fy = from / width
            for (radius in 1..NEAR_OPEN_RADIUS) {
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val x = fx + dx
                        val y = fy + dy
                        if (x < 0 || y < 0 || x >= width || y >= navHeight()) continue
                        val i = y * width + x
                        if (nav[i]) return i
                    }
                }
            }
            return null
        }

        private fun largestComponentRatio(nav: BooleanArray): Float {
            val open = nav.count { it }
            if (open == 0) return 0f
            val dist = distances(nav, navNodeOf(baseBlock))
            val reached = dist.count { it >= 0 }
            return reached.toFloat() / open
        }

        /** 본진에서 바깥으로 나가는 서로 다른 첫 방향의 수. (가이드 §4.7) */
        private fun baseExitCount(nav: BooleanArray): Int {
            val width = navWidth()
            val bx = (baseBlock % blocksX) * Constants.CELLS_PER_BLOCK
            val by = (baseBlock / blocksX) * Constants.CELLS_PER_BLOCK
            var count = 0
            for (d in NEIGHBOURS) {
                val x = bx + d[0] * Constants.CELLS_PER_BLOCK
                val y = by + d[1] * Constants.CELLS_PER_BLOCK
                if (x < 0 || y < 0 || x >= width || y >= navHeight()) continue
                if (nav[y * width + x]) count++
            }
            return count
        }

        private fun reachesCenter(nav: BooleanArray, block: Int): Boolean {
            val dist = distances(nav, navNodeOf(block))
            val center = navNodeOf(index(blocksX / 2, blocksY / 2))
            return dist.getOrElse(center) { -1 } >= 0
        }

        /** 플레이어 사이의 거리·면적 편차. 1 에 가까울수록 공정하다. (가이드 §10) */
        private fun fairness(nav: BooleanArray, failures: MutableList<String>): Float {
            if (playerSpawns.size < 2) return 1f
            val center = navNodeOf(index(blocksX / 2, blocksY / 2))
            val steps = profile.ruleInt("reachableAreaSteps", 10) * Constants.CELLS_PER_BLOCK

            val toCenter = ArrayList<Int>()
            val toEnemy = ArrayList<Int>()
            val area = ArrayList<Int>()
            for (spawn in playerSpawns) {
                val dist = distances(nav, navNodeOf(spawn))
                toCenter += dist.getOrElse(center) { -1 }
                toEnemy += enemySpawns.map { dist.getOrElse(navNodeOf(it)) { -1 } }
                    .filter { it >= 0 }.minOrNull() ?: -1
                area += dist.count { it in 0..steps }
            }
            if (toCenter.any { it < 0 } || toEnemy.any { it < 0 }) {
                failures += "일부 플레이어가 중앙 또는 적 스폰과 끊겼다"
                return 0f
            }

            val centerDev = deviation(toCenter)
            val enemyDev = deviation(toEnemy)
            val areaRatio = area.max().toFloat() / max(1, area.min())

            if (centerDev > profile.rule("maximumCenterDistanceDeviation", 0.15f)) {
                failures += "중앙까지 거리 편차 $centerDev"
            }
            if (enemyDev > profile.rule("maximumEnemySpawnDistanceDeviation", 0.18f)) {
                failures += "적 스폰까지 거리 편차 $enemyDev"
            }
            if (areaRatio > profile.rule("maximumReachableAreaRatio", 1.25f)) {
                failures += "초기 이동 면적 비 $areaRatio"
            }

            // 허용치를 1.0 으로 놓고 잰다. 딱 한계선이면 절반, 여유가 있을수록 높다.
            // 처음에는 한계선을 0점으로 뒀는데, 가이드의 모든 필수 규칙을 지킨 맵이
            // 85점을 못 넘어 전부 폐기됐다. 규칙을 지킨 맵은 쓸 수 있어야 한다.
            val centerLimit = profile.rule("maximumCenterDistanceDeviation", 0.15f)
            val enemyLimit = profile.rule("maximumEnemySpawnDistanceDeviation", 0.18f)
            val areaLimit = profile.rule("maximumReachableAreaRatio", 1.25f) - 1f
            val worst = max(
                centerDev / centerLimit,
                max(enemyDev / enemyLimit, (areaRatio - 1f) / areaLimit),
            )
            return (1f - worst / 2f).coerceIn(0f, 1f)
        }

        /** 평균 대비 최대 벗어남 비율. */
        private fun deviation(values: List<Int>): Float {
            val mean = values.average().toFloat()
            if (mean <= 0f) return 1f
            return values.maxOf { abs(it - mean) } / mean
        }

        /** 가이드 §15 배점. */
        private fun score(ratio: Float, exits: Int, fairness: Float, failures: List<String>): Int {
            val connectivity = profile.score("connectivity", 30)
            val fair = profile.score("fairness", 20)
            val baseBalance = profile.score("baseBalance", 15)
            val spawnSafety = profile.score("spawnSafety", 15)
            val clustering = profile.score("clustering", 10)
            val variety = profile.score("variety", 10)

            var total = 0
            total += (connectivity * ratio.coerceIn(0f, 1f)).toInt()
            total += (fair * fairness).toInt()
            total += (baseBalance * min(1f, exits / 4f)).toInt()
            total += if (spawnsAreSafe()) spawnSafety else 0
            total += (clustering * clusterQuality()).toInt()
            total += (variety * varietyQuality()).toInt()
            return if (failures.isEmpty()) total else min(total, MAX_FAILED_SCORE)
        }

        private fun spawnsAreSafe(): Boolean =
            (playerSpawns + enemySpawns).all { spawn ->
                val bx = spawn % blocksX
                val by = spawn / blocksX
                (-1..1).all { dy ->
                    (-1..1).all { dx ->
                        val x = bx + dx
                        val y = by + dy
                        !inBounds(x, y) || obstacle[index(x, y)] == NONE
                    }
                }
            }

        /** 같은 지형끼리 붙어 있는 비율. 소금·후추가 되면 낮다. (가이드 §7, §17) */
        private fun clusterQuality(): Float {
            var filled = 0
            var neighbours = 0
            for (by in 0 until blocksY) {
                for (bx in 0 until blocksX) {
                    val i = index(bx, by)
                    if (obstacle[i] == NONE) continue
                    filled++
                    for (d in NEIGHBOURS) {
                        val x = bx + d[0]
                        val y = by + d[1]
                        if (inBounds(x, y) && obstacle[index(x, y)] == obstacle[i]) {
                            neighbours++
                            break
                        }
                    }
                }
            }
            return if (filled == 0) 0f else neighbours.toFloat() / filled
        }

        /** 지형 종류가 고루 나왔는가. */
        private fun varietyQuality(): Float {
            val kinds = setOf(BRICK, STEEL, WATER)
            val present = kinds.count { kind -> obstacle.any { it == kind } }
            val hasCover = cover.any { it == FOREST }
            val hasIce = ground.any { it == GROUND_ICE }
            return (present + (if (hasCover) 1 else 0) + (if (hasIce) 1 else 0)) / 5f
        }

        // -------------------------------------------------------------------
        // [6] 수선 (가이드 §16)
        // -------------------------------------------------------------------

        fun repair(report: Report) {
            // 1. 스폰 앞을 막은 장애물부터 치운다.
            for (spawn in playerSpawns + enemySpawns) {
                markZone(spawn, profile.zone("spawnClearRadius", 1), logic[spawn])
            }
            // 2. 본진 출구가 모자라면 둘레를 연다.
            if (report.baseExitCount < profile.ruleInt("minimumBaseExits", 3)) {
                openAround(baseBlock)
            }
            // 3. 끊긴 곳은 다시 통로를 판다. 강철은 벽돌로 낮춘다.
            val center = index(blocksX / 2, blocksY / 2)
            for (spawn in playerSpawns + enemySpawns) {
                corridor(spawn, center, profile.zone("mainPathWidth", 2))
            }
            // 4. 물이 맵을 가르지 않도록 큰 물 덩어리에 육로를 낸다.
            softenBarrier()
            // 5. 불리한 플레이어 주변을 덜어 낸다. (가이드 §16-6)
            thinAroundWorstPlayer()
            // 6. 덜어 낸 만큼 모자라면 다시 채운다. 밀도 범위는 지켜야 한다.
            enforceDensities()
        }

        /**
         * 초기 이동 면적이 가장 좁은 플레이어 둘레의 벽을 걷어 낸다.
         *
         * 좌우로 접어 놓아도 **안쪽 두 명과 바깥 두 명**의 형편은 여전히 다르다.
         * 안쪽은 본진 방벽에 붙어 있고 바깥은 개활지에 선다. 그 차이가 1.25배를
         * 넘으면 가이드 §16-6 이 말하는 대로 불리한 쪽 장애물을 덜어 낸다.
         */
        private fun thinAroundWorstPlayer() {
            if (playerSpawns.size < 2) return
            val nav = footprintGraph()
            val steps = profile.ruleInt("reachableAreaSteps", 10) * Constants.CELLS_PER_BLOCK
            val areas = playerSpawns.map { spawn ->
                distances(nav, navNodeOf(spawn)).count { it in 0..steps }
            }
            val worst = areas.indexOf(areas.min())
            val spawn = playerSpawns[worst]
            val bx = spawn % blocksX
            val by = spawn / blocksX

            // 벽돌부터 걷는다. 물과 강철은 밀도 하한이 있어 함부로 지우면
            // 프로필 범위를 벗어난다. 벽돌만으로 부족할 때만 손댄다.
            var removed = thin(bx, by, THIN_BUDGET) { it == BRICK }
            if (removed < THIN_BUDGET) {
                removed += thin(bx, by, THIN_BUDGET - removed) { it != NONE }
            }
        }

        private inline fun thin(bx: Int, by: Int, budget: Int, accept: (Byte) -> Boolean): Int {
            var removed = 0
            for (radius in 1..THIN_RADIUS) {
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        if (max(abs(dx), abs(dy)) != radius) continue
                        val x = bx + dx
                        val y = by + dy
                        if (!inBounds(x, y)) continue
                        val i = index(x, y)
                        if (logic[i] == BASE_ZONE || !accept(obstacle[i])) continue
                        obstacle[i] = NONE
                        removed++
                        if (removed >= budget) return removed
                    }
                }
            }
            return removed
        }

        private fun openAround(block: Int) {
            val bx = block % blocksX
            val by = block / blocksX
            for (d in NEIGHBOURS) {
                for (step in 1..2) {
                    val x = bx + d[0] * step
                    val y = by + d[1] * step
                    if (!inBounds(x, y)) continue
                    obstacle[index(x, y)] = NONE
                }
            }
        }

        /** 가로로 물/강철이 한 줄을 다 채우면 두 칸을 열어 준다. */
        private fun softenBarrier() {
            for (by in 0 until blocksY) {
                val blocked = (0 until blocksX).count { obstacle[index(it, by)] != NONE }
                if (blocked < blocksX - 1) continue
                val gap = rng.nextInt(1, blocksX - 2)
                obstacle[index(gap, by)] = NONE
                obstacle[index(gap + 1, by)] = NONE
            }
            for (bx in 0 until blocksX) {
                val blocked = (0 until blocksY).count { obstacle[index(bx, it)] != NONE }
                if (blocked < blocksY - 1) continue
                val gap = rng.nextInt(1, blocksY - 2)
                obstacle[index(bx, gap)] = NONE
                obstacle[index(bx, gap + 1)] = NONE
            }
        }

        // -------------------------------------------------------------------
        // [7] 바닥과 그림 (가이드 §12)
        // -------------------------------------------------------------------

        fun paintGround(theme: StageTheme) {
            val radius = profile.biomeBiasRadius
            for (by in 0 until blocksY) {
                for (bx in 0 until blocksX) {
                    val i = index(bx, by)
                    if (ground[i] == GROUND_ICE) continue
                    ground[i] = biomeAt(bx, by, radius, theme)
                }
            }
        }

        /**
         * 이웃을 보고 바닥 기조를 정한다.
         *
         * 완전 독립 난수로 뿌리면 소금·후추가 된다. 물가는 풀이 무성하고, 적 스폰
         * 근처는 그을려 있고, 벽 주변은 갈라져 있는 편이 자연스럽다.
         */
        private fun biomeAt(bx: Int, by: Int, radius: Int, theme: StageTheme): Byte {
            var water = 0
            var forest = 0
            var wall = 0
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    val x = bx + dx
                    val y = by + dy
                    if (!inBounds(x, y)) continue
                    val i = index(x, y)
                    when {
                        obstacle[i] == WATER -> water++
                        cover[i] == FOREST -> forest++
                        obstacle[i] != NONE -> wall++
                    }
                }
            }
            val nearEnemy = enemySpawns.any { manhattan(index(bx, by), it) <= radius + 1 }

            return when {
                nearEnemy -> GROUND_SCORCHED
                water >= 2 -> GROUND_LUSH
                forest >= 2 -> GROUND_DRY
                wall >= 3 -> GROUND_CRACKED
                theme.biome == StageTheme.Biome.GRASS -> GROUND_LUSH
                else -> GROUND_DIRT
            }
        }

        /** 좌표 해시로 그림을 고른다. 같은 seed 면 어느 기기에서나 같다. (가이드 §12) */
        private fun variationOf(semantic: String, bx: Int, by: Int, seed: Long): String {
            val weighted = profile.variation(semantic) ?: return ""
            var hash = seed xor (bx.toLong() * 0x9E3779B1L) xor (by.toLong() * 0x85EBCA77L)
            hash = hash xor semantic.hashCode().toLong()
            hash *= 0xC2B2AE3DL
            hash = hash xor (hash ushr 29)
            return weighted.pick(((hash ushr 1) % Int.MAX_VALUE).toInt())
        }

        // -------------------------------------------------------------------
        // 완성
        // -------------------------------------------------------------------

        fun finish(theme: StageTheme, seed: Long, report: Report): StageData {
            val cellsX = blocksX * Constants.CELLS_PER_BLOCK
            val cellsY = blocksY * Constants.CELLS_PER_BLOCK
            val cells = ByteArray(cellsX * cellsY) { TileType.EMPTY.id }
            val cellSprite = ShortArray(cells.size) { -1 }
            val groundIndex = ShortArray(blocks)
            val explosive = HashSet<Int>()
            val whole = HashSet<Int>()

            for (by in 0 until blocksY) {
                for (bx in 0 until blocksX) {
                    val i = index(bx, by)
                    groundIndex[i] = groundId(variationOf(groundName(ground[i]), bx, by, seed))

                    val type = typeOf(i)
                    if (type == TileType.EMPTY) continue
                    val sprite = when (type) {
                        TileType.BRICK -> variationOf("BRICK", bx, by, seed)
                        TileType.STEEL -> variationOf("STEEL", bx, by, seed)
                        TileType.FOREST -> variationOf("FOREST", bx, by, seed)
                        else -> null
                    }
                    // 블록 하나를 셀 2x2 로 펼친다. 벽은 셀 단위로 부서진다.
                    for (dy in 0 until Constants.CELLS_PER_BLOCK) {
                        for (dx in 0 until Constants.CELLS_PER_BLOCK) {
                            val cell = (by * 2 + dy) * cellsX + bx * 2 + dx
                            cells[cell] = type.id
                            if (sprite != null) cellSprite[cell] = spriteId(sprite)
                        }
                    }
                }
            }

            // 본진 (가이드 §4.7 — 생성 시에는 정상 상태만 놓는다)
            val baseX = (baseBlock % blocksX) * Constants.CELLS_PER_BLOCK
            val baseY = (baseBlock / blocksX) * Constants.CELLS_PER_BLOCK
            for (dy in 0 until Constants.CELLS_PER_BLOCK) {
                for (dx in 0 until Constants.CELLS_PER_BLOCK) {
                    cells[(baseY + dy) * cellsX + baseX + dx] = TileType.BASE.id
                }
            }
            buildBaseWall(cells, cellSprite, cellsX, baseX, baseY, seed)

            for ((cell, prop) in props) {
                if (cell !in cells.indices) continue
                if (cells[cell] != TileType.EMPTY.id) continue
                cells[cell] = prop.type.id
                cellSprite[cell] = spriteId(prop.sprite)
                whole += cell
                if (prop.explosive) explosive += cell
            }

            for (spawn in playerSpawns + enemySpawns) {
                val sx = (spawn % blocksX) * Constants.CELLS_PER_BLOCK
                val sy = (spawn / blocksX) * Constants.CELLS_PER_BLOCK
                for (dy in 0 until Constants.CELLS_PER_BLOCK) {
                    for (dx in 0 until Constants.CELLS_PER_BLOCK) {
                        val cell = (sy + dy) * cellsX + sx + dx
                        if (cells[cell] == TileType.EMPTY.id) cells[cell] = TileType.SPAWN.id
                    }
                }
            }

            return StageData(
                blocksX = blocksX,
                blocksY = blocksY,
                cells = cells,
                cellSprite = cellSprite,
                spriteNames = spriteIds.keys.toTypedArray(),
                ground = groundIndex,
                groundNames = groundIds.keys.toTypedArray(),
                decor = emptyList(),
                explosiveCells = explosive,
                wholeSpriteCells = whole,
                baseBlock = baseBlock,
                comSpawnBlocks = enemySpawns,
                playerSpawnBlocks = playerSpawns,
                theme = theme,
                seed = seed,
                report = report,
            )
        }

        /** 원작과 같은 ㄷ자 벽돌 방벽. 아래쪽만 열려 있다. (가이드 §11) */
        private fun buildBaseWall(
            cells: ByteArray,
            cellSprite: ShortArray,
            cellsX: Int,
            baseX: Int,
            baseY: Int,
            seed: Long,
        ) {
            val sprite = spriteId(variationOf("BRICK", baseX, baseY, seed))
            fun wall(cx: Int, cy: Int) {
                if (cx < 0 || cy < 0 || cx >= cellsX || cy >= blocksY * Constants.CELLS_PER_BLOCK) return
                val cell = cy * cellsX + cx
                if (cells[cell] != TileType.EMPTY.id && cells[cell] != TileType.SPAWN.id) return
                cells[cell] = TileType.BRICK.id
                cellSprite[cell] = sprite
            }
            for (dx in -1..Constants.CELLS_PER_BLOCK) wall(baseX + dx, baseY - 1)
            for (dy in -1 until Constants.CELLS_PER_BLOCK) {
                wall(baseX - 1, baseY + dy)
                wall(baseX + Constants.CELLS_PER_BLOCK, baseY + dy)
            }
        }

        private fun typeOf(i: Int): TileType = when {
            obstacle[i] == BRICK -> TileType.BRICK
            obstacle[i] == STEEL -> TileType.STEEL
            obstacle[i] == WATER -> TileType.WATER
            cover[i] == FOREST -> TileType.FOREST
            ground[i] == GROUND_ICE -> TileType.ICE
            else -> TileType.EMPTY
        }

        private fun groundName(value: Byte): String = when (value) {
            GROUND_CRACKED -> "CRACKED_DIRT"
            GROUND_SCORCHED -> "SCORCHED_DIRT"
            GROUND_DRY -> "DRY_GRASS"
            GROUND_LUSH -> "LUSH_GRASS"
            GROUND_ICE -> "ICE"
            else -> "DIRT"
        }

        private fun manhattan(a: Int, b: Int): Int =
            abs(a % blocksX - b % blocksX) + abs(a / blocksX - b / blocksX)
    }

    companion object {
        const val GENERATOR_VERSION = 2

        const val MIN_PLAYERS = MapGenProfile.MIN_PLAYERS
        const val MAX_PLAYERS = MapGenProfile.MAX_PLAYERS

        // 블록 레이어 값
        private const val NONE: Byte = 0
        private const val BRICK: Byte = 1
        private const val STEEL: Byte = 2
        private const val WATER: Byte = 3
        private const val FOREST: Byte = 1

        private const val GROUND_DIRT: Byte = 0
        private const val GROUND_CRACKED: Byte = 1
        private const val GROUND_SCORCHED: Byte = 2
        private const val GROUND_DRY: Byte = 3
        private const val GROUND_LUSH: Byte = 4
        private const val GROUND_ICE: Byte = 5

        private const val PLAYER_SPAWN: Byte = 1
        private const val ENEMY_SPAWN: Byte = 2
        private const val RESERVED_PATH: Byte = 3
        private const val BASE_ZONE: Byte = 4

        private val NEIGHBOURS = listOf(
            intArrayOf(0, -1), intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(-1, 0),
        )

        private const val STAGE_SALT = 7919L
        /**
         * 검증을 통과할 때까지 바꿔 볼 seed 수.
         *
         * 가이드 §15 는 "75점 미만이면 seed 를 폐기하고 재생성" 이라고만 하고 몇 번까지
         * 시도할지는 정하지 않는다. 한 판을 만드는 데 8번으로는 모자랐다. 스무 판 중
         * 한 판꼴로 여덟 번을 내리 실패했다. 넉넉히 잡아도 생성은 수십 ms 다.
         */
        private const val MAX_SEEDS = 24
        private const val MAX_CLUSTER_TRIES = 400
        private const val FREE_BLOCK_TRIES = 64
        private const val NEAR_OPEN_RADIUS = 4

        /** 불리한 플레이어 둘레를 얼마나 넓게, 몇 칸이나 덜어 낼지. */
        private const val THIN_RADIUS = 6
        private const val THIN_BUDGET = 8

        private const val LAYER_OBSTACLE = 0
        private const val LAYER_COVER = 1
        private const val LAYER_GROUND = 2

        /** 프로필의 밀도 항목과 실제 레이어를 잇는 표. (가이드 §7) */
        internal class DensitySpec(val key: String, val layer: Int, val value: Byte)

        private val DENSITY_SPECS = listOf(
            DensitySpec("brick", LAYER_OBSTACLE, BRICK),
            DensitySpec("permanentObstacle", LAYER_OBSTACLE, STEEL),
            DensitySpec("water", LAYER_OBSTACLE, WATER),
            DensitySpec("cover", LAYER_COVER, FOREST),
            DensitySpec("ice", LAYER_GROUND, GROUND_ICE),
        )

        /** 필수 규칙을 어기면 점수와 무관하게 폐기한다. (가이드 §15) */
        private const val MAX_FAILED_SCORE = 74
    }
}
