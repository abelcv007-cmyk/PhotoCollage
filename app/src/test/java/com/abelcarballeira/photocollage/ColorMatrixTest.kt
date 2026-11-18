package com.abelcarballeira.photocollage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Pruebas unitarias de los filtros y ajustes de color (blanco y negro, sepia, restaurar,
 * brillo, saturación). Trabajan con la matriz de color pura de [CollageLogic], sin emulador.
 * Los valores esperados se calculan aquí a mano con las fórmulas, no con el código probado.
 */
class ColorMatrixTest {

    /** Aplica una matriz 4×5 a un color RGB opaco, igual que ColorMatrixColorFilter (con recorte a 0..255). */
    private fun apply(matrix: FloatArray, r: Int, g: Int, b: Int): IntArray {
        fun channel(row: Int): Int {
            val value = matrix[row] * r + matrix[row + 1] * g + matrix[row + 2] * b +
                matrix[row + 3] * 255 + matrix[row + 4]
            return value.roundToInt().coerceIn(0, 255)
        }
        return intArrayOf(channel(0), channel(5), channel(10))
    }

    private fun luma(r: Int, g: Int, b: Int): Double = 0.213 * r + 0.715 * g + 0.072 * b

    private fun assertColor(expected: IntArray, actual: IntArray) {
        // ±1 por el redondeo de los decimales.
        for (i in 0..2) assertEquals("canal $i: esperado ${expected.toList()} y salió ${actual.toList()}", expected[i].toDouble(), actual[i].toDouble(), 1.0)
    }

    // ------------------------------------------------------------------ Blanco y negro

    @Test
    fun grayscale_makesAllChannelsEqualToTheLuminosity() {
        val matrix = CollageLogic.colorMatrix(PhotoFilter.GRAYSCALE, brightness = 0, saturation = 100)
        val out = apply(matrix, 200, 100, 50)
        val expected = luma(200, 100, 50).roundToInt() // 117,7 → 118
        assertColor(intArrayOf(expected, expected, expected), out)
        assertEquals(out[0], out[1])
        assertEquals(out[1], out[2])
    }

    @Test
    fun grayscale_keepsBlackAndWhiteAndOnlyGraysStayGray() {
        val matrix = CollageLogic.colorMatrix(PhotoFilter.GRAYSCALE, 0, 100)
        assertColor(intArrayOf(0, 0, 0), apply(matrix, 0, 0, 0))
        assertColor(intArrayOf(255, 255, 255), apply(matrix, 255, 255, 255))
        assertColor(intArrayOf(90, 90, 90), apply(matrix, 90, 90, 90))
    }

    @Test
    fun grayscale_isNotAffectedByTheSaturationSlider() {
        // La saturación conserva la luminosidad, así que el resultado en gris es el mismo.
        val normal = apply(CollageLogic.colorMatrix(PhotoFilter.GRAYSCALE, 0, 100), 200, 100, 50)
        val boosted = apply(CollageLogic.colorMatrix(PhotoFilter.GRAYSCALE, 0, 200), 200, 100, 50)
        assertColor(normal, boosted)
    }

    // ------------------------------------------------------------------ Sepia

    @Test
    fun sepia_tintsAGrayPixelBrownish() {
        val out = apply(CollageLogic.colorMatrix(PhotoFilter.SEPIA, 0, 100), 100, 100, 100)
        // 100 · (0,393+0,769+0,189) = 135,1 · 100 · (0,349+0,686+0,168) = 120,3 · 100 · (0,272+0,534+0,131) = 93,7
        assertColor(intArrayOf(135, 120, 94), out)
        assertTrue("el rojo debe ser mayor que el verde y este que el azul", out[0] > out[1] && out[1] > out[2])
    }

    @Test
    fun sepia_keepsBlackAndSaturatesWhiteTowardsWarm() {
        val matrix = CollageLogic.colorMatrix(PhotoFilter.SEPIA, 0, 100)
        assertColor(intArrayOf(0, 0, 0), apply(matrix, 0, 0, 0))
        // Blanco: 255·1,351 y 255·1,203 se recortan a 255; 255·0,937 = 238,9.
        assertColor(intArrayOf(255, 255, 239), apply(matrix, 255, 255, 255))
    }

    @Test
    fun sepia_isAppliedAfterTheSaturation() {
        // Con saturación 0 el color se pasa antes a gris y luego se aplica el sepia.
        val gray = luma(200, 100, 50)
        val expected = intArrayOf(
            (gray * (0.393 + 0.769 + 0.189)).roundToInt(),
            (gray * (0.349 + 0.686 + 0.168)).roundToInt(),
            (gray * (0.272 + 0.534 + 0.131)).roundToInt()
        )
        assertColor(expected, apply(CollageLogic.colorMatrix(PhotoFilter.SEPIA, 0, 0), 200, 100, 50))
    }

    // ------------------------------------------------------------------ Restaurar

    @Test
    fun neutralSettings_giveTheIdentityMatrix_soTheOriginalIsRestored() {
        val matrix = CollageLogic.colorMatrix(PhotoFilter.NONE, CollageLogic.DEFAULT_BRIGHTNESS, CollageLogic.DEFAULT_SATURATION)
        assertArrayEquals(CollageLogic.identityMatrix(), matrix, 0f)
        for ((r, g, b) in listOf(Triple(0, 0, 0), Triple(12, 200, 99), Triple(255, 255, 255))) {
            assertEquals(listOf(r, g, b), apply(matrix, r, g, b).toList())
        }
    }

    @Test
    fun theMatrixDependsOnlyOnTheCurrentSettings_notOnPreviousEdits() {
        // Aplicar gris, luego sepia y volver a lo neutro da exactamente la misma matriz que empezar de cero.
        CollageLogic.colorMatrix(PhotoFilter.GRAYSCALE, 80, 30)
        CollageLogic.colorMatrix(PhotoFilter.SEPIA, -50, 180)
        assertArrayEquals(CollageLogic.identityMatrix(), CollageLogic.colorMatrix(PhotoFilter.NONE, 0, 100), 0f)
        // Y el resultado de unos ajustes es siempre el mismo.
        assertArrayEquals(
            CollageLogic.colorMatrix(PhotoFilter.SEPIA, 20, 150),
            CollageLogic.colorMatrix(PhotoFilter.SEPIA, 20, 150),
            0f
        )
    }

    // ------------------------------------------------------------------ Brillo y saturación

    @Test
    fun brightness_addsTheSameOffsetToEveryChannel() {
        // +40 → desplazamiento 40 · 1,5 = 60 en cada canal.
        val up = apply(CollageLogic.colorMatrix(PhotoFilter.NONE, 40, 100), 10, 20, 30)
        assertColor(intArrayOf(70, 80, 90), up)
        val down = apply(CollageLogic.colorMatrix(PhotoFilter.NONE, -40, 100), 100, 120, 140)
        assertColor(intArrayOf(40, 60, 80), down)
    }

    @Test
    fun brightness_neverLeavesTheValidRange() {
        val bright = apply(CollageLogic.colorMatrix(PhotoFilter.NONE, 100, 100), 250, 200, 100)
        assertColor(intArrayOf(255, 255, 250), bright)
        val dark = apply(CollageLogic.colorMatrix(PhotoFilter.NONE, -100, 100), 100, 20, 200)
        assertColor(intArrayOf(0, 0, 50), dark)
    }

    @Test
    fun saturation_zeroIsGray_andTwoPushesColorsAwayFromTheGray() {
        val gray = luma(200, 100, 50)
        assertColor(
            intArrayOf(gray.roundToInt(), gray.roundToInt(), gray.roundToInt()),
            apply(CollageLogic.colorMatrix(PhotoFilter.NONE, 0, 0), 200, 100, 50)
        )
        // canal' = gris + 2 · (canal − gris), recortado a 0..255
        val boosted = apply(CollageLogic.colorMatrix(PhotoFilter.NONE, 0, 200), 200, 100, 50)
        assertColor(
            intArrayOf(
                (gray + 2 * (200 - gray)).roundToInt().coerceIn(0, 255),
                (gray + 2 * (100 - gray)).roundToInt().coerceIn(0, 255),
                (gray + 2 * (50 - gray)).roundToInt().coerceIn(0, 255)
            ),
            boosted
        )
    }

    @Test
    fun brightnessAndSaturation_combine() {
        // Saturación 0 (gris 117,7) + brillo +40 (desplazamiento 60) → 177,7 en todos los canales.
        val out = apply(CollageLogic.colorMatrix(PhotoFilter.NONE, 40, 0), 200, 100, 50)
        val expected = (luma(200, 100, 50) + 60).roundToInt()
        assertColor(intArrayOf(expected, expected, expected), out)
    }

    @Test
    fun sepiaWithBrightness_firstTintsThenShifts() {
        // Sepia de (100,100,100) = (135,1 · 120,3 · 93,7); con brillo −20 (desplazamiento −30).
        val out = apply(CollageLogic.colorMatrix(PhotoFilter.SEPIA, -20, 100), 100, 100, 100)
        assertColor(intArrayOf(105, 90, 64), out)
    }

    @Test
    fun everyCombination_keepsTheAlphaRowUntouched() {
        for (filter in PhotoFilter.values()) {
            for (brightness in listOf(-100, 0, 55, 100)) {
                for (saturation in listOf(0, 100, 200)) {
                    val matrix = CollageLogic.colorMatrix(filter, brightness, saturation)
                    assertEquals(20, matrix.size)
                    assertEquals(
                        "$filter/$brightness/$saturation",
                        listOf(0f, 0f, 0f, 1f, 0f),
                        matrix.slice(15..19)
                    )
                }
            }
        }
    }

    @Test
    fun outOfRangeSliders_areLimitedLikeTheRealRange() {
        assertArrayEquals(
            CollageLogic.colorMatrix(PhotoFilter.NONE, 100, 200),
            CollageLogic.colorMatrix(PhotoFilter.NONE, 900, 900),
            0f
        )
        assertArrayEquals(
            CollageLogic.colorMatrix(PhotoFilter.NONE, -100, 0),
            CollageLogic.colorMatrix(PhotoFilter.NONE, -900, -900),
            0f
        )
    }
}
