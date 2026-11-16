package com.perezfranco.balrog.security

import java.security.SecureRandom
import kotlin.math.ln

/**
 * Generates diceware-style passphrases: several words drawn uniformly at
 * random from a fixed list. Easier to type and remember than a random
 * string, with strength that comes from the number of words.
 */
object PassphraseGenerator {

    const val MIN_WORDS = 3
    const val MAX_WORDS = 10
    const val DEFAULT_WORDS = 5

    private val random = SecureRandom()

    /**
     * @param words the list to draw from (see [WordList]).
     * @param capitalize upper-case the first letter of every word.
     * @param includeNumber append a random digit to one randomly chosen word,
     *        for sites that insist on a number.
     */
    fun generate(
        words: List<String>,
        wordCount: Int = DEFAULT_WORDS,
        separator: String = "-",
        capitalize: Boolean = false,
        includeNumber: Boolean = false,
    ): String {
        require(words.size >= 2) { "Word list too small" }
        require(wordCount >= 1) { "At least one word is required" }

        val picked = MutableList(wordCount) {
            val word = words[random.nextInt(words.size)]
            if (capitalize) word.replaceFirstChar { it.uppercaseChar() } else word
        }
        if (includeNumber) {
            val at = random.nextInt(wordCount)
            picked[at] = picked[at] + random.nextInt(10)
        }
        return picked.joinToString(separator)
    }

    /** Entropy in bits of a passphrase from [generate] with these options. */
    fun entropyBits(listSize: Int, wordCount: Int, includeNumber: Boolean = false): Double {
        var bits = wordCount * log2(listSize.toDouble())
        // A digit (10 choices) placed on one of wordCount words.
        if (includeNumber) bits += log2(10.0 * wordCount)
        return bits
    }

    private fun log2(x: Double): Double = ln(x) / ln(2.0)
}
