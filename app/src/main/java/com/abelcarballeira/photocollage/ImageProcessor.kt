package com.abelcarballeira.photocollage

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Todo el tratamiento de imágenes está hecho en Kotlin con Canvas y ColorMatrix:
 * lectura reducida de la foto, filtros, giro, brillo, saturación y texto.
 */
object ImageProcessor {

    private const val TAG = "ImageProcessor"

    // ------------------------------------------------------------------ Lectura

    /**
     * Lee una foto reducida (lado mayor ≤ [maxSide]) y con la orientación EXIF corregida.
     * Devuelve null si el archivo no se puede leer o no es una imagen.
     * Se llama desde un hilo secundario.
     */
    fun decode(resolver: ContentResolver, uri: Uri, maxSide: Int = CollageLogic.MAX_SIDE): Bitmap? {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsStream = resolver.openInputStream(uri) ?: return null
            boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = CollageLogic.sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            }
            val stream = resolver.openInputStream(uri) ?: return null
            val decoded = stream.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            return applyOrientation(scaleDown(decoded, maxSide), readOrientation(resolver, uri))
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo leer la imagen $uri", e)
            return null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Sin memoria al leer la imagen $uri", e)
            return null
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longest
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun readOrientation(resolver: ContentResolver, uri: Uri): Int =
        try {
            val stream = resolver.openInputStream(uri)
            if (stream == null) {
                ExifInterface.ORIENTATION_NORMAL
            } else {
                stream.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }
            }
        } catch (e: Exception) {
            // Sin datos EXIF legibles (por ejemplo en un PNG): se usa la imagen tal cual.
            ExifInterface.ORIENTATION_NORMAL
        }

    /** Las fotos de la cámara suelen guardarse "tumbadas" con una etiqueta EXIF que indica el giro. */
    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (oriented !== bitmap) bitmap.recycle()
        return oriented
    }

    // ------------------------------------------------------------------ Edición

    /** Igual que [render], con todos los ajustes agrupados. */
    fun render(original: Bitmap, edit: EditSettings): Bitmap =
        render(original, edit.filter, edit.rotation, edit.brightness, edit.saturation, edit.text)

    /**
     * Devuelve una imagen NUEVA con los ajustes aplicados sobre [original], que no se toca.
     * Si no hay ningún ajuste se devuelve la propia [original] (la foto "restaurada").
     *
     * Es una operación pesada (reserva y dibuja un Bitmap completo): hay que llamarla desde un
     * hilo secundario. El resultado solo depende de [original] y de los ajustes recibidos,
     * nunca de ediciones anteriores, así que editar muchas veces no degrada la foto.
     */
    fun render(
        original: Bitmap,
        filter: PhotoFilter,
        rotation: Int,
        brightness: Int,
        saturation: Int,
        text: PhotoText?
    ): Bitmap {
        val turns = CollageLogic.normalizeTurns(rotation)
        val overlay = text?.takeIf { it.text.isNotBlank() }
        val untouched = filter == PhotoFilter.NONE && turns == 0 &&
            brightness == CollageLogic.DEFAULT_BRIGHTNESS &&
            saturation == CollageLogic.DEFAULT_SATURATION && overlay == null
        if (untouched) return original

        val width = original.width
        val height = original.height
        val sideways = CollageLogic.swapsSides(turns)
        val outWidth = if (sideways) height else width
        val outHeight = if (sideways) width else height

        val result = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // Giro en sentido horario alrededor del centro y recolocación en el lienzo nuevo.
        val matrix = Matrix().apply {
            postRotate(turns.toFloat(), width / 2f, height / 2f)
            postTranslate((outWidth - width) / 2f, (outHeight - height) / 2f)
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(CollageLogic.colorMatrix(filter, brightness, saturation))
            )
        }
        canvas.drawBitmap(original, matrix, paint)

        if (overlay != null) drawText(canvas, overlay, outWidth, outHeight)
        return result
    }

    /** Texto blanco con contorno negro (legible sobre cualquier foto), centrado horizontalmente. */
    private fun drawText(canvas: Canvas, overlay: PhotoText, width: Int, height: Int) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        var size = min(width, height) * overlay.sizePercent / 100f
        fill.textSize = size
        // Si el texto es más ancho que la foto se reduce para que quepa entero.
        val maxWidth = width * 0.92f
        val measured = fill.measureText(overlay.text)
        if (measured > maxWidth) {
            size *= maxWidth / measured
            fill.textSize = size
        }
        val outline = Paint(fill).apply {
            style = Paint.Style.STROKE
            color = Color.BLACK
            strokeWidth = size * 0.14f
            strokeJoin = Paint.Join.ROUND
        }

        val metrics = fill.fontMetrics
        val margin = size * 0.6f
        val x = width / 2f
        val baseline = when (overlay.position) {
            TextPosition.TOP -> margin - metrics.ascent
            TextPosition.CENTER -> height / 2f - (metrics.ascent + metrics.descent) / 2f
            TextPosition.BOTTOM -> height - margin - metrics.descent
        }
        canvas.drawText(overlay.text, x, baseline, outline)
        canvas.drawText(overlay.text, x, baseline, fill)
    }
}
