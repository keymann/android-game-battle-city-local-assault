package com.kophas.battlecity.net

/**
 * 로비. (계획서 §28, §38)
 *
 * 자리 4개를 두고 누가 들어왔는지, 준비했는지를 센다. Host 만 시작할 수 있고
 * 최소 두 명이 있어야 한다. 네트워크를 모르고 규칙만 안다. 그래서 통로 없이도
 * 상태 변화를 그대로 시험할 수 있다.
 */
class LobbyState(private val maxPlayers: Int = Protocol.MAX_PLAYERS) {

    class Slot(val index: Int) {
        var name: String = ""
            internal set
        var tankType: Int = 0
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
        slot.name = name
        slot.tankType = tankType
        slot.connected = true
        slot.host = true
        slot.ready = true
        slot.lastSeenMs = nowMs
        return slot
    }

    /** @return 배정한 자리. 방이 찼거나 이미 시작했으면 null. */
    fun join(name: String, tankType: Int, nowMs: Long): Slot? {
        if (started) return null
        val slot = slots.firstOrNull { !it.connected } ?: return null
        slot.name = name
        slot.tankType = tankType
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

    fun setReady(index: Int, ready: Boolean, tankType: Int, nowMs: Long) {
        val slot = slots.getOrNull(index) ?: return
        if (!slot.connected) return
        slot.ready = ready
        slot.tankType = tankType
        slot.lastSeenMs = nowMs
        if (!ready) countdownTicks = 0
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
            Messages.LobbySlot(it.index, it.name, it.tankType, it.ready, it.connected, it.host)
        },
        countdownTicks = countdownTicks,
    )
}
