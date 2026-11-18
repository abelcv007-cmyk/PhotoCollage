package com.abelcarballeira.photocollage

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.abelcarballeira.photocollage.TestSupport.assertPixel
import com.abelcarballeira.photocollage.TestSupport.channelDistance
import com.abelcarballeira.photocollage.TestSupport.context
import com.abelcarballeira.photocollage.TestSupport.halves
import com.abelcarballeira.photocollage.TestSupport.solid
import com.abelcarballeira.photocollage.TestSupport.writePng
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToInt

/**
 * Pruebas con Bitmaps reales de [ImageProcessor]: filtros, restaurar, giro, brillo y saturación
 * combinados, texto y lectura de fotos. Necesitan Android (emulador o móvil).
 */
@RunWith(AndroidJUnit4::class)
class ImageProcessorInstrumentedTest {

    private val orange = Color.rgb(200, 100, 50)

    private fun render(
        original: Bitmap,
        filter: PhotoFilter = PhotoFilter.NONE,
        rotation: Int = 0,
        brightness: Int = 0,
        saturation: Int = 100,
        text: PhotoText? = null
    ) = ImageProcessor.render(original, filter, rotation, brightness, saturation, text)

    /** Los mismos pasos que daba la primera versión de la app con android.graphics.ColorMatrix. */
    private fun androidMatrix(filter: PhotoFilter, brightness: Int, saturation: Int): FloatArray {
        val matrix = ColorMatrix().apply { setSaturation(saturation / 100f) }
        when (filter) {
            PhotoFilter.GRAYSCALE -> matrix.postConcat(ColorMatrix().apply { setSaturation(0f) })
            PhotoFilter.SEPIA -> matrix.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        0.393f, 0.769f, 0.189f, 0f, 0f,
                        0.349f, 0.686f, 0.168f, 0f, 0f,
                        0.272f, 0.534f, 0.131f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
            PhotoFilter.NONE -> Unit
        }
        val offset = brightness * 1.5f
        matrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, offset,
                    0f, 1f, 0f, 0f, offset,
                    0f, 0f, 1f, 0f, offset,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        return matrix.array
    }

    private fun applyMatrix(m: FloatArray, color: Int): Int {
        fun channel(row: Int): Int {
            val v = m[row] * Color.red(color) + m[row + 1] * Color.green(color) +
                m[row + 2] * Color.blue(color) + m[row + 4]
            return v.roundToInt().coerceIn(0, 255)
        }
        return Color.rgb(channel(0), channel(5), channel(10))
    }

    // ------------------------------------------------------------------ Filtros

    @Test
    fun grayscale_turnsEveryPixelGray() {
        val original = solid(32, 32, orange)
        val result = render(original, filter = PhotoFilter.GRAYSCALE)
        assertNotSame(original, result)
        val gray = (0.213 * 200 + 0.715 * 100 + 0.072 * 50).roundToInt() // 118
        for ((x, y) in listOf(0 to 0, 16 to 16, 31 to 31)) {
            assertPixel(result, x, y, Color.rgb(gray, gray, gray))
        }
    }

    @Test
    fun sepia_tintsGrayPixelsBrownish() {
        val result = render(solid(32, 32, Color.rgb(100, 100, 100)), filter = PhotoFilter.SEPIA)
        assertPixel(result, 16, 16, Color.rgb(135, 120, 94))
        val pixel = result.getPixel(16, 16)
        assertTrue(Color.red(pixel) > Color.green(pixel) && Color.green(pixel) > Color.blue(pixel))
    }

    // ------------------------------------------------------------------ Restaurar y no degradar

    @Test
    fun withoutAdjustments_theOriginalItselfIsReturned() {
        val original = solid(32, 16, orange)
        assertSame(original, render(original))
        assertSame(original, ImageProcessor.render(original, EditSettings()))
        assertSame(original, render(original, rotation = 360)) // una vuelta completa = sin giro
        assertSame(original, render(original, text = PhotoText("   "))) // un texto en blanco no cuenta
    }

    @Test
    fun editing_neverModifiesTheOriginal() {
        val original = halves(40, 20, Color.RED, Color.BLUE)
        val copy = original.copy(Bitmap.Config.ARGB_8888, false)
        render(original, PhotoFilter.SEPIA, 90, 40, 150, PhotoText("Hola", 10, TextPosition.CENTER))
        render(original, PhotoFilter.GRAYSCALE, 270, -40, 0, null)
        assertTrue("la original cambió", original.sameAs(copy))
    }

    @Test
    fun repeatedEditing_doesNotAccumulateChanges() {
        val original = halves(40, 20, orange, Color.rgb(50, 100, 200))
        val first = render(original, PhotoFilter.SEPIA, 90, 30, 120)
        // Se hacen otras ediciones distintas y se vuelve a los mismos ajustes: sale exactamente lo mismo.
        render(original, PhotoFilter.GRAYSCALE, 180, -60, 10)
        render(original, PhotoFilter.NONE, 270, 80, 200)
        val again = render(original, PhotoFilter.SEPIA, 90, 30, 120)
        assertTrue("la misma edición dio un resultado distinto", first.sameAs(again))
        // Y restaurar devuelve la propia imagen original, sin copias.
        assertSame(original, render(original))
    }

    // ------------------------------------------------------------------ Giro

    @Test
    fun rotation_swapsSidesAndTurnsClockwise() {
        val red = Color.rgb(220, 30, 30)
        val blue = Color.rgb(30, 30, 220)
        val original = halves(40, 20, red, blue) // izquierda roja, derecha azul

        val quarter = render(original, rotation = 90)
        assertEquals(20, quarter.width)
        assertEquals(40, quarter.height)
        assertPixel(quarter, 10, 5, red) // la izquierda pasa arriba
        assertPixel(quarter, 10, 35, blue)

        val half = render(original, rotation = 180)
        assertEquals(40, half.width)
        assertEquals(20, half.height)
        assertPixel(half, 5, 10, blue) // izquierda y derecha se intercambian
        assertPixel(half, 35, 10, red)

        val threeQuarters = render(original, rotation = 270)
        assertEquals(20, threeQuarters.width)
        assertEquals(40, threeQuarters.height)
        assertPixel(threeQuarters, 10, 35, red) // la izquierda pasa abajo
        assertPixel(threeQuarters, 10, 5, blue)
    }

    // ------------------------------------------------------------------ Combinaciones

    @Test
    fun brightnessSaturationAndRotation_combineCorrectly() {
        val left = orange
        val right = Color.rgb(50, 100, 200)
        val original = halves(40, 20, left, right)

        val result = render(original, PhotoFilter.NONE, rotation = 90, brightness = 20, saturation = 150)

        assertEquals(20, result.width)
        assertEquals(40, result.height)
        val matrix = androidMatrix(PhotoFilter.NONE, 20, 150)
        assertPixel(result, 10, 5, applyMatrix(matrix, left)) // la mitad izquierda quedó arriba
        assertPixel(result, 10, 35, applyMatrix(matrix, right))
    }

    @Test
    fun filterWithBrightnessAndSaturation_matchesTheExpectedColor() {
        val original = solid(24, 24, orange)
        for (filter in PhotoFilter.values()) {
            val result = render(original, filter, brightness = -25, saturation = 60)
            val expected = applyMatrix(androidMatrix(filter, -25, 60), orange)
            assertPixel(result, 12, 12, expected)
        }
    }

    @Test
    fun pureColorMatrix_isTheSameAsAndroidColorMatrix() {
        for (filter in PhotoFilter.values()) {
            for (brightness in listOf(-100, -30, 0, 45, 100)) {
                for (saturation in listOf(0, 50, 100, 150, 200)) {
                    assertArrayEquals(
                        "$filter brillo=$brightness saturación=$saturation",
                        androidMatrix(filter, brightness, saturation),
                        CollageLogic.colorMatrix(filter, brightness, saturation),
                        1e-4f
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ Texto

    @Test
    fun text_isDrawnWhereItWasAsked_andKeepsTheSize() {
        val base = Color.rgb(120, 120, 120)
        val original = solid(100, 100, base)
        val result = render(original, text = PhotoText("Hola", sizePercent = 20, position = TextPosition.BOTTOM))
        assertEquals(100, result.width)
        assertEquals(100, result.height)
        var changedTop = 0
        var changedBottom = 0
        for (y in 0 until 100) {
            for (x in 0 until 100) {
                if (channelDistance(result.getPixel(x, y), base) > 8) {
                    if (y < 50) changedTop++ else changedBottom++
                }
            }
        }
        assertEquals("el texto debe ir abajo, no arriba", 0, changedTop)
        assertTrue("no se dibujó ningún texto", changedBottom > 20)
    }

    // ------------------------------------------------------------------ Lectura de fotos

    @Test
    fun decode_reducesBigPhotosToTheLimit() {
        val uri = writePng("grande.png", solid(2048, 1024, orange))
        val decoded = ImageProcessor.decode(context.contentResolver, uri)!!
        assertTrue("lado mayor ${maxOf(decoded.width, decoded.height)}", maxOf(decoded.width, decoded.height) <= CollageLogic.MAX_SIDE)
        assertEquals(1024, decoded.width)
        assertEquals(512, decoded.height)
        assertPixel(decoded, 10, 10, orange)
    }

    @Test
    fun decode_appliesTheExifOrientation() {
        val file = File(File(context.cacheDir, "test-images").apply { mkdirs() }, "tumbada.jpg")
        file.outputStream().use { check(halves(40, 20, Color.RED, Color.BLUE).compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val decoded = ImageProcessor.decode(context.contentResolver, android.net.Uri.fromFile(file))!!
        assertEquals(20, decoded.width) // la foto "tumbada" se endereza
        assertEquals(40, decoded.height)
    }

    @Test
    fun decode_returnsNullInsteadOfCrashing_withBadFiles() {
        val directory = File(context.cacheDir, "test-images").apply { mkdirs() }
        val notAnImage = File(directory, "texto.png").apply { writeText("esto no es una imagen") }
        assertNull(ImageProcessor.decode(context.contentResolver, android.net.Uri.fromFile(notAnImage)))
        val missing = File(directory, "no-existe.png")
        assertNull(ImageProcessor.decode(context.contentResolver, android.net.Uri.fromFile(missing)))
    }
}
