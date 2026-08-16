package com.perezfranco.balrog.data

import com.perezfranco.balrog.crypto.CryptoEngine
import com.perezfranco.balrog.security.SessionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.GeneralSecurityException

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
