package com.kophas.battlecity.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 패킷을 쓰고 읽는 것이 정확히 되돌아오는지 본다.
 *
 * 인코딩이 어긋나면 게임은 터지지 않고 **엉뚱하게 움직인다.** 탱크가 순간이동하거나
 * 점수가 뒤집히는 식이라 눈으로 원인을 찾기가 어렵다. 여기서 잡는다.
 */
class ProtocolTest {

    private fun writer() = PacketWriter(ByteArray(Protocol.MAX_PACKET))

    private fun reader(packet: PacketWriter): PacketReader {
        val readerInstance = PacketReader(packet.buffer, packet.length)
        assertTrue("머리가 우리 패킷이 아니다", Protocol.readType(readerInstance) > 0)
        return readerInstance
    }

    @Test
    fun `머리가 다르면 남의 패킷으로 본다`() {
        // 같은 포트에 다른 프로그램이 떠들 수 있다. 앞머리로 걸러 낸다.
        val alien = PacketWriter(ByteArray(32)).int(0x12345678).byte(1).byte(1)
        assertEquals(-1, Protocol.readType(PacketReader(alien.buffer, alien.length)))
    }

    @Test
    fun `프로토콜 판이 다르면 받지 않는다`() {
        val old = PacketWriter(ByteArray(32))
            .int(Protocol.MAGIC).byte(Protocol.VERSION + 1).byte(Protocol.Type.JOIN)
        assertEquals(-1, Protocol.readType(PacketReader(old.buffer, old.length)))
    }

    @Test
    fun `짧은 패킷을 읽어도 터지지 않는다`() {
        assertEquals(-1, Protocol.readType(PacketReader(ByteArray(3), 3)))
    }

    @Test
    fun `방 알림이 그대로 돌아온다`() {
        val value = Messages.Announce("거실 티비", 3, 4, started = false)
        assertEquals(value, Messages.readAnnounce(reader(Messages.writeAnnounce(writer(), value))))
    }

    @Test
    fun `입장과 배정이 그대로 돌아온다`() {
        val join = Messages.Join("P2", 2, 3)
        assertEquals(join, Messages.readJoin(reader(Messages.writeJoin(writer(), join))))

        val ack = Messages.JoinAck(3, 1, 5)
        assertEquals(ack, Messages.readJoinAck(reader(Messages.writeJoinAck(writer(), ack))))
    }

    @Test
    fun `로비 현황이 그대로 돌아온다`() {
        val value = Messages.LobbyUpdate(
            slots = listOf(
                Messages.LobbySlot(0, "HST", 0, 0, ready = true, connected = true, host = true),
                Messages.LobbySlot(1, "GST", 2, 4, ready = false, connected = true, host = false),
                Messages.LobbySlot(2, "", 0, 2, ready = false, connected = false, host = false),
            ),
            countdownTicks = 137,
        )
        assertEquals(value, Messages.readLobby(reader(Messages.writeLobby(writer(), value))))
    }

    @Test
    fun `시작 정보가 그대로 돌아온다`() {
        // seed 와 해시는 64비트다. 32비트로 잘리면 맵이 서로 달라진다.
        val value = Messages.Start(
            seed = -8936626864634572759L,
            stageIndex = 7,
            playerCount = 4,
            gridHash = 4765930776757569877L,
            startTick = 123456789L,
        )
        assertEquals(value, Messages.readStart(reader(Messages.writeStart(writer(), value))))
    }

    @Test
    fun `조종 입력이 그대로 돌아온다`() {
        for (direction in 0..Messages.Input.NO_DIRECTION) {
            val value = Messages.Input(
                tick = 900L,
                direction = direction,
                moving = direction % 2 == 0,
                fire = direction % 3 == 0,
                special = direction == 1,
            )
            assertEquals(value, Messages.readInput(reader(Messages.writeInput(writer(), value))))
        }
    }

    @Test
    fun `상태 스냅샷이 그대로 돌아온다`() {
        val value = Messages.Snapshot(
            tick = 4242L,
            phase = 1,
            enemiesRemaining = 73,
            baseDestroyed = false,
            tanks = listOf(
                Messages.TankState(1, 0, 0, 2, 640.25f, 128.5f, 3, 75, specialActive = true),
                Messages.TankState(9, -1, 1, 1, 1920f, 1024.75f, 0, 100, specialActive = false),
            ),
            projectiles = listOf(
                Messages.ProjectileState(3, 12.5f, 900.25f, 2, piercing = true),
                Messages.ProjectileState(4, 0f, 0f, 1, piercing = false),
            ),
            scores = listOf(
                Messages.ScoreState(0, 18, 2, eliminated = false),
                Messages.ScoreState(1, 0, 0, eliminated = true),
            ),
        )
        val decoded = Messages.readSnapshot(reader(Messages.writeSnapshot(writer(), value)))
        assertEquals(value, decoded)
    }

    @Test
    fun `좌표는 0점25 픽셀까지 살아남는다`() {
        // 이보다 잘게 보내면 패킷이 커지고, 굵게 보내면 탱크가 떨린다.
        val packet = writer().position(1234.25f).position(0f).position(2047.75f)
        val read = PacketReader(packet.buffer, packet.length)
        assertEquals(1234.25f, read.position(), 0f)
        assertEquals(0f, read.position(), 0f)
        assertEquals(2047.75f, read.position(), 0f)
    }

    @Test
    fun `스냅샷 하나가 한 패킷에 들어간다`() {
        // 쪼개지면 UDP 에서 한 조각만 잃어도 통째로 못 쓴다.
        val tanks = (0 until 16).map {
            Messages.TankState(it, -1, 1, it % 3, 100f * it, 50f * it, it % 4, 100, false)
        }
        val projectiles = (0 until 32).map {
            Messages.ProjectileState(it, 10f * it, 20f * it, it % 4, it % 2 == 0)
        }
        val scores = (0 until 4).map { Messages.ScoreState(it, 20, 3, false) }
        val packet = Messages.writeSnapshot(
            writer(),
            Messages.Snapshot(1L, 0, 80, false, tanks, projectiles, scores),
        )
        assertTrue("스냅샷이 ${packet.length}바이트다", packet.length <= Protocol.MAX_PACKET)
    }

    @Test
    fun `상태 동기화는 게임 틱을 정확히 나눈다`() {
        // 나누어떨어지지 않으면 스냅샷 간격이 들쭉날쭉해져 보간이 튄다.
        assertEquals(60, Protocol.SNAPSHOT_HZ * Protocol.TICKS_PER_SNAPSHOT)
        assertNotEquals(0, Protocol.TICKS_PER_SNAPSHOT)
    }
}
