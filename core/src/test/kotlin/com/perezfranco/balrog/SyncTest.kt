package com.perezfranco.balrog

import com.perezfranco.balrog.crypto.CryptoEngine
import com.perezfranco.balrog.crypto.VaultBackup
import com.perezfranco.balrog.crypto.VaultBackup.KeyMaterial
import com.perezfranco.balrog.crypto.VaultBackup.Record
import com.perezfranco.balrog.crypto.VaultBackup.Tombstone
import com.perezfranco.balrog.sync.SyncClient
import com.perezfranco.balrog.sync.VaultMerge
import com.perezfranco.balrog.sync.VaultSync
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Merge rules, and whole sync rounds against the real PHP script. */
class SyncTest {

    private val dek: SecretKey = SecretKeySpec(CryptoEngine.randomBytes(32), "AES")
    private val key = KeyMaterial(byteArrayOf(1), 1000, byteArrayOf(2), updatedAt = 10)

    private fun record(uid: String, text: String, updatedAt: Long) =
        Record("NOTE", CryptoEngine.encrypt(dek, text.encodeToByteArray()), 1, updatedAt, uid)

    private fun text(record: Record) = CryptoEngine.decrypt(dek, record.payload).decodeToString()

    private fun state(
        records: List<Record> = emptyList(),
        tombstones: List<Tombstone> = emptyList(),
        keyMaterial: KeyMaterial = key,
    ) = VaultMerge.State(keyMaterial, records, tombstones)

    /** A device that keeps its vault in memory. */
    private inner class FakeDevice(var vault: VaultMerge.State = state()) : VaultSync.Device {
        override fun state() = vault
        override fun apply(merged: VaultMerge.State) {
            vault = merged
        }
        fun contents() = vault.records.associate { it.uid to text(it) }
        fun add(uid: String, text: String, at: Long) {
            vault = state(vault.records.filter { it.uid != uid } + record(uid, text, at),
                vault.tombstones, vault.keyMaterial)
        }
        fun delete(uid: String, at: Long) {
            vault = state(vault.records.filter { it.uid != uid },
                vault.tombstones + Tombstone(uid, at), vault.keyMaterial)
        }
    }

    // ---- Merge rules -----------------------------------------------------------

    @Test
    fun `items from both sides are kept and the newer edit wins`() {
        val merged = VaultMerge.merge(
            state(listOf(record("a", "a-old", 5), record("b", "b", 5))),
            state(listOf(record("a", "a-new", 9), record("c", "c", 5))),
        )
        assertEquals(
            mapOf("a" to "a-new", "b" to "b", "c" to "c"),
            merged.records.associate { it.uid to text(it) },
        )
    }

    @Test
    fun `a deletion beats older edits but not newer ones`() {
        val deleted = VaultMerge.merge(
            state(listOf(record("a", "a", 5))),
            state(tombstones = listOf(Tombstone("a", 7))),
        )
        assertTrue(deleted.records.isEmpty())
        assertEquals(listOf("a"), deleted.tombstones.map { it.uid })

        val revived = VaultMerge.merge(
            state(listOf(record("a", "edited later", 9))),
            state(tombstones = listOf(Tombstone("a", 7))),
        )
        assertEquals(listOf("edited later"), revived.records.map(::text))
        assertTrue(revived.tombstones.isEmpty())
    }

    @Test
    fun `merging is symmetric and stable`() {
        val a = state(listOf(record("x", "one", 5), record("y", "y", 3)), listOf(Tombstone("z", 4)))
        val b = state(listOf(record("x", "two", 5), record("z", "z", 2)))
        val ab = VaultMerge.merge(a, b)
        val ba = VaultMerge.merge(b, a)
        assertTrue(VaultMerge.sameContent(ab, ba))
        assertTrue(VaultMerge.sameContent(ab, VaultMerge.merge(ab, a)))
        assertFalse(VaultMerge.sameContent(a, b))
    }

    @Test
    fun `the most recently set master password wins`() {
        val newer = KeyMaterial(byteArrayOf(9), 1000, byteArrayOf(9), updatedAt = 20)
        assertEquals(20, VaultMerge.merge(state(), state(keyMaterial = newer)).keyMaterial.updatedAt)
        assertEquals(20, VaultMerge.merge(state(keyMaterial = newer), state()).keyMaterial.updatedAt)
    }

    // ---- Against the real server script ----------------------------------------

    private fun client(token: String = TOKEN) = SyncClient("http://127.0.0.1:$port/balrog-sync.php", token)

    @Test
    fun `two devices converge through the server`() {
        assumeTrue("php not available", server != null)
        reset()
        val phone = FakeDevice()
        val pc = FakeDevice()
        phone.add("a", "from phone", 5)

        assertTrue(VaultSync.sync(client(), dek, phone).pushed)      // first upload
        val first = VaultSync.sync(client(), dek, pc)                // pc joins
        assertTrue(first.pulled)
        assertFalse(first.pushed)
        assertEquals(mapOf("a" to "from phone"), pc.contents())

        pc.add("b", "from pc", 6)
        pc.add("a", "edited on pc", 7)
        phone.add("c", "also phone", 6)
        VaultSync.sync(client(), dek, pc)
        VaultSync.sync(client(), dek, phone)                         // merges both ways
        VaultSync.sync(client(), dek, pc)
        val expected = mapOf("a" to "edited on pc", "b" to "from pc", "c" to "also phone")
        assertEquals(expected, phone.contents())
        assertEquals(expected, pc.contents())

        phone.delete("b", 8)
        VaultSync.sync(client(), dek, phone)
        VaultSync.sync(client(), dek, pc)
        assertEquals(setOf("a", "c"), pc.contents().keys)

        val idle = VaultSync.sync(client(), dek, pc)                 // nothing to do
        assertFalse(idle.pulled || idle.pushed)
    }

    @Test
    fun `the server refuses to overwrite changes the caller has not seen`() {
        assumeTrue("php not available", server != null)
        reset()
        val c = client()
        assertNull(c.fetch().bytes)
        val first = c.push("one".encodeToByteArray(), "")!!
        assertNull(c.push("two".encodeToByteArray(), ""))            // stale base
        assertNull(c.push("two".encodeToByteArray(), "0".repeat(64)))
        val second = c.push("two".encodeToByteArray(), first)!!
        val remote = c.fetch()
        assertEquals("two", remote.bytes!!.decodeToString())
        assertEquals(second, remote.revision)
        // The previous version is kept, and neither file can be downloaded
        // directly: asking for one runs its guard instead of returning it.
        assertTrue(File(root, "balrog-data/vault.previous.php").readText().endsWith("one"))
        val direct = java.net.URL("http://127.0.0.1:$port/balrog-data/vault.php")
            .openConnection() as java.net.HttpURLConnection
        assertEquals(404, direct.responseCode)
    }

    @Test
    fun `a wrong token and a foreign vault are both refused`() {
        assumeTrue("php not available", server != null)
        reset()
        try {
            client("not the token, but long enough").fetch()
            fail("Expected the token to be rejected")
        } catch (e: SyncClient.SyncException) {
            assertEquals(SyncClient.Failure.UNAUTHORIZED, e.failure)
        }
        val phone = FakeDevice().apply { add("a", "mine", 5) }
        VaultSync.sync(client(), dek, phone)
        val otherKey = SecretKeySpec(CryptoEngine.randomBytes(32), "AES")
        try {
            VaultSync.sync(client(), otherKey, FakeDevice())
            fail("Expected a different vault to be refused")
        } catch (e: VaultSync.DifferentVaultException) {
            // and the server's copy is untouched
            assertEquals(mapOf("a" to "mine"), FakeDevice().also {
                VaultSync.sync(client(), dek, it)
            }.contents())
        }
    }

    @Test
    fun `the token comes from balrog-config php, which never reveals it`() {
        assumeTrue("php not available", server != null)
        reset()
        val configFile = File(root, "balrog-config.php")
        val original = configFile.readText()
        fun status(): SyncClient.Failure? = try {
            client().fetch(); null
        } catch (e: SyncClient.SyncException) { e.failure }
        try {
            // Replacing the script (as an update does) leaves the token alone.
            File(root, "balrog-sync.php").writeText(
                File(root, "balrog-sync.php").readText() + "\n// an updated version\n"
            )
            assertNull(status())

            // Opening the config in a browser shows nothing at all.
            val page = java.net.URL("http://127.0.0.1:$port/balrog-config.php").openConnection()
                as java.net.HttpURLConnection
            assertEquals(200, page.responseCode)
            assertEquals("", page.inputStream.readBytes().decodeToString())

            // No config, a too-short token, or a malformed config: refused with
            // a pointer to the fix, never open to everyone.
            for (broken in listOf(null, configFor("short"), "<?php return 'oops';", "<?php return array();")) {
                if (broken == null) configFile.delete() else configFile.writeText(broken)
                assertEquals(SyncClient.Failure.SERVER, status())
            }
            val response = java.net.URL("http://127.0.0.1:$port/balrog-sync.php")
                .openConnection() as java.net.HttpURLConnection
            assertEquals(503, response.responseCode)
            assertTrue(response.errorStream.readBytes().decodeToString().contains("balrog-config.php"))
        } finally {
            configFile.writeText(original)
        }
        assertNull(status())
    }

    @Test
    fun `plain http is refused for anything but this machine`() {
        try {
            SyncClient("http://example.com/balrog-sync.php", TOKEN).fetch()
            fail("Expected plain http to be refused")
        } catch (e: SyncClient.SyncException) {
            assertEquals(SyncClient.Failure.BAD_ADDRESS, e.failure)
        }
    }

    private fun reset() {
        File(root, "balrog-data").deleteRecursively()
    }

    companion object {
        private const val TOKEN = "test-token-0123456789-abcdefghij"

        /** What a user's balrog-config.php looks like. */
        private fun configFor(token: String) = "<?php return array('token' => '$token');\n"
        private var server: Process? = null
        private var port = 0
        private lateinit var root: File

        @BeforeClass
        @JvmStatic
        fun startServer() {
            // The script as shipped, with only the token filled in. Tests run
            // with the module as working directory.
            val script = listOf("../server/balrog-sync.php", "server/balrog-sync.php")
                .map(::File).firstOrNull { it.isFile } ?: return
            root = Files.createTempDirectory("balrog-sync").toFile()
            File(root, "balrog-sync.php").writeText(script.readText())
            File(root, "balrog-config.php").writeText(configFor(TOKEN))
            port = ServerSocket(0).use { it.localPort }
            val process = try {
                ProcessBuilder("php", "-S", "127.0.0.1:$port", "-t", root.path)
                    .redirectErrorStream(true)
                    .redirectOutput(File(root, "server.log"))
                    .start()
            } catch (e: Exception) {
                return // no php here: the server tests are skipped
            }
            repeat(50) {
                try {
                    Socket("127.0.0.1", port).close()
                    server = process
                    return
                } catch (e: Exception) {
                    Thread.sleep(100)
                }
            }
            process.destroy()
        }

        @AfterClass
        @JvmStatic
        fun stopServer() {
            server?.destroy()
            if (::root.isInitialized) root.deleteRecursively()
        }
    }
}
