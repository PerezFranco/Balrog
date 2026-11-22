package com.perezfranco.balrog.sync

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the sync script on the user's own website (server/balrog-sync.php).
 *
 * The server is a dumb, untrusted store for one encrypted vault file. It
 * never sees a key or a password; all it does is hand the file back and
 * refuse to let one device overwrite changes it has not seen:
 *
 *  - `GET`  returns the file and its revision (a hash of its contents);
 *  - `POST` replaces the file, but only if the caller names the revision it
 *    started from — otherwise 409, and the caller merges and tries again.
 */
class SyncClient(private val url: String, private val token: String) {

    enum class Failure { BAD_ADDRESS, UNAUTHORIZED, UNREACHABLE, SERVER }

    class SyncException(val failure: Failure, message: String) : Exception(message)

    /** @property bytes the vault file, or null if the server has none yet. */
    class Remote(val bytes: ByteArray?, val revision: String)

    fun fetch(): Remote {
        val connection = open("GET")
        try {
            return when (val code = connection.responseCode) {
                200 -> Remote(
                    connection.inputStream.use { it.readBytes() },
                    connection.getHeaderField(REVISION_HEADER).orEmpty(),
                )
                204, 404 -> Remote(null, "")
                else -> throw failureFor(code)
            }
        } catch (e: IOException) {
            throw unreachable(e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Stores [bytes], provided the server still holds revision [baseRevision]
     * ("" if it held nothing). Returns the new revision, or null if another
     * device got there first.
     */
    fun push(bytes: ByteArray, baseRevision: String): String? {
        val connection = open("POST")
        try {
            connection.doOutput = true
            connection.setRequestProperty(BASE_HEADER, baseRevision)
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            return when (val code = connection.responseCode) {
                200 -> connection.getHeaderField(REVISION_HEADER).orEmpty()
                409 -> null
                else -> throw failureFor(code)
            }
        } catch (e: IOException) {
            throw unreachable(e)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(method: String): HttpURLConnection {
        val parsed = try {
            URL(url.trim())
        } catch (e: Exception) {
            throw SyncException(Failure.BAD_ADDRESS, "That is not a valid web address")
        }
        // The token travels in a header, so never over plain http (except to
        // this machine, for testing).
        val local = parsed.host == "localhost" || parsed.host == "127.0.0.1"
        if (parsed.protocol != "https" && !(parsed.protocol == "http" && local)) {
            throw SyncException(Failure.BAD_ADDRESS, "The address must start with https://")
        }
        val connection = try {
            parsed.openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw unreachable(e)
        }
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.useCaches = false
        connection.instanceFollowRedirects = false
        // A custom header: shared hosts often strip "Authorization".
        connection.setRequestProperty(TOKEN_HEADER, token)
        return connection
    }

    private fun failureFor(code: Int) = when (code) {
        401, 403 -> SyncException(Failure.UNAUTHORIZED, "The server rejected the sync token")
        in 300..399 -> SyncException(
            Failure.BAD_ADDRESS, "The address redirects elsewhere; use the script's exact address"
        )
        503 -> SyncException(Failure.SERVER, "The sync script has no token yet (create balrog-config.php next to it)")
        else -> SyncException(Failure.SERVER, "The server answered with error $code")
    }

    private fun unreachable(e: IOException) =
        SyncException(Failure.UNREACHABLE, "Could not reach the server")

    private companion object {
        const val TOKEN_HEADER = "X-Balrog-Token"
        const val BASE_HEADER = "X-Balrog-Base"
        const val REVISION_HEADER = "X-Balrog-Revision"
    }
}
