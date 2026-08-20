package com.kophas.battlecity.ai

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.core.Direction
import com.kophas.battlecity.gameplay.BalanceConfig
import com.kophas.battlecity.gameplay.GameWorld
import com.kophas.battlecity.gameplay.Tank
import com.kophas.battlecity.map.Rng
import com.kophas.battlecity.map.TileType
import kotlin.math.abs
import kotlin.math.max

/**
 * 탱크 한 대를 조종하는 상태 머신. (계획서 §10)
 *
 * **사람이 쥐는 조종간과 똑같은 것만 만진다.** 방향을 꺾고, 가속을 켜고, 쏘고,
 * 특수기를 누른다. 좌표를 직접 옮기지 않는다. 그래야 Phase 7 에서 사람이 잡았을 때
 * 규칙이 하나도 달라지지 않고, Phase 6 에서 Host 가 굴려도 결과가 같다.
 *
 * ```
 * SPAWN ─▶ PATROL ⇄ CHASE ⇄ ATTACK
 *            │        │       │
 *            └────────┴───▶ SIEGE_BASE
 *                     └───▶ AVOID ──▶ (회복하면 되돌아간다)
 * ```
 *
 * ### 계획서 §10 의 `DefendBase` 를 [State.SIEGE_BASE] 로 옮긴 이유
 *
 * 본진은 **플레이어가 지키는 대상**이다. COM 포탄이 닿으면 즉시 GAME OVER 다.
 * (계획서 §14, §15.1) 그러니 COM 이 본진을 "방어" 할 일은 없다. 방어형 COM 이
 * "본진 주변을 방어하는 AI를 우선한다" (§8.2) 는 말은, 본진 앞마당을 차지하고
 * 앉아 접근하는 플레이어를 막으면서 본진을 두들기는 행동으로 옮겼다.
 * 상태 머신의 자리와 우선순위는 계획서 그대로다.
 */
class TankAi(
    private val nav: NavGrid,
    private val settings: AiSettings,
    private val rng: Rng,
) {
    enum class State {
        /** 등장 직후. 무적 시간 동안 움직이지 않는다. */
        SPAWN,

        /** 목표가 없다. 갈 만한 곳을 골라 돌아다닌다. */
        PATROL,

        /** 목표를 향해 경로를 따라 간다. */
        CHASE,

        /** 사선이 열렸다. 멈춰서 쏜다. */
        ATTACK,

        /**
         * 본진이 위험하다. 침입한 적을 요격하러 간다. (계획서 §10 DefendBase)
         *
         * 본진을 **가진 쪽**만 이 상태에 들어간다.
         */
        DEFEND_BASE,

        /** 남의 본진 앞마당을 차지하고 두들긴다. (계획서 §10 DefendBase 의 거울) */
        SIEGE_BASE,

        /** 체력이 위험하다. 물러난다. */
        AVOID,

        /** 부서졌다. */
        DEAD,
    }

    var state: State = State.SPAWN
        private set

    /** 이 탱크가 본진을 노리는 임무를 받았는지. 스폰 때 한 번만 정한다. */
    var missionBase: Boolean = false
        private set

    var profile: AiProfile = AiProfile.fallback(Tank.Type.ATTACK)
        private set

    /** 지금 쫓는 적 탱크 id. 없으면 -1. */
    var targetTankId: Int = -1
        private set

    /**
     * 지금 경로가 향하는 칸. 없으면 -1.
     *
     * 목표를 무엇으로 잡았는지는 겉으로 드러나지 않는다. 본진으로 가는지
     * 플레이어를 쫓는지 확인하려면 이 값을 봐야 한다.
     */
    var goalNode: Int = -1
        private set

    private var attacksBase = false
    private var defendsBase = false

    /** 목표가 탱크 한 대일 때 쓰는 1칸짜리 목적지 배열. 매번 새로 잡지 않는다. */
    private val singleGoal = IntArray(1)
    private var pathBuffer = IntArray(settings.maxPathNodes)
    private var pathLength = 0
    private var pathIndex = 0
    private var pathUsedSoft = false

    private var decisionTimer = 0f
    private var repathTimer = 0f
    private var patrolTimer = 0f
    private var aimTimer = 0f
    private var stuckTimer = 0f
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var patrolDirection = Direction.UP
    private var avoidDirection = Direction.UP

    /** 스폰 직후 한 번 불러 성향과 임무를 정한다. 풀에서 재사용해도 상태가 남지 않는다. */
    fun attach(tank: Tank, profile: AiProfile, attacksBase: Boolean, defendsBase: Boolean = false) {
        this.profile = profile
        this.attacksBase = attacksBase
        this.defendsBase = defendsBase
        missionBase = attacksBase && rng.chance(profile.baseFocus)
        state = State.SPAWN
        targetTankId = -1
        goalNode = -1
        pathLength = 0
        pathIndex = 0
        pathUsedSoft = false
        decisionTimer = 0f
        repathTimer = 0f
        patrolTimer = 0f
        // 나오자마자 쏘지 않는다. 첫 사선에도 반응 시간을 준다.
        aimTimer = profile.reactionSeconds
        stuckTimer = 0f
        lastX = Float.NaN
        lastY = Float.NaN
        patrolDirection = tank.direction
        avoidDirection = tank.direction.opposite
    }

    // -----------------------------------------------------------------------

    fun update(world: GameWorld, tank: Tank, deltaSeconds: Float) {
        if (!tank.alive) {
            state = State.DEAD
            return
        }

        // 등장 연출이 끝나기 전에는 아무것도 하지 않는다. 스폰 킬 방지 시간과 같다.
        if (tank.spawnGuardRemaining > 0f) {
            state = State.SPAWN
            tank.moving = false
            return
        }

        decisionTimer -= deltaSeconds
        repathTimer -= deltaSeconds
        aimTimer -= deltaSeconds
        trackStuck(tank, deltaSeconds)

        if (decisionTimer <= 0f) {
            decisionTimer = settings.decisionIntervalSeconds
            state = decide(world, tank)
        }

        act(world, tank, deltaSeconds)
    }

    /**
     * 계획서 §10 의 우선순위를 그대로 옮긴 판단이다.
     *
     * ```
     * 위험한가?          -> 물러난다
     * 본진에 붙었나?      -> 자리를 잡고 두들긴다
     * 사선이 열렸나?      -> 쏜다
     * 목표가 있나?        -> 쫓는다
     * 아무것도 없으면      -> 배회
     * ```
     */
    private fun decide(world: GameWorld, tank: Tank): State {
        val threat = nearestHostile(world, tank)
        targetTankId = threat?.id ?: -1

        if (shouldRetreat(tank, threat)) {
            // 위협의 반대쪽으로 방향을 잡는다. 경로를 그리지 않는 이유는, 도망은
            // 최단 경로가 아니라 "지금 당장 멀어지는 쪽" 이 맞기 때문이다.
            if (threat != null) {
                avoidDirection = Direction.fromVector(
                    tank.centerX - threat.centerX,
                    tank.centerY - threat.centerY,
                ) ?: tank.direction.opposite
            }
            return State.AVOID
        }

        // 본진을 가진 쪽은 본진이 먼저다. 계획서 §10 의 우선순위 그대로다.
        if (defendsBase && !world.map.baseDestroyed) {
            val raider = nearestRaider(world, tank)
            if (raider != null) {
                targetTankId = raider.id
                if (pickAimTarget(world, tank, raider, baseAlive = false) != null) return State.ATTACK
                return State.DEFEND_BASE
            }
        }

        val baseAlive = attacksBase && !world.map.baseDestroyed
        if (baseAlive && missionBase && withinBaseHold(world, tank)) return State.SIEGE_BASE

        if (pickAimTarget(world, tank, threat, baseAlive) != null) return State.ATTACK

        if (threat != null || baseAlive) return State.CHASE
        return State.PATROL
    }

    private fun act(world: GameWorld, tank: Tank, deltaSeconds: Float) {
        when (state) {
            State.SPAWN, State.DEAD -> tank.moving = false

            State.PATROL -> patrol(world, tank, deltaSeconds)

            State.CHASE, State.DEFEND_BASE -> {
                repathIfNeeded(world, tank)
                followPath(world, tank)
                // 쫓는 중에도 이미 겨눠져 있으면 쏜다. 다만 조준하려고 몸을 돌리지는
                // 않는다. 돌리면 경로를 벗어난다.
                shootIfAimed(world, tank, allowTurn = false)
                useSpecial(world, tank)
            }

            // 사선을 잡았으면 멈춰서 쏘고, 놓쳤으면 다시 붙는다.
            // 상태는 0.25초마다 다시 판단하므로, 그 사이에 목표가 비켜서도
            // 가만히 서 있지 않게 하려면 여기서 한 번 더 봐야 한다.
            State.ATTACK, State.SIEGE_BASE -> {
                if (shootIfAimed(world, tank, allowTurn = true)) {
                    tank.moving = false
                } else {
                    repathIfNeeded(world, tank)
                    followPath(world, tank)
                }
                useSpecial(world, tank)
            }

            State.AVOID -> {
                steerIfPassable(world, tank, avoidDirection)
                tank.moving = true
                shootIfAimed(world, tank, allowTurn = false)
                useSpecial(world, tank)
            }
        }
    }

    // -----------------------------------------------------------------------
    // 목표 고르기
    // -----------------------------------------------------------------------

    /** 시야 안에서 가장 가까운 적대 탱크. 숲에 숨은 탱크는 코앞이 아니면 안 보인다. */
    private fun nearestHostile(world: GameWorld, tank: Tank): Tank? {
        val sight = profile.sightRangeBlocks * Constants.BLOCK_PX
        val reveal = settings.concealedRevealBlocks * Constants.BLOCK_PX
        var best: Tank? = null
        var bestDistance = Float.MAX_VALUE

        for (other in world.tanks) {
            if (!other.alive || other.faction == tank.faction) continue
            if (other.spawnGuardRemaining > 0f) continue

            val dx = other.centerX - tank.centerX
            val dy = other.centerY - tank.centerY
            val distance = dx * dx + dy * dy
            if (distance > sight * sight) continue

            val concealed = world.map.conceals(other.x, other.y, Tank.SIZE, Tank.SIZE)
            if (concealed && distance > reveal * reveal) continue
            if (distance >= bestDistance) continue

            best = other
            bestDistance = distance
        }
        return best
    }

    /**
     * 본진 반경 안까지 들어온 적 중 **본진에 가장 가까운** 하나.
     *
     * 나에게 가까운 쪽이 아니다. 본진을 때리기 직전인 놈을 먼저 막아야 한다.
     */
    private fun nearestRaider(world: GameWorld, tank: Tank): Tank? {
        val (baseX, baseY) = world.baseCellCenter()
        val radius = settings.baseDefendRadiusBlocks * Constants.BLOCK_PX
        var best: Tank? = null
        var bestDistance = Float.MAX_VALUE

        for (other in world.tanks) {
            if (!other.alive || other.faction == tank.faction) continue
            if (other.spawnGuardRemaining > 0f) continue
            val dx = abs(other.centerX - baseX)
            val dy = abs(other.centerY - baseY)
            if (dx > radius || dy > radius) continue
            val distance = dx + dy
            if (distance >= bestDistance) continue
            best = other
            bestDistance = distance
        }
        return best
    }

    private fun shouldRetreat(tank: Tank, threat: Tank?): Boolean {
        if (profile.retreatHpRatio <= 0f) return false
        if (tank.hpRatio > profile.retreatHpRatio) return false
        // 위협이 없으면 도망칠 이유도 없다.
        return threat != null
    }

    private fun withinBaseHold(world: GameWorld, tank: Tank): Boolean {
        val (baseX, baseY) = world.baseCellCenter()
        val hold = profile.holdRadiusBlocks * Constants.BLOCK_PX
        return abs(tank.centerX - baseX) <= hold && abs(tank.centerY - baseY) <= hold
    }

    // -----------------------------------------------------------------------
    // 사격
    // -----------------------------------------------------------------------

    /** 지금 쏠 수 있는 방향. 없으면 null. 조준 대기 시간도 여기서 관리한다. */
    private fun pickAimTarget(
        world: GameWorld,
        tank: Tank,
        threat: Tank?,
        baseAlive: Boolean,
    ): Direction? {
        val range = profile.fireRangeBlocks * Constants.BLOCK_PX

        if (threat != null) {
            lineOfFire(world, tank, threat.centerX, threat.centerY, range, isBase = false)
                ?.let { return it }
        }
        if (baseAlive) {
            val (baseX, baseY) = world.baseCellCenter()
            lineOfFire(world, tank, baseX, baseY, range, isBase = true)?.let { return it }
        }
        return null
    }

    /**
     * 사선이 열려 있으면 쏜다.
     *
     * @param allowTurn 조준하려고 몸을 돌려도 되는지. 경로를 따라가는 중이거나
     *   물러나는 중에는 돌리면 안 된다. 돌리는 순간 가려던 방향이 아니라 표적
     *   쪽으로 굴러가 버리기 때문이다. 그때는 이미 겨눠진 경우에만 쏜다.
     * @return 사선을 잡았으면 true. 반응 시간 때문에 아직 쏘지 못했어도 true 다.
     */
    private fun shootIfAimed(world: GameWorld, tank: Tank, allowTurn: Boolean): Boolean {
        val threat = world.tanks.firstOrNull { it.id == targetTankId && it.alive }
        val baseAlive = attacksBase && !world.map.baseDestroyed
        val direction = pickAimTarget(world, tank, threat, baseAlive)

        if (direction == null) {
            aimTimer = profile.reactionSeconds
            return false
        }

        if (direction != tank.direction) {
            if (!allowTurn) return false
            steerIfPassable(world, tank, direction)
        }
        // 사람이라면 조준하고 방아쇠를 당기기까지 시간이 걸린다. 그 틈이 없으면
        // 시야에 들어오는 순간 맞아서 손 쓸 도리가 없다.
        if (aimTimer <= 0f) world.fire(tank)
        return true
    }

    /**
     * [targetX]/[targetY] 를 향해 포탄이 실제로 닿는 방향. 닿지 않으면 null.
     *
     * 축이 어긋나면 못 쏜다. 포탄은 4방향으로만 날아가기 때문이다.
     * 중간에 벽이 있으면 못 쏜다 — 단, 벽돌은 부수며 뚫는 성향이면 그대로 쏜다.
     * 사선에 아군이 있으면 쏘지 않는다.
     */
    private fun lineOfFire(
        world: GameWorld,
        tank: Tank,
        targetX: Float,
        targetY: Float,
        rangePx: Float,
        isBase: Boolean,
    ): Direction? {
        val dx = targetX - tank.centerX
        val dy = targetY - tank.centerY
        val tolerance = settings.fireAlignTolerancePx

        val direction = when {
            abs(dy) <= tolerance && abs(dx) <= rangePx && abs(dx) > 1f ->
                if (dx > 0f) Direction.RIGHT else Direction.LEFT

            abs(dx) <= tolerance && abs(dy) <= rangePx && abs(dy) > 1f ->
                if (dy > 0f) Direction.DOWN else Direction.UP

            else -> return null
        }

        val distance = max(abs(dx), abs(dy))
        if (!clearLine(world, tank, direction, distance, isBase)) return null
        if (friendlyInLine(world, tank, direction, distance)) return null
        return direction
    }

    private fun clearLine(
        world: GameWorld,
        tank: Tank,
        direction: Direction,
        distance: Float,
        isBase: Boolean,
    ): Boolean {
        var travelled = Constants.BLOCK_PX * 0.5f
        while (travelled < distance) {
            val x = tank.centerX + direction.dx * travelled
            val y = tank.centerY + direction.dy * travelled
            val type = world.tileAt(world.map.toCellX(x), world.map.toCellY(y))

            if (type.blocksBullet) {
                // 본진을 노리는 중이라면 본진 자체는 장애물이 아니라 표적이다.
                if (type == TileType.BASE) return isBase
                // 벽돌은 부수며 나아가는 성향이면 그대로 쏜다. 어차피 길이 열린다.
                if (type == TileType.BRICK && profile.breakWalls) return true
                return false
            }
            travelled += Constants.CELL_PX
        }
        return true
    }

    private fun friendlyInLine(
        world: GameWorld,
        tank: Tank,
        direction: Direction,
        distance: Float,
    ): Boolean {
        val tolerance = settings.fireAlignTolerancePx
        for (other in world.tanks) {
            if (other === tank || !other.alive || other.faction != tank.faction) continue
            val dx = other.centerX - tank.centerX
            val dy = other.centerY - tank.centerY
            val along = dx * direction.dx + dy * direction.dy
            if (along <= 0f || along > distance) continue
            val across = if (direction.isHorizontal) abs(dy) else abs(dx)
            if (across <= tolerance) return true
        }
        return false
    }

    // -----------------------------------------------------------------------
    // 특수기 (계획서 §6)
    // -----------------------------------------------------------------------

    /**
     * 특수기는 쓸 값어치가 있을 때만 쓴다.
     *
     * balance.json 에서 COM 에게 특수기를 주지 않으면 [Tank.canUseSpecial] 이
     * 늘 false 라 이 함수는 아무 일도 하지 않는다.
     */
    private fun useSpecial(world: GameWorld, tank: Tank) {
        if (!tank.canUseSpecial) return
        val worth = when (tank.special) {
            // 관통탄은 사선이 열렸을 때만. 벽에 낭비하지 않는다.
            BalanceConfig.Special.PIERCING -> state == State.ATTACK || state == State.SIEGE_BASE
            // 방어막은 맞고 있을 때. 멀쩡할 때 켜면 그냥 버리는 것이다.
            BalanceConfig.Special.SHIELD -> tank.hpRatio <= SHIELD_HP_RATIO && targetTankId >= 0
            // 대시는 거리를 좁힐 때. 멈춰 서서 쓰면 의미가 없다.
            BalanceConfig.Special.DASH -> state == State.CHASE || state == State.AVOID
            BalanceConfig.Special.NONE -> false
        }
        if (worth) world.activateSpecial(tank)
    }

    // -----------------------------------------------------------------------
    // 이동
    // -----------------------------------------------------------------------

    private fun patrol(world: GameWorld, tank: Tank, deltaSeconds: Float) {
        patrolTimer -= deltaSeconds
        if (patrolTimer <= 0f || stuckTimer >= settings.stuckSeconds) {
            patrolTimer = rng.nextFloat(
                profile.patrolIntervalSeconds * 0.5f,
                profile.patrolIntervalSeconds * 1.5f,
            )
            stuckTimer = 0f
            patrolDirection = pickOpenDirection(world, tank)
        }
        steerIfPassable(world, tank, patrolDirection)
        tank.moving = true
        shootIfAimed(world, tank, allowTurn = false)
    }

    /** 지금 자리에서 실제로 뚫려 있는 방향 중 하나. 왔던 길은 마지막에 고른다. */
    private fun pickOpenDirection(world: GameWorld, tank: Tank): Direction {
        val node = nav.nodeAt(tank.x, tank.y)
        val nx = nav.nodeX(node)
        val ny = nav.nodeY(node)
        val back = tank.direction.opposite

        var openCount = 0
        for (direction in Direction.VALUES) {
            if (direction == back) continue
            if (!isOpen(nx, ny, direction)) continue
            openCount++
        }
        if (openCount == 0) return back

        var pick = rng.nextInt(openCount)
        for (direction in Direction.VALUES) {
            if (direction == back) continue
            if (!isOpen(nx, ny, direction)) continue
            if (pick == 0) return direction
            pick--
        }
        return back
    }

    private fun isOpen(nx: Int, ny: Int, direction: Direction): Boolean {
        val tx = nx + direction.dx
        val ty = ny + direction.dy
        if (!nav.inBounds(tx, ty)) return false
        return nav.kindOf(nav.nodeOf(tx, ty)) == NavGrid.FREE
    }

    private fun repathIfNeeded(world: GameWorld, tank: Tank) {
        val exhausted = pathIndex >= pathLength
        val stuck = stuckTimer >= settings.stuckSeconds
        if (repathTimer > 0f && !exhausted && !stuck) return

        repathTimer = settings.repathIntervalSeconds
        stuckTimer = 0f
        buildPath(world, tank)
    }

    private fun buildPath(world: GameWorld, tank: Tank) {
        pathLength = 0
        pathIndex = 0
        pathUsedSoft = false
        goalNode = -1

        val goals = goalNodes(world, tank) ?: return
        val start = nav.nodeAt(tank.x, tank.y)

        pathLength = nav.findPath(start, goals, allowSoft = false, out = pathBuffer)
        if (pathLength > 0) {
            goalNode = pathBuffer[pathLength - 1]
            return
        }

        // 돌아갈 길이 없다. 그제서야 벽을 부수는 경로를 본다.
        if (!profile.breakWalls) return
        pathLength = nav.findPath(start, goals, allowSoft = true, out = pathBuffer)
        pathUsedSoft = pathLength > 0
        if (pathLength > 0) goalNode = pathBuffer[pathLength - 1]
    }

    private fun goalNodes(world: GameWorld, tank: Tank): IntArray? {
        val baseAlive = attacksBase && !world.map.baseDestroyed
        // 본진 임무를 받은 COM 은 눈앞의 적에게 끌려다니지 않는다. 쏘기는 쏜다.
        if (missionBase && baseAlive) return baseApproachNodes(world)

        val threat = world.tanks.firstOrNull { it.id == targetTankId && it.alive }
        if (threat != null) {
            singleGoal[0] = nav.nodeAt(threat.x, threat.y)
            return singleGoal
        }
        return if (baseAlive) baseApproachNodes(world) else null
    }

    /** 본진 자리에는 올라설 수 없다. 앞마당의 설 수 있는 칸들을 목적지로 삼는다. */
    private fun baseApproachNodes(world: GameWorld): IntArray? {
        val (baseX, baseY) = world.baseCellCenter()
        val center = nav.nodeAt(
            baseX - Constants.BLOCK_PX * 0.5f,
            baseY - Constants.BLOCK_PX * 0.5f,
        )
        val around = nav.freeNodesAround(center, BASE_APPROACH_RADIUS)
        return if (around.isEmpty()) null else around
    }

    /**
     * 경로를 따라 한 칸씩 간다.
     *
     * 탱크는 연속 좌표로 움직이고 경로는 격자라 정확히 노드에 닿는 순간이 없다.
     * 그래서 "충분히 가까우면 지나간 것으로 친다". 그 허용치가
     * [AiSettings.turnTolerancePx] 이고, 꺾을 때 튀는 최대 거리이기도 하다.
     */
    private fun followPath(world: GameWorld, tank: Tank) {
        val tolerance = settings.turnTolerancePx

        // 지나온 칸을 건너뛴다. 한 틱에 여러 칸을 통과했을 수도 있다.
        var node = -1
        var targetX = 0f
        var targetY = 0f
        var dx = 0f
        var dy = 0f
        while (pathIndex < pathLength) {
            node = pathBuffer[pathIndex]
            targetX = nav.nodePxX(node)
            targetY = nav.nodePxY(node)
            dx = targetX - tank.x
            dy = targetY - tank.y
            if (abs(dx) > tolerance || abs(dy) > tolerance) break
            pathIndex++
            node = -1
        }

        if (node < 0) {
            tank.moving = false
            return
        }

        // 한 블록 이상 어긋났으면 경로에서 벗어난 것이다. 다음 틱에 다시 그린다.
        if (abs(dx) > Constants.BLOCK_PX || abs(dy) > Constants.BLOCK_PX) {
            pathLength = 0
            repathTimer = 0f
            tank.moving = false
            return
        }

        // 두 축이 다 어긋나 있으면 먼저 맞출 축을 하나 고른다. 가던 축을 유지하면
        // 코너에서 덜 흔들린다.
        val direction = if (abs(dx) > tolerance && abs(dy) > tolerance) {
            if (tank.direction.isHorizontal) horizontalTo(dx) else verticalTo(dy)
        } else if (abs(dx) > tolerance) {
            horizontalTo(dx)
        } else {
            verticalTo(dy)
        }

        steerIfPassable(world, tank, direction)
        tank.moving = true

        // 벽돌을 뚫고 가는 경로라면, 앞을 막은 벽돌을 쏴서 연다.
        if (pathUsedSoft && nav.kindOf(node) == NavGrid.SOFT) {
            world.fire(tank)
        }
    }

    private fun horizontalTo(dx: Float): Direction =
        if (dx > 0f) Direction.RIGHT else Direction.LEFT

    private fun verticalTo(dy: Float): Direction =
        if (dy > 0f) Direction.DOWN else Direction.UP

    /**
     * 방향을 바꾼다.
     *
     * [GameWorld.steer] 는 수직으로 꺾을 때 격자에 스냅하고, 스냅한 자리가 막혀 있으면
     * 되돌린다. 그래서 여기서 따로 검사할 것이 없다.
     */
    private fun steerIfPassable(world: GameWorld, tank: Tank, direction: Direction) {
        world.steer(tank, direction)
    }

    private fun trackStuck(tank: Tank, deltaSeconds: Float) {
        if (!tank.moving || lastX.isNaN()) {
            stuckTimer = 0f
        } else if (abs(tank.x - lastX) < STUCK_EPSILON && abs(tank.y - lastY) < STUCK_EPSILON) {
            stuckTimer += deltaSeconds
        } else {
            stuckTimer = 0f
        }
        lastX = tank.x
        lastY = tank.y
    }

    private companion object {
        const val STUCK_EPSILON = 0.05f
        const val SHIELD_HP_RATIO = 0.6f

        /** 본진에서 이 반경 안의 설 수 있는 칸을 접근 목적지로 쓴다. */
        const val BASE_APPROACH_RADIUS = 4
    }
}
