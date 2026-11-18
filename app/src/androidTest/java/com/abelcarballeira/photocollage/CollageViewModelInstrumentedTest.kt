package com.abelcarballeira.photocollage

import android.content.res.Configuration
import android.app.UiAutomation
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.abelcarballeira.photocollage.TestSupport.application
import com.abelcarballeira.photocollage.TestSupport.assertPixel
import com.abelcarballeira.photocollage.TestSupport.awaitUntil
import com.abelcarballeira.photocollage.TestSupport.context
import com.abelcarballeira.photocollage.TestSupport.instrumentation
import com.abelcarballeira.photocollage.TestSupport.onMain
import com.abelcarballeira.photocollage.TestSupport.solid
import com.abelcarballeira.photocollage.TestSupport.writePng
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Pruebas del [CollageViewModel] con fotos reales: edición en segundo plano (la última edición
 * gana), restaurar, límites de columnas, collage vacío, exportación y supervivencia a giros.
 */
@RunWith(AndroidJUnit4::class)
class CollageViewModelInstrumentedTest {

    private lateinit var vm: CollageViewModel
    private val orange = Color.rgb(200, 100, 50)

    @Before
    fun setUp() {
        File(context.cacheDir, "shared").listFiles()?.forEach { it.delete() }
        onMain { vm = CollageViewModel(application) }
    }

    // ------------------------------------------------------------------ Ayudas

    private fun photo(id: Long): Photo = vm.state.value.photos.first { it.id == id }

    /** Añade una foto como si se hubiera elegido en el selector y espera a que termine de cargarse. */
    private fun addPhoto(name: String, bitmap: android.graphics.Bitmap): Photo {
        val uri = writePng(name, bitmap)
        onMain { vm.addPhotos(listOf(uri)) }
        awaitUntil("la foto $name no terminó de cargarse") {
            vm.state.value.photos.any { it.uri == uri } && !vm.state.value.loading
        }
        drainEvents()
        return vm.state.value.photos.first { it.uri == uri }
    }

    private fun select(photo: Photo) = onMain { vm.toggleSelection(photo.id) }

    private fun awaitRendered(id: Long) =
        awaitUntil("la foto $id no terminó de editarse") { !photo(id).needsRender }

    private fun nextEvent(timeoutMs: Long = 8_000): UiEvent =
        runBlocking { withTimeout(timeoutMs) { vm.uiEvents.first() } }

    private fun noEventWithin(timeoutMs: Long): UiEvent? =
        runBlocking { withTimeoutOrNull(timeoutMs) { vm.uiEvents.first() } }

    private fun drainEvents() {
        while (noEventWithin(100) != null) Unit
    }

    // ------------------------------------------------------------------ Añadir fotos y columnas

    @Test
    fun addPhotos_numbersThemAndIgnoresDuplicates() {
        val a = writePng("a.png", solid(64, 32, orange))
        val b = writePng("b.png", solid(32, 64, orange))
        onMain { vm.addPhotos(listOf(a, b, a)) }
        awaitUntil("no se cargaron las fotos") { vm.state.value.photos.size == 2 && !vm.state.value.loading }
        assertEquals(listOf(1, 2), vm.state.value.photos.map { it.number })

        drainEvents()
        onMain { vm.addPhotos(listOf(a)) } // ya estaba: no se vuelve a añadir
        assertTrue(nextEvent() is UiEvent.Message)
        assertEquals(2, vm.state.value.photos.size)
    }

    @Test
    fun addPhotos_withUnreadableFiles_reportsItAndKeepsWorking() {
        val directory = File(context.cacheDir, "test-images").apply { mkdirs() }
        val broken = File(directory, "roto.png").apply { writeText("no es una imagen") }
        val missing = File(directory, "no-existe.png")
        onMain { vm.addPhotos(listOf(android.net.Uri.fromFile(broken), android.net.Uri.fromFile(missing))) }
        assertTrue("debería avisar del error", nextEvent() is UiEvent.Message)
        assertTrue(vm.state.value.photos.isEmpty())
        assertFalse("el indicador de progreso se quedó encendido", vm.state.value.loading)

        val ok = addPhoto("valida.png", solid(16, 16, orange)) // y después se puede seguir añadiendo
        assertEquals(1, ok.number)
    }

    @Test
    fun columns_areLimitedBetweenOneAndSix() {
        assertEquals(2, vm.state.value.columns)
        for ((asked, expected) in listOf(0 to 1, -5 to 1, 1 to 1, 3 to 3, 6 to 6, 9 to 6)) {
            onMain { vm.setColumns(asked) }
            assertEquals("columnas pedidas: $asked", expected, vm.state.value.columns)
        }
    }

    // ------------------------------------------------------------------ Edición en segundo plano

    @Test
    fun filter_thenRestore_givesBackTheOriginalImage() {
        val added = addPhoto("filtro.png", solid(64, 32, orange))
        select(added)

        onMain { vm.applyFilter(PhotoFilter.GRAYSCALE) }
        awaitRendered(added.id)
        val gray = photo(added.id)
        assertNotSame(gray.original, gray.display)
        val g = (0.213 * 200 + 0.715 * 100 + 0.072 * 50).toInt()
        assertPixel(gray.display, 10, 10, Color.rgb(g, g, g), tolerance = 3)

        onMain { vm.restoreOriginal() }
        awaitRendered(added.id)
        val restored = photo(added.id)
        assertSame("restaurar debe devolver la imagen original, no una copia", restored.original, restored.display)
        assertEquals(EditSettings(), restored.edit)
    }

    @Test
    fun rotate_fourTimes_isBackToTheOriginalImage() {
        val added = addPhoto("giro.png", solid(64, 32, orange))
        select(added)
        val sizes = mutableListOf<Pair<Int, Int>>()
        repeat(4) {
            onMain { vm.rotate() }
            awaitRendered(added.id)
            sizes += photo(added.id).display.let { it.width to it.height }
        }
        assertEquals(listOf(32 to 64, 64 to 32, 32 to 64, 64 to 32), sizes)
        assertSame(photo(added.id).original, photo(added.id).display)
    }

    @Test
    fun fastSliderMovement_doesNotLaunchOneRenderPerEvent() {
        val added = addPhoto("rafaga.png", solid(256, 256, Color.rgb(100, 100, 100)))
        select(added)
        val before = vm.rendersStarted

        // 100 movimientos del deslizador en el mismo instante, como si se arrastrara muy deprisa.
        onMain { for (value in 1..100) vm.setBrightness(value) }
        awaitRendered(added.id)

        val renders = vm.rendersStarted - before
        assertTrue("se lanzaron $renders cálculos para 100 movimientos", renders in 1..3)
        val finalPhoto = photo(added.id)
        assertEquals(100, finalPhoto.brightness)
        val expected = ImageProcessor.render(finalPhoto.original, PhotoFilter.NONE, 0, 100, 100, null)
        assertTrue("la imagen final no corresponde al último valor del deslizador", expected.sameAs(finalPhoto.display))
    }

    @Test
    fun anOlderEdit_neverOverwritesANewerOne() {
        val added = addPhoto("orden.png", solid(256, 256, Color.rgb(90, 120, 150)))
        select(added)
        // Cambios muy seguidos y con pausas irregulares: acaben como acaben los cálculos, gana el último.
        val values = listOf(100, -100, 60, -30, 80, -80, 10, 95, -95, 37)
        for (value in values) {
            onMain { vm.setBrightness(value) }
            Thread.sleep((value % 7).toLong().coerceAtLeast(0) + 1)
        }
        awaitRendered(added.id)
        val finalPhoto = photo(added.id)
        assertEquals(37, finalPhoto.brightness)
        val expected = ImageProcessor.render(finalPhoto.original, PhotoFilter.NONE, 0, 37, 100, null)
        assertTrue("quedó una imagen antigua", expected.sameAs(finalPhoto.display))
    }

    @Test
    fun deletingAPhotoWhileItIsBeingEdited_isSafe() {
        val first = addPhoto("borrar.png", solid(512, 512, orange))
        select(first)
        onMain {
            vm.setSaturation(150)
            vm.deleteSelected()
        }
        Thread.sleep(500) // tiempo de sobra para que terminara cualquier cálculo pendiente
        assertTrue(vm.state.value.photos.isEmpty())
        assertFalse(vm.state.value.loading)

        // Después todo sigue funcionando con otra foto.
        val second = addPhoto("despues.png", solid(64, 32, orange))
        select(second)
        onMain { vm.rotate() }
        awaitRendered(second.id)
        assertEquals(32, photo(second.id).display.width)
    }

    // ------------------------------------------------------------------ Compartir y guardar

    @Test
    fun emptyCollage_cannotBeSharedNorSaved() {
        onMain {
            vm.share()
            vm.saveToGallery()
        }
        val expected = context.getString(R.string.msg_collage_empty)
        repeat(2) {
            val event = nextEvent()
            assertTrue("se esperaba un aviso y llegó $event", event is UiEvent.Message)
            assertEquals(expected, (event as UiEvent.Message).text)
        }
        assertNull("no debe haberse preparado nada para compartir", noEventWithin(300))
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun share_waitsForPendingEdits_soTheCollageShowsWhatTheScreenShows() {
        val added = addPhoto("compartir.png", solid(64, 64, Color.rgb(100, 100, 100)))
        select(added)
        // Brillo +100 (desplazamiento +150) y compartir en seguida, sin esperar a que se vea el cambio.
        onMain {
            vm.setBrightness(100)
            vm.share()
        }
        val event = nextEvent()
        assertTrue("se esperaba el selector de compartir y llegó $event", event is UiEvent.Share)
        val bitmap = context.contentResolver.openInputStream((event as UiEvent.Share).uri).use { BitmapFactory.decodeStream(it) }
        // Con 2 columnas y una sola foto, esta llena la primera celda (esquina en 12,12; lado 512).
        assertPixel(bitmap, 12 + 256, 12 + 256, Color.rgb(250, 250, 250), tolerance = 6)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun doubleTapOnShare_exportsOnlyOnce() {
        addPhoto("doble.png", solid(32, 32, orange))
        onMain {
            vm.share()
            vm.share()
        }
        assertTrue(nextEvent() is UiEvent.Share)
        assertNull("el segundo toque no debe lanzar otro collage", noEventWithin(1_000))
        assertEquals(1, File(context.cacheDir, "shared").listFiles().orEmpty().size)
    }

    // ------------------------------------------------------------------ Giro de pantalla

    @Test
    fun photosAndEdits_surviveRecreatingTheActivity() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var before: CollageViewModel
            scenario.onActivity { before = ViewModelProvider(it)[CollageViewModel::class.java] }

            val a = writePng("giro-a.png", solid(64, 32, orange))
            val b = writePng("giro-b.png", solid(32, 64, orange))
            scenario.onActivity { before.addPhotos(listOf(a, b)) }
            awaitUntil("no se cargaron las fotos") { before.state.value.photos.size == 2 && !before.state.value.loading }
            val id = before.state.value.photos.first().id
            scenario.onActivity {
                before.toggleSelection(id)
                before.rotate()
                before.setBrightness(30)
                before.setColumns(3)
            }
            awaitUntil("no terminó la edición") { before.state.value.photos.none { it.needsRender } }

            scenario.recreate() // lo mismo que hace Android al girar la pantalla

            lateinit var after: CollageViewModel
            lateinit var grid: RecyclerView
            scenario.onActivity {
                after = ViewModelProvider(it)[CollageViewModel::class.java]
                grid = it.findViewById(R.id.rvPhotos)
            }
            assertSame("debe conservarse el mismo ViewModel", before, after)
            val edited = after.state.value.photos.first { it.id == id }
            assertEquals(90, edited.rotation)
            assertEquals(30, edited.brightness)
            assertEquals(32, edited.display.width) // 64×32 girada 90° → 32×64
            assertEquals(3, after.state.value.columns)
            awaitUntil("la pantalla nueva no muestra las fotos") {
                var ok = false
                scenario.onActivity {
                    val recycler = it.findViewById<RecyclerView>(R.id.rvPhotos)
                    ok = recycler.adapter?.itemCount == 2 && (recycler.layoutManager as GridLayoutManager).spanCount == 3
                }
                ok
            }
        }
    }

    @Test
    fun photosAndEdits_surviveARealRotationOfTheScreen() {
        val automation = instrumentation.uiAutomation
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vmBefore: CollageViewModel
                scenario.onActivity { vmBefore = ViewModelProvider(it)[CollageViewModel::class.java] }
                val uri = writePng("rotacion.png", solid(64, 32, orange))
                scenario.onActivity { vmBefore.addPhotos(listOf(uri)) }
                awaitUntil("no se cargó la foto") { vmBefore.state.value.photos.size == 1 && !vmBefore.state.value.loading }
                scenario.onActivity { vmBefore.toggleSelection(vmBefore.state.value.photos.first().id) }

                automation.setRotation(UiAutomation.ROTATION_FREEZE_90)
                var landscape = false
                val limit = System.currentTimeMillis() + 8_000
                while (!landscape && System.currentTimeMillis() < limit) {
                    scenario.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                    if (!landscape) Thread.sleep(100)
                }
                assumeTrue("este dispositivo no permitió girar la pantalla", landscape)

                lateinit var vmAfter: CollageViewModel
                scenario.onActivity { vmAfter = ViewModelProvider(it)[CollageViewModel::class.java] }
                assertSame(vmBefore, vmAfter)
                assertEquals(1, vmAfter.state.value.photos.size)
                assertTrue("la foto seguiría seleccionada", vmAfter.state.value.photos.first().selected)
            }
        } finally {
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
    }
}
