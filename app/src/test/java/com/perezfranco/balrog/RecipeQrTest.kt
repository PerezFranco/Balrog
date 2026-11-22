package com.perezfranco.balrog

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.perezfranco.balrog.security.DeterministicGenerator.Recipe
import com.perezfranco.balrog.security.DeterministicGenerator.Type
import com.perezfranco.balrog.security.RecipeQr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A saved recipe QR code has to be readable by the generator's "Scan QR",
 * which reads with ZXing and then parses the text with [Recipe.parse]. These
 * tests do exactly that to codes drawn by [RecipeQr].
 */
class RecipeQrTest {

    private fun scan(text: String): String {
        val matrix = RecipeQr.encode(text)
        val scale = RecipeQr.scaleFor(matrix)
        val size = matrix.width * scale
        val source = RGBLuminanceSource(size, size, RecipeQr.pixels(matrix, scale))
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    private val recipes = listOf(
        Recipe("Roberto", "Joaquin", "Perez-Franco", 10, Type.SPECIAL, null, "m...G"),
        Recipe("site", "", "s", 12, Type.ALPHANUMERIC, "s...r", "x...y"),
        Recipe("Pérez 日本 😀", "Ünï", "sälz", 12, Type.SPECIAL, "Ü...p", "g..._"),
        // The form trims its fields, so real recipes never start or end with a space.
        Recipe("long base with spaces and punctuation, ok. ".repeat(3).trim(), "case ".repeat(5).trim(),
            "salt-".repeat(8), 94, Type.SPECIAL, "a...L", "\\...~"),
    )

    @Test
    fun `a recipe scans back exactly as copied, and the app parses it`() {
        for (recipe in recipes) {
            val text = recipe.toText()
            val scanned = scan(text)
            assertEquals(text, scanned)
            assertEquals(recipe, Recipe.parse(scanned))
        }
    }

    @Test
    fun `the image is black on white with a quiet zone and a usable size`() {
        val matrix = RecipeQr.encode(recipes[0].toText())
        val scale = RecipeQr.scaleFor(matrix)
        val size = matrix.width * scale
        assertTrue("image too small: $size", size >= 600)
        assertTrue(scale >= 6)
        val pixels = RecipeQr.pixels(matrix, scale)
        assertEquals(size * size, pixels.size)
        // The four corners lie in the blank border.
        for (corner in listOf(0, size - 1, size * (size - 1), size * size - 1)) {
            assertEquals(0xFFFFFFFF.toInt(), pixels[corner])
        }
        assertTrue(pixels.any { it == 0xFF000000.toInt() })
        assertTrue(pixels.all { it == 0xFF000000.toInt() || it == 0xFFFFFFFF.toInt() })
        // The first dark module sits exactly after the 4-module border.
        val firstDark = (0 until size).first { pixels[(RecipeQr.QUIET_ZONE_MODULES * scale) * size + it] == 0xFF000000.toInt() }
        assertEquals(RecipeQr.QUIET_ZONE_MODULES * scale, firstDark)
    }
}
