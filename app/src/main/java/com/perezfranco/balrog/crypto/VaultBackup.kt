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
 * The backup file format: a self-contained, encrypted copy of the vault.
 *
 * A backup holds exactly what is on disk — the wrapped vault key with its
 * KDF parameters, and every record still encrypted — so nothing is ever
 * decrypted to produce one, and it is as hard to break as the vault itself:
 * it opens only with the master password in use when it was made.
 *
 * Each record is authenticated on its own by AES-GCM. The [Backup.seal]
 * additionally binds the set of records together (a digest of all of them,
 * encrypted under the vault key) so that records cannot be dropped,
 * reordered or relabelled without detection.
 */
object VaultBackup {

    const val FILE_EXTENSION = "balrog"
    private const val FORMAT = "balrog-backup"
    private const val VERSION = 1
    private const val MAX_ITERATIONS = 10_000_000

    class KeyMaterial(val salt: ByteArray, val iterations: Int, val wrappedDek: ByteArray)

    class Record(
        val type: String,
        val payload: ByteArray,
        val createdAt: Long,
        val updatedAt: Long,
    )

    class Backup(
        val createdAt: Long,
        val keyMaterial: KeyMaterial,
        val records: List<Record>,
        val seal: ByteArray,
    )

    /** The file is not a Balrog backup, or has been damaged or altered. */
    class InvalidBackupException(message: String) : Exception(message)

    /** The password does not open this backup. */
    class WrongPasswordException : Exception("Wrong password for this backup")

    /** Serializes a backup. [dek] is the unlocked vault key, used for the seal. */
    fun encode(
        keyMaterial: KeyMaterial,
        records: List<Record>,
        dek: SecretKey,
        createdAt: Long = System.currentTimeMillis(),
    ): String {
        val items = JSONArray()
        for (record in records) {
            items.put(
                JSONObject()
                    .put("type", record.type)
                    .put("payload", b64(record.payload))
                    .put("createdAt", record.createdAt)
                    .put("updatedAt", record.updatedAt)
            )
        }
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
            .put("seal", b64(CryptoEngine.encrypt(dek, digest(records))))
            .put("items", items)
            .toString(2)
    }

    /** Reads a backup file's structure. Nothing is decrypted yet. */
    fun parse(text: String): Backup {
        try {
            val root = JSONObject(text)
            if (root.optString("format") != FORMAT) {
                throw InvalidBackupException("This is not a Balrog backup file")
            }
            if (root.getInt("version") > VERSION) {
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
                )
            }
            return Backup(
                createdAt = root.optLong("createdAt"),
                keyMaterial = KeyMaterial(salt, iterations, unb64(root.getString("wrappedKey"))),
                records = records,
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
     * that its records are intact. Returns the key that decrypts them.
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
        val sealed = try {
            CryptoEngine.decrypt(dek, backup.seal)
        } catch (e: Exception) {
            null
        }
        if (sealed == null || !MessageDigest.isEqual(sealed, digest(backup.records))) {
            throw InvalidBackupException("The backup file is damaged or has been altered")
        }
        return dek
    }

    /** SHA-256 over every record, in order, with unambiguous framing. */
    private fun digest(records: List<Record>): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        fun putInt(n: Int) = sha.update(
            byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
        )
        fun putLong(n: Long) {
            putInt((n shr 32).toInt())
            putInt(n.toInt())
        }
        putInt(records.size)
        for (record in records) {
            val type = record.type.encodeToByteArray()
            putInt(type.size)
            sha.update(type)
            putLong(record.createdAt)
            putLong(record.updatedAt)
            putInt(record.payload.size)
            sha.update(record.payload)
        }
        return sha.digest()
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)
}
