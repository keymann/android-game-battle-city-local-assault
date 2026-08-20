package com.kophas.battlecity.gameplay

/**
 * 한 판의 진행 상태. (계획서 §5, §12, §16, §17, §32)
 *
 * 규칙만 담고 렌더/입력/네트워크를 모른다. Phase 6 에서 Host 가 이 객체를
 * 권위 있게 굴리고 결과만 내려보낸다. (계획서 §41-17)
 */
class MatchState(
    val playerCount: Int,
    val balance: BalanceConfig,
) {
    enum class Phase { PLAYING, VICTORY, GAME_OVER }

    enum class EndReason { NONE, ALL_ENEMIES_DESTROYED, BASE_DESTROYED, ALL_PLAYERS_ELIMINATED }

    /**
     * 플레이어 한 명의 상태. (계획서 §5.1 PlayerState, §32 PlayerScore)
     *
     * HP 와 Life 를 분리한다. HP 가 0이면 탱크가 부서지고 Life 가 1 줄어든다. (계획서 §12)
     */
    class Slot(val index: Int, val tankType: Tank.Type, lives: Int) {
        var lives: Int = lives
            internal set

        var kills: Int = 0
            internal set

        var deaths: Int = 0
            internal set

        var damageDealt: Int = 0
            internal set

        var eliminated: Boolean = false
            internal set

        /** 죽은 뒤 다시 나오기까지 남은 시간. 0 이하이고 살아 있지 않으면 스폰 가능. */
        var respawnRemaining: Float = 0f
            internal set

        /** 지금 이 슬롯이 조종 중인 탱크 id. 없으면 -1. */
        var tankId: Int = -1
            internal set

        /** 마지막으로 알려진 HP. 동점 처리에 쓴다. (계획서 §16.2) */
        var lastHp: Int = 0
            internal set

        val alive: Boolean get() = tankId >= 0

        val canRespawn: Boolean
            get() = !eliminated && !alive && respawnRemaining <= 0f
    }

    val rules = balance.rules

    val players: List<Slot> = List(playerCount) { index ->
        Slot(index, Tank.Type.entries[index % Tank.Type.entries.size], rules.livesPerPlayer)
    }

    /** 이번 판에 만들어야 할 COM 총수. (계획서 §9) */
    val totalEnemies: Int = rules.totalEnemies(playerCount)

    /** 동시에 존재할 수 있는 COM 수. 총량과 분리한다. (계획서 §44.2) */
    val maxActiveEnemies: Int = rules.maxActiveEnemies(playerCount)

    /** 아직 한 번도 등장하지 않은 COM 수. */
    var enemiesPending: Int = totalEnemies
        private set

    var enemiesDestroyed: Int = 0
        private set

    /** 지금 필드에 있는 COM 수. */
    var enemiesActive: Int = 0
        private set

    var phase: Phase = Phase.PLAYING
        private set

    var endReason: EndReason = EndReason.NONE
        private set

    /** 승자. 동점이면 여럿이다. (계획서 §16.2) */
    var winners: List<Int> = emptyList()
        private set

    val enemiesRemaining: Int get() = totalEnemies - enemiesDestroyed

    // -----------------------------------------------------------------------
    // 스폰 예약
    // -----------------------------------------------------------------------

    /** COM 을 한 기 더 내보낼 수 있는지. */
    fun canSpawnEnemy(): Boolean =
        phase == Phase.PLAYING && enemiesPending > 0 && enemiesActive < maxActiveEnemies

    fun onEnemySpawned() {
        enemiesPending--
        enemiesActive++
    }

    fun onPlayerSpawned(slotIndex: Int, tankId: Int) {
        val slot = players.getOrNull(slotIndex) ?: return
        slot.tankId = tankId
        slot.respawnRemaining = 0f
        slot.lastHp = rules.maxHp
    }

    // -----------------------------------------------------------------------
    // 파괴
    // -----------------------------------------------------------------------

    /**
     * COM 이 부서졌다.
     *
     * @param killerSlot 처치한 플레이어 슬롯. 플레이어가 아니면 -1.
     */
    fun onEnemyDestroyed(killerSlot: Int) {
        enemiesActive = (enemiesActive - 1).coerceAtLeast(0)
        enemiesDestroyed++
        players.getOrNull(killerSlot)?.let { it.kills += rules.killScore }
        evaluate()
    }

    /**
     * 플레이어 탱크가 부서졌다. Life 를 1 줄이고 리스폰을 예약한다. (계획서 §12)
     *
     * @param killerSlot 아군 오사라면 그 슬롯. 아니면 -1.
     */
    fun onPlayerDestroyed(slotIndex: Int, killerSlot: Int = -1) {
        val slot = players.getOrNull(slotIndex) ?: return
        if (slot.eliminated) return

        slot.tankId = -1
        slot.deaths++
        slot.lives--
        slot.lastHp = 0

        if (killerSlot >= 0 && killerSlot != slotIndex) {
            players.getOrNull(killerSlot)?.let { it.kills += rules.friendlyKillScore }
        }

        if (slot.lives <= 0) {
            slot.lives = 0
            slot.eliminated = true
        } else {
            slot.respawnRemaining = rules.respawnDelaySeconds
        }
        evaluate()
    }

    fun onPlayerDamaged(slotIndex: Int, dealerSlot: Int, amount: Int, remainingHp: Int) {
        players.getOrNull(slotIndex)?.lastHp = remainingHp
        players.getOrNull(dealerSlot)?.let { it.damageDealt += amount }
    }

    fun onEnemyDamaged(dealerSlot: Int, amount: Int) {
        players.getOrNull(dealerSlot)?.let { it.damageDealt += amount }
    }

    fun onBaseDestroyed() {
        if (phase != Phase.PLAYING) return
        phase = Phase.GAME_OVER
        endReason = EndReason.BASE_DESTROYED
        winners = emptyList()
    }

    // -----------------------------------------------------------------------
    // 틱
    // -----------------------------------------------------------------------

    fun update(deltaSeconds: Float) {
        if (phase != Phase.PLAYING) return
        for (slot in players) {
            if (slot.eliminated || slot.alive) continue
            if (slot.respawnRemaining > 0f) slot.respawnRemaining -= deltaSeconds
        }
    }

    // -----------------------------------------------------------------------
    // 승패 판정 (계획서 §16, §17)
    // -----------------------------------------------------------------------

    private fun evaluate() {
        if (phase != Phase.PLAYING) return

        if (players.all { it.eliminated }) {
            phase = Phase.GAME_OVER
            endReason = EndReason.ALL_PLAYERS_ELIMINATED
            winners = emptyList()
            return
        }

        if (enemiesDestroyed >= totalEnemies) {
            phase = Phase.VICTORY
            endReason = EndReason.ALL_ENEMIES_DESTROYED
            winners = decideWinners()
        }
    }

    /**
     * 처치 수가 가장 많은 플레이어가 승리한다.
     * 동점이면 남은 Life, 남은 HP 순으로 가르고, 그래도 같으면 공동 승리다. (계획서 §16.2)
     */
    fun decideWinners(): List<Int> {
        if (players.isEmpty()) return emptyList()

        var candidates = players.toList()
        for (criterion in balance.tieBreakOrder) {
            if (candidates.size <= 1) break
            val selector: (Slot) -> Int = when (criterion) {
                "KILLS" -> Slot::kills
                "LIVES" -> Slot::lives
                "HP" -> Slot::lastHp
                else -> break
            }
            val best = candidates.maxOf(selector)
            candidates = candidates.filter { selector(it) == best }
        }
        return candidates.map { it.index }
    }

    /** 결과 화면용 순위. (계획서 §33) */
    fun ranking(): List<Slot> =
        players.sortedWith(
            compareByDescending<Slot> { it.kills }
                .thenByDescending { it.lives }
                .thenByDescending { it.lastHp }
                .thenBy { it.index },
        )
}
