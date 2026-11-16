package com.perezfranco.balrog.security

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Time-based one-time passwords (RFC 6238) — the codes produced by
 * authenticator apps. Pure Kotlin/JCA so it can be unit-tested off-device.
 */
object Totp {

    enum class Algorithm(val macName: String) {
        SHA1("HmacSHA1"),
        SHA256("HmacSHA256"),
        SHA512("HmacSHA512"),
    }

    /**
     * Everything needed to produce codes for one account.
     *
     * @property secret the shared key, Base32 (upper-case, no padding).
     */
    data class Config(
        val secret: String,
        val algorithm: Algorithm = Algorithm.SHA1,
        val digits: Int = 6,
        val periodSeconds: Int = 30,
        val issuer: String = "",
        val account: String = "",
    ) {
        /** Serializes to the standard `otpauth://` key URI format. */
        fun toUri(): String {
            val label = when {
                issuer.isNotEmpty() && account.isNotEmpty() -> "$issuer:$account"
                account.isNotEmpty() -> account
                else -> issuer
            }
            return buildString {
                append("otpauth://totp/").append(encode(label))
                append("?secret=").append(secret)
                if (issuer.isNotEmpty()) append("&issuer=").append(encode(issuer))
                append("&algorithm=").append(algorithm.name)
                append("&digits=").append(digits)
                append("&period=").append(periodSeconds)
            }
        }
    }

    class InvalidTotpException(message: String) : Exception(message)

    /**
     * Parses what a user might paste or scan when a site offers 2FA setup:
     * either a full `otpauth://totp/…` URI or just the Base32 setup key.
     *
     * @throws InvalidTotpException with a user-presentable message.
     */
    fun parse(input: String): Config {
        val text = input.trim()
        if (text.isEmpty()) throw InvalidTotpException("Enter a setup key")
        return if (text.startsWith("otpauth://", ignoreCase = true)) {
            parseUri(text)
        } else {
            Config(secret = normalizeSecret(text))
        }
    }

    /** Like [parse] but returns null instead of throwing. */
    fun parseOrNull(input: String): Config? = try {
        parse(input)
    } catch (e: InvalidTotpException) {
        null
    }

    /** The code valid at [timeMillis], zero-padded to the configured length. */
    fun generate(config: Config, timeMillis: Long = System.currentTimeMillis()): String {
        val counter = Math.floorDiv(timeMillis / 1000, config.periodSeconds.toLong())
        return hotp(Base32.decode(config.secret), counter, config.digits, config.algorithm)
    }

    /** Seconds until the code produced by [generate] at [timeMillis] rolls over. */
    fun secondsRemaining(config: Config, timeMillis: Long = System.currentTimeMillis()): Int {
        val period = config.periodSeconds
        return period - Math.floorMod(timeMillis / 1000, period.toLong()).toInt()
    }

    /** HOTP (RFC 4226): HMAC the counter, then dynamically truncate. */
    internal fun hotp(key: ByteArray, counter: Long, digits: Int, algorithm: Algorithm): String {
        val mac = Mac.getInstance(algorithm.macName)
        mac.init(SecretKeySpec(key, algorithm.macName))
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash[hash.size - 1].toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        var modulus = 1L
        repeat(digits) { modulus *= 10 }
        return (binary % modulus).toString().padStart(digits, '0')
    }

    private fun parseUri(text: String): Config {
        val uri = try {
            URI(text)
        } catch (e: Exception) {
            throw InvalidTotpException("That is not a valid otpauth:// link")
        }
        when (uri.host?.lowercase()) {
            "totp" -> Unit
            "hotp" -> throw InvalidTotpException(
                "Counter-based (HOTP) codes are not supported — only time-based ones"
            )
            else -> throw InvalidTotpException("That is not a valid otpauth:// link")
        }
        val params = (uri.rawQuery ?: "").split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                decode(key).lowercase() to decode(value)
            }

        val secret = normalizeSecret(
            params["secret"] ?: throw InvalidTotpException("The link has no secret key")
        )
        val algorithm = when (val name = params["algorithm"]?.uppercase()?.replace("-", "")) {
            null, "", "SHA1" -> Algorithm.SHA1
            "SHA256" -> Algorithm.SHA256
            "SHA512" -> Algorithm.SHA512
            else -> throw InvalidTotpException("Unsupported algorithm: $name")
        }
        val digits = params["digits"]?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 6
        if (digits !in 6..8) throw InvalidTotpException("Unsupported code length: $digits")
        val period = params["period"]?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 30
        if (period !in 1..3600) throw InvalidTotpException("Unsupported period: $period")

        // Label is "Issuer:account" or just "account"; an explicit issuer wins.
        val label = decode((uri.rawPath ?: "").removePrefix("/"))
        val labelIssuer = label.substringBefore(':', "").trim()
        val account = label.substringAfter(':').trim()
        val issuer = params["issuer"]?.trim().orEmpty().ifEmpty { labelIssuer }

        return Config(secret, algorithm, digits, period, issuer, account)
    }

    /** Strips the spacing sites add for readability and validates the key. */
    private fun normalizeSecret(raw: String): String {
        val cleaned = raw.filterNot { it.isWhitespace() || it == '-' || it == '=' }.uppercase()
        if (cleaned.isEmpty()) throw InvalidTotpException("Enter a setup key")
        val bytes = try {
            Base32.decode(cleaned)
        } catch (e: IllegalArgumentException) {
            throw InvalidTotpException(
                "Setup keys only contain the letters A–Z and the digits 2–7"
            )
        }
        if (bytes.isEmpty()) throw InvalidTotpException("That setup key is too short")
        return cleaned
    }

    private fun encode(text: String): String =
        URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private fun decode(text: String): String = try {
        // Key URIs percent-encode; a literal '+' is not a space here.
        URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        text
    }
}

/** RFC 4648 Base32, as used for authenticator setup keys. */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /**
     * Decodes unpadded or padded Base32, case-insensitively.
     *
     * @throws IllegalArgumentException on characters outside the alphabet.
     */
    fun decode(text: String): ByteArray {
        val input = text.trimEnd('=')
        val out = ByteArray(input.length * 5 / 8)
        var buffer = 0
        var bits = 0
        var index = 0
        for (c in input) {
            val value = ALPHABET.indexOf(c.uppercaseChar())
            require(value >= 0) { "Invalid Base32 character" }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[index++] = (buffer shr bits).toByte()
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return out
    }

    fun encode(bytes: ByteArray): String = buildString {
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                append(ALPHABET[(buffer shr bits) and 0x1f])
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) append(ALPHABET[(buffer shl (5 - bits)) and 0x1f])
    }
}
