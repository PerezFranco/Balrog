package com.perezfranco.balrog.security

/**
 * Pure logic of the nine-dot pattern: dots are numbered 0–8, left to right,
 * top to bottom.
 */
object PatternLogic {

    /**
     * Adds [dot] to [pattern] as the finger reaches it. As on the Android
     * lock screen, a dot can be used once, and a stroke that jumps straight
     * over an unused dot (corner to corner, say) picks that dot up on the way.
     */
    fun extend(pattern: List<Int>, dot: Int): List<Int> {
        if (dot in pattern) return pattern
        val last = pattern.lastOrNull() ?: return listOf(dot)
        val rowSum = last / 3 + dot / 3
        val colSum = last % 3 + dot % 3
        val between = if (rowSum % 2 == 0 && colSum % 2 == 0) {
            (rowSum / 2) * 3 + colSum / 2
        } else null
        return if (between != null && between !in pattern) pattern + between + dot
        else pattern + dot
    }

    /** The pattern as the secret that gets sealed, e.g. "03678". */
    fun encode(pattern: List<Int>): CharArray = CharArray(pattern.size) { '0' + pattern[it] }
}
