package com.kophas.battlecity.net

import com.kophas.battlecity.core.Direction
import com.kophas.battlecity.gameplay.GameWorld
import com.kophas.battlecity.gameplay.MatchState
import com.kophas.battlecity.gameplay.Tank
import com.kophas.battlecity.map.TileType

/**
 * 게임 상태와 패킷 사이를 옮긴다. (계획서 §35)
 *
 * Host 는 [capture] 로 지금 상태를 담고, Client 는 [apply] 로 받은 상태를 자기
 * [GameWorld] 에 얹는다. Client 는 규칙을 굴리지 않는다. 받은 것을 그리기만 한다.
 *
 * 맵 자체는 오가지 않는다. seed 가 같으면 어느 기기에서나 같은 맵이 나오기 때문이다.
 * 다만 **부서진 자리**는 보내야 한다. Client 는 규칙을 굴리지 않으므로 알려 주지
 * 않으면 사라진 벽이 화면에 그대로 남는다.
 */
object SnapshotBridge {

    fun capture(world: GameWorld, match: MatchState, tick: Long): Messages.Snapshot =
        Messages.Snapshot(
            tick = tick,
            phase = match.phase.ordinal,
            enemiesRemaining = match.enemiesRemaining,
            baseDestroyed = world.map.baseDestroyed,
            baseHits = world.map.baseHitsRemaining,
            tanks = world.tanks.filter { it.alive }.map { tank ->
                Messages.TankState(
                    id = tank.id,
                    slot = tank.ownerSlot,
                    faction = tank.faction.ordinal,
                    type = tank.type.ordinal,
                    x = tank.x,
                    y = tank.y,
                    direction = tank.direction.ordinal,
                    hp = tank.hp.coerceIn(0, 255),
                    specialActive = tank.specialActive,
                )
            },
            projectiles = world.projectiles.active.filter { it.active }.map { projectile ->
                Messages.ProjectileState(
                    id = projectile.id,
                    x = projectile.x,
                    y = projectile.y,
                    direction = projectile.direction.ordinal,
                    piercing = projectile.piercing,
                )
            },
            scores = match.players.map { slot ->
                Messages.ScoreState(slot.index, slot.kills, slot.lives, slot.eliminated)
            },
            baseShielded = world.map.baseShielded,
            tiles = world.map
                .collectChanges(Protocol.TILE_REDUNDANCY, Protocol.MAX_TILE_CHANGES)
                .map { Messages.TileChange(it, world.map.typeAtIndex(it).id.toInt()) },
        )

    /**
     * 받은 상태를 세계에 얹는다.
     *
     * [previous] 가 있으면 그 사이를 [alpha] 만큼 메워 그린다. 상태는 20Hz 로 오는데
     * 화면은 60Hz 라 그대로 놓으면 초당 스무 번씩 뚝뚝 끊긴다.
     *
     * 세계의 탱크 목록을 스냅샷에 맞춰 늘리고 줄인다. Host 에서 부서진 탱크는
     * 스냅샷에서 빠지므로 여기서도 사라진다.
     */
    fun apply(
        world: GameWorld,
        latest: Messages.Snapshot,
        previous: Messages.Snapshot?,
        alpha: Float,
        /**
         * 자리 번호 -> 그 사람이 로비에서 고른 색.
         *
         * 색은 패킷에 실리지 않는다. START 로 이미 받은 것이라 여기서 되짚는다.
         * 자리 번호를 그대로 색으로 쓰면 로비에서 색을 바꾼 사람이 남의 색으로 보인다.
         */
        colorOf: (Int) -> Int = { it },
    ) {
        applyTiles(world, latest)

        val before = previous?.tanks?.associateBy { it.id }.orEmpty()

        val seen = HashSet<Int>()
        for (state in latest.tanks) {
            seen += state.id
            val tank = world.tanks.firstOrNull { it.id == state.id }
                ?: spawnGhost(world, state, colorOf)
                ?: continue
            val old = before[state.id]
            // 겉모습도 매번 다시 맞춘다. 번호는 풀에서 돌려 쓰므로, 부서진 탱크가
            // 쓰던 번호를 다음 탱크가 물려받는다. 자리와 갈래와 색을 갱신하지 않으면
            // 다시 나온 탱크가 **앞사람 모습**으로 그려진다.
            tank.faction = factionOf(state)
            tank.type = typeOf(state)
            tank.ownerSlot = state.slot
            tank.colorSlot = colorSlotOf(state, colorOf)
            tank.x = blend(old?.x, state.x, alpha)
            tank.y = blend(old?.y, state.y, alpha)
            tank.direction = Direction.VALUES[state.direction.coerceIn(0, 3)]
            tank.hp = state.hp
            tank.specialActiveRemaining = if (state.specialActive) 1f else 0f
            tank.alive = true
        }

        // 스냅샷에 없는 탱크는 Host 에서 사라진 것이다.
        for (tank in world.tanks.toList()) {
            if (tank.id !in seen) world.despawn(tank)
        }

        applyProjectiles(world, latest, previous, alpha)
    }

    /**
     * 부서진 자리와 본진 상태를 그대로 놓는다.
     *
     * 셀마다 지금 종류를 받으므로 순서가 뒤바뀌어도 다음 스냅샷이 바로잡는다.
     */
    private fun applyTiles(world: GameWorld, latest: Messages.Snapshot) {
        for (tile in latest.tiles) {
            world.map.applyRemoteCell(tile.index, TileType.fromId(tile.type.toByte()))
        }
        world.map.applyRemoteBase(latest.baseDestroyed, latest.baseShielded, latest.baseHits)
    }

    private fun applyProjectiles(
        world: GameWorld,
        latest: Messages.Snapshot,
        previous: Messages.Snapshot?,
        alpha: Float,
    ) {
        val before = previous?.projectiles?.associateBy { it.id }.orEmpty()
        // 포탄은 수가 자주 바뀐다. 전부 걷어내고 다시 놓는 편이 짝을 맞추는 것보다 싸다.
        for (projectile in world.projectiles.active.toList()) projectile.active = false
        world.projectiles.releaseIf { !it.active }

        for (state in latest.projectiles) {
            val projectile = world.projectiles.obtain() ?: break
            val old = before[state.id]
            projectile.launch(
                fromX = blend(old?.x, state.x, alpha),
                fromY = blend(old?.y, state.y, alpha),
                direction = Direction.VALUES[state.direction.coerceIn(0, 3)],
                owner = null,
                speed = 0f,
                power = 0,
                piercing = state.piercing,
            )
        }
    }

    private fun blend(from: Float?, to: Float, alpha: Float): Float =
        if (from == null) to else from + (to - from) * alpha.coerceIn(0f, 1f)

    private fun spawnGhost(
        world: GameWorld,
        state: Messages.TankState,
        colorOf: (Int) -> Int,
    ): Tank? = world.adoptRemoteTank(
        id = state.id,
        faction = factionOf(state),
        type = typeOf(state),
        ownerSlot = state.slot,
        colorSlot = colorSlotOf(state, colorOf),
        x = state.x,
        y = state.y,
        direction = Direction.VALUES[state.direction.coerceIn(0, 3)],
    )

    private fun factionOf(state: Messages.TankState): Tank.Faction =
        Tank.Faction.entries[state.faction.coerceIn(0, 1)]

    private fun typeOf(state: Messages.TankState): Tank.Type =
        Tank.Type.entries[state.type.coerceIn(0, Tank.Type.entries.lastIndex)]

    /** COM 은 자리가 없다(-1). 그때는 COM 색을 쓰라는 뜻으로 -1 을 그대로 넘긴다. */
    private fun colorSlotOf(state: Messages.TankState, colorOf: (Int) -> Int): Int =
        if (state.slot < 0) -1 else colorOf(state.slot)
}
