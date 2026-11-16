package com.perezfranco.balrog.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A vault record. The payload is an AES-256-GCM blob (IV || ciphertext) of the
 * record's JSON, encrypted with the in-memory vault key — the database itself
 * never sees plaintext.
 */
@Entity(tableName = "vault_items")
data class VaultItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val payload: ByteArray,
    val createdAt: Long,
    val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is VaultItemEntity && other.id == id && other.updatedAt == updatedAt

    override fun hashCode(): Int = id.hashCode()
}

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault_items ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<VaultItemEntity>>

    @Query("SELECT * FROM vault_items ORDER BY id")
    suspend fun getAll(): List<VaultItemEntity>

    @Query("SELECT * FROM vault_items WHERE id = :id")
    suspend fun getById(id: Long): VaultItemEntity?

    @Insert
    suspend fun insert(item: VaultItemEntity): Long

    @Update
    suspend fun update(item: VaultItemEntity)

    @Delete
    suspend fun delete(item: VaultItemEntity)

    @Query("DELETE FROM vault_items")
    suspend fun deleteAll()
}

@Database(entities = [VaultItemEntity::class], version = 1, exportSchema = false)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun vaultDao(): VaultDao

    companion object {
        @Volatile
        private var instance: VaultDatabase? = null

        fun get(context: Context): VaultDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VaultDatabase::class.java,
                    "balrog_vault.db"
                ).build().also { instance = it }
            }
    }
}
