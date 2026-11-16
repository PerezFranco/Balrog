package com.perezfranco.balrog.importer

import com.perezfranco.balrog.data.PasswordEntry
import com.perezfranco.balrog.data.SecureNote
import com.perezfranco.balrog.data.VaultItem
import com.perezfranco.balrog.security.Totp
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant

/**
 * Reads a Bitwarden vault export — the unencrypted `.json` format (which
 * carries everything) or the `.csv` format (logins and notes only).
 *
 * Logins become password entries, including their two-factor setup, and
 * items keep their Bitwarden folder. Balrog
 * has no dedicated cards or identities, so those — like anything else it has
 * no field for (extra websites, custom fields) — are kept as
 * readable text in a secure note or in the entry's notes; nothing in the
 * export is dropped.
 */
object BitwardenImport {

    class ImportException(message: String) : Exception(message)

    /** @throws ImportException with a user-presentable message. */
    fun parse(text: String): List<VaultItem> {
        val content = text.removePrefix("﻿").trim()
        return when {
            content.startsWith("{") -> parseJson(content)
            content.lineSequence().firstOrNull().orEmpty().contains("login_password") ->
                parseCsv(content)
            else -> throw ImportException("This is not a Bitwarden export file")
        }
    }

    // ---- JSON ------------------------------------------------------------------

    private const val TYPE_LOGIN = 1
    private const val TYPE_NOTE = 2
    private val TYPE_NAMES = mapOf(3 to "card", 4 to "identity", 5 to "sshKey")

    private fun parseJson(content: String): List<VaultItem> {
        val root = try {
            JSONObject(content)
        } catch (e: JSONException) {
            throw ImportException("This is not a Bitwarden export file")
        }
        if (root.optBoolean("encrypted")) {
            throw ImportException(
                "This export is encrypted. In Bitwarden, export again and choose " +
                    "the file format \".json\" (not \".json (Encrypted)\")."
            )
        }
        val items = root.optJSONArray("items")
            ?: throw ImportException("This is not a Bitwarden export file")

        // Personal exports group by folder, organization exports by collection.
        val groupNames = HashMap<String, String>()
        for (key in listOf("folders", "collections")) {
            val groups = root.optJSONArray(key) ?: continue
            for (i in 0 until groups.length()) {
                val group = groups.optJSONObject(i) ?: continue
                groupNames[group.str("id")] = group.str("name")
            }
        }

        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val groups = buildList {
                groupNames[item.str("folderId")]?.let(::add)
                item.optJSONArray("collectionIds")?.let { ids ->
                    for (j in 0 until ids.length()) groupNames[ids.optString(j)]?.let(::add)
                }
            }.filter { it.isNotBlank() }
            val fields = item.optJSONArray("fields")?.let { array ->
                (0 until array.length()).mapNotNull { j ->
                    array.optJSONObject(j)?.let { it.str("name") to it.str("value") }
                }
            }.orEmpty()
            val created = item.str("creationDate").toEpochMillis()
            val updated = item.str("revisionDate").toEpochMillis()

            val type = item.optInt("type")
            if (type == TYPE_LOGIN) {
                val login = item.optJSONObject("login") ?: JSONObject()
                val uris = login.optJSONArray("uris")?.let { array ->
                    (0 until array.length()).mapNotNull { j ->
                        array.optJSONObject(j)?.str("uri")?.takeIf { it.isNotBlank() }
                    }
                }.orEmpty()
                login(
                    name = item.str("name"),
                    username = login.str("username"),
                    password = login.str("password"),
                    uris = uris,
                    totp = login.str("totp"),
                    notes = item.str("notes"),
                    fields = fields,
                    groups = groups,
                    createdAt = created,
                    updatedAt = updated,
                )
            } else {
                // Cards, identities and the like: spell their fields out.
                val details = TYPE_NAMES[type]?.let(item::optJSONObject)
                val lines = details?.keys()?.asSequence()?.toList().orEmpty().mapNotNull { key ->
                    val value = details!!.str(key)
                    if (value.isBlank()) null else humanize(key) to value
                }
                SecureNote(
                    title = item.str("name"),
                    body = composeNotes(lines + fields, item.str("notes"), groups.drop(1)),
                    folder = groups.firstOrNull().orEmpty(),
                    createdAt = created,
                    updatedAt = updated,
                )
            }
        }
    }

    // ---- CSV -------------------------------------------------------------------

    private fun parseCsv(content: String): List<VaultItem> {
        val rows = Csv.parse(content)
        val header = rows.first().map { it.trim().lowercase() }
        fun List<String>.col(name: String): String =
            header.indexOf(name).let { if (it in indices) this[it] else "" }

        return rows.drop(1).filter { row -> row.any { it.isNotBlank() } }.map { row ->
            val groups = listOf(row.col("folder"), row.col("collections"))
                .filter { it.isNotBlank() }
            // Custom fields arrive as "name: value" lines.
            val fields = row.col("fields").lines().filter { it.isNotBlank() }.map { line ->
                line.substringBefore(": ", "") to line.substringAfter(": ")
            }
            if (row.col("type").trim().lowercase() == "note") {
                SecureNote(
                    title = row.col("name"),
                    body = composeNotes(fields, row.col("notes"), groups.drop(1)),
                    folder = groups.firstOrNull().orEmpty(),
                )
            } else {
                login(
                    name = row.col("name"),
                    username = row.col("login_username"),
                    password = row.col("login_password"),
                    uris = row.col("login_uri").split(',').map { it.trim() }
                        .filter { it.isNotEmpty() },
                    totp = row.col("login_totp"),
                    notes = row.col("notes"),
                    fields = fields,
                    groups = groups,
                )
            }
        }
    }

    // ---- Shared ----------------------------------------------------------------

    private fun login(
        name: String,
        username: String,
        password: String,
        uris: List<String>,
        totp: String,
        notes: String,
        fields: List<Pair<String, String>>,
        groups: List<String>,
        createdAt: Long = 0,
        updatedAt: Long = 0,
    ): PasswordEntry {
        val extras = ArrayList<Pair<String, String>>()
        uris.drop(1).forEach { extras += "Website" to it }
        // Bitwarden stores either a bare key or an otpauth:// link here.
        val config = if (totp.isBlank()) null else Totp.parseOrNull(totp)
        if (totp.isNotBlank() && config == null) extras += "Two-factor key" to totp
        return PasswordEntry(
            title = name,
            username = username,
            password = password,
            url = uris.firstOrNull().orEmpty(),
            notes = composeNotes(extras + fields, notes, groups.drop(1)),
            totp = config?.toUri().orEmpty(),
            folder = groups.firstOrNull().orEmpty(),
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    /**
     * The original notes first, then whatever Balrog has no field for.
     * [groups] are folders/collections beyond the one the item is filed in.
     */
    private fun composeNotes(
        lines: List<Pair<String, String>>,
        notes: String,
        groups: List<String>,
    ): String {
        val extra = lines.filter { it.second.isNotBlank() }.map { (label, value) ->
            if (label.isBlank()) value else "$label: $value"
        } + groups.map { "Also in: $it" }
        return listOf(notes.trim(), extra.joinToString("\n"))
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
    }

    /** "cardholderName" → "Cardholder name". */
    private fun humanize(key: String): String =
        key.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
            .lowercase()
            .replaceFirstChar { it.uppercaseChar() }

    /** String value of [key], with JSON null and absent both read as "". */
    private fun JSONObject.str(key: String): String =
        if (isNull(key)) "" else optString(key)

    private fun String.toEpochMillis(): Long = try {
        if (isEmpty()) 0 else Instant.parse(this).toEpochMilli()
    } catch (e: Exception) {
        0
    }
}

/** Minimal RFC 4180 reader: quoted fields, doubled quotes, embedded newlines. */
internal object Csv {
    fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        fun endField() {
            row.add(field.toString())
            field.setLength(0)
        }
        fun endRow() {
            endField()
            rows.add(row)
            row = ArrayList()
        }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }
                quoted && c == '"' -> quoted = false
                quoted -> field.append(c)
                c == '"' && field.isEmpty() -> quoted = true
                c == ',' -> endField()
                c == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> Unit
                c == '\n' || c == '\r' -> endRow()
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}
