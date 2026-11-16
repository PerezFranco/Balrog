package com.perezfranco.balrog

import com.perezfranco.balrog.security.DeterministicGenerator
import com.perezfranco.balrog.security.DeterministicGenerator.Recipe
import com.perezfranco.balrog.security.DeterministicGenerator.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the deterministic generator to the standalone Generator app. If one
 * of the reference values below ever changes, the algorithm has been broken:
 * fix the code, never the expected value.
 */
class DeterministicGeneratorTest {

    private fun generate(type: Type, trim: Int = 10, pepper: String = "") =
        DeterministicGenerator.generate("Roberto", "Joaquin", "Perez-Franco", pepper, trim, type)

    @Test
    fun `matches the Generator app's reference values`() {
        assertEquals("mpyz\\XK-%G", generate(Type.SPECIAL))
        assertEquals("orxycYT73Q", generate(Type.ALPHANUMERIC))
        assertEquals("prxyeaWGDV", generate(Type.LETTERS))
    }

    @Test
    fun `a pepper is the salt continued`() {
        assertEquals(
            DeterministicGenerator.generate("b", "c", "saltpepper", "", 12, Type.SPECIAL),
            DeterministicGenerator.generate("b", "c", "salt", "pepper", 12, Type.SPECIAL),
        )
        assertNotEquals(generate(Type.SPECIAL), generate(Type.SPECIAL, pepper = "x"))
    }

    @Test
    fun `full-length passwords use every character of the pool exactly once`() {
        for (type in Type.entries) {
            val password = generate(type, trim = type.maxTrim)
            assertEquals(type.maxTrim, password.length)
            assertEquals(type.maxTrim, password.toSet().size)
        }
        assertTrue(generate(Type.LETTERS, 52).all { it.isLetter() })
        assertTrue(generate(Type.ALPHANUMERIC, 62).all { it.isLetterOrDigit() })
        assertTrue(generate(Type.SPECIAL, 94).all { it in '!'..'~' })
    }

    @Test
    fun `trim is part of the derivation, not a cut`() {
        assertNotEquals(generate(Type.SPECIAL, 10), generate(Type.SPECIAL, 11).take(10))
    }

    @Test
    fun `recipes use the Generator's Full Copy format`() {
        val recipe = Recipe("Roberto", "Joaquin", "Perez-Franco", 10, Type.ALPHANUMERIC, "a...L", "o...Q")
        val text = "Base: Roberto\nCase: Joaquin\nSalt: Perez-Franco\nTrim: 10\nType: AN" +
            "\nPepper: a...L\nSane: o...Q"
        assertEquals(text, recipe.toText())
        assertEquals(recipe, Recipe.parse(text))
        assertEquals(recipe, Recipe.parse(text.replace("\n", "\r\n")))
    }

    @Test
    fun `recipes without case, pepper or check parse cleanly`() {
        val recipe = Recipe.parse("Base: site\nCase: \nSalt: s: with colon\nTrim: 20\nType: ANC")!!
        assertEquals("site", recipe.base)
        assertEquals("", recipe.case)
        assertEquals("s: with colon", recipe.salt)
        assertEquals(20, recipe.trim)
        assertEquals(Type.SPECIAL, recipe.type)
        assertNull(recipe.pepperHint)
        assertNull(recipe.sane)
        assertNull(Recipe.parse("just some text"))
        assertNull(Recipe.parse(""))
    }

    @Test
    fun `hints are the first and last character`() {
        assertEquals("m...G", DeterministicGenerator.hint("mpyz\\XK-%G"))
        assertEquals("x...x", DeterministicGenerator.hint("x"))
        assertNull(DeterministicGenerator.hint(""))
    }
}
