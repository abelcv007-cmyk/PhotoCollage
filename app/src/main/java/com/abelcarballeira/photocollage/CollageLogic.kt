package com.abelcarballeira.photocollage

import kotlin.math.max
import kotlin.math.min

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
}
