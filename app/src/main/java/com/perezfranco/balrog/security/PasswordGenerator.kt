package com.perezfranco.balrog.security

import java.security.SecureRandom

/** Generates random passwords with a cryptographically secure RNG. */
object PasswordGenerator {

    const val LOWERCASE = "abcdefghijklmnopqrstuvwxyz"
    const val UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    const val DIGITS = "0123456789"
    const val SYMBOLS = "!@#\$%^&*()-_=+[]{};:,.<>?/"

    private val random = SecureRandom()

    /**
     * Generates a password of [length] characters drawn from the enabled
     * classes, guaranteeing at least one character from each enabled class.
     */
    fun generate(
        length: Int,
        useLowercase: Boolean = true,
        useUppercase: Boolean = true,
        useDigits: Boolean = true,
        useSymbols: Boolean = true,
    ): String {
        val pools = buildList {
            if (useLowercase) add(LOWERCASE)
            if (useUppercase) add(UPPERCASE)
            if (useDigits) add(DIGITS)
            if (useSymbols) add(SYMBOLS)
        }
        require(pools.isNotEmpty()) { "At least one character class must be enabled" }
        require(length >= pools.size) { "Length too short for the enabled classes" }

        val allChars = pools.joinToString("")
        val chars = CharArray(length)
        // One guaranteed pick per pool, the rest from the combined alphabet.
        pools.forEachIndexed { i, pool ->
            chars[i] = pool[random.nextInt(pool.length)]
        }
        for (i in pools.size until length) {
            chars[i] = allChars[random.nextInt(allChars.length)]
        }
        // Fisher–Yates shuffle so guaranteed picks aren't clustered up front.
        for (i in chars.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
        return String(chars)
    }
}
