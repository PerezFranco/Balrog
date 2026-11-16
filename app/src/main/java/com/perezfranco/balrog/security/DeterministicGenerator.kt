package com.perezfranco.balrog.security

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The deterministic generator: the same inputs always give the same
 * password, so a password can be recreated from its recipe instead of being
 * stored.
 *
 * This is a faithful port of the standalone "Generator" app
 * (art.zirie.generator) and must keep producing exactly the same output as
 * that app and its JavaScript and PHP siblings. Do not "improve" the
 * algorithm: any change, however small, silently changes every password
 * anyone has ever derived with it. [DeterministicGeneratorTest] pins the
 * reference values.
 */
object DeterministicGenerator {

    /** The character sets a password can be drawn from. */
    enum class Type(val abbreviation: String, val label: String, val poolSize: Int) {
        SPECIAL("ANC", "Letters, numbers and special", 94),
        ALPHANUMERIC("AN", "Letters and numbers", 62),
        LETTERS("A", "Letters only", 52);

        /** The longest password this type can produce (no character repeats). */
        val maxTrim: Int get() = poolSize
    }

    /**
     * @param base required; what the password is for.
     * @param case optional qualifier.
     * @param salt required.
     * @param pepper optional secret, appended directly to [salt].
     * @param trim length of the password, 1..[Type.maxTrim].
     */
    fun generate(
        base: String,
        case: String,
        salt: String,
        pepper: String,
        trim: Int,
        type: Type,
    ): String {
        require(trim in 1..type.maxTrim) { "Trim must be between 1 and ${type.maxTrim}" }
        val ranks = computeRanks(base, case, salt, pepper, trim, type.poolSize)
        val sb = StringBuilder()
        for (i in 0 until trim) {
            val r = ranks[i]
            val c = when (type) {
                Type.SPECIAL -> 33 + r
                Type.ALPHANUMERIC -> when {
                    r < 10 -> r + 48
                    r > 35 -> r + (97 - 36)
                    else -> r + (65 - 10)
                }
                Type.LETTERS -> if (r > 25) r + (97 - 26) else r + 65
            }
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    /** "a...L": the first and last character of [value], or null if it is empty. */
    fun hint(value: String): String? =
        if (value.isEmpty()) null else "${value.first()}...${value.last()}"

    // ---- Core algorithm (mirrors the original, line for line) ------------------

    private fun deriveBytes(passwordStr: String, saltStr: String, iterations: Int, byteLen: Int): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(
            passwordStr.toCharArray(),
            saltStr.toByteArray(StandardCharsets.US_ASCII),
            iterations,
            byteLen * 8
        )
        return factory.generateSecret(spec).encoded
    }

    private fun computeRanks(
        base: String, case: String, salt: String, pepper: String, trim: Int, poolSize: Int
    ): IntArray {
        val baseStr = "My base is $base."
        val caseStr = if (case.isNotEmpty()) " My case is $case." else " I use no case."
        val baseCase = baseStr + caseStr

        val saltCombined = salt + pepper
        val salt1 = if (saltCombined.isNotEmpty()) "My salt 1 is $saltCombined." else "I use no salt 1."
        val salt2 = " My trim is $trim."
        val fullSalt = salt1 + salt2

        val derived = deriveBytes(baseCase, fullSalt, 100000, 200)
        val b64 = Base64.getEncoder().encodeToString(derived)

        val numbers = LongArray(poolSize)
        for (i in 0 until poolSize) {
            val p = 2 * i
            val sb = StringBuilder()
            for (n in 0 until 5) {
                sb.append(b64[p + n].code)
            }
            numbers[i] = sb.toString().toLong()
        }

        val indices = (0 until poolSize).sortedBy { numbers[it] }
        val ranks = IntArray(poolSize)
        indices.forEachIndexed { sortedPos, origIndex -> ranks[origIndex] = sortedPos }
        return ranks
    }

    // ---- Recipes -----------------------------------------------------------------

    /**
     * Everything needed to recreate a password, minus the Pepper's value —
     * in the Generator app's "Full Copy" text format, so recipes move freely
     * between the two apps (by paste or QR code).
     *
     * @property pepperHint "a...L" for the Pepper that was used, or null if none.
     * @property sane "m...G": first and last character of the password, used
     *           to confirm that a regenerated password is the right one.
     */
    data class Recipe(
        val base: String = "",
        val case: String = "",
        val salt: String = "",
        val trim: Int? = null,
        val type: Type? = null,
        val pepperHint: String? = null,
        val sane: String? = null,
    ) {
        fun toText(): String = buildString {
            append("Base: $base\nCase: $case\nSalt: $salt\nTrim: ${trim ?: ""}")
            append("\nType: ${(type ?: Type.SPECIAL).abbreviation}")
            if (pepperHint != null) append("\nPepper: $pepperHint")
            if (sane != null) append("\nSane: $sane")
        }

        companion object {
            /** Reads "Full Copy" text. Returns null if it has none of the expected lines. */
            fun parse(text: String): Recipe? {
                val data = HashMap<String, String>()
                for (line in text.split("\n")) {
                    val parts = line.trimEnd('\r').split(": ", limit = 2)
                    if (parts.size == 2) data[parts[0].trim()] = parts[1].trim()
                }
                if (listOf("Base", "Salt", "Trim", "Type").none(data::containsKey)) return null
                return Recipe(
                    base = data["Base"].orEmpty(),
                    case = data["Case"].orEmpty(),
                    salt = data["Salt"].orEmpty(),
                    trim = data["Trim"]?.toIntOrNull(),
                    type = Type.entries.firstOrNull { it.abbreviation == data["Type"] },
                    pepperHint = data["Pepper"]?.let(::normalizeHint),
                    sane = data["Sane"]?.let(::normalizeHint),
                )
            }

            /** "ab...yz" → "a...z"; null unless it has a character on each side. */
            private fun normalizeHint(value: String): String? {
                val parts = value.split("...")
                if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null
                return "${parts[0][0]}...${parts[1][0]}"
            }
        }
    }
}
