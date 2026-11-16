package com.perezfranco.balrog.crypto

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * The vault file format: a self-contained, encrypted copy of the vault. It
 * is what a backup file is, and what devices exchange when they sync.
 *
 * A vault file holds exactly what is on disk — the wrapped vault key with
 * its KDF parameters, and every record still encrypted — so nothing is ever
 * decrypted to produce one, and it is as hard to break as the vault itself:
 * it opens only with the master password in use when it was made.
 *
 * Each record is authenticated on its own by AES-GCM. The [Backup.seal]
 * additionally binds the whole file together (a digest of the key material,
 * every record and every tombstone, encrypted under the vault key), so
 * nothing can be dropped, reordered, relabelled or swapped without
 * detection — and only someone holding the vault key can produce a file
 * that verifies.
 *
 * Version 2 added what sync needs: a permanent [Record.uid] per item,
 * [Tombstone]s for deletions, and [KeyMaterial.updatedAt]. Version 1 files
 * are still read.
 */
object VaultBackup {

    const val FILE_EXTENSION = "balrog"
    private const val FORMAT = "balrog-backup"
    private const val VERSION = 2
    private const val MAX_ITERATIONS = 10_000_000

    /**
     * @property updatedAt when the master password was last set; the newer
     *           key material wins when devices sync.
     */
    class KeyMaterial(
        val salt: ByteArray,
        val iterations: Int,
        val wrappedDek: ByteArray,
        val updatedAt: Long = 0,
    )

    /** @property uid permanent identity of the item across devices; "" in version 1 files. */
    class Record(
        val type: String,
        val payload: ByteArray,
        val createdAt: Long,
        val updatedAt: Long,
        val uid: String = "",
    )

    /** Remembers that the item [uid] was deleted, so the deletion can spread. */
    class Tombstone(val uid: String, val deletedAt: Long)

    class Backup(
        val version: Int,
        val createdAt: Long,
        val keyMaterial: KeyMaterial,
        val records: List<Record>,
        val tombstones: List<Tombstone>,
        val seal: ByteArray,
    )

    /** The file is not a Balrog vault file, or has been damaged or altered. */
    class InvalidBackupException(message: String) : Exception(message)

    /** The password does not open this file. */
    class WrongPasswordException : Exception("Wrong password for this backup")

    /** Serializes a vault file. [dek] is the unlocked vault key, used for the seal. */
    fun encode(
        keyMaterial: KeyMaterial,
        records: List<Record>,
        dek: SecretKey,
        createdAt: Long = System.currentTimeMillis(),
        tombstones: List<Tombstone> = emptyList(),
    ): String {
        val items = JSONArray()
        for (record in records) {
            items.put(
                JSONObject()
                    .put("uid", record.uid)
                    .put("type", record.type)
                    .put("payload", b64(record.payload))
                    .put("createdAt", record.createdAt)
                    .put("updatedAt", record.updatedAt)
            )
        }
        val deleted = JSONArray()
        for (tombstone in tombstones) {
            deleted.put(
                JSONObject().put("uid", tombstone.uid).put("deletedAt", tombstone.deletedAt)
            )
        }
        val digest = digest(VERSION, keyMaterial, records, tombstones)
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("createdAt", createdAt)
            .put(
                "kdf", JSONObject()
                    .put("algorithm", "PBKDF2-HMAC-SHA256")
                    .put("salt", b64(keyMaterial.salt))
                    .put("iterations", keyMaterial.iterations)
            )
            .put("wrappedKey", b64(keyMaterial.wrappedDek))
            .put("keyUpdatedAt", keyMaterial.updatedAt)
            .put("seal", b64(CryptoEngine.encrypt(dek, digest)))
            .put("items", items)
            .put("deleted", deleted)
            .toString(2)
    }

    /** Reads a vault file's structure. Nothing is decrypted yet. */
    fun parse(text: String): Backup {
        try {
            val root = JSONObject(text)
            if (root.optString("format") != FORMAT) {
                throw InvalidBackupException("This is not a Balrog backup file")
            }
            val version = root.getInt("version")
            if (version > VERSION) {
                throw InvalidBackupException(
                    "This backup was made by a newer version of Balrog"
                )
            }
            val kdf = root.getJSONObject("kdf")
            val iterations = kdf.getInt("iterations")
            val salt = unb64(kdf.getString("salt"))
            if (iterations !in 1..MAX_ITERATIONS || salt.isEmpty()) {
                throw InvalidBackupException("The backup file is damaged")
            }
            val items = root.getJSONArray("items")
            val records = List(items.length()) { i ->
                val item = items.getJSONObject(i)
                Record(
                    type = item.getString("type"),
                    payload = unb64(item.getString("payload")),
                    createdAt = item.getLong("createdAt"),
                    updatedAt = item.getLong("updatedAt"),
                    uid = item.optString("uid"),
                )
            }
            val deleted = root.optJSONArray("deleted")
            val tombstones = List(deleted?.length() ?: 0) { i ->
                val entry = deleted!!.getJSONObject(i)
                Tombstone(entry.getString("uid"), entry.getLong("deletedAt"))
            }
            return Backup(
                version = version,
                createdAt = root.optLong("createdAt"),
                keyMaterial = KeyMaterial(
                    salt, iterations, unb64(root.getString("wrappedKey")),
                    root.optLong("keyUpdatedAt"),
                ),
                records = records,
                tombstones = tombstones,
                seal = unb64(root.getString("seal")),
            )
        } catch (e: JSONException) {
            throw InvalidBackupException("This is not a Balrog backup file")
        } catch (e: IllegalArgumentException) {
            throw InvalidBackupException("The backup file is damaged")
        }
    }

    /**
     * Unlocks [backup] with the master password it was made under and checks
     * that it is intact. Returns the key that decrypts its records.
     */
    fun open(backup: Backup, password: CharArray): SecretKey {
        val material = backup.keyMaterial
        val kek = CryptoEngine.deriveKey(password, material.salt, material.iterations)
        val dek = try {
            val dekBytes = CryptoEngine.decrypt(kek, material.wrappedDek)
            SecretKeySpec(dekBytes, "AES").also { dekBytes.fill(0) }
        } catch (e: GeneralSecurityException) {
            throw WrongPasswordException()
        } catch (e: IllegalArgumentException) {
            throw InvalidBackupException("The backup file is damaged")
        }
        verify(backup, dek)
        return dek
    }

    /**
     * Checks [backup] against the vault key itself: that it belongs to the
     * vault [dek] opens, and that it is exactly as its author wrote it.
     */
    fun verify(backup: Backup, dek: SecretKey) {
        val sealed = try {
            CryptoEngine.decrypt(dek, backup.seal)
        } catch (e: Exception) {
            null
        }
        val expected = digest(backup.version, backup.keyMaterial, backup.records, backup.tombstones)
        if (sealed == null || !MessageDigest.isEqual(sealed, expected)) {
            throw InvalidBackupException("The backup file is damaged or has been altered")
        }
    }

    /** SHA-256 over the file's contents, in order, with unambiguous framing. */
    private fun digest(
        version: Int,
        keyMaterial: KeyMaterial,
        records: List<Record>,
        tombstones: List<Tombstone>,
    ): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        fun putInt(n: Int) = sha.update(
            byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
        )
        fun putLong(n: Long) {
            putInt((n shr 32).toInt())
            putInt(n.toInt())
        }
        fun putBytes(bytes: ByteArray) {
            putInt(bytes.size)
            sha.update(bytes)
        }
        val v2 = version >= 2
        if (v2) {
            // Version 1 sealed the records only.
            putBytes("balrog-v2".encodeToByteArray())
            putBytes(keyMaterial.salt)
            putInt(keyMaterial.iterations)
            putBytes(keyMaterial.wrappedDek)
            putLong(keyMaterial.updatedAt)
        }
        putInt(records.size)
        for (record in records) {
            if (v2) putBytes(record.uid.encodeToByteArray())
            putBytes(record.type.encodeToByteArray())
            putLong(record.createdAt)
            putLong(record.updatedAt)
            putBytes(record.payload)
        }
        if (v2) {
            putInt(tombstones.size)
            for (tombstone in tombstones) {
                putBytes(tombstone.uid.encodeToByteArray())
                putLong(tombstone.deletedAt)
            }
        }
        return sha.digest()
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)
}
