package com.perezfranco.balrog

import com.perezfranco.balrog.security.PatternLogic
import org.junit.Assert.assertEquals
import org.junit.Test

class PatternLogicTest {

    private fun draw(vararg dots: Int): List<Int> =
        dots.fold(emptyList()) { pattern, dot -> PatternLogic.extend(pattern, dot) }

    @Test
    fun `dots are added in order and never twice`() {
        assertEquals(listOf(0, 1, 2, 5), draw(0, 1, 2, 5))
        assertEquals(listOf(0, 1, 4), draw(0, 1, 0, 1, 4, 4))
    }

    @Test
    fun `a stroke over an unused dot picks it up`() {
        assertEquals(listOf(0, 1, 2), draw(0, 2))       // along a row
        assertEquals(listOf(0, 3, 6), draw(0, 6))       // down a column
        assertEquals(listOf(0, 4, 8), draw(0, 8))       // diagonal
        assertEquals(listOf(2, 4, 6), draw(2, 6))
        assertEquals(listOf(3, 4, 5), draw(3, 5))
    }

    @Test
    fun `a stroke over an already used dot passes through`() {
        assertEquals(listOf(4, 0, 8), draw(4, 0, 8))
        assertEquals(listOf(1, 0, 2), draw(1, 0, 2))
    }

    @Test
    fun `knight moves and neighbours skip nothing`() {
        assertEquals(listOf(0, 5), draw(0, 5))
        assertEquals(listOf(0, 7), draw(0, 7))
        assertEquals(listOf(1, 3), draw(1, 3))
        assertEquals(listOf(0, 4), draw(0, 4))
    }

    @Test
    fun `patterns encode as their dot numbers`() {
        assertEquals("03678", String(PatternLogic.encode(listOf(0, 3, 6, 7, 8))))
    }
}
