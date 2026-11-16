package com.perezfranco.balrog.data

import org.json.JSONObject

enum class ItemType { PASSWORD, NOTE, PEPPER }

/** A decrypted vault item, held in memory only while the vault is unlocked. */
sealed interface VaultItem {
    val id: Long
    val title: String

    /** Name of the folder this item is filed under, or empty for none. */
    val folder: String
    val createdAt: Long
    val updatedAt: Long

    fun toPayloadJson(): String

    val type: ItemType
        get() = when (this) {
            is PasswordEntry -> ItemType.PASSWORD
            is SecureNote -> ItemType.NOTE
            is Pepper -> ItemType.PEPPER
        }
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
    /**
     * If the password came from the deterministic generator: its recipe, in
     * "Full Copy" text form (see DeterministicGenerator.Recipe). Else empty.
     */
    val recipe: String = "",
    override val folder: String = "",
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
        .put("recipe", recipe)
        .put("folder", folder)
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
                recipe = obj.optString("recipe"),
                folder = obj.optString("folder"),
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
    override val folder: String = "",
    override val createdAt: Long = 0,
    override val updatedAt: Long = 0,
) : VaultItem {
    override fun toPayloadJson(): String = JSONObject()
        .put("title", title)
        .put("body", body)
        .put("folder", folder)
        .toString()

    companion object {
        fun fromPayloadJson(json: String, entity: VaultItemEntity): SecureNote {
            val obj = JSONObject(json)
            return SecureNote(
                id = entity.id,
                title = obj.optString("title"),
                body = obj.optString("body"),
                folder = obj.optString("folder"),
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
            )
        }
    }
}

/**
 * A Pepper for the deterministic generator: a named secret that is mixed
 * into the salt. Kept in the vault like any other item, so it is encrypted
 * and travels with backups. [title] is its name.
 */
data class Pepper(
    override val id: Long = 0,
    override val title: String = "",
    val value: String = "",
    override val createdAt: Long = 0,
    override val updatedAt: Long = 0,
) : VaultItem {
    override val folder: String get() = ""

    override fun toPayloadJson(): String = JSONObject()
        .put("title", title)
        .put("value", value)
        .toString()

    companion object {
        fun fromPayloadJson(json: String, entity: VaultItemEntity): Pepper {
            val obj = JSONObject(json)
            return Pepper(
                id = entity.id,
                title = obj.optString("title"),
                value = obj.optString("value"),
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
            )
        }
    }
}
