package com.kophas.battlecity.gameplay

/**
 * 고정 크기 오브젝트 풀. (계획서 §25.2, §41-10)
 *
 * 포탄 / 폭발 / 적 탱크는 초당 수십 개가 생겼다 사라지므로
 * 매번 할당하면 GC 가 프레임을 끊는다. 미리 만들어 두고 재사용한다.
 */
class ObjectPool<T>(capacity: Int, factory: (Int) -> T) {

    private val items: List<T> = List(capacity) { factory(it) }
    private val free = ArrayDeque<T>(capacity)

    /** 지금 살아 있는 원소. */
    val active: MutableList<T> = ArrayList(capacity)

    val capacity: Int get() = items.size
    val activeCount: Int get() = active.size
    val freeCount: Int get() = free.size

    /** 풀이 비어 실패한 획득 요청 횟수. 0이 아니면 capacity 를 늘려야 한다. */
    var exhaustedCount: Int = 0
        private set

    init {
        free.addAll(items)
    }

    /** @return 여유가 없으면 null */
    fun obtain(): T? {
        val item = free.removeFirstOrNull()
        if (item == null) {
            exhaustedCount++
            return null
        }
        active += item
        return item
    }

    /**
     * 정해진 자리의 원소를 꺼낸다. 없거나 이미 쓰고 있으면 null.
     *
     * Client 가 Host 의 탱크를 그대로 받을 때 쓴다. 번호가 곧 자리 번호라, Host 가
     * 부른 번호로 꺼내면 다음 스냅샷에서도 같은 탱크를 알아본다.
     */
    fun obtainAt(index: Int): T? {
        if (index !in items.indices) return null
        val item = items[index]
        if (!free.remove(item)) return null
        active += item
        return item
    }

    fun release(item: T) {
        if (!active.remove(item)) return
        free.addLast(item)
    }

    /** [predicate] 가 true 인 원소를 한 번에 회수한다. 순회 중 제거가 안전하다. */
    fun releaseIf(predicate: (T) -> Boolean) {
        var i = active.size - 1
        while (i >= 0) {
            val item = active[i]
            if (predicate(item)) {
                active.removeAt(i)
                free.addLast(item)
            }
            i--
        }
    }

    fun clear() {
        free.clear()
        free.addAll(items)
        active.clear()
        exhaustedCount = 0
    }
}
