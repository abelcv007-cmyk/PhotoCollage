package com.abelcarballeira.photocollage

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/** Utilidades comunes de las pruebas instrumentadas (se ejecutan en el emulador o el móvil). */
object TestSupport {

    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context: Context get() = instrumentation.targetContext
    val application: Application get() = context.applicationContext as Application

    /** Ejecuta [block] en el hilo principal y espera a que termine. */
    fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    fun solid(width: Int, height: Int, color: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    /** Imagen con la mitad izquierda de un color y la derecha de otro (sirve para ver hacia dónde gira). */
    fun halves(width: Int, height: Int, left: Int, right: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = left
        canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), paint)
        paint.color = right
        canvas.drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), paint)
        return bitmap
    }

    /** Guarda la imagen como PNG (sin pérdida) en la caché y devuelve su URI file:// para poder "elegirla". */
    fun writePng(name: String, bitmap: Bitmap): Uri {
        val directory = File(context.cacheDir, "test-images").apply { mkdirs() }
        val file = File(directory, name)
        FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return Uri.fromFile(file)
    }

    fun photo(id: Long, original: Bitmap, display: Bitmap = original) = Photo(
        id = id, number = id.toInt(), uri = Uri.parse("test://photo/$id"), original = original, display = display
    )

    /** Espera (sin bloquear el hilo principal) hasta que se cumpla [condition]; si no, la prueba falla. */
    fun awaitUntil(message: String, timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val limit = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < limit) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail("Tiempo agotado: $message")
    }

    /** Comprueba el color del píxel (x, y) con un margen de [tolerance] niveles por canal. */
    fun assertPixel(bitmap: Bitmap, x: Int, y: Int, expected: Int, tolerance: Int = 2) {
        val actual = bitmap.getPixel(x, y)
        val where = "píxel ($x, $y) de ${bitmap.width}x${bitmap.height}"
        assertEquals("$where: rojo", Color.red(expected).toDouble(), Color.red(actual).toDouble(), tolerance.toDouble())
        assertEquals("$where: verde", Color.green(expected).toDouble(), Color.green(actual).toDouble(), tolerance.toDouble())
        assertEquals("$where: azul", Color.blue(expected).toDouble(), Color.blue(actual).toDouble(), tolerance.toDouble())
    }

    fun channelDistance(a: Int, b: Int): Int =
        maxOf(abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)))
}
