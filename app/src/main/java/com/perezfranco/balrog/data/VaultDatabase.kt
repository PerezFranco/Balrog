package com.perezfranco.balrog.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * A vault record. The payload is an AES-256-GCM blob (IV || ciphertext) of the
 * record's JSON, encrypted with the in-memory vault key — the database itself
 * never sees plaintext.
 */
@Entity(tableName = "vault_items", indices = [Index(value = ["uid"], unique = true)])
data class VaultItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Permanent identity of the item across devices (random; reveals nothing). */
    @ColumnInfo(defaultValue = "") val uid: String,
    val type: String,
    val payload: ByteArray,
    val createdAt: Long,
    val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is VaultItemEntity && other.id == id && other.updatedAt == updatedAt &&
            other.payload.contentEquals(payload)

    override fun hashCode(): Int = id.hashCode()
}

/** Remembers a deleted item, so that syncing spreads the deletion. */
@Entity(tableName = "tombstones")
data class TombstoneEntity(
    @PrimaryKey val uid: String,
    val deletedAt: Long,
)

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault_items ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<VaultItemEntity>>

    @Query("SELECT * FROM vault_items ORDER BY id")
    suspend fun getAll(): List<VaultItemEntity>

    @Query("SELECT * FROM vault_items WHERE uid = :uid")
    suspend fun getByUid(uid: String): VaultItemEntity?

    @Query("SELECT * FROM tombstones")
    suspend fun getTombstones(): List<TombstoneEntity>

    @Query("SELECT * FROM tombstones WHERE uid = :uid")
    suspend fun getTombstone(uid: String): TombstoneEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTombstone(tombstone: TombstoneEntity)

    @Query("DELETE FROM tombstones WHERE uid = :uid")
    suspend fun deleteTombstone(uid: String)

    @Query("DELETE FROM tombstones")
    suspend fun deleteAllTombstones()

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

/** Version 2 added what sync needs: a uid per item, and tombstones. */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE vault_items ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
        // randomblob() is evaluated per row, so every item gets its own id.
        db.execSQL("UPDATE vault_items SET uid = lower(hex(randomblob(16)))")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_vault_items_uid ON vault_items (uid)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS tombstones " +
                "(uid TEXT NOT NULL, deletedAt INTEGER NOT NULL, PRIMARY KEY(uid))"
        )
    }
}

@Database(
    entities = [VaultItemEntity::class, TombstoneEntity::class],
    version = 2,
    exportSchema = false,
)
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
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
