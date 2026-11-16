package com.perezfranco.balrog

import com.perezfranco.balrog.data.PasswordEntry
import com.perezfranco.balrog.data.SecureNote
import com.perezfranco.balrog.importer.BitwardenImport
import com.perezfranco.balrog.security.Totp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BitwardenImportTest {

    // Shaped like a real Bitwarden "Export vault → .json" file.
    private val json = """
    {
      "encrypted": false,
      "folders": [{"id": "f1", "name": "Work"}],
      "items": [
        {
          "id": "a", "organizationId": null, "folderId": "f1", "type": 1, "reprompt": 0,
          "name": "GitHub", "notes": "recovery codes in the safe", "favorite": true,
          "fields": [{"name": "PIN", "value": "4321", "type": 0, "linkedId": null}],
          "login": {
            "uris": [{"match": null, "uri": "https://github.com/login"},
                     {"match": null, "uri": "https://gist.github.com"}],
            "username": "octocat", "password": "hunter2\"quoted\"",
            "totp": "otpauth://totp/GitHub:octocat?secret=JBSWY3DPEHPK3PXP&issuer=GitHub"
          },
          "collectionIds": null,
          "creationDate": "2023-01-02T03:04:05.678Z", "revisionDate": "2024-05-06T07:08:09.000Z"
        },
        {
          "id": "b", "folderId": null, "type": 1, "name": "Bare key",
          "notes": null, "fields": [],
          "login": {"uris": [], "username": null, "password": "pw", "totp": "jbsw y3dp ehpk 3pxp"}
        },
        {
          "id": "c", "folderId": null, "type": 1, "name": "Steam", "notes": null,
          "login": {"username": "gabe", "password": "pw", "totp": "steam://ABCDEFGH"}
        },
        {
          "id": "d", "folderId": null, "type": 2, "name": "Wi-Fi", "notes": "password: tapioca",
          "secureNote": {"type": 0}
        },
        {
          "id": "e", "folderId": "f1", "type": 3, "name": "Visa", "notes": null,
          "card": {"cardholderName": "R P", "brand": "Visa", "number": "4111111111111111",
                   "expMonth": "12", "expYear": "2030", "code": "123"}
        },
        {
          "id": "f", "folderId": null, "type": 4, "name": "Me", "notes": "n",
          "identity": {"title": null, "firstName": "Ro", "lastName": "PF", "email": "a@b.c"}
        }
      ]
    }
    """.trimIndent()

    private val items = BitwardenImport.parse(json)

    @Test
    fun `logins keep credentials, first website and two-factor setup`() {
        val entry = items[0] as PasswordEntry
        assertEquals("GitHub", entry.title)
        assertEquals("octocat", entry.username)
        assertEquals("hunter2\"quoted\"", entry.password)
        assertEquals("https://github.com/login", entry.url)
        val totp = Totp.parse(entry.totp)
        assertEquals("JBSWY3DPEHPK3PXP", totp.secret)
        assertEquals("GitHub", totp.issuer)
        assertEquals(1672628645678, entry.createdAt)
        assertEquals(1714979289000, entry.updatedAt)
    }

    @Test
    fun `what Balrog has no field for is kept in the notes`() {
        val notes = (items[0] as PasswordEntry).notes
        assertTrue(notes.startsWith("recovery codes in the safe"))
        assertTrue("Website: https://gist.github.com" in notes)
        assertTrue("PIN: 4321" in notes)
        assertTrue("Folder: Work" in notes)
    }

    @Test
    fun `bare two-factor keys are accepted and null fields read as empty`() {
        val entry = items[1] as PasswordEntry
        assertEquals("JBSWY3DPEHPK3PXP", Totp.parse(entry.totp).secret)
        assertEquals("", entry.username)
        assertEquals("", entry.url)
        assertEquals("", entry.notes)
    }

    @Test
    fun `two-factor keys Balrog cannot use are preserved as text`() {
        val entry = items[2] as PasswordEntry
        assertEquals("", entry.totp)
        assertEquals("Two-factor key: steam://ABCDEFGH", entry.notes)
    }

    @Test
    fun `notes, cards and identities become secure notes`() {
        val note = items[3] as SecureNote
        assertEquals("Wi-Fi", note.title)
        assertEquals("password: tapioca", note.body)

        val card = items[4] as SecureNote
        assertEquals("Visa", card.title)
        for (line in listOf(
            "Cardholder name: R P", "Number: 4111111111111111", "Exp month: 12",
            "Exp year: 2030", "Code: 123", "Folder: Work",
        )) assertTrue("$line missing from ${card.body}", line in card.body)

        val identity = items[5] as SecureNote
        assertTrue(identity.body.startsWith("n\n\n"))
        assertTrue("First name: Ro" in identity.body && "Email: a@b.c" in identity.body)
        assertTrue("null" !in identity.body && "Title" !in identity.body)
        assertEquals(6, items.size)
    }

    @Test
    fun `reads the csv export including quoted multi-line cells`() {
        val csv = "folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username," +
            "login_password,login_totp\r\n" +
            "Work,1,login,\"Acme, Inc\",\"line one\nline \"\"two\"\"\",\"PIN: 99\"," +
            "0,\"https://a.example,https://b.example\",me@a.example,\"p,w\"," +
            "JBSWY3DPEHPK3PXP\r\n" +
            ",,note,Door code,1234,,0,,,,\r\n"
        val parsed = BitwardenImport.parse(csv)
        assertEquals(2, parsed.size)
        val entry = parsed[0] as PasswordEntry
        assertEquals("Acme, Inc", entry.title)
        assertEquals("me@a.example", entry.username)
        assertEquals("p,w", entry.password)
        assertEquals("https://a.example", entry.url)
        assertEquals("JBSWY3DPEHPK3PXP", Totp.parse(entry.totp).secret)
        assertEquals(
            "line one\nline \"two\"\n\nWebsite: https://b.example\nPIN: 99\nFolder: Work",
            entry.notes,
        )
        val note = parsed[1] as SecureNote
        assertEquals("Door code", note.title)
        assertEquals("1234", note.body)
    }

    @Test
    fun `encrypted exports and other files are refused with guidance`() {
        fun messageFor(text: String): String = try {
            BitwardenImport.parse(text)
            fail("Expected rejection")
            ""
        } catch (e: BitwardenImport.ImportException) {
            e.message.orEmpty()
        }
        assertTrue(
            messageFor("""{"encrypted":true,"passwordProtected":true,"data":"2.x|y|z"}""")
                .contains("encrypted")
        )
        assertTrue(messageFor("""{"format":"balrog-backup"}""").contains("not a Bitwarden"))
        assertTrue(messageFor("hello,world\n1,2").contains("not a Bitwarden"))
        assertTrue(messageFor("").contains("not a Bitwarden"))
    }
}
