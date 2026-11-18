package com.abelcarballeira.photocollage

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Genera la imagen final del collage y la comparte o la guarda en la galería. */
object CollageExporter {

    private const val ALBUM = "PhotoCollage"
    private const val MIME_JPEG = "image/jpeg"
    private const val JPEG_QUALITY = 92
    private const val SHARED_DIR = "shared"

    // ------------------------------------------------------------------ Imagen final

    /** Dibuja todas las fotos (ya editadas) en una cuadrícula, respetando su proporción. */
    fun render(photos: List<Photo>, columns: Int): Bitmap {
        val cols = columns.coerceIn(CollageLogic.MIN_COLUMNS, CollageLogic.MAX_COLUMNS)
        val rows = CollageLogic.rowsFor(photos.size, cols)
        val cell = CollageLogic.cellSize(cols, rows)
        val gap = max(4, cell / 40)

        val width = cols * cell + (cols + 1) * gap
        val height = max(1, rows) * cell + (max(1, rows) + 1) * gap
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)

        val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val cellPaint = Paint().apply { color = CELL_BACKGROUND }
        photos.forEachIndexed { index, photo ->
            val left = gap + (index % cols) * (cell + gap)
            val top = gap + (index / cols) * (cell + gap)
            canvas.drawRect(left.toFloat(), top.toFloat(), (left + cell).toFloat(), (top + cell).toFloat(), cellPaint)

            // La foto se ajusta dentro de su celda sin deformarse (se mantiene la proporción).
            val source = photo.display
            val scale = min(cell.toFloat() / source.width, cell.toFloat() / source.height)
            val drawWidth = source.width * scale
            val drawHeight = source.height * scale
            val target = RectF(
                left + (cell - drawWidth) / 2f,
                top + (cell - drawHeight) / 2f,
                left + (cell + drawWidth) / 2f,
                top + (cell + drawHeight) / 2f
            )
            canvas.drawBitmap(source, null, target, photoPaint)
        }
        return result
    }

    private val CELL_BACKGROUND = Color.rgb(0xEC, 0xE8, 0xF5)

    // ------------------------------------------------------------------ Compartir

    /** Guarda el collage en la caché y devuelve un URI content:// seguro (FileProvider). */
    fun writeForSharing(context: Context, bitmap: Bitmap): Uri {
        val directory = File(context.cacheDir, SHARED_DIR)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("No se pudo crear la carpeta temporal")
        }
        // Solo se conserva el último collage compartido.
        directory.listFiles()?.forEach { it.delete() }

        val file = File(directory, fileName())
        FileOutputStream(file).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                throw IOException("No se pudo comprimir la imagen")
            }
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun shareIntent(uri: Uri, title: CharSequence): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_JPEG
            putExtra(Intent.EXTRA_STREAM, uri)
            // ClipData permite que el selector muestre una vista previa y concede el permiso de lectura.
            clipData = ClipData.newRawUri(title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title)
    }

    // ------------------------------------------------------------------ Guardar en la galería

    /**
     * Guarda el collage en Pictures/PhotoCollage mediante MediaStore.
     * Android 10 o superior: no hace falta ningún permiso.
     * Android 9 o inferior: requiere WRITE_EXTERNAL_STORAGE (lo pide la pantalla antes de llamar aquí).
     */
    @Suppress("DEPRECATION")
    fun saveToGallery(context: Context, bitmap: Bitmap): Uri {
        val resolver = context.contentResolver
        val name = fileName()
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, MIME_JPEG)
            if (modern) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                put(MediaStore.Images.Media.DATA, legacyFile(name).absolutePath)
            }
        }
        val collection = if (modern) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, values) ?: throw IOException("No se pudo crear la imagen")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("No se pudo abrir el destino")
            out.use {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) {
                    throw IOException("No se pudo comprimir la imagen")
                }
            }
            if (modern) {
                val published = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, published, null, null)
            }
            return uri
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        } catch (e: SecurityException) {
            resolver.delete(uri, null, null)
            throw IOException("Sin permiso para escribir en la galería", e)
        }
    }

    /** Solo Android 9 o inferior: ruta completa dentro de la carpeta pública de imágenes. */
    @Suppress("DEPRECATION")
    private fun legacyFile(name: String): File {
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
        if (!directory.exists() && !directory.mkdirs()) throw IOException("No se pudo crear la carpeta $directory")
        return File(directory, name)
    }

    /** Nombre único con fecha y hora: PhotoCollage_20261117_120530.jpg */
    private fun fileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "PhotoCollage_$stamp.jpg"
    }
}
