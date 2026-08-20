package com.kophas.battlecity.net

import com.kophas.battlecity.gameplay.PlayerProfile

/**
 * 로비. (계획서 §28, §38)
 *
 * 자리 4개를 두고 누가 들어왔는지, 준비했는지를 센다. Host 만 시작할 수 있고
 * 최소 두 명이 있어야 한다. 네트워크를 모르고 규칙만 안다. 그래서 통로 없이도
 * 상태 변화를 그대로 시험할 수 있다.
 */
class LobbyState(
    private val maxPlayers: Int = Protocol.MAX_PLAYERS,
    /** 고를 수 있는 색의 수. 사람 수보다 많아야 모두 다른 색을 쥘 수 있다. */
    private val paletteSize: Int = Protocol.MAX_PLAYERS + 2,
) {

    class Slot(val index: Int) {
        var name: String = ""
            internal set
        var tankType: Int = 0
            internal set

        /** 팔레트 자리 번호. 사람끼리 겹칠 수 없다. */
        var colorIndex: Int = index
            internal set
        var ready: Boolean = false
            internal set
        var connected: Boolean = false
            internal set
        var host: Boolean = false
            internal set

        /** 마지막으로 소식을 들은 시각. 끊김 판정에 쓴다. (계획서 §37) */
        var lastSeenMs: Long = 0
            internal set
    }

    val slots: List<Slot> = List(maxPlayers) { Slot(it) }

    var countdownTicks: Int = 0
        private set

    var started: Boolean = false
        private set

    val connectedCount: Int get() = slots.count { it.connected }

    /** Host 는 언제나 0번 자리다. 방을 연 사람이 곧 권위다. (계획서 §4.2) */
    fun openAsHost(name: String, tankType: Int, nowMs: Long): Slot {
        val slot = slots[0]
        // 이름 규칙은 여기 한 곳에만 둔다. 비면 자리 번호로 P1 ~ P4 가 들어간다.
        slot.name = PlayerProfile.sanitize(name, 0)
        slot.tankType = tankType
        slot.colorIndex = 0
        slot.connected = true
        slot.host = true
        slot.ready = true
        slot.lastSeenMs = nowMs
        return slot
    }

    /** @return 배정한 자리. 방이 찼거나 이미 시작했으면 null. */
    fun join(name: String, tankType: Int, colorIndex: Int, nowMs: Long): Slot? {
        if (started) return null
        // 세는 중에 새로 들어오면 인원이 달라져 서로 다른 맵을 만든다. 세기가 끝날
        // 때까지 받지 않는다. (계획서 §35 맵은 seed 로만 보낸다)
        if (countdownTicks > 0) return null
        val slot = slots.firstOrNull { !it.connected } ?: return null
        slot.name = PlayerProfile.sanitize(name, slot.index)
        slot.tankType = tankType
        // 원하는 색이 이미 쓰이고 있으면 남은 색으로 바꿔 준다.
        slot.colorIndex = if (isColorFree(colorIndex, slot.index)) colorIndex else freeColor(slot.index)
        slot.connected = true
        slot.host = false
        slot.ready = false
        slot.lastSeenMs = nowMs
        return slot
    }

    fun leave(index: Int) {
        val slot = slots.getOrNull(index) ?: return
        slot.connected = false
        slot.ready = false
        slot.host = false
        countdownTicks = 0
    }

    /**
     * 준비 상태와 고른 것을 함께 받는다.
     *
     * 색이 이미 쓰이고 있으면 **바꾸지 않고 쓰던 것을 지킨다.** 거절하는 대신
     * 조용히 되돌리는 이유는, 두 사람이 같은 순간에 같은 색을 누르는 일이 흔하고
     * 그때마다 오류를 띄우면 로비가 시끄러워지기 때문이다.
     */
    fun setReady(
        index: Int,
        ready: Boolean,
        tankType: Int,
        colorIndex: Int,
        name: String,
        nowMs: Long,
    ) {
        val slot = slots.getOrNull(index) ?: return
        if (!slot.connected) return
        slot.ready = ready
        slot.tankType = tankType
        if (isColorFree(colorIndex, index)) slot.colorIndex = colorIndex
        if (name.isNotBlank()) slot.name = PlayerProfile.sanitize(name, index)
        slot.lastSeenMs = nowMs
        if (!ready) countdownTicks = 0
    }

    /** 그 색을 지금 다른 사람이 쓰고 있지 않은가. */
    fun isColorFree(colorIndex: Int, exceptSlot: Int): Boolean {
        if (colorIndex !in 0 until paletteSize) return false
        return slots.none { it.connected && it.index != exceptSlot && it.colorIndex == colorIndex }
    }

    private fun freeColor(slotIndex: Int): Int =
        (0 until paletteSize).firstOrNull { isColorFree(it, slotIndex) } ?: slotIndex

    /** 준비 상태만 바꾼다. 고른 탱크와 색은 건드리지 않는다. */
    fun markReady(index: Int, ready: Boolean) {
        val slot = slots.getOrNull(index) ?: return
        if (!slot.connected) return
        slot.ready = ready
        if (!ready) countdownTicks = 0
    }

    /**
     * 참가자 준비를 모두 해제한다. 방장은 그대로 둔다.
     *
     * 방 규칙이 바뀌면 준비했던 판과 다른 판이 열린다. 무엇에 준비했는지 모르는
     * 준비는 준비가 아니다. (계획서 §28)
     */
    fun clearReady() {
        for (slot in slots) {
            if (!slot.connected || slot.host) continue
            slot.ready = false
        }
        countdownTicks = 0
    }

    fun touch(index: Int, nowMs: Long) {
        slots.getOrNull(index)?.lastSeenMs = nowMs
    }

    /**
     * 소식이 끊긴 자리를 비운다.
     *
     * @return 이번에 끊긴 자리 번호
     */
    fun dropTimedOut(nowMs: Long, timeoutMs: Long = Protocol.TIMEOUT_MS): List<Int> {
        val dropped = ArrayList<Int>()
        for (slot in slots) {
            if (!slot.connected || slot.host) continue
            if (nowMs - slot.lastSeenMs <= timeoutMs) continue
            leave(slot.index)
            dropped += slot.index
        }
        return dropped
    }

    /** Host 만 누를 수 있고, 두 명 이상이 모두 준비해야 한다. (계획서 §28) */
    val canStart: Boolean
        get() = !started &&
            connectedCount >= Protocol.MIN_PLAYERS &&
            slots.all { !it.connected || it.ready }

    fun beginCountdown(seconds: Int = Protocol.COUNTDOWN_SECONDS, hz: Int = 60): Boolean {
        if (!canStart) return false
        countdownTicks = seconds * hz
        return true
    }

    /** @return 이번 틱에 카운트다운이 끝났으면 true. 그때 START 를 보낸다. */
    fun tickCountdown(): Boolean {
        if (countdownTicks <= 0) return false
        countdownTicks--
        if (countdownTicks > 0) return false
        started = true
        return true
    }

    fun markStarted() {
        started = true
        countdownTicks = 0
    }

    /**
     * 판이 끝나고 로비로 되돌린다. 들어와 있는 사람은 그대로 둔다. (계획서 §33)
     *
     * [reset] 과 다르다. reset 은 방을 비우고, 이쪽은 자리를 유지한 채 준비만 푼다.
     * 다시 하려고 모여 있는 사람을 내보낼 이유가 없다.
     */
    fun reopen(nowMs: Long) {
        started = false
        countdownTicks = 0
        for (slot in slots) {
            if (!slot.connected) continue
            // 방장은 준비한 것으로 둔다. 시작을 누르는 사람이 자기 준비를 기다릴 일은 없다.
            slot.ready = slot.host
            slot.lastSeenMs = nowMs
        }
    }

    fun reset() {
        for (slot in slots) {
            slot.connected = false
            slot.ready = false
            slot.host = false
            slot.name = ""
        }
        countdownTicks = 0
        started = false
    }

    fun snapshot(): Messages.LobbyUpdate = Messages.LobbyUpdate(
        slots = slots.map {
            Messages.LobbySlot(
                it.index, it.name, it.tankType, it.colorIndex, it.ready, it.connected, it.host,
            )
        },
        countdownTicks = countdownTicks,
    )
}
