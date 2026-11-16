package com.perezfranco.balrog.data

import org.json.JSONObject

enum class ItemType { PASSWORD, NOTE }

/** A decrypted vault item, held in memory only while the vault is unlocked. */
sealed interface VaultItem {
    val id: Long
    val title: String
    val createdAt: Long
    val updatedAt: Long

    fun toPayloadJson(): String
}

data class PasswordEntry(
    override val id: Long = 0,
    override val title: String = "",
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val notes: String = "",
    /** Two-factor setup as an `otpauth://totp/…` URI, or empty if none. */
    val totp: String = "",
    override val createdAt: Long = 0,
    override val updatedAt: Long = 0,
) : VaultItem {
    override fun toPayloadJson(): String = JSONObject()
        .put("title", title)
        .put("username", username)
        .put("password", password)
        .put("url", url)
        .put("notes", notes)
        .put("totp", totp)
        .toString()

    companion object {
        fun fromPayloadJson(json: String, entity: VaultItemEntity): PasswordEntry {
            val obj = JSONObject(json)
            return PasswordEntry(
                id = entity.id,
                title = obj.optString("title"),
                username = obj.optString("username"),
                password = obj.optString("password"),
                url = obj.optString("url"),
                notes = obj.optString("notes"),
                totp = obj.optString("totp"),
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
            )
        }
    }
}

data class SecureNote(
    override val id: Long = 0,
    override val title: String = "",
    val body: String = "",
    override val createdAt: Long = 0,
    override val updatedAt: Long = 0,
) : VaultItem {
    override fun toPayloadJson(): String = JSONObject()
        .put("title", title)
        .put("body", body)
        .toString()

    companion object {
        fun fromPayloadJson(json: String, entity: VaultItemEntity): SecureNote {
            val obj = JSONObject(json)
            return SecureNote(
                id = entity.id,
                title = obj.optString("title"),
                body = obj.optString("body"),
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
            )
        }
    }
}
