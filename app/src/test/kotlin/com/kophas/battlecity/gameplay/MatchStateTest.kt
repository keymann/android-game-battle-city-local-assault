package com.kophas.battlecity.gameplay

import com.kophas.battlecity.render.AssetSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Life / 점수 / 승패 규칙. (계획서 §12, §16, §17, §32) */
class MatchStateTest {

    private val assetsDir: File = sequenceOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).firstOrNull { it.isDirectory } ?: error("assets 디렉터리를 찾지 못했다")

    private val balance = BalanceConfig.load(AssetSource.ofDirectory(assetsDir))

    private fun match(players: Int = 4) = MatchState(players, balance)

    // --- 초기 상태 ---------------------------------------------------------

    @Test
    fun `모든 플레이어가 하트 3개로 시작한다`() {
        val match = match()
        assertEquals(4, match.players.size)
        assertTrue(match.players.all { it.lives == 3 })
        assertTrue(match.players.none { it.eliminated })
    }

    @Test
    fun `총 COM 수는 플레이어 수 곱하기 20 이다`() {
        assertEquals(40, match(2).totalEnemies)
        assertEquals(60, match(3).totalEnemies)
        assertEquals(80, match(4).totalEnemies)
    }

    @Test
    fun `동시 COM 수를 넘겨 스폰하지 않는다`() {
        val match = match()
        repeat(match.maxActiveEnemies) {
            assertTrue(match.canSpawnEnemy())
            match.onEnemySpawned()
        }
        assertFalse("동시 상한을 넘어서면 안 된다", match.canSpawnEnemy())
    }

    @Test
    fun `COM 이 죽으면 다시 스폰할 여유가 생긴다`() {
        val match = match()
        repeat(match.maxActiveEnemies) { match.onEnemySpawned() }
        match.onEnemyDestroyed(killerSlot = 0)
        assertTrue(match.canSpawnEnemy())
    }

    // --- 점수 (계획서 §32) -------------------------------------------------

    @Test
    fun `COM 을 처치하면 처치자에게만 킬이 붙는다`() {
        val match = match()
        match.onEnemySpawned()
        match.onEnemyDestroyed(killerSlot = 2)

        assertEquals(1, match.players[2].kills)
        assertEquals(0, match.players[0].kills)
        assertEquals(1, match.enemiesDestroyed)
    }

    @Test
    fun `처치자가 없으면 아무도 점수를 못 얻는다`() {
        val match = match()
        match.onEnemySpawned()
        match.onEnemyDestroyed(killerSlot = -1)

        assertTrue(match.players.all { it.kills == 0 })
        assertEquals(1, match.enemiesDestroyed)
    }

    @Test
    fun `남은 COM 수가 줄어든다`() {
        val match = match(2)
        assertEquals(40, match.enemiesRemaining)
        repeat(5) {
            match.onEnemySpawned()
            match.onEnemyDestroyed(0)
        }
        assertEquals(35, match.enemiesRemaining)
    }

    // --- Life (계획서 §12) -------------------------------------------------

    @Test
    fun `죽으면 Life 가 하나 줄고 리스폰이 예약된다`() {
        val match = match()
        match.onPlayerSpawned(0, tankId = 10)
        match.onPlayerDestroyed(0)

        assertEquals(2, match.players[0].lives)
        assertEquals(1, match.players[0].deaths)
        assertFalse(match.players[0].alive)
        assertTrue(match.players[0].respawnRemaining > 0f)
        assertFalse(match.players[0].eliminated)
    }

    @Test
    fun `리스폰 대기가 끝나야 다시 나올 수 있다`() {
        val match = match()
        match.onPlayerSpawned(0, tankId = 10)
        match.onPlayerDestroyed(0)

        assertFalse(match.players[0].canRespawn)
        repeat(200) { match.update(1f / 60f) }
        assertTrue(match.players[0].canRespawn)
    }

    @Test
    fun `Life 를 모두 잃으면 탈락한다`() {
        val match = match()
        repeat(3) {
            match.onPlayerSpawned(0, tankId = it)
            match.onPlayerDestroyed(0)
        }
        assertEquals(0, match.players[0].lives)
        assertTrue(match.players[0].eliminated)
        assertFalse("탈락하면 다시 나올 수 없다", match.players[0].canRespawn)
    }

    @Test
    fun `탈락한 플레이어는 더 이상 Life 가 줄지 않는다`() {
        val match = match()
        repeat(5) {
            match.onPlayerSpawned(0, tankId = it)
            match.onPlayerDestroyed(0)
        }
        assertEquals(0, match.players[0].lives)
        assertEquals(3, match.players[0].deaths)
    }

    // --- GAME OVER (계획서 §17) --------------------------------------------

    @Test
    fun `본진이 파괴되면 즉시 GAME OVER 다`() {
        val match = match()
        match.onBaseDestroyed()

        assertEquals(MatchState.Phase.GAME_OVER, match.phase)
        assertEquals(MatchState.EndReason.BASE_DESTROYED, match.endReason)
        assertTrue(match.winners.isEmpty())
    }

    @Test
    fun `모든 플레이어가 탈락하면 GAME OVER 다`() {
        val match = match(2)
        for (slot in 0..1) {
            repeat(3) {
                match.onPlayerSpawned(slot, tankId = slot * 10 + it)
                match.onPlayerDestroyed(slot)
            }
        }
        assertEquals(MatchState.Phase.GAME_OVER, match.phase)
        assertEquals(MatchState.EndReason.ALL_PLAYERS_ELIMINATED, match.endReason)
    }

    @Test
    fun `GAME OVER 이후에는 승패가 바뀌지 않는다`() {
        val match = match(2)
        match.onBaseDestroyed()
        repeat(match.totalEnemies) {
            match.onEnemySpawned()
            match.onEnemyDestroyed(0)
        }
        assertEquals(MatchState.Phase.GAME_OVER, match.phase)
        assertEquals(MatchState.EndReason.BASE_DESTROYED, match.endReason)
    }

    // --- VICTORY (계획서 §16) ----------------------------------------------

    @Test
    fun `COM 을 전멸시키면 승리하고 최다 처치자가 이긴다`() {
        val match = match(2)
        // P1 이 더 많이 잡도록 나눈다.
        repeat(match.totalEnemies) { index ->
            match.onEnemySpawned()
            match.onEnemyDestroyed(if (index < 25) 1 else 0)
        }

        assertEquals(MatchState.Phase.VICTORY, match.phase)
        assertEquals(MatchState.EndReason.ALL_ENEMIES_DESTROYED, match.endReason)
        assertEquals(listOf(1), match.winners)
        assertEquals(25, match.players[1].kills)
    }

    @Test
    fun `처치 수가 같으면 남은 Life 로 가른다`() {
        val match = match(2)
        // P0 이 한 번 죽어 Life 가 준다.
        match.onPlayerSpawned(0, tankId = 1)
        match.onPlayerDestroyed(0)

        repeat(match.totalEnemies) { index ->
            match.onEnemySpawned()
            match.onEnemyDestroyed(index % 2)
        }

        assertEquals(MatchState.Phase.VICTORY, match.phase)
        assertEquals("킬이 같으면 Life 가 많은 쪽이 이긴다", listOf(1), match.winners)
    }

    @Test
    fun `킬과 Life 가 같으면 남은 HP 로 가른다`() {
        val match = match(2)
        match.onPlayerSpawned(0, tankId = 1)
        match.onPlayerSpawned(1, tankId = 2)
        match.onPlayerDamaged(slotIndex = 0, dealerSlot = -1, amount = 50, remainingHp = 50)

        repeat(match.totalEnemies) { index ->
            match.onEnemySpawned()
            match.onEnemyDestroyed(index % 2)
        }

        assertEquals(listOf(1), match.winners)
    }

    @Test
    fun `모두 완전히 같으면 공동 승리다`() {
        val match = match(2)
        match.onPlayerSpawned(0, tankId = 1)
        match.onPlayerSpawned(1, tankId = 2)

        repeat(match.totalEnemies) { index ->
            match.onEnemySpawned()
            match.onEnemyDestroyed(index % 2)
        }

        assertEquals(MatchState.Phase.VICTORY, match.phase)
        assertEquals(listOf(0, 1), match.winners.sorted())
    }

    // --- 결과 화면 (계획서 §33) --------------------------------------------

    @Test
    fun `순위는 처치 수 내림차순이다`() {
        val match = match(4)
        repeat(18) { match.onEnemySpawned(); match.onEnemyDestroyed(0) }
        repeat(27) { match.onEnemySpawned(); match.onEnemyDestroyed(1) }
        repeat(15) { match.onEnemySpawned(); match.onEnemyDestroyed(2) }
        repeat(20) { match.onEnemySpawned(); match.onEnemyDestroyed(3) }

        val ranking = match.ranking()
        assertEquals(listOf(1, 3, 0, 2), ranking.map { it.index })
        assertEquals(27, ranking.first().kills)
    }

    @Test
    fun `누적 데미지가 기록된다`() {
        val match = match()
        match.onEnemyDamaged(dealerSlot = 1, amount = 50)
        match.onEnemyDamaged(dealerSlot = 1, amount = 25)
        assertEquals(75, match.players[1].damageDealt)
    }
}
