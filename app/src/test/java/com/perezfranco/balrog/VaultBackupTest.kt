package com.perezfranco.balrog

import com.perezfranco.balrog.crypto.CryptoEngine
import com.perezfranco.balrog.crypto.VaultBackup
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class VaultBackupTest {

    private val password = "correct horse battery staple"
    private val dek: SecretKey = SecretKeySpec(CryptoEngine.randomBytes(32), "AES")
    private val material = run {
        val salt = CryptoEngine.randomBytes(16)
        val kek = CryptoEngine.deriveKey(password.toCharArray(), salt, 1_000)
        VaultBackup.KeyMaterial(salt, 1_000, CryptoEngine.encrypt(kek, dek.encoded))
    }
    private val plaintexts = listOf("""{"title":"one"}""", """{"title":"two"}""", """{"body":"n"}""")
    private val records = plaintexts.mapIndexed { i, json ->
        VaultBackup.Record(
            type = if (i < 2) "PASSWORD" else "NOTE",
            payload = CryptoEngine.encrypt(dek, json.encodeToByteArray()),
            createdAt = 1000L + i,
            updatedAt = 2000L + i,
        )
    }
    private val file = VaultBackup.encode(material, records, dek, createdAt = 42)

    private fun edit(change: (JSONObject) -> Unit): String =
        JSONObject(file).also(change).toString()

    private inline fun <reified T : Exception> assertRejects(text: String, pw: String = password) {
        try {
            VaultBackup.open(VaultBackup.parse(text), pw.toCharArray())
            fail("Expected ${T::class.simpleName}")
        } catch (e: Exception) {
            assertTrue("Got $e", e is T)
        }
    }

    @Test
    fun `backup round-trips and decrypts with the original password`() {
        val backup = VaultBackup.parse(file)
        assertEquals(42, backup.createdAt)
        assertEquals(3, backup.records.size)
        val key = VaultBackup.open(backup, password.toCharArray())
        backup.records.forEachIndexed { i, record ->
            assertEquals(records[i].type, record.type)
            assertEquals(records[i].createdAt, record.createdAt)
            assertEquals(records[i].updatedAt, record.updatedAt)
            assertArrayEquals(
                plaintexts[i].encodeToByteArray(), CryptoEngine.decrypt(key, record.payload)
            )
        }
    }

    @Test
    fun `an empty vault backs up and restores`() {
        val empty = VaultBackup.parse(VaultBackup.encode(material, emptyList(), dek))
        VaultBackup.open(empty, password.toCharArray())
        assertTrue(empty.records.isEmpty())
    }

    @Test
    fun `the file contains no plaintext`() {
        assertTrue(plaintexts.none { it in file })
        assertTrue("one" !in file && password !in file)
    }

    @Test
    fun `wrong password is reported as such`() {
        assertRejects<VaultBackup.WrongPasswordException>(file, "not the password")
    }

    @Test
    fun `dropping, reordering or relabelling records is detected`() {
        assertRejects<VaultBackup.InvalidBackupException>(
            edit { it.getJSONArray("items").remove(1) }
        )
        assertRejects<VaultBackup.InvalidBackupException>(edit {
            val items = it.getJSONArray("items")
            val first = items.get(0)
            items.put(0, items.get(1))
            items.put(1, first)
        })
        assertRejects<VaultBackup.InvalidBackupException>(
            edit { it.getJSONArray("items").getJSONObject(0).put("type", "NOTE") }
        )
        assertRejects<VaultBackup.InvalidBackupException>(
            edit { it.getJSONArray("items").getJSONObject(0).put("updatedAt", 9) }
        )
    }

    @Test
    fun `files that are not backups are rejected`() {
        for (text in listOf("", "hello", "{}", """{"format":"balrog-backup","version":1}""")) {
            assertRejects<VaultBackup.InvalidBackupException>(text)
        }
        assertRejects<VaultBackup.InvalidBackupException>(edit { it.put("version", 99) })
        assertRejects<VaultBackup.InvalidBackupException>(
            edit { it.getJSONObject("kdf").put("iterations", 2_000_000_000) }
        )
        assertRejects<VaultBackup.InvalidBackupException>(edit { it.put("wrappedKey", "!!") })
    }
}
