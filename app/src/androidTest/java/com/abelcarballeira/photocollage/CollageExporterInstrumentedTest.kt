package com.abelcarballeira.photocollage

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.abelcarballeira.photocollage.TestSupport.assertPixel
import com.abelcarballeira.photocollage.TestSupport.context
import com.abelcarballeira.photocollage.TestSupport.photo
import com.abelcarballeira.photocollage.TestSupport.solid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Pruebas con Bitmaps reales de [CollageExporter]: generación del collage (tamaño, posiciones,
 * proporciones, límites de columnas), compartir con FileProvider y guardar con MediaStore.
 */
@RunWith(AndroidJUnit4::class)
class CollageExporterInstrumentedTest {

    private val red = Color.rgb(220, 30, 30)
    private val green = Color.rgb(30, 180, 60)
    private val blue = Color.rgb(30, 30, 220)

    @Before
    fun cleanSharedFolder() {
        File(context.cacheDir, "shared").listFiles()?.forEach { it.delete() }
    }

    // ------------------------------------------------------------------ Generación del collage

    @Test
    fun render_threePhotosInTwoColumns_hasTheExpectedSizeAndPositions() {
        val photos = listOf(
            photo(1, solid(100, 100, red)),
            photo(2, solid(100, 100, green)),
            photo(3, solid(100, 100, blue))
        )
        val collage = CollageExporter.render(photos, columns = 2)

        // 2 filas · celda 512 · margen 12 → 2·512 + 3·12 = 1060 en los dos lados.
        assertEquals(1060, collage.width)
        assertEquals(1060, collage.height)
        assertPixel(collage, 0, 0, Color.WHITE, tolerance = 0) // el margen es blanco
        assertPixel(collage, 12 + 256, 12 + 256, red) // centro de la celda 1
        assertPixel(collage, 536 + 256, 12 + 256, green) // centro de la celda 2
        assertPixel(collage, 12 + 256, 536 + 256, blue) // centro de la celda 3
        assertPixel(collage, 536 + 256, 536 + 256, Color.WHITE, tolerance = 0) // la cuarta celda no existe
        collage.recycle()
    }

    @Test
    fun render_keepsEachPhotoProportion() {
        // Foto apaisada 200×100 en una celda de 512: ocupa 512×256 centrada, sin deformarse.
        val collage = CollageExporter.render(listOf(photo(1, solid(200, 100, green))), columns = 1)
        assertEquals(536, collage.width) // 512 + 2·12
        assertEquals(536, collage.height)
        assertPixel(collage, 12 + 256, 12 + 256, green) // centro: foto
        assertPixel(collage, 12 + 5, 140 + 5, green) // esquina superior izquierda de la foto (y = 12 + 128)
        assertPixel(collage, 12 + 256, 12 + 10, CollageExporter.CELL_BACKGROUND) // franja libre de arriba
        assertPixel(collage, 12 + 256, 12 + 512 - 10, CollageExporter.CELL_BACKGROUND) // franja libre de abajo
        collage.recycle()
    }

    @Test
    fun render_limitsTheNumberOfColumns() {
        val photos = List(7) { photo(it + 1L, solid(32, 32, red)) }

        val wide = CollageExporter.render(photos, columns = 99) // se limita a 6 columnas
        assertEquals(6 * 500 + 7 * 12, wide.width) // celda 500 (3000 / 6), margen 12
        assertEquals(2 * 500 + 3 * 12, wide.height) // 7 fotos en 6 columnas = 2 filas
        wide.recycle()

        val single = CollageExporter.render(photos, columns = 0) // se sube a 1 columna
        assertEquals(428 + 2 * 10, single.width) // celda 428 (3000 / 7), margen 10
        assertEquals(7 * 428 + 8 * 10, single.height)
        single.recycle()
    }

    @Test
    fun render_usesTheEditedImage_notTheOriginal() {
        val original = solid(64, 64, Color.rgb(200, 100, 50))
        val edited = ImageProcessor.render(original, PhotoFilter.GRAYSCALE, 0, 0, 100, null)
        val collage = CollageExporter.render(listOf(photo(1, original, edited)), columns = 2)
        val pixel = collage.getPixel(12 + 256, 12 + 256)
        // Gris: los tres canales valen lo mismo (la foto original era naranja).
        assertEquals(Color.red(pixel).toDouble(), Color.green(pixel).toDouble(), 2.0)
        assertEquals(Color.red(pixel).toDouble(), Color.blue(pixel).toDouble(), 2.0)
        collage.recycle()
    }

    @Test
    fun render_withoutPhotos_doesNotCrash() {
        val collage = CollageExporter.render(emptyList(), columns = 2)
        assertTrue(collage.width > 0 && collage.height > 0)
        collage.recycle()
    }

    // ------------------------------------------------------------------ Compartir (FileProvider)

    @Test
    fun writeForSharing_createsAReadableJpegBehindTheFileProvider() {
        val bitmap = solid(50, 40, red)
        val uri = CollageExporter.writeForSharing(context, bitmap)

        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.fileprovider", uri.authority)
        val decoded = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
        assertNotNull("el collage compartido no se puede leer", decoded)
        assertEquals(50, decoded.width)
        assertEquals(40, decoded.height)
        assertEquals("image/jpeg", context.contentResolver.getType(uri))
    }

    @Test
    fun writeForSharing_keepsOnlyTheLastCollage() {
        CollageExporter.writeForSharing(context, solid(20, 20, red))
        CollageExporter.writeForSharing(context, solid(30, 30, blue))
        val files = File(context.cacheDir, "shared").listFiles().orEmpty()
        assertEquals(1, files.size)
        assertTrue(files.single().length() > 0)
    }

    @Test
    fun shareIntent_sendsTheImageWithReadPermission() {
        val uri = Uri.parse("content://${context.packageName}.fileprovider/shared_collages/prueba.jpg")
        val chooser = CollageExporter.shareIntent(uri, "Compartir")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)

        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/jpeg", send.type)
        @Suppress("DEPRECATION")
        assertEquals(uri, send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, send.clipData?.getItemAt(0)?.uri)
    }

    // ------------------------------------------------------------------ Guardar (MediaStore)

    @Test
    fun saveToGallery_publishesAJpegInPicturesPhotoCollage() {
        assumeTrue("MediaStore con RELATIVE_PATH exige Android 10 o superior", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val resolver = context.contentResolver
        val uri = CollageExporter.saveToGallery(context, solid(60, 30, blue))
        try {
            val columns = arrayOf(
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.IS_PENDING
            )
            resolver.query(uri, columns, null, null, null).use { cursor ->
                assertNotNull(cursor)
                assertTrue("la imagen no aparece en MediaStore", cursor!!.moveToFirst())
                assertTrue(cursor.getString(0).startsWith("Pictures/PhotoCollage"))
                assertEquals("image/jpeg", cursor.getString(1))
                assertTrue("el archivo está vacío", cursor.getLong(2) > 0)
                assertEquals("sigue marcada como pendiente", 0, cursor.getInt(3))
            }
            val decoded: Bitmap? = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
            assertEquals(60, decoded!!.width)
            assertEquals(30, decoded.height)
        } finally {
            resolver.delete(uri, null, null) // no se deja basura en la galería del dispositivo
        }
    }
}
