package com.perezfranco.balrog

import com.perezfranco.balrog.security.PassphraseGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PassphraseGeneratorTest {

    // Unit tests run with the module directory as the working directory.
    private val words = File("src/main/res/raw/eff_large_wordlist.txt")
        .readLines().filter { it.isNotBlank() }

    @Test
    fun `bundled word list is the full EFF large list`() {
        assertEquals(7776, words.size)
        assertEquals(7776, words.toSet().size)
        assertTrue(words.all { word -> word.all { it in 'a'..'z' || it == '-' } })
    }

    @Test
    fun `generates the requested number of list words`() {
        // A space never occurs inside a word, so it splits unambiguously.
        val phrase = PassphraseGenerator.generate(words, wordCount = 6, separator = " ")
        val parts = phrase.split(" ")
        assertEquals(6, parts.size)
        assertTrue(parts.all { it in words })
    }

    @Test
    fun `capitalize and number options are applied`() {
        repeat(50) {
            val parts = PassphraseGenerator.generate(
                words, wordCount = 4, separator = " ", capitalize = true, includeNumber = true,
            ).split(" ")
            assertTrue(parts.all { it.first().isUpperCase() })
            assertEquals(1, parts.count { it.last().isDigit() })
            assertTrue(parts.all { it.trimEnd { c -> c.isDigit() }.lowercase() in words })
        }
    }

    @Test
    fun `draws vary`() {
        val phrases = List(20) { PassphraseGenerator.generate(words) }.toSet()
        assertEquals(20, phrases.size)
    }

    @Test
    fun `entropy is about 12_9 bits per word`() {
        assertEquals(64.6, PassphraseGenerator.entropyBits(7776, 5), 0.1)
        assertEquals(77.5, PassphraseGenerator.entropyBits(7776, 6), 0.1)
        assertTrue(
            PassphraseGenerator.entropyBits(7776, 5, includeNumber = true) >
                PassphraseGenerator.entropyBits(7776, 5)
        )
    }
}
