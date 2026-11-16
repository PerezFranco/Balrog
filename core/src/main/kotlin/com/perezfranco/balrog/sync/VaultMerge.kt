package com.perezfranco.balrog.sync

import com.perezfranco.balrog.crypto.VaultBackup.KeyMaterial
import com.perezfranco.balrog.crypto.VaultBackup.Record
import com.perezfranco.balrog.crypto.VaultBackup.Tombstone

/**
 * Combines two copies of the same vault — this device's and the server's —
 * into one. All devices share the vault key, so records are merged as
 * opaque ciphertext; nothing is decrypted.
 *
 * The rules, applied per item (identified by its uid):
 *  - the most recently edited version of an item wins;
 *  - a deletion wins over any version of the item edited before it;
 *  - an item edited after it was deleted elsewhere comes back;
 *  - the most recently set master password wins.
 *
 * The result does not depend on which side is "local", so two devices
 * merging the same pair of copies arrive at the same vault.
 */
object VaultMerge {

    class State(
        val keyMaterial: KeyMaterial,
        val records: List<Record>,
        val tombstones: List<Tombstone>,
    )

    fun merge(local: State, remote: State): State {
        val records = HashMap<String, Record>()
        for (record in local.records + remote.records) {
            val known = records[record.uid]
            if (known == null || isNewer(record, known)) records[record.uid] = record
        }
        val deletions = HashMap<String, Long>()
        for (tombstone in local.tombstones + remote.tombstones) {
            deletions[tombstone.uid] = maxOf(deletions[tombstone.uid] ?: 0, tombstone.deletedAt)
        }
        // A deletion and a surviving item never coexist.
        records.values.removeAll { record -> (deletions[record.uid] ?: -1) >= record.updatedAt }
        deletions.keys.removeAll(records.keys)

        val key = if (remote.keyMaterial.updatedAt > local.keyMaterial.updatedAt) {
            remote.keyMaterial
        } else local.keyMaterial

        return State(
            keyMaterial = key,
            records = records.values.sortedBy { it.uid },
            tombstones = deletions.map { Tombstone(it.key, it.value) }.sortedBy { it.uid },
        )
    }

    /** Whether [a] should replace [b], two versions of the same item. */
    private fun isNewer(a: Record, b: Record): Boolean {
        if (a.updatedAt != b.updatedAt) return a.updatedAt > b.updatedAt
        // Same timestamp, different content: any fixed rule will do, as long
        // as every device applies the same one.
        return compare(a.payload, b.payload) > 0
    }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    /** Whether two copies hold the same vault, regardless of ordering. */
    fun sameContent(a: State, b: State): Boolean {
        fun key(m: KeyMaterial) = listOf(
            m.salt.toList(), m.iterations, m.wrappedDek.toList(), m.updatedAt,
        )
        fun records(s: State) = s.records.associate {
            it.uid to listOf(it.type, it.createdAt, it.updatedAt, it.payload.toList())
        }
        fun tombstones(s: State) = s.tombstones.associate { it.uid to it.deletedAt }
        return key(a.keyMaterial) == key(b.keyMaterial) &&
            records(a) == records(b) && tombstones(a) == tombstones(b)
    }
}
