package com.kophas.battlecity.gameplay

import com.kophas.battlecity.render.AssetSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 실제 `balance.json` 으로 검증한다.
 * 능력치 표가 계획서 §7 과 어긋나면 여기서 바로 드러난다.
 */
class BalanceConfigTest {

    private val assetsDir: File = sequenceOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).firstOrNull { it.isDirectory } ?: error("assets 디렉터리를 찾지 못했다")

    private val balance = BalanceConfig.load(AssetSource.ofDirectory(assetsDir))

    // --- 능력치 표 (계획서 §7) --------------------------------------------

    @Test
    fun `플레이어 탱크 3종이 모두 정의돼 있다`() {
        assertEquals(setOf(Tank.Type.ATTACK, Tank.Type.DEFENSE, Tank.Type.SPEED), balance.playerTanks.keys)
    }

    @Test
    fun `공격형 능력치가 계획서와 같다`() {
        val stats = balance.playerTanks.getValue(Tank.Type.ATTACK)
        assertEquals(3, stats.attackPower)
        assertEquals(1, stats.defensePower)
        assertEquals(2, stats.moveSpeedRank)
        assertEquals(2, stats.fireRateRank)
        assertEquals(BalanceConfig.Special.PIERCING, stats.special)
        assertEquals(8f, stats.specialCooldownSeconds, 1e-4f)
    }

    @Test
    fun `방어형 능력치가 계획서와 같다`() {
        val stats = balance.playerTanks.getValue(Tank.Type.DEFENSE)
        assertEquals(2, stats.attackPower)
        assertEquals(3, stats.defensePower)
        assertEquals(1, stats.moveSpeedRank)
        assertEquals(BalanceConfig.Special.SHIELD, stats.special)
        assertEquals(10f, stats.specialCooldownSeconds, 1e-4f)
        assertEquals(3f, stats.specialDurationSeconds, 1e-4f)
        assertEquals(0.5f, stats.damageReduction, 1e-4f)
    }

    @Test
    fun `스피드형 능력치가 계획서와 같다`() {
        val stats = balance.playerTanks.getValue(Tank.Type.SPEED)
        assertEquals(1, stats.attackPower)
        assertEquals(1, stats.defensePower)
        assertEquals(4, stats.moveSpeedRank)
        assertEquals(3, stats.fireRateRank)
        assertEquals(BalanceConfig.Special.DASH, stats.special)
        assertEquals(6f, stats.specialCooldownSeconds, 1e-4f)
        assertEquals(2f, stats.speedMultiplier, 1e-4f)
    }

    @Test
    fun `COM 3종 능력치도 계획서와 같다`() {
        // 계획서 §8 - COM 도 플레이어와 같은 등급표를 쓴다.
        assertEquals(3, balance.enemyTanks.getValue(Tank.Type.ATTACK).attackPower)
        assertEquals(3, balance.enemyTanks.getValue(Tank.Type.DEFENSE).defensePower)
        assertEquals(4, balance.enemyTanks.getValue(Tank.Type.SPEED).moveSpeedRank)
    }

    // --- 등급 -> 실제 단위 -------------------------------------------------

    @Test
    fun `이동속도 등급이 높을수록 빠르다`() {
        val speeds = (1..4).map { balance.units.moveSpeedOf(it) }
        assertEquals(speeds.sorted(), speeds)
        assertTrue("등급 1도 0보다 빨라야 한다", speeds.first() > 0f)
    }

    @Test
    fun `연사속도 등급이 높을수록 쿨타임이 짧다`() {
        val cooldowns = (1..4).map { balance.units.fireCooldownOf(it) }
        assertEquals(cooldowns.sortedDescending(), cooldowns)
        assertTrue("쿨타임은 항상 양수여야 한다", cooldowns.all { it > 0f })
    }

    @Test
    fun `스피드형이 방어형보다 확실히 빠르다`() {
        val fast = balance.moveSpeedFor(Tank.Faction.PLAYER, Tank.Type.SPEED)
        val slow = balance.moveSpeedFor(Tank.Faction.PLAYER, Tank.Type.DEFENSE)
        assertTrue("스피드형 $fast 가 방어형 $slow 보다 빨라야 한다", fast > slow * 1.5f)
    }

    @Test
    fun `스피드형이 공격형보다 빨리 쏜다`() {
        val fast = balance.fireCooldownFor(Tank.Faction.PLAYER, Tank.Type.SPEED)
        val slow = balance.fireCooldownFor(Tank.Faction.PLAYER, Tank.Type.ATTACK)
        assertTrue("스피드형 쿨타임 $fast 가 공격형 $slow 보다 짧아야 한다", fast < slow)
    }

    // --- 데미지 공식 (계획서 §13) -----------------------------------------

    @Test
    fun `공격형이 스피드형을 치면 데미지 2 다`() {
        // Attack 3 - Defense 1 x 0.5 = 2.5 -> 내림 2 (계획서 §13 예시)
        val damage = balance.damageOf(attackPower = 3, defensePower = 1)
        assertEquals(2 * balance.rules.damageUnitHp, damage)
    }

    @Test
    fun `방어형이 방어형을 치면 데미지 1 이다`() {
        // Attack 2 - Defense 3 x 0.5 = 0.5 -> 내림 0, 최소값 1 로 보정 (계획서 §13 예시)
        val damage = balance.damageOf(attackPower = 2, defensePower = 3)
        assertEquals(1 * balance.rules.damageUnitHp, damage)
    }

    @Test
    fun `최소 데미지는 1 이상으로 보장된다`() {
        val damage = balance.damageOf(attackPower = 1, defensePower = 99)
        assertEquals(balance.rules.minDamage * balance.rules.damageUnitHp, damage)
        assertTrue(damage > 0)
    }

    @Test
    fun `방어력이 높을수록 덜 아프다`() {
        val weak = balance.damageOf(3, 1)
        val tough = balance.damageOf(3, 3)
        assertTrue("방어력 3이 방어력 1보다 덜 맞아야 한다", tough <= weak)
    }

    // --- 규칙 --------------------------------------------------------------

    @Test
    fun `Life 는 3 이고 HP 와 분리돼 있다`() {
        assertEquals(3, balance.rules.livesPerPlayer)
        assertEquals(100, balance.rules.maxHp)
    }

    @Test
    fun `총 COM 수는 플레이어 수 곱하기 20 이다`() {
        assertEquals(40, balance.rules.totalEnemies(2))
        assertEquals(60, balance.rules.totalEnemies(3))
        assertEquals(80, balance.rules.totalEnemies(4))
    }

    @Test
    fun `동시 COM 수는 총량과 분리돼 있고 8에서 12 사이다`() {
        for (players in 2..4) {
            val active = balance.rules.maxActiveEnemies(players)
            assertTrue("players=$players 동시 COM $active 가 범위를 벗어났다", active in 8..12)
            assertTrue("동시 수는 총량보다 훨씬 적어야 한다", active < balance.rules.totalEnemies(players))
        }
    }

    @Test
    fun `COM 1대는 1킬이다`() {
        assertEquals(1, balance.rules.killScore)
    }

    @Test
    fun `COM 출현 가중치가 3종 모두 정의돼 있다`() {
        assertEquals(3, balance.enemyMix.size)
        assertTrue(balance.enemyMix.values.all { it > 0 })
    }

    @Test
    fun `동점 처리 순서가 계획서 순서다`() {
        assertEquals(listOf("KILLS", "LIVES", "HP", "SHARED"), balance.tieBreakOrder)
    }

    @Test
    fun `관통탄이 일반 포탄보다 빠르다`() {
        assertTrue(balance.units.piercingProjectileSpeed > balance.units.projectileSpeed)
    }

    @Test
    fun `없는 타입을 물어도 죽지 않는다`() {
        assertNotNull(balance.statsFor(Tank.Faction.ENEMY, Tank.Type.SPEED))
    }
}
