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

    // ------------------------------------------------------------------ Giro

    @Test
    fun turns_areNormalizedToAFullCircle() {
        assertEquals(0, CollageLogic.normalizeTurns(0))
        assertEquals(90, CollageLogic.normalizeTurns(450))
        assertEquals(270, CollageLogic.normalizeTurns(-90))
        assertEquals(0, CollageLogic.normalizeTurns(-360))
    }

    @Test
    fun onlyQuarterTurnsSwapWidthAndHeight() {
        assertEquals(listOf(false, true, false, true), listOf(0, 90, 180, 270).map { CollageLogic.swapsSides(it) })
        assertTrue(CollageLogic.swapsSides(-90))
        assertTrue(!CollageLogic.swapsSides(360))
    }

    // ------------------------------------------------------------------ Columnas

    @Test
    fun columns_areLimitedToTheAllowedRange() {
        assertEquals(1, CollageLogic.clampColumns(0))
        assertEquals(1, CollageLogic.clampColumns(-4))
        assertEquals(1, CollageLogic.clampColumns(1))
        assertEquals(3, CollageLogic.clampColumns(3))
        assertEquals(6, CollageLogic.clampColumns(6))
        assertEquals(6, CollageLogic.clampColumns(7))
        assertEquals(6, CollageLogic.clampColumns(Int.MAX_VALUE))
    }

    // ------------------------------------------------------------------ Generación del collage

    @Test
    fun layout_threePhotosInTwoColumns_needsTwoRows() {
        val layout = CollageLogic.layoutFor(photoCount = 3, columns = 2)
        assertEquals(2, layout.columns)
        assertEquals(2, layout.rows)
        assertEquals(512, layout.cell)
        assertEquals(12, layout.gap) // 512 / 40
        assertEquals(2 * 512 + 3 * 12, layout.width)
        assertEquals(2 * 512 + 3 * 12, layout.height)
    }

    @Test
    fun layout_placesEveryPhotoInItsOwnCell() {
        val layout = CollageLogic.layoutFor(photoCount = 4, columns = 2)
        val step = layout.cell + layout.gap
        assertEquals(layout.gap, layout.left(0))
        assertEquals(layout.gap, layout.top(0))
        assertEquals(layout.gap + step, layout.left(1))
        assertEquals(layout.gap, layout.top(1))
        assertEquals(layout.gap, layout.left(2))
        assertEquals(layout.gap + step, layout.top(2))
        assertEquals(layout.gap + step, layout.left(3))
        assertEquals(layout.gap + step, layout.top(3))
        // La última celda cabe dentro de la imagen.
        assertTrue(layout.left(3) + layout.cell <= layout.width)
        assertTrue(layout.top(3) + layout.cell <= layout.height)
    }

    @Test
    fun layout_usesTheColumnLimits() {
        assertEquals(1, CollageLogic.layoutFor(5, 0).columns)
        assertEquals(1, CollageLogic.layoutFor(5, -3).columns)
        val widest = CollageLogic.layoutFor(photoCount = 7, columns = 99)
        assertEquals(6, widest.columns)
        assertEquals(2, widest.rows)
        assertEquals(500, widest.cell) // 3000 / 6
        assertEquals(12, widest.gap)
        assertEquals(6 * 500 + 7 * 12, widest.width)
    }

    @Test
    fun layout_neverProducesAnEmptyOrHugeImage() {
        val empty = CollageLogic.layoutFor(photoCount = 0, columns = 2)
        assertEquals(0, empty.rows)
        assertTrue(empty.width > 0 && empty.height > 0)

        val tall = CollageLogic.layoutFor(photoCount = 40, columns = 1)
        assertEquals(75, tall.cell) // 3000 / 40
        assertEquals(4, tall.gap) // el margen mínimo es 4
        assertEquals(40 * 75 + 41 * 4, tall.height)
    }

    @Test
    fun scaleToFit_keepsTheProportionInsideTheCell() {
        // 200×100 en una celda de 512: el lado largo ocupa la celda (512×256), sin deformar.
        val wide = CollageLogic.scaleToFit(200, 100, 512)
        assertEquals(2.56f, wide, 0.0001f)
        assertEquals(512f, 200 * wide, 0.01f)
        assertEquals(256f, 100 * wide, 0.01f)
        // Vertical: es el alto el que manda.
        assertEquals(2.56f, CollageLogic.scaleToFit(100, 200, 512), 0.0001f)
        // Una foto mayor que la celda se reduce.
        assertTrue(CollageLogic.scaleToFit(1024, 768, 512) < 1f)
    }
}
