package com.kophas.battlecity.gameplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectPoolTest {

    private class Item(val index: Int)

    @Test
    fun `획득하면 활성 목록에 들어간다`() {
        val pool = ObjectPool(4) { Item(it) }
        val a = pool.obtain()
        assertNotNull(a)
        assertEquals(1, pool.activeCount)
        assertEquals(3, pool.freeCount)
    }

    @Test
    fun `반납하면 다시 쓸 수 있다`() {
        val pool = ObjectPool(2) { Item(it) }
        val a = pool.obtain()!!
        pool.release(a)
        assertEquals(0, pool.activeCount)
        assertEquals(2, pool.freeCount)
        assertNotNull(pool.obtain())
    }

    @Test
    fun `풀이 비면 null 을 주고 횟수를 센다`() {
        val pool = ObjectPool(2) { Item(it) }
        pool.obtain()
        pool.obtain()
        assertNull(pool.obtain())
        assertNull(pool.obtain())
        assertEquals(2, pool.exhaustedCount)
    }

    @Test
    fun `새 객체를 만들지 않고 재사용한다`() {
        var created = 0
        val pool = ObjectPool(2) { created++; Item(it) }
        assertEquals(2, created)

        repeat(50) {
            val item = pool.obtain()!!
            pool.release(item)
        }
        assertEquals("풀은 추가 할당을 하지 않아야 한다", 2, created)
    }

    @Test
    fun `releaseIf 는 조건에 맞는 것만 회수한다`() {
        val pool = ObjectPool(6) { Item(it) }
        val obtained = List(6) { pool.obtain()!! }
        pool.releaseIf { it.index % 2 == 0 }

        assertEquals(3, pool.activeCount)
        assertTrue(pool.active.all { it.index % 2 == 1 })
        assertEquals(obtained.size, pool.activeCount + pool.freeCount)
    }

    @Test
    fun `clear 는 전부 되돌린다`() {
        val pool = ObjectPool(3) { Item(it) }
        repeat(3) { pool.obtain() }
        pool.obtain()
        pool.clear()

        assertEquals(0, pool.activeCount)
        assertEquals(3, pool.freeCount)
        assertEquals(0, pool.exhaustedCount)
    }
}
