package com.abelcarballeira.photocollage

import kotlin.math.max
import kotlin.math.min

/** Medidas del collage exportado: cuadrícula de celdas cuadradas separadas por un margen blanco. */
data class CollageLayout(val columns: Int, val rows: Int, val cell: Int, val gap: Int) {
    val width: Int get() = columns * cell + (columns + 1) * gap
    val height: Int get() = max(1, rows) * cell + (max(1, rows) + 1) * gap

    /** Coordenada izquierda de la celda número [index] (empezando en 0). */
    fun left(index: Int): Int = gap + (index % columns) * (cell + gap)

    /** Coordenada superior de la celda número [index] (empezando en 0). */
    fun top(index: Int): Int = gap + (index / columns) * (cell + gap)
}

/**
 * Lógica pura del collage (sin clases de Android) para poder probarla con tests unitarios.
 */
object CollageLogic {

    /** Lado mayor máximo (px) con el que se guarda cada foto en memoria. */
    const val MAX_SIDE = 1024

    const val MIN_COLUMNS = 1
    const val MAX_COLUMNS = 6

    /** Valores neutros de los ajustes: con ellos la foto es idéntica a la original. */
    const val DEFAULT_BRIGHTNESS = 0
    const val DEFAULT_SATURATION = 100

    private const val BRIGHTNESS_FACTOR = 1.5f
    private const val MAX_CELL_SIZE = 512
    private const val MAX_COLLAGE_SIDE = 3000

    /** Matriz de color sepia (filas R, G, B, A; 4 columnas de mezcla + 1 de desplazamiento). */
    val SEPIA_MATRIX: FloatArray = floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )

    /** Siguiente giro: 90° más en sentido horario; tras 270° se vuelve a 0°. */
    fun nextRotation(current: Int): Int = (current + 90) % 360

    /** Giro llevado a 0, 90, 180 o 270 (admite negativos y vueltas completas). */
    fun normalizeTurns(rotation: Int): Int = ((rotation % 360) + 360) % 360

    /** Con 90° o 270° la foto pasa de horizontal a vertical: ancho y alto se intercambian. */
    fun swapsSides(rotation: Int): Boolean = normalizeTurns(rotation) % 180 != 0

    /** Brillo −100..100 → desplazamiento que se suma a cada canal de color (0 = sin cambio). */
    fun brightnessOffset(value: Int): Float = value.coerceIn(-100, 100) * BRIGHTNESS_FACTOR

    /** Saturación 0..200 → factor de ColorMatrix.setSaturation (100 = original). */
    fun saturationScale(value: Int): Float = value.coerceIn(0, 200) / 100f

    /** Elementos de [incoming] que no están en [existing], sin repetidos entre sí. Mantiene el orden. */
    fun <T> newItems(existing: Collection<T>, incoming: List<T>): List<T> {
        val seen = existing.toHashSet()
        return incoming.filter { seen.add(it) }
    }

    /** Mayor potencia de 2 que deja el lado mayor decodificado por encima de [target] píxeles. */
    fun sampleSize(width: Int, height: Int, target: Int): Int {
        val longest = max(width, height)
        var sample = 1
        while (longest / (sample * 2) >= target) sample *= 2
        return sample
    }

    fun rowsFor(count: Int, columns: Int): Int =
        if (count <= 0) 0 else (count + columns - 1) / columns

    /** Lado de cada celda del collage exportado: limita el tamaño total de la imagen final. */
    fun cellSize(columns: Int, rows: Int): Int =
        min(MAX_CELL_SIZE, MAX_COLLAGE_SIDE / max(1, max(columns, rows)))

    // ------------------------------------------------------------------ Matriz de color

    /**
     * Matriz de color 4×5 (20 valores, por filas: R, G, B, A) con los ajustes de una foto.
     * Orden de aplicación: saturación → filtro (gris o sepia) → brillo.
     * Con los valores neutros es la matriz identidad, es decir, la foto no cambia.
     *
     * Es la misma operación que hace android.graphics.ColorMatrix (setSaturation + postConcat),
     * pero escrita en Kotlin puro para poder comprobarla con tests unitarios sin emulador.
     */
    fun colorMatrix(filter: PhotoFilter, brightness: Int, saturation: Int): FloatArray {
        var matrix = saturationMatrix(saturationScale(saturation))
        matrix = when (filter) {
            PhotoFilter.GRAYSCALE -> concat(post = saturationMatrix(0f), pre = matrix)
            PhotoFilter.SEPIA -> concat(post = SEPIA_MATRIX, pre = matrix)
            PhotoFilter.NONE -> matrix
        }
        return concat(post = brightnessMatrix(brightnessOffset(brightness)), pre = matrix)
    }

    /** Matriz que no cambia ningún color. */
    fun identityMatrix(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )

    /** Saturación [scale]: 1 = original, 0 = escala de grises (pesos de luminosidad de ColorMatrix). */
    private fun saturationMatrix(scale: Float): FloatArray {
        val inverse = 1f - scale
        val r = 0.213f * inverse
        val g = 0.715f * inverse
        val b = 0.072f * inverse
        return floatArrayOf(
            r + scale, g, b, 0f, 0f,
            r, g + scale, b, 0f, 0f,
            r, g, b + scale, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    }

    private fun brightnessMatrix(offset: Float): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, offset,
        0f, 1f, 0f, 0f, offset,
        0f, 0f, 1f, 0f, offset,
        0f, 0f, 0f, 1f, 0f
    )

    /** Producto de matrices 4×5: se aplica primero [pre] y después [post] (como ColorMatrix.postConcat). */
    private fun concat(post: FloatArray, pre: FloatArray): FloatArray {
        val result = FloatArray(20)
        var index = 0
        for (row in 0 until 20 step 5) {
            for (column in 0 until 4) {
                result[index++] = post[row] * pre[column] + post[row + 1] * pre[column + 5] +
                    post[row + 2] * pre[column + 10] + post[row + 3] * pre[column + 15]
            }
            result[index++] = post[row] * pre[4] + post[row + 1] * pre[9] +
                post[row + 2] * pre[14] + post[row + 3] * pre[19] + post[row + 4]
        }
        return result
    }

    // ------------------------------------------------------------------ Collage exportado

    /** Número de columnas permitido (entre [MIN_COLUMNS] y [MAX_COLUMNS]). */
    fun clampColumns(columns: Int): Int = columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)

    /** Medidas de la imagen final para [photoCount] fotos repartidas en [columns] columnas. */
    fun layoutFor(photoCount: Int, columns: Int): CollageLayout {
        val cols = clampColumns(columns)
        val rows = rowsFor(photoCount, cols)
        val cell = cellSize(cols, rows)
        return CollageLayout(columns = cols, rows = rows, cell = cell, gap = max(4, cell / 40))
    }

    /** Factor con el que una imagen de [width]×[height] cabe entera en una celda cuadrada sin deformarse. */
    fun scaleToFit(width: Int, height: Int, cell: Int): Float =
        min(cell.toFloat() / width, cell.toFloat() / height)
}
