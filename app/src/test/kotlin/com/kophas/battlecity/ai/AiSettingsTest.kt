package com.kophas.battlecity.ai

import com.kophas.battlecity.gameplay.Tank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `balance.json` 의 `ai` 구역을 실제로 읽고 있는지 확인한다.
 *
 * 키 이름을 하나 틀리면 조용히 기본값으로 떨어진다. 그러면 파일을 고쳐도
 * 게임이 안 바뀌는데 아무도 모른다. 파일 값과 기본값이 **다른 항목**을 골라
 * 읽기가 실제로 먹었는지 본다.
 */
class AiSettingsTest {

    private val settings = AiSettings.from(AiFixture.balance)

    @Test
    fun `AI 구역이 비어 있지 않다`() {
        assertTrue(AiFixture.balance.root["ai"] != null)
    }

    @Test
    fun `세 타입 모두 성향이 정의돼 있다`() {
        val labels = Tank.Type.entries.map { settings.profileOf(it).label }
        assertEquals(labels.toSet().size, labels.size)
        for (type in Tank.Type.entries) {
            val profile = settings.profileOf(type)
            assertTrue("$type 시야가 0 이다", profile.sightRangeBlocks > 0f)
            assertTrue("$type 사거리가 0 이다", profile.fireRangeBlocks > 0f)
        }
    }

    @Test
    fun `계획서 8장의 타입별 성향이 값으로 드러난다`() {
        val attack = settings.profileOf(Tank.Type.ATTACK)
        val defense = settings.profileOf(Tank.Type.DEFENSE)
        val speed = settings.profileOf(Tank.Type.SPEED)

        // §8.1 공격적인 AI — 가장 빨리 반응하고 물러나지 않는다
        assertTrue(attack.reactionSeconds < defense.reactionSeconds)
        assertEquals(0f, attack.retreatHpRatio, 0.001f)

        // §8.2 본진 주변을 우선한다
        assertTrue(defense.baseFocus > attack.baseFocus)
        assertTrue(defense.baseFocus > speed.baseFocus)

        // §8.3 플레이어를 적극 추적한다 — 가장 멀리 본다
        assertTrue(speed.sightRangeBlocks > attack.sightRangeBlocks)
        assertTrue(speed.sightRangeBlocks > defense.sightRangeBlocks)
    }

    @Test
    fun `스폰 검사 값을 파일에서 읽는다`() {
        assertTrue(settings.spawn.minPlayerDistanceBlocks > 0f)
        assertTrue(settings.spawn.minBaseDistanceBlocks > 0f)
        assertTrue(settings.spawn.clearanceBlocks > 0f)
    }

    @Test
    fun `파일이 없으면 기본값으로 떨어진다`() {
        val fallback = AiSettings(null)
        assertEquals(AiProfile.fallback(Tank.Type.SPEED), fallback.profileOf(Tank.Type.SPEED))
        // 파일 값과 기본값이 서로 달라야 "읽고 있다" 를 확인할 수 있다.
        assertNotEquals(fallback.profileOf(Tank.Type.ATTACK), settings.profileOf(Tank.Type.ATTACK))
    }

    @Test
    fun `꺾는 허용치는 한 틱 이동 거리보다 커야 한다`() {
        // 이보다 작으면 노드를 그냥 지나쳐 버려 영영 못 꺾는다.
        val fastest = AiFixture.balance.units.moveSpeedOf(4)
        val perTick = fastest * com.kophas.battlecity.core.Constants.TICK_SECONDS
        assertTrue(
            "허용치 ${settings.turnTolerancePx} < 한 틱 $perTick",
            settings.turnTolerancePx > perTick,
        )
    }
}
