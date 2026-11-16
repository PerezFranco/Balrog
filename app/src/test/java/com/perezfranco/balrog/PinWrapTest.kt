package com.perezfranco.balrog

import com.perezfranco.balrog.crypto.CryptoEngine
import com.perezfranco.balrog.crypto.PinWrap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PinWrapTest {

    private val secret = CryptoEngine.randomBytes(32)

    @Test
    fun `the right PIN recovers the secret`() {
        val sealed = PinWrap.seal(secret, "4821".toCharArray(), iterations = 1_000)
        assertArrayEquals(
            secret,
            PinWrap.open(sealed.blob, sealed.salt, sealed.iterations, "4821".toCharArray()),
        )
    }

    @Test
    fun `a wrong PIN recovers nothing`() {
        val sealed = PinWrap.seal(secret, "4821".toCharArray(), iterations = 1_000)
        for (guess in listOf("4822", "0000", "48210", "")) {
            assertNull(PinWrap.open(sealed.blob, sealed.salt, sealed.iterations, guess.toCharArray()))
        }
        assertNull(PinWrap.open(ByteArray(3), sealed.salt, sealed.iterations, "4821".toCharArray()))
    }

    @Test
    fun `sealing twice gives unrelated blobs that do not expose the secret`() {
        val a = PinWrap.seal(secret, "4821".toCharArray(), iterations = 1_000)
        val b = PinWrap.seal(secret, "4821".toCharArray(), iterations = 1_000)
        assertFalse(a.blob.contentEquals(b.blob))
        assertFalse(a.salt.contentEquals(b.salt))
        assertFalse(a.blob.toList().windowed(secret.size).any { it == secret.toList() })
    }
}
