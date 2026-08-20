package com.kophas.battlecity.map

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.render.AssetManifest

/**
 * seed 기반 결정론적 스테이지 생성기. (docs/STAGE_GENERATION.md)
 *
 * 순수 함수다. 같은 `(seed, playerCount, stageIndex)` 는 항상 같은 [StageData] 를 만든다.
 * 덕분에 Host 는 26x26~38x38 그리드를 전송하지 않고 **8바이트 seed 만 브로드캐스트**하면
 * 모든 Client 가 같은 맵을 얻는다. (계획서 §41-4, §35)
 *
 * 어떤 스프라이트를 쓸지는 전부 [manifest] 에서 읽는다. 리소스를 바꿔도 이 코드는 그대로다.
 */
class StageGenerator(private val manifest: AssetManifest) {

    /** 알고리즘이 바뀌면 올린다. Host/Client 버전 스큐 감지에 쓴다. */
    val version: Int = GENERATOR_VERSION

    fun generate(seed: Long, playerCount: Int, stageIndex: Int = 0): StageData {
        var currentSeed = seed
        repeat(MAX_ATTEMPTS) {
            val stage = build(currentSeed, playerCount, stageIndex)
            if (stage != null) return stage
            currentSeed = Rng.advanceSeed(currentSeed)
        }
        // 여기까지 오면 생성기 버그다. 마지막 시도는 복구를 강제해서라도 돌려준다.
        return build(currentSeed, playerCount, stageIndex, forceRepair = true)!!
    }

    /** 가로 블록 수. 세로는 항상 이 값의 3/4 이라 맵이 4:3 을 유지한다. */
    fun blocksXFor(playerCount: Int): Int =
        (BASE_BLOCKS_X + BLOCKS_PER_EXTRA_PLAYER * (playerCount - MIN_PLAYERS))
            .coerceIn(BASE_BLOCKS_X, MAX_BLOCKS_X)

    fun blocksYFor(playerCount: Int): Int = blocksXFor(playerCount) * 3 / 4

    // -----------------------------------------------------------------------

    private fun build(
        seed: Long,
        playerCount: Int,
        stageIndex: Int,
        forceRepair: Boolean = false,
    ): StageData? {
        val rng = Rng(seed + stageIndex * STAGE_SALT)
        val builder = Builder(manifest, rng, blocksXFor(playerCount), blocksYFor(playerCount))

        val theme = StageTheme.roll(rng, manifest.propGroupIds)
        val anchors = builder.planAnchors(playerCount)

        builder.paintGround(theme)
        builder.stampStructures(theme, anchors)
        builder.carveHazards(theme, anchors)
        builder.writeAnchors(anchors)
        builder.scatterProps(theme, anchors)

        if (!builder.repairConnectivity(anchors) && !forceRepair) return null

        return builder.finish(theme, seed, anchors)
    }

    // -----------------------------------------------------------------------

    /** 본진과 스폰 위치. 구조물/해저드가 이 근처를 피해야 하므로 가장 먼저 정한다. */
    internal data class Anchors(
        val baseBlock: Int,
        val comSpawnBlocks: IntArray,
        val playerSpawnBlocks: IntArray,
    ) {
        val allSpawns: IntArray get() = comSpawnBlocks + playerSpawnBlocks

        override fun equals(other: Any?): Boolean = this === other

        override fun hashCode(): Int = baseBlock
    }

    private class Builder(
        private val manifest: AssetManifest,
        private val rng: Rng,
        private val blocksX: Int,
        private val blocksY: Int,
    ) {
        private val cellsX = blocksX * Constants.CELLS_PER_BLOCK
        private val cellsY = blocksY * Constants.CELLS_PER_BLOCK
        private val cells = ByteArray(cellsX * cellsY) { TileType.EMPTY.id }
        private val cellSprite = ShortArray(cellsX * cellsY) { -1 }
        private val ground = ShortArray(blocksX * blocksY)
        private val decor = ArrayList<StageData.Decor>()
        private val explosive = HashSet<Int>()

        private val spriteIds = LinkedHashMap<String, Short>()
        private val groundIds = LinkedHashMap<String, Short>()

        private fun spriteId(name: String): Short =
            spriteIds.getOrPut(name) { spriteIds.size.toShort() }

        private fun groundId(name: String): Short =
            groundIds.getOrPut(name) { groundIds.size.toShort() }

        private fun index(cellX: Int, cellY: Int) = cellY * cellsX + cellX

        private fun inBounds(cellX: Int, cellY: Int) =
            cellX in 0 until cellsX && cellY in 0 until cellsY

        private fun typeAt(cellX: Int, cellY: Int): TileType =
            if (!inBounds(cellX, cellY)) TileType.STEEL
            else TileType.fromId(cells[index(cellX, cellY)])

        private fun setCell(cellX: Int, cellY: Int, type: TileType, sprite: String? = null) {
            if (!inBounds(cellX, cellY)) return
            val i = index(cellX, cellY)
            cells[i] = type.id
            cellSprite[i] = sprite?.let { spriteId(it) } ?: -1
            if (type != TileType.BRICK) explosive.remove(i)
        }

        // -------------------------------------------------------------------
        // [5] Anchor - 본진과 스폰 (docs/STAGE_GENERATION.md §3-[5])
        // -------------------------------------------------------------------

        fun planAnchors(playerCount: Int): Anchors {
            val centerX = blocksX / 2
            val baseY = blocksY - 2
            val baseBlock = baseY * blocksX + centerX

            // COM 은 상단 좌 / 중앙 / 우 3지점. 서로 겹치지 않도록 오프셋을 준다.
            val comColumns = intArrayOf(1, centerX, blocksX - 2)
            val comSpawns = IntArray(comColumns.size) { i ->
                val jitter = rng.nextInt(-1, 2)
                val column = (comColumns[i] + jitter).coerceIn(1, blocksX - 2)
                column
            }

            // 플레이어는 본진 좌우로 대칭 배치한다.
            val offsets = intArrayOf(-2, 2, -4, 4)
            val playerSpawns = IntArray(playerCount) { i ->
                val column = (centerX + offsets[i % offsets.size]).coerceIn(0, blocksX - 1)
                (blocksY - 1) * blocksX + column
            }

            return Anchors(baseBlock, comSpawns, playerSpawns)
        }

        fun writeAnchors(anchors: Anchors) {
            // 스폰 반경은 무조건 비운다. 시작하자마자 갇히는 상황을 원천 차단한다.
            for (spawn in anchors.allSpawns) {
                clearBlockRadius(spawn, SPAWN_CLEAR_RADIUS)
                markSpawn(spawn)
            }

            val baseX = anchors.baseBlock % blocksX
            val baseY = anchors.baseBlock / blocksX
            clearBlockRadius(anchors.baseBlock, 1)

            // 본진 본체 (2x2 셀)
            val bcx = baseX * Constants.CELLS_PER_BLOCK
            val bcy = baseY * Constants.CELLS_PER_BLOCK
            for (dy in 0 until Constants.CELLS_PER_BLOCK) {
                for (dx in 0 until Constants.CELLS_PER_BLOCK) {
                    setCell(bcx + dx, bcy + dy, TileType.BASE)
                }
            }

            // 원작과 같은 ㄷ자 벽돌 방벽. 아래쪽만 열려 있다.
            val wallSprite = pickSprite(manifest.tileSprites("BRICK"))
            for (dx in -1..Constants.CELLS_PER_BLOCK) {
                setCell(bcx + dx, bcy - 1, TileType.BRICK, wallSprite)
            }
            for (dy in -1 until Constants.CELLS_PER_BLOCK) {
                setCell(bcx - 1, bcy + dy, TileType.BRICK, wallSprite)
                setCell(bcx + Constants.CELLS_PER_BLOCK, bcy + dy, TileType.BRICK, wallSprite)
            }
        }

        private fun markSpawn(blockIndex: Int) {
            val cx = (blockIndex % blocksX) * Constants.CELLS_PER_BLOCK
            val cy = (blockIndex / blocksX) * Constants.CELLS_PER_BLOCK
            for (dy in 0 until Constants.CELLS_PER_BLOCK) {
                for (dx in 0 until Constants.CELLS_PER_BLOCK) {
                    setCell(cx + dx, cy + dy, TileType.SPAWN)
                }
            }
        }

        private fun clearBlockRadius(blockIndex: Int, radiusBlocks: Int) {
            val bx = blockIndex % blocksX
            val by = blockIndex / blocksX
            for (y in (by - radiusBlocks)..(by + radiusBlocks)) {
                for (x in (bx - radiusBlocks)..(bx + radiusBlocks)) {
                    if (x !in 0 until blocksX || y !in 0 until blocksY) continue
                    val cx = x * Constants.CELLS_PER_BLOCK
                    val cy = y * Constants.CELLS_PER_BLOCK
                    for (dy in 0 until Constants.CELLS_PER_BLOCK) {
                        for (dx in 0 until Constants.CELLS_PER_BLOCK) {
                            setCell(cx + dx, cy + dy, TileType.EMPTY)
                        }
                    }
                }
            }
        }

        private fun isProtected(anchors: Anchors, blockX: Int, blockY: Int, radius: Int): Boolean {
            val targets = anchors.allSpawns + anchors.baseBlock
            for (block in targets) {
                val bx = block % blocksX
                val by = block / blocksX
                if (kotlin.math.abs(bx - blockX) <= radius && kotlin.math.abs(by - blockY) <= radius) {
                    return true
                }
            }
            return false
        }

        // -------------------------------------------------------------------
        // [2] Ground - 지형과 도로 (충돌 없음)
        // -------------------------------------------------------------------

        fun paintGround(theme: StageTheme) {
            val tiler = AutoTiler(manifest, rng, theme.biome)
            val road = BooleanArray(blocksX * blocksY)
            plotRoads(theme.roadStyle, road)

            for (by in 0 until blocksY) {
                for (bx in 0 until blocksX) {
                    val i = by * blocksX + bx
                    val name = if (road[i]) {
                        var mask = 0
                        if (by > 0 && road[i - blocksX]) mask = mask or AutoTiler.NORTH
                        if (bx < blocksX - 1 && road[i + 1]) mask = mask or AutoTiler.EAST
                        if (by < blocksY - 1 && road[i + blocksX]) mask = mask or AutoTiler.SOUTH
                        if (bx > 0 && road[i - 1]) mask = mask or AutoTiler.WEST
                        tiler.roadTile(mask) ?: tiler.groundTile(theme.biome, bx, by, blocksX)
                    } else {
                        tiler.groundTile(theme.biome, bx, by, blocksX)
                    }
                    ground[i] = groundId(name)
                }
            }
        }

        private fun plotRoads(style: StageTheme.RoadStyle, road: BooleanArray) {
            fun mark(bx: Int, by: Int) {
                if (bx in 0 until blocksX && by in 0 until blocksY) road[by * blocksX + bx] = true
            }
            when (style) {
                StageTheme.RoadStyle.NONE -> Unit

                StageTheme.RoadStyle.CROSS -> {
                    val midX = blocksX / 2
                    val midY = blocksY / 2
                    for (i in 0 until blocksY) mark(midX, i)
                    for (i in 0 until blocksX) mark(i, midY)
                }

                StageTheme.RoadStyle.RING -> {
                    val margin = 2
                    for (i in margin until blocksX - margin) {
                        mark(i, margin)
                        mark(i, blocksY - 1 - margin)
                    }
                    for (i in margin until blocksY - margin) {
                        mark(margin, i)
                        mark(blocksX - 1 - margin, i)
                    }
                    val midX = blocksX / 2
                    for (i in margin..(blocksY - 1 - margin)) mark(midX, i)
                }

                StageTheme.RoadStyle.GRID -> {
                    val step = rng.nextInt(3, 5)
                    for (bx in 0 until blocksX) {
                        if (bx % step != 1) continue
                        // 일부 구간을 지워 격자가 너무 기계적으로 보이지 않게 한다.
                        for (by in 0 until blocksY) if (rng.chance(0.85f)) mark(bx, by)
                    }
                    for (by in 0 until blocksY) {
                        if (by % step != 1) continue
                        for (bx in 0 until blocksX) if (rng.chance(0.85f)) mark(bx, by)
                    }
                }
            }
        }

        // -------------------------------------------------------------------
        // [3] Structure - 패턴 스탬프 + 좌우 미러
        // -------------------------------------------------------------------

        fun stampStructures(theme: StageTheme, anchors: Anchors) {
            val brickSprites = manifest.tileSprites("BRICK")
            val steelSprites = manifest.tileSprites("STEEL")
            val half = if (theme.mirrorX) cellsX / 2 else cellsX

            var regionY = 0
            while (regionY < cellsY) {
                var regionX = 0
                while (regionX < half) {
                    if (rng.chance(theme.structureDensity)) {
                        stampOne(regionX, regionY, brickSprites, steelSprites, anchors)
                    }
                    regionX += StagePatterns.SIZE
                }
                regionY += StagePatterns.SIZE
            }

            if (theme.mirrorX) mirrorLeftToRight()
        }

        private fun stampOne(
            originX: Int,
            originY: Int,
            brickSprites: List<String>,
            steelSprites: List<String>,
            anchors: Anchors,
        ) {
            val pattern = rng.pick(StagePatterns.VARIANTS)
            val brick = pickSprite(brickSprites)
            val steel = pickSprite(steelSprites)

            for (row in 0 until StagePatterns.SIZE) {
                for (col in 0 until StagePatterns.SIZE) {
                    val type = StagePatterns.typeOf(pattern[row][col]) ?: continue
                    val cx = originX + col
                    val cy = originY + row
                    if (!inBounds(cx, cy)) continue
                    val bx = cx / Constants.CELLS_PER_BLOCK
                    val by = cy / Constants.CELLS_PER_BLOCK
                    if (isProtected(anchors, bx, by, SPAWN_CLEAR_RADIUS)) continue

                    val sprite = if (type == TileType.STEEL) steel else brick
                    setCell(cx, cy, type, sprite)
                }
            }
        }

        private fun mirrorLeftToRight() {
            val half = cellsX / 2
            for (cy in 0 until cellsY) {
                for (cx in 0 until half) {
                    val from = index(cx, cy)
                    val to = index(cellsX - 1 - cx, cy)
                    cells[to] = cells[from]
                    cellSprite[to] = cellSprite[from]
                }
            }
        }

        // -------------------------------------------------------------------
        // [4] Hazard - 물 / 얼음 / 숲
        // -------------------------------------------------------------------

        fun carveHazards(theme: StageTheme, anchors: Anchors) {
            val ponds = 1 + (theme.waterWeight * 3f).toInt().coerceIn(0, 2)
            repeat(ponds) { carvePond(anchors) }

            val patches = (theme.iceWeight * 3f).toInt().coerceIn(0, 2)
            repeat(patches) { carveIce(anchors) }

            val groves = 2 + (theme.forestWeight * 4f).toInt().coerceIn(0, 3)
            repeat(groves) { carveGrove(anchors) }
        }

        private fun carvePond(anchors: Anchors) {
            val sizeBlocks = rng.nextInt(3, 6)
            val bx = rng.nextInt(1, (blocksX - sizeBlocks - 1).coerceAtLeast(2))
            val by = rng.nextInt(1, (blocksY - sizeBlocks - 3).coerceAtLeast(2))
            if (isProtected(anchors, bx + sizeBlocks / 2, by + sizeBlocks / 2, HAZARD_CLEAR_RADIUS)) return

            val x0 = bx * Constants.CELLS_PER_BLOCK
            val y0 = by * Constants.CELLS_PER_BLOCK
            val side = sizeBlocks * Constants.CELLS_PER_BLOCK

            // 셀룰러 오토마타 2세대로 유기적인 연못 모양을 만든다.
            var grid = Array(side) { BooleanArray(side) { rng.chance(0.55f) } }
            repeat(2) {
                val next = Array(side) { BooleanArray(side) }
                for (y in 0 until side) {
                    for (x in 0 until side) {
                        var neighbours = 0
                        for (dy in -1..1) {
                            for (dx in -1..1) {
                                if (dx == 0 && dy == 0) continue
                                val nx = x + dx
                                val ny = y + dy
                                if (nx !in 0 until side || ny !in 0 until side) neighbours++
                                else if (grid[ny][nx]) neighbours++
                            }
                        }
                        next[y][x] = neighbours >= 5
                    }
                }
                grid = next
            }

            for (y in 0 until side) {
                for (x in 0 until side) {
                    if (!grid[y][x]) continue
                    val cx = x0 + x
                    val cy = y0 + y
                    val blockX = cx / Constants.CELLS_PER_BLOCK
                    val blockY = cy / Constants.CELLS_PER_BLOCK
                    if (isProtected(anchors, blockX, blockY, HAZARD_CLEAR_RADIUS)) continue
                    setCell(cx, cy, TileType.WATER)
                }
            }
        }

        private fun carveIce(anchors: Anchors) {
            val w = rng.nextInt(2, 5)
            val h = rng.nextInt(2, 5)
            val bx = rng.nextInt(1, (blocksX - w - 1).coerceAtLeast(2))
            val by = rng.nextInt(1, (blocksY - h - 2).coerceAtLeast(2))

            for (y in by until by + h) {
                for (x in bx until bx + w) {
                    if (isProtected(anchors, x, y, HAZARD_CLEAR_RADIUS)) continue
                    val cx = x * Constants.CELLS_PER_BLOCK
                    val cy = y * Constants.CELLS_PER_BLOCK
                    for (dy in 0 until Constants.CELLS_PER_BLOCK) {
                        for (dx in 0 until Constants.CELLS_PER_BLOCK) {
                            if (typeAt(cx + dx, cy + dy) != TileType.EMPTY) continue
                            setCell(cx + dx, cy + dy, TileType.ICE)
                        }
                    }
                }
            }
        }

        private fun carveGrove(anchors: Anchors) {
            val trees = manifest.tileSprites("FOREST")
            if (trees.isEmpty()) return

            var bx = rng.nextInt(1, blocksX - 1)
            var by = rng.nextInt(1, blocksY - 1)
            val size = rng.nextInt(3, 8)

            repeat(size) {
                if (bx in 0 until blocksX && by in 0 until blocksY &&
                    !isProtected(anchors, bx, by, 1)
                ) {
                    val cx = bx * Constants.CELLS_PER_BLOCK
                    val cy = by * Constants.CELLS_PER_BLOCK
                    var placeable = true
                    for (dy in 0 until Constants.CELLS_PER_BLOCK) {
                        for (dx in 0 until Constants.CELLS_PER_BLOCK) {
                            if (typeAt(cx + dx, cy + dy) != TileType.EMPTY) placeable = false
                        }
                    }
                    if (placeable) {
                        for (dy in 0 until Constants.CELLS_PER_BLOCK) {
                            for (dx in 0 until Constants.CELLS_PER_BLOCK) {
                                setCell(cx + dx, cy + dy, TileType.FOREST)
                            }
                        }
                        // 나무는 셀 단위로 쪼개지 않고 블록 하나를 덮는 캐노피로 그린다.
                        decor += StageData.Decor(
                            spriteIndex = spriteId(rng.pick(trees)),
                            x = bx * Constants.BLOCK_PX,
                            y = by * Constants.BLOCK_PX,
                            width = Constants.BLOCK_PX,
                            height = Constants.BLOCK_PX,
                            rotation = 0f,
                            layer = Constants.Layer.CANOPY,
                        )
                    }
                }
                // 랜덤 워크로 군락을 뻗는다.
                if (rng.nextBoolean()) bx += if (rng.nextBoolean()) 1 else -1
                else by += if (rng.nextBoolean()) 1 else -1
            }
        }

        // -------------------------------------------------------------------
        // [6] Prop - 환경 오브젝트
        // -------------------------------------------------------------------

        fun scatterProps(theme: StageTheme, anchors: Anchors) {
            for (groupId in theme.propPalette) {
                val group = manifest.propGroup(groupId) ?: continue
                val sprites = group["sprites"]?.asStringList.orEmpty()
                if (sprites.isEmpty()) continue

                when (group["kind"]?.asString) {
                    "explosive" -> scatterExplosive(sprites, anchors)
                    "solid" ->
                        if (group["layout"]?.asString == "line") {
                            scatterLine(sprites, group["destructible"]?.asBoolean ?: true, anchors)
                        } else {
                            scatterSolid(sprites, group["destructible"]?.asBoolean ?: true, anchors)
                        }
                    else -> scatterDecor(sprites)
                }
            }
        }

        private fun scatterExplosive(sprites: List<String>, anchors: Anchors) {
            val clusters = rng.nextInt(3, 9)
            repeat(clusters) {
                val bx = rng.nextInt(1, blocksX - 1)
                val by = rng.nextInt(1, blocksY - 1)
                // 폭발물은 본진/스폰에서 확실히 떨어뜨린다.
                if (isProtected(anchors, bx, by, EXPLOSIVE_CLEAR_RADIUS)) return@repeat

                val count = rng.nextInt(2, 5)
                val cx = bx * Constants.CELLS_PER_BLOCK
                val cy = by * Constants.CELLS_PER_BLOCK
                repeat(count) { i ->
                    val tx = cx + (i % Constants.CELLS_PER_BLOCK)
                    val ty = cy + (i / Constants.CELLS_PER_BLOCK)
                    if (typeAt(tx, ty) != TileType.EMPTY) return@repeat
                    setCell(tx, ty, TileType.BRICK, rng.pick(sprites))
                    explosive += index(tx, ty)
                }
            }
        }

        private fun scatterSolid(sprites: List<String>, destructible: Boolean, anchors: Anchors) {
            val type = if (destructible) TileType.BRICK else TileType.STEEL
            val density = rng.nextFloat(0.04f, 0.08f)
            for (by in 1 until blocksY - 1) {
                for (bx in 1 until blocksX - 1) {
                    if (!rng.chance(density)) continue
                    if (isProtected(anchors, bx, by, SPAWN_CLEAR_RADIUS)) continue
                    val cx = bx * Constants.CELLS_PER_BLOCK + rng.nextInt(Constants.CELLS_PER_BLOCK)
                    val cy = by * Constants.CELLS_PER_BLOCK + rng.nextInt(Constants.CELLS_PER_BLOCK)
                    if (typeAt(cx, cy) != TileType.EMPTY) continue
                    setCell(cx, cy, type, rng.pick(sprites))
                }
            }
        }

        private fun scatterLine(sprites: List<String>, destructible: Boolean, anchors: Anchors) {
            val type = if (destructible) TileType.BRICK else TileType.STEEL
            val lines = rng.nextInt(2, 6)
            repeat(lines) {
                val length = rng.nextInt(3, 7)
                val horizontal = rng.nextBoolean()
                var cx = rng.nextInt(2, cellsX - 2)
                var cy = rng.nextInt(2, cellsY - 2)
                val sprite = rng.pick(sprites)
                repeat(length) {
                    val bx = cx / Constants.CELLS_PER_BLOCK
                    val by = cy / Constants.CELLS_PER_BLOCK
                    if (inBounds(cx, cy) &&
                        typeAt(cx, cy) == TileType.EMPTY &&
                        !isProtected(anchors, bx, by, SPAWN_CLEAR_RADIUS)
                    ) {
                        setCell(cx, cy, type, sprite)
                    }
                    if (horizontal) cx++ else cy++
                }
            }
        }

        private fun scatterDecor(sprites: List<String>) {
            val density = rng.nextFloat(0.12f, 0.20f)
            for (by in 0 until blocksY) {
                for (bx in 0 until blocksX) {
                    if (!rng.chance(density)) continue
                    val cx = bx * Constants.CELLS_PER_BLOCK
                    val cy = by * Constants.CELLS_PER_BLOCK
                    if (typeAt(cx, cy) != TileType.EMPTY) continue

                    val size = Constants.BLOCK_PX * rng.nextFloat(0.35f, 0.7f)
                    decor += StageData.Decor(
                        spriteIndex = spriteId(rng.pick(sprites)),
                        x = bx * Constants.BLOCK_PX + rng.nextFloat(0f, Constants.BLOCK_PX - size),
                        y = by * Constants.BLOCK_PX + rng.nextFloat(0f, Constants.BLOCK_PX - size),
                        width = size,
                        height = size,
                        rotation = rng.nextFloat(0f, TWO_PI),
                        layer = Constants.Layer.DECAL,
                        alpha = rng.nextFloat(0.55f, 0.9f),
                    )
                }
            }
        }

        // -------------------------------------------------------------------
        // [7] Validate & Repair - 연결성
        // -------------------------------------------------------------------

        /**
         * 모든 스폰에서 본진까지 갈 수 있어야 한다.
         * COM 이 본진에 못 가면 게임이 안 끝나고, 플레이어가 COM 을 못 만나면 이길 수 없다.
         *
         * @return 복구 없이 통과했으면 true
         */
        fun repairConnectivity(anchors: Anchors): Boolean {
            val goals = baseApproachCells(anchors.baseBlock)
            if (goals.isEmpty()) return false

            var clean = true
            for (spawn in anchors.allSpawns) {
                val (sx, sy) = spawnCell(spawn)
                if (reachable(sx, sy, goals)) continue
                clean = false
                if (!carvePath(sx, sy, goals)) return false
            }
            return clean
        }

        private fun spawnCell(blockIndex: Int): Pair<Int, Int> {
            val cx = (blockIndex % blocksX) * Constants.CELLS_PER_BLOCK
            val cy = (blockIndex / blocksX) * Constants.CELLS_PER_BLOCK
            return cx to cy
        }

        private fun baseApproachCells(baseBlock: Int): Set<Int> {
            val bx = (baseBlock % blocksX) * Constants.CELLS_PER_BLOCK
            val by = (baseBlock / blocksX) * Constants.CELLS_PER_BLOCK
            val result = HashSet<Int>()
            for (d in -1..Constants.CELLS_PER_BLOCK) {
                addApproach(result, bx + d, by - 2)
                addApproach(result, bx + d, by + Constants.CELLS_PER_BLOCK + 1)
                addApproach(result, bx - 2, by + d)
                addApproach(result, bx + Constants.CELLS_PER_BLOCK + 1, by + d)
            }
            return result
        }

        private fun addApproach(target: MutableSet<Int>, cellX: Int, cellY: Int) {
            if (inBounds(cellX, cellY)) target += index(cellX, cellY)
        }

        private fun reachable(startX: Int, startY: Int, goals: Set<Int>): Boolean {
            val visited = BooleanArray(cells.size)
            val queue = ArrayDeque<Int>()
            val start = index(startX, startY)
            visited[start] = true
            queue += start

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (current in goals) return true
                val cx = current % cellsX
                val cy = current / cellsX
                for (dir in NEIGHBOURS) {
                    val nx = cx + dir[0]
                    val ny = cy + dir[1]
                    if (!inBounds(nx, ny)) continue
                    val n = index(nx, ny)
                    if (visited[n] || !typeAt(nx, ny).isWalkable) continue
                    visited[n] = true
                    queue += n
                }
            }
            return false
        }

        /** 벽을 통과하는 BFS 로 최단 경로를 찾고, 그 경로 위의 막힌 셀만 뚫는다. */
        private fun carvePath(startX: Int, startY: Int, goals: Set<Int>): Boolean {
            val parent = IntArray(cells.size) { -1 }
            val queue = ArrayDeque<Int>()
            val start = index(startX, startY)
            parent[start] = start
            queue += start

            var found = -1
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (current in goals) {
                    found = current
                    break
                }
                val cx = current % cellsX
                val cy = current / cellsX
                for (dir in NEIGHBOURS) {
                    val nx = cx + dir[0]
                    val ny = cy + dir[1]
                    if (!inBounds(nx, ny)) continue
                    val n = index(nx, ny)
                    if (parent[n] != -1) continue
                    // 본진 자체는 뚫지 않는다.
                    if (typeAt(nx, ny) == TileType.BASE) continue
                    parent[n] = current
                    queue += n
                }
            }
            if (found == -1) return false

            var node = found
            while (node != start) {
                val cx = node % cellsX
                val cy = node / cellsX
                if (!typeAt(cx, cy).isWalkable) setCell(cx, cy, TileType.EMPTY)
                node = parent[node]
            }
            return true
        }

        // -------------------------------------------------------------------

        private fun pickSprite(candidates: List<String>): String? =
            if (candidates.isEmpty()) null else rng.pick(candidates)

        fun finish(theme: StageTheme, seed: Long, anchors: Anchors): StageData = StageData(
            blocksX = blocksX,
            blocksY = blocksY,
            cells = cells,
            cellSprite = cellSprite,
            spriteNames = spriteIds.keys.toTypedArray(),
            ground = ground,
            groundNames = groundIds.keys.toTypedArray(),
            decor = decor,
            explosiveCells = explosive,
            baseBlock = anchors.baseBlock,
            comSpawnBlocks = anchors.comSpawnBlocks,
            playerSpawnBlocks = anchors.playerSpawnBlocks,
            theme = theme,
            seed = seed,
        )

        private companion object {
            const val SPAWN_CLEAR_RADIUS = 2
            const val HAZARD_CLEAR_RADIUS = 3
            const val EXPLOSIVE_CLEAR_RADIUS = 3
            const val TWO_PI = 6.2831855f

            val NEIGHBOURS = arrayOf(
                intArrayOf(0, -1),
                intArrayOf(1, 0),
                intArrayOf(0, 1),
                intArrayOf(-1, 0),
            )
        }
    }

    companion object {
        const val GENERATOR_VERSION = 1

        const val MIN_PLAYERS = 2
        const val MAX_PLAYERS = 4
        /** 2인 기준 가로 블록 수. 세로는 12 가 되어 4:3 이다. */
        const val BASE_BLOCKS_X = 16
        const val BLOCKS_PER_EXTRA_PLAYER = 4
        const val MAX_BLOCKS_X = 24

        private const val MAX_ATTEMPTS = 8
        private const val STAGE_SALT = 7919L
    }
}
