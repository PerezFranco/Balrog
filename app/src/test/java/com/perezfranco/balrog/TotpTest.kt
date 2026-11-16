package com.perezfranco.balrog

import com.perezfranco.balrog.security.Base32
import com.perezfranco.balrog.security.Totp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TotpTest {

    // RFC 6238 Appendix B test keys: ASCII digits, one key length per hash.
    private val sha1Key = Base32.encode("12345678901234567890".toByteArray())
    private val sha256Key = Base32.encode("12345678901234567890123456789012".toByteArray())
    private val sha512Key = Base32.encode(
        "1234567890123456789012345678901234567890123456789012345678901234".toByteArray()
    )

    private fun code(key: String, algorithm: Totp.Algorithm, seconds: Long) =
        Totp.generate(Totp.Config(key, algorithm, digits = 8), seconds * 1000)

    @Test
    fun `matches RFC 6238 test vectors for SHA1`() {
        assertEquals("94287082", code(sha1Key, Totp.Algorithm.SHA1, 59))
        assertEquals("07081804", code(sha1Key, Totp.Algorithm.SHA1, 1111111109))
        assertEquals("14050471", code(sha1Key, Totp.Algorithm.SHA1, 1111111111))
        assertEquals("89005924", code(sha1Key, Totp.Algorithm.SHA1, 1234567890))
        assertEquals("69279037", code(sha1Key, Totp.Algorithm.SHA1, 2000000000))
        assertEquals("65353130", code(sha1Key, Totp.Algorithm.SHA1, 20000000000))
    }

    @Test
    fun `matches RFC 6238 test vectors for SHA256`() {
        assertEquals("46119246", code(sha256Key, Totp.Algorithm.SHA256, 59))
        assertEquals("68084774", code(sha256Key, Totp.Algorithm.SHA256, 1111111109))
        assertEquals("91819424", code(sha256Key, Totp.Algorithm.SHA256, 1234567890))
        assertEquals("77737706", code(sha256Key, Totp.Algorithm.SHA256, 20000000000))
    }

    @Test
    fun `matches RFC 6238 test vectors for SHA512`() {
        assertEquals("90693936", code(sha512Key, Totp.Algorithm.SHA512, 59))
        assertEquals("25091201", code(sha512Key, Totp.Algorithm.SHA512, 1111111109))
        assertEquals("93441116", code(sha512Key, Totp.Algorithm.SHA512, 1234567890))
        assertEquals("47863826", code(sha512Key, Totp.Algorithm.SHA512, 20000000000))
    }

    @Test
    fun `six digit codes keep their leading zeros`() {
        // The 8-digit vector at T=1111111109 is 07081804; its last six digits.
        val config = Totp.Config(sha1Key)
        assertEquals("081804", Totp.generate(config, 1111111109_000))
    }

    @Test
    fun `seconds remaining counts down within the period`() {
        val config = Totp.Config(sha1Key)
        assertEquals(30, Totp.secondsRemaining(config, 60_000))
        assertEquals(1, Totp.secondsRemaining(config, 89_999))
    }

    @Test
    fun `base32 round-trips and matches RFC 4648 vectors`() {
        assertEquals("MZXW6YTBOI", Base32.encode("foobar".toByteArray()))
        assertArrayEquals("foobar".toByteArray(), Base32.decode("MZXW6YTBOI======"))
        assertArrayEquals("fooba".toByteArray(), Base32.decode("mzxw6ytb"))
        for (n in 0..40) {
            val bytes = ByteArray(n) { (it * 37 + n).toByte() }
            assertArrayEquals(bytes, Base32.decode(Base32.encode(bytes)))
        }
    }

    @Test
    fun `parses a bare setup key with the spacing sites add`() {
        val config = Totp.parse("  jbsw y3dp-ehpk 3pxp ")
        assertEquals("JBSWY3DPEHPK3PXP", config.secret)
        assertEquals(Totp.Algorithm.SHA1, config.algorithm)
        assertEquals(6, config.digits)
        assertEquals(30, config.periodSeconds)
    }

    @Test
    fun `parses an otpauth URI with issuer and account`() {
        val config = Totp.parse(
            "otpauth://totp/ACME%20Co:john.doe+test@email.com" +
                "?secret=HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ&issuer=ACME%20Co" +
                "&algorithm=SHA256&digits=8&period=60"
        )
        assertEquals("HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ", config.secret)
        assertEquals("ACME Co", config.issuer)
        assertEquals("john.doe+test@email.com", config.account)
        assertEquals(Totp.Algorithm.SHA256, config.algorithm)
        assertEquals(8, config.digits)
        assertEquals(60, config.periodSeconds)
    }

    @Test
    fun `takes the issuer from the label when there is no issuer parameter`() {
        val config = Totp.parse("otpauth://totp/GitHub:octocat?secret=JBSWY3DPEHPK3PXP")
        assertEquals("GitHub", config.issuer)
        assertEquals("octocat", config.account)
    }

    @Test
    fun `URI serialization round-trips`() {
        val original = Totp.Config(
            secret = "JBSWY3DPEHPK3PXP",
            algorithm = Totp.Algorithm.SHA512,
            digits = 7,
            periodSeconds = 45,
            issuer = "Müller & Söhne",
            account = "a b+c@example.com",
        )
        assertEquals(original, Totp.parse(original.toUri()))
        val bare = Totp.Config(secret = "JBSWY3DPEHPK3PXP")
        assertEquals(bare, Totp.parse(bare.toUri()))
    }

    @Test
    fun `rejects bad input with a readable message`() {
        fun messageFor(input: String): String = try {
            Totp.parse(input)
            fail("Expected $input to be rejected")
            ""
        } catch (e: Totp.InvalidTotpException) {
            e.message.orEmpty()
        }
        assertTrue(messageFor("").isNotEmpty())
        assertTrue(messageFor("not-base32-1890!").contains("A–Z"))
        assertTrue(messageFor("otpauth://hotp/x?secret=JBSWY3DPEHPK3PXP&counter=1").contains("HOTP"))
        assertTrue(messageFor("otpauth://totp/x").contains("secret"))
        assertTrue(messageFor("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&digits=12").contains("12"))
        assertTrue(messageFor("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&algorithm=MD5").contains("MD5"))
        assertNull(Totp.parseOrNull("https://example.com/"))
    }
}
