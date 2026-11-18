package com.abelcarballeira.photocollage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pruebas unitarias de la lógica pura del collage (no necesitan emulador). */
class CollageLogicTest {

    @Test
    fun rotation_adds90DegreesClockwiseEachTime() {
        var rotation = 0
        val sequence = List(5) { rotation = CollageLogic.nextRotation(rotation); rotation }
        assertEquals(listOf(90, 180, 270, 0, 90), sequence)
    }

    @Test
    fun brightness_zeroMeansNoChange_andIsLimitedToTheRange() {
        assertEquals(0f, CollageLogic.brightnessOffset(0), 0f)
        assertTrue(CollageLogic.brightnessOffset(50) > 0f)
        assertTrue(CollageLogic.brightnessOffset(-50) < 0f)
        assertEquals(CollageLogic.brightnessOffset(100), CollageLogic.brightnessOffset(500), 0f)
        assertEquals(CollageLogic.brightnessOffset(-100), CollageLogic.brightnessOffset(-500), 0f)
    }

    @Test
    fun saturation_defaultIsOriginal() {
        assertEquals(1f, CollageLogic.saturationScale(CollageLogic.DEFAULT_SATURATION), 0f)
        assertEquals(0f, CollageLogic.saturationScale(0), 0f)
        assertEquals(2f, CollageLogic.saturationScale(200), 0f)
        assertEquals(2f, CollageLogic.saturationScale(999), 0f)
    }

    @Test
    fun newItems_ignoresPhotosAlreadyInTheCollage() {
        val result = CollageLogic.newItems(existing = listOf("a", "b"), incoming = listOf("b", "c", "a", "d"))
        assertEquals(listOf("c", "d"), result)
    }

    @Test
    fun newItems_ignoresRepeatsInsideTheSameSelection() {
        val result = CollageLogic.newItems(existing = emptyList<String>(), incoming = listOf("x", "y", "x", "x", "z"))
        assertEquals(listOf("x", "y", "z"), result)
    }

    @Test
    fun newItems_withNothingNewReturnsEmpty() {
        assertTrue(CollageLogic.newItems(listOf(1, 2, 3), listOf(3, 2, 1)).isEmpty())
    }

    @Test
    fun sepiaMatrix_hasTheExpectedShape() {
        assertEquals(20, CollageLogic.SEPIA_MATRIX.size)
        // La fila del canal alfa no cambia la transparencia.
        assertEquals(listOf(0f, 0f, 0f, 1f, 0f), CollageLogic.SEPIA_MATRIX.slice(15..19))
    }

    @Test
    fun sampleSize_keepsTheImageAtLeastAsBigAsTheTarget() {
        assertEquals(1, CollageLogic.sampleSize(800, 600, 1024))
        assertEquals(1, CollageLogic.sampleSize(1500, 1000, 1024))
        assertEquals(2, CollageLogic.sampleSize(4000, 3000, 1024))
        assertEquals(4, CollageLogic.sampleSize(4608, 3456, 1024))
        for ((w, h) in listOf(4000 to 3000, 6000 to 4000, 12000 to 3000, 1025 to 10)) {
            val sample = CollageLogic.sampleSize(w, h, 1024)
            assertTrue("$w x $h → muestra $sample", maxOf(w, h) / sample >= 1024 || sample == 1)
        }
    }

    @Test
    fun rows_roundUp() {
        assertEquals(0, CollageLogic.rowsFor(0, 2))
        assertEquals(1, CollageLogic.rowsFor(1, 2))
        assertEquals(1, CollageLogic.rowsFor(2, 2))
        assertEquals(2, CollageLogic.rowsFor(3, 2))
        assertEquals(3, CollageLogic.rowsFor(7, 3))
    }

    @Test
    fun cellSize_limitsTheSizeOfTheFinalImage() {
        assertEquals(512, CollageLogic.cellSize(columns = 2, rows = 3))
        assertEquals(500, CollageLogic.cellSize(columns = 6, rows = 6))
        assertTrue(CollageLogic.cellSize(columns = 1, rows = 40) * 40 <= 3000)
        assertTrue(CollageLogic.cellSize(columns = 1, rows = 0) > 0)
    }
}
