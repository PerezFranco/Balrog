package com.perezfranco.balrog.security

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Turns a deterministic-generator recipe into a QR code image.
 *
 * The text encoded is exactly what "Copy Recipe" puts on the clipboard
 * ([DeterministicGenerator.Recipe.toText]), which is what the generator's
 * "Scan QR" reads back, so a saved QR code can always be scanned in again.
 * Pure Kotlin on top of ZXing's encoder, so it is covered by plain unit tests.
 */
object RecipeQr {

    /** Width of the blank border, in modules. The QR standard asks for 4. */
    const val QUIET_ZONE_MODULES = 4

    private const val TARGET_PIXELS = 1000
    private const val MIN_PIXELS_PER_MODULE = 6

    /** The code as a matrix, one cell per module, quiet zone included. */
    fun encode(text: String): BitMatrix {
        val hints = HashMap<EncodeHintType, Any>()
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
        hints[EncodeHintType.MARGIN] = QUIET_ZONE_MODULES
        // Plain ASCII needs no declaration. Anything else is declared as UTF-8
        // so that readers don't have to guess the character set.
        if (text.any { it.code > 127 }) hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        // Width and height 0: one cell per module; scaling is done by [pixels].
        return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    }

    /** Pixels per module that make the image about 1000 px wide, but never fewer than 6. */
    fun scaleFor(matrix: BitMatrix): Int = maxOf(MIN_PIXELS_PER_MODULE, TARGET_PIXELS / matrix.width)

    /** Opaque black-on-white ARGB pixels of [matrix], each module [scale] pixels square. */
    fun pixels(matrix: BitMatrix, scale: Int): IntArray {
        val size = matrix.width * scale
        val out = IntArray(size * size)
        for (y in 0 until size) {
            val row = y / scale
            for (x in 0 until size) {
                out[y * size + x] = if (matrix.get(x / scale, row)) BLACK else WHITE
            }
        }
        return out
    }

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
