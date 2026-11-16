package com.perezfranco.balrog.data

import com.perezfranco.balrog.crypto.CryptoEngine
import com.perezfranco.balrog.crypto.VaultBackup
import com.perezfranco.balrog.security.SessionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.GeneralSecurityException
import javax.crypto.SecretKey

/** Outcome of an import: items added, and items skipped as already present. */
data class ImportSummary(val imported: Int, val skipped: Int)

/**
 * Encrypts vault items on the way into Room and decrypts them on the way out,
 * using the session's in-memory vault key.
 */
class VaultRepository(private val dao: VaultDao) {

    /** All items, decrypted. Records that fail authentication are dropped. */
    fun observeItems(): Flow<List<VaultItem>> =
        dao.observeAll().map { entities -> entities.mapNotNull(::decryptOrNull) }

    suspend fun getItem(id: Long): VaultItem? =
        dao.getById(id)?.let(::decryptOrNull)

    suspend fun saveItem(item: VaultItem): Long {
        val now = System.currentTimeMillis()
        val payload = encrypt(item.toPayloadJson())
        val type = item.type
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

    /**
     * Moves every item in folder [old] to folder [new] (empty = no folder).
     * Folders exist only as names on items, so this is also how a folder is
     * renamed, merged into another, or removed. Returns the number moved.
     */
    suspend fun renameFolder(old: String, new: String): Int {
        var moved = 0
        for (entity in dao.getAll()) {
            val item = decryptOrNull(entity) ?: continue
            if (item.folder != old) continue
            val updated = when (item) {
                is PasswordEntry -> item.copy(folder = new)
                is SecureNote -> item.copy(folder = new)
                is Pepper -> item // Peppers are not filed in folders
            }
            dao.update(entity.copy(payload = encrypt(updated.toPayloadJson())))
            moved++
        }
        return moved
    }

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
        val known = ItemType.entries.map { it.name }
        val present = presentItems(SessionManager.requireKey())
        var imported = 0
        var skipped = 0
        for (record in records) {
            if (record.type !in known) {
                skipped++
                continue
            }
            val json = CryptoEngine.decrypt(backupKey, record.payload).decodeToString()
            if (insertIfNew(present, record.type, json, record.createdAt, record.updatedAt)) {
                imported++
            } else {
                skipped++
            }
        }
        return ImportSummary(imported, skipped)
    }

    /**
     * Adds items read from another password manager's export. Items identical
     * to ones already in the vault are skipped, so importing the same file
     * twice is harmless.
     */
    suspend fun importItems(items: List<VaultItem>): ImportSummary {
        val present = presentItems(SessionManager.requireKey())
        val now = System.currentTimeMillis()
        var imported = 0
        for (item in items) {
            val type = item.type
            val created = item.createdAt.takeIf { it > 0 } ?: now
            val updated = item.updatedAt.takeIf { it > 0 } ?: created
            if (insertIfNew(present, type.name, item.toPayloadJson(), created, updated)) imported++
        }
        return ImportSummary(imported, items.size - imported)
    }

    /** (type, plaintext JSON) of everything in the vault, for duplicate checks. */
    private suspend fun presentItems(key: SecretKey): MutableSet<Pair<String, String>> =
        dao.getAll().mapNotNullTo(HashSet()) { entity ->
            try {
                entity.type to CryptoEngine.decrypt(key, entity.payload).decodeToString()
            } catch (e: GeneralSecurityException) {
                null
            }
        }

    private suspend fun insertIfNew(
        present: MutableSet<Pair<String, String>>,
        type: String,
        json: String,
        createdAt: Long,
        updatedAt: Long,
    ): Boolean {
        if (!present.add(type to json)) return false
        dao.insert(
            VaultItemEntity(
                type = type,
                payload = encrypt(json),
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
        )
        return true
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
            ItemType.PEPPER.name -> Pepper.fromPayloadJson(json, entity)
            else -> null
        }
    }
}
