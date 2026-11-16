package com.perezfranco.balrog.sync

import com.perezfranco.balrog.crypto.VaultBackup
import javax.crypto.SecretKey

/**
 * One round of syncing a device with the server: fetch the server's copy,
 * merge it with the device's, bring the device up to date, and store the
 * merged result back if the server is behind. Shared by every Balrog app.
 */
object VaultSync {

    /** The device being synced. */
    interface Device {
        /** The device's current copy of the vault. */
        fun state(): VaultMerge.State

        /**
         * Brings the device up to date with [merged]. Must not lose edits
         * made on the device since [state] was read.
         */
        fun apply(merged: VaultMerge.State)
    }

    /**
     * @property pulled changes from elsewhere were applied to this device.
     * @property pushed this device's changes were stored on the server.
     */
    class Outcome(val pulled: Boolean, val pushed: Boolean)

    /** The server holds a vault that this device's key does not open. */
    class DifferentVaultException :
        Exception("The server holds a different vault, not a copy of this one")

    private const val MAX_ATTEMPTS = 4

    /**
     * @param dek the unlocked vault key: proves the server's copy is genuine
     *        and seals what is sent back.
     * @throws SyncClient.SyncException if the server cannot be used.
     * @throws DifferentVaultException see there.
     */
    fun sync(client: SyncClient, dek: SecretKey, device: Device): Outcome {
        var pulled = false
        repeat(MAX_ATTEMPTS) {
            val remote = client.fetch()
            val remoteState = remote.bytes?.let { bytes ->
                try {
                    val file = VaultBackup.parse(bytes.decodeToString())
                    VaultBackup.verify(file, dek)
                    VaultMerge.State(file.keyMaterial, file.records, file.tombstones)
                } catch (e: VaultBackup.InvalidBackupException) {
                    throw DifferentVaultException()
                }
            }
            val local = device.state()
            val merged = if (remoteState == null) local else VaultMerge.merge(local, remoteState)
            if (!VaultMerge.sameContent(merged, local)) {
                device.apply(merged)
                pulled = true
            }
            if (remoteState != null && VaultMerge.sameContent(merged, remoteState)) {
                return Outcome(pulled, pushed = false)
            }
            val file = VaultBackup.encode(
                merged.keyMaterial, merged.records, dek, tombstones = merged.tombstones,
            )
            if (client.push(file.encodeToByteArray(), remote.revision) != null) {
                return Outcome(pulled, pushed = true)
            }
            // Another device stored something in between: go round again.
        }
        throw SyncClient.SyncException(
            SyncClient.Failure.SERVER, "The server is busy; sync will be retried"
        )
    }
}
