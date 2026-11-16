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
    fun `ids, deletions and key material are carried and sealed`() {
        val withIds = records.mapIndexed { i, r ->
            VaultBackup.Record(r.type, r.payload, r.createdAt, r.updatedAt, uid = "id-$i")
        }
        val text = VaultBackup.encode(
            material, withIds, dek, tombstones = listOf(VaultBackup.Tombstone("gone", 77)),
        )
        val backup = VaultBackup.parse(text)
        VaultBackup.verify(backup, dek)
        assertEquals(listOf("id-0", "id-1", "id-2"), backup.records.map { it.uid })
        assertEquals(listOf("gone" to 77L), backup.tombstones.map { it.uid to it.deletedAt })

        fun tampered(change: (JSONObject) -> Unit) = JSONObject(text).also(change).toString()
        assertRejects<VaultBackup.InvalidBackupException>(
            tampered { it.getJSONArray("items").getJSONObject(0).put("uid", "other") }
        )
        assertRejects<VaultBackup.InvalidBackupException>(
            tampered { it.put("deleted", org.json.JSONArray()) }
        )
        assertRejects<VaultBackup.InvalidBackupException>(tampered { it.put("keyUpdatedAt", 5) })
    }

    @Test
    fun `version 1 files are still read`() {
        // As written by Balrog 1.1–1.4: no ids, no deletions, records-only seal.
        val v1 = JSONObject(file).apply {
            put("version", 1)
            remove("deleted")
            remove("keyUpdatedAt")
            val items = getJSONArray("items")
            for (i in 0 until items.length()) items.getJSONObject(i).remove("uid")
        }
        // Recompute the seal the way version 1 did.
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        fun putInt(n: Int) = sha.update(java.nio.ByteBuffer.allocate(4).putInt(n).array())
        putInt(records.size)
        for (r in records) {
            val type = r.type.encodeToByteArray()
            putInt(type.size); sha.update(type)
            sha.update(java.nio.ByteBuffer.allocate(8).putLong(r.createdAt).array())
            sha.update(java.nio.ByteBuffer.allocate(8).putLong(r.updatedAt).array())
            putInt(r.payload.size); sha.update(r.payload)
        }
        v1.put("seal", java.util.Base64.getEncoder().encodeToString(CryptoEngine.encrypt(dek, sha.digest())))
        val backup = VaultBackup.parse(v1.toString())
        VaultBackup.open(backup, password.toCharArray())
        assertEquals(3, backup.records.size)
        assertTrue(backup.records.all { it.uid.isEmpty() })
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
