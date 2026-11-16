package com.perezfranco.balrog.data

import com.perezfranco.balrog.crypto.CryptoEngine
import com.perezfranco.balrog.crypto.VaultBackup
import com.perezfranco.balrog.security.SessionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.GeneralSecurityException
import javax.crypto.SecretKey

/**
 * Encrypts vault items on the way into Room and decrypts them on the way out,
 * using the session's in-memory vault key.
 */
/** Outcome of [VaultRepository.importRecords]. */
data class ImportSummary(val imported: Int, val skipped: Int)

class VaultRepository(private val dao: VaultDao) {

    /** All items, decrypted. Records that fail authentication are dropped. */
    fun observeItems(): Flow<List<VaultItem>> =
        dao.observeAll().map { entities -> entities.mapNotNull(::decryptOrNull) }

    suspend fun getItem(id: Long): VaultItem? =
        dao.getById(id)?.let(::decryptOrNull)

    suspend fun saveItem(item: VaultItem): Long {
        val now = System.currentTimeMillis()
        val payload = encrypt(item.toPayloadJson())
        val type = when (item) {
            is PasswordEntry -> ItemType.PASSWORD
            is SecureNote -> ItemType.NOTE
        }
        return if (item.id == 0L) {
            dao.insert(
                VaultItemEntity(
                    type = type.name, payload = payload,
                    createdAt = now, updatedAt = now,
                )
            )
        } else {
            val existing = dao.getById(item.id) ?: return 0L
            dao.update(
                existing.copy(payload = payload, updatedAt = now)
            )
            item.id
        }
    }

    suspend fun deleteItem(id: Long) {
        dao.getById(id)?.let { dao.delete(it) }
    }

    suspend fun deleteAll() = dao.deleteAll()

    // ---- Backup ----------------------------------------------------------------

    /** Every record exactly as stored (still encrypted), for a backup file. */
    suspend fun exportRecords(): List<VaultBackup.Record> =
        dao.getAll().map { VaultBackup.Record(it.type, it.payload, it.createdAt, it.updatedAt) }

    /**
     * Adds a backup's records to the vault, re-encrypting them from
     * [backupKey] to the session key. Items identical to ones already in the
     * vault are skipped, so importing the same backup twice is harmless.
     */
    suspend fun importRecords(
        records: List<VaultBackup.Record>,
        backupKey: SecretKey,
    ): ImportSummary {
        val sessionKey = SessionManager.requireKey()
        val known = ItemType.entries.map { it.name }
        val present = dao.getAll().mapNotNullTo(HashSet()) { entity ->
            try {
                entity.type to CryptoEngine.decrypt(sessionKey, entity.payload).decodeToString()
            } catch (e: GeneralSecurityException) {
                null
            }
        }
        var imported = 0
        var skipped = 0
        for (record in records) {
            if (record.type !in known) {
                skipped++
                continue
            }
            val json = CryptoEngine.decrypt(backupKey, record.payload)
            if (!present.add(record.type to json.decodeToString())) {
                skipped++
                continue
            }
            dao.insert(
                VaultItemEntity(
                    type = record.type,
                    payload = CryptoEngine.encrypt(sessionKey, json),
                    createdAt = record.createdAt,
                    updatedAt = record.updatedAt,
                )
            )
            imported++
        }
        return ImportSummary(imported, skipped)
    }

    private fun encrypt(json: String): ByteArray =
        CryptoEngine.encrypt(SessionManager.requireKey(), json.encodeToByteArray())

    private fun decryptOrNull(entity: VaultItemEntity): VaultItem? {
        val key = SessionManager.keyOrNull() ?: return null
        val json = try {
            CryptoEngine.decrypt(key, entity.payload).decodeToString()
        } catch (e: GeneralSecurityException) {
            return null
        }
        return when (entity.type) {
            ItemType.PASSWORD.name -> PasswordEntry.fromPayloadJson(json, entity)
            ItemType.NOTE.name -> SecureNote.fromPayloadJson(json, entity)
            else -> null
        }
    }
}
