package com.perezfranco.balrog

import com.perezfranco.balrog.crypto.CryptoEngine
import com.perezfranco.balrog.security.PasswordGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

class CryptoEngineTest {

    @Test
    fun `encrypt then decrypt round-trips`() {
        val key = CryptoEngine.deriveKey(
            "correct horse battery staple".toCharArray(),
            CryptoEngine.randomBytes(16),
            iterations = 1_000, // keep the test fast
        )
        val plaintext = "the vault payload".encodeToByteArray()
        val blob = CryptoEngine.encrypt(key, plaintext)
        assertArrayEquals(plaintext, CryptoEngine.decrypt(key, blob))
    }

    @Test
    fun `same password and salt derive the same key`() {
        val salt = CryptoEngine.randomBytes(16)
        val a = CryptoEngine.deriveKey("hunter2hunter2".toCharArray(), salt, 1_000)
        val b = CryptoEngine.deriveKey("hunter2hunter2".toCharArray(), salt, 1_000)
        assertArrayEquals(a.encoded, b.encoded)
    }

    @Test
    fun `different salts derive different keys`() {
        val a = CryptoEngine.deriveKey(
            "hunter2hunter2".toCharArray(), CryptoEngine.randomBytes(16), 1_000
        )
        val b = CryptoEngine.deriveKey(
            "hunter2hunter2".toCharArray(), CryptoEngine.randomBytes(16), 1_000
        )
        assertFalse(a.encoded.contentEquals(b.encoded))
    }

    @Test(expected = AEADBadTagException::class)
    fun `wrong key fails authentication`() {
        val salt = CryptoEngine.randomBytes(16)
        val right = CryptoEngine.deriveKey("right password".toCharArray(), salt, 1_000)
        val wrong = CryptoEngine.deriveKey("wrong password".toCharArray(), salt, 1_000)
        val blob = CryptoEngine.encrypt(right, "secret".encodeToByteArray())
        CryptoEngine.decrypt(wrong, blob)
    }

    @Test(expected = AEADBadTagException::class)
    fun `tampered ciphertext fails authentication`() {
        val key = CryptoEngine.deriveKey(
            "some password".toCharArray(), CryptoEngine.randomBytes(16), 1_000
        )
        val blob = CryptoEngine.encrypt(key, "secret".encodeToByteArray())
        blob[blob.lastIndex] = (blob[blob.lastIndex].toInt() xor 1).toByte()
        CryptoEngine.decrypt(key, blob)
    }

    @Test
    fun `generator respects length and enabled classes`() {
        val password = PasswordGenerator.generate(
            length = 24,
            useLowercase = true,
            useUppercase = true,
            useDigits = true,
            useSymbols = false,
        )
        assertEquals(24, password.length)
        assertTrue(password.any { it in PasswordGenerator.LOWERCASE })
        assertTrue(password.any { it in PasswordGenerator.UPPERCASE })
        assertTrue(password.any { it in PasswordGenerator.DIGITS })
        assertTrue(password.none { it in PasswordGenerator.SYMBOLS })
    }
}
