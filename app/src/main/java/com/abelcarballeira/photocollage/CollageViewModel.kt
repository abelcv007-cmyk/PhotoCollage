package com.abelcarballeira.photocollage

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Cosas que la pantalla debe mostrar una sola vez (mensajes, selector de "compartir"). */
sealed interface UiEvent {
    data class Message(val text: String, val long: Boolean = false) : UiEvent
    data class Share(val uri: Uri) : UiEvent
}

/**
 * Guarda el estado del collage. Como es un ViewModel, las fotos y sus ajustes sobreviven
 * a los giros de pantalla y a otros cambios de configuración.
 * Todo el estado se modifica en el hilo principal; las tareas pesadas (leer fotos, editar una
 * foto, generar la imagen final) se hacen en hilos secundarios.
 *
 * Edición sin bloquear la pantalla: los ajustes de una foto se guardan al instante, pero su imagen
 * se recalcula en segundo plano con un solo trabajo por foto. Si mientras tanto llegan más cambios
 * (por ejemplo, al arrastrar un deslizador) no se lanzan trabajos nuevos: al terminar, el mismo
 * trabajo vuelve a calcular con los ajustes más recientes. Así se saltan los valores intermedios y
 * una edición antigua nunca pisa a una más nueva.
 */
class CollageViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(CollageState())
    val state: StateFlow<CollageState> = _state.asStateFlow()

    private val events = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvents: Flow<UiEvent> = events.receiveAsFlow()

    private var nextId = 1L
    private var nextNumber = 1
    private var activeWork = 0

    /** Un trabajo de edición por foto y la exportación en curso. Solo se tocan desde el hilo principal. */
    private val renderJobs = HashMap<Long, Job>()
    private var exportJob: Job? = null

    /** Cuántos cálculos de imagen se han iniciado desde que existe el ViewModel (lo usan las pruebas). */
    internal var rendersStarted = 0
        private set

    // ------------------------------------------------------------------ Añadir fotos

    /** Añade las fotos elegidas en el selector, ignorando las que ya están en el collage. */
    fun addPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return // el usuario cerró el selector sin elegir nada
        val fresh = CollageLogic.newItems(_state.value.photos.map { it.uri }, uris)
        var duplicates = uris.size - fresh.size
        var added = 0
        var failed = 0

        viewModelScope.launch {
            beginWork()
            try {
                val resolver = getApplication<Application>().contentResolver
                for (uri in fresh) {
                    val bitmap = withContext(Dispatchers.IO) { ImageProcessor.decode(resolver, uri) }
                    if (bitmap == null) {
                        failed++
                        continue
                    }
                    // Otra selección pudo añadir la misma foto mientras esta se leía.
                    if (_state.value.photos.any { it.uri == uri }) {
                        duplicates++
                        continue
                    }
                    val photo = Photo(id = nextId++, number = nextNumber++, uri = uri, original = bitmap, display = bitmap)
                    _state.update { it.copy(photos = it.photos + photo) } // la pantalla se actualiza con cada foto
                    added++
                }
            } finally {
                endWork()
            }
            reportAddResult(added, duplicates, failed)
        }
    }

    private fun reportAddResult(added: Int, duplicates: Int, failed: Int) {
        val parts = mutableListOf<String>()
        if (added > 0) parts += plural(R.plurals.msg_added, added)
        if (duplicates > 0) parts += plural(R.plurals.msg_duplicates, duplicates)
        if (failed > 0) parts += plural(R.plurals.msg_failed, failed)
        if (parts.isNotEmpty()) say(parts.joinToString(". "), long = true)
    }

    // ------------------------------------------------------------------ Selección y borrado

    /** Solo una foto puede estar seleccionada; volver a tocarla la deselecciona. */
    fun toggleSelection(id: Long) {
        _state.update { s ->
            s.copy(photos = s.photos.map { it.copy(selected = it.id == id && !it.selected) })
        }
    }

    fun deleteSelected() {
        val selected = _state.value.selected
        if (selected == null) {
            say(str(R.string.msg_select_to_delete))
            return
        }
        // Se deja de calcular su imagen y se suelta la referencia: el recolector libera sus Bitmap.
        // No se usa Bitmap.recycle(): la lista aún puede estar dibujando la foto durante la animación.
        renderJobs.remove(selected.id)?.cancel()
        _state.update { s -> s.copy(photos = s.photos.filterNot { it.id == selected.id }) }
        say(str(R.string.msg_deleted, selected.number))
    }

    fun deleteAll() {
        nextNumber = 1
        renderJobs.values.forEach { it.cancel() }
        renderJobs.clear()
        _state.update { it.copy(photos = emptyList()) }
        say(str(R.string.msg_deleted_all))
    }

    /** Devuelve true si hay fotos; si no, avisa al usuario con el mensaje de colección vacía. */
    fun requireNotEmpty(): Boolean {
        if (_state.value.photos.isEmpty()) {
            say(str(R.string.msg_collage_empty))
            return false
        }
        return true
    }

    /** Devuelve true si hay una foto seleccionada; si no, avisa con [messageRes]. */
    fun requireSelection(@StringRes messageRes: Int): Boolean {
        if (_state.value.selected == null) {
            say(str(messageRes))
            return false
        }
        return true
    }

    // ------------------------------------------------------------------ Edición de la foto seleccionada

    fun applyFilter(filter: PhotoFilter) = editSelected(R.string.msg_select_to_filter) { it.copy(filter = filter) }

    /** Quita todos los ajustes de la foto seleccionada y vuelve a mostrar la original. */
    fun restoreOriginal() = editSelected(R.string.msg_select_to_filter) {
        it.copy(
            filter = PhotoFilter.NONE,
            rotation = 0,
            brightness = CollageLogic.DEFAULT_BRIGHTNESS,
            saturation = CollageLogic.DEFAULT_SATURATION,
            text = null
        )
    }

    fun rotate() = editSelected(R.string.msg_select_to_rotate) {
        it.copy(rotation = CollageLogic.nextRotation(it.rotation))
    }

    /** Se llama continuamente mientras se mueve el deslizador: la pantalla avisa antes si no hay foto. */
    fun setBrightness(value: Int) = editSelected(null) { it.copy(brightness = value.coerceIn(-100, 100)) }

    fun setSaturation(value: Int) = editSelected(null) { it.copy(saturation = value.coerceIn(0, 200)) }

    fun setText(text: PhotoText?) = editSelected(R.string.msg_select_to_text) { it.copy(text = text) }

    /** Guarda los nuevos ajustes de la foto seleccionada y pide recalcular su imagen en segundo plano. */
    private fun editSelected(@StringRes noSelectionMessage: Int?, change: (Photo) -> Photo) {
        val selected = _state.value.selected
        if (selected == null) {
            if (noSelectionMessage != null) say(str(noSelectionMessage))
            return
        }
        _state.update { s -> s.copy(photos = s.photos.map { if (it.id == selected.id) change(it) else it }) }
        scheduleRender(selected.id)
    }

    /** Si ya hay un trabajo calculando esta foto no se lanza otro: ese recogerá los ajustes más nuevos. */
    private fun scheduleRender(id: Long) {
        if (renderJobs[id]?.isActive == true) return
        renderJobs[id] = viewModelScope.launch { renderUntilCurrent(id) }
    }

    /** Calcula la imagen de la foto hasta que coincide con sus ajustes actuales. */
    private suspend fun renderUntilCurrent(id: Long) {
        while (true) {
            val photo = _state.value.photos.firstOrNull { it.id == id } ?: return // eliminada mientras tanto
            if (!photo.needsRender) return
            val target = photo.edit
            rendersStarted++
            val display = try {
                withContext(Dispatchers.Default) { ImageProcessor.render(photo.original, target) }
            } catch (e: OutOfMemoryError) {
                // Sin memoria: los ajustes vuelven a los de la imagen que se está viendo, para que coincidan.
                replacePhoto(id) { it.withEdit(it.renderedWith) }
                say(str(R.string.msg_out_of_memory))
                return
            }
            replacePhoto(id) { it.copy(display = display, renderedWith = target) }
        }
    }

    private fun replacePhoto(id: Long, change: (Photo) -> Photo) {
        _state.update { s -> s.copy(photos = s.photos.map { if (it.id == id) change(it) else it }) }
    }

    /** Espera a que terminen todas las ediciones en curso, para exportar siempre lo último que se ve. */
    private suspend fun awaitPendingRenders() {
        while (true) {
            val running = renderJobs.values.filter { it.isActive }
            if (running.isEmpty()) return
            running.joinAll()
        }
    }

    // ------------------------------------------------------------------ Distribución

    fun setColumns(columns: Int) {
        _state.update { it.copy(columns = CollageLogic.clampColumns(columns)) }
    }

    // ------------------------------------------------------------------ Exportar

    fun share() = export(R.string.msg_share_error) { bitmap ->
        val context = getApplication<Application>()
        val uri = withContext(Dispatchers.IO) { CollageExporter.writeForSharing(context, bitmap) }
        events.send(UiEvent.Share(uri))
    }

    fun saveToGallery() = export(R.string.msg_save_error) { bitmap ->
        val context = getApplication<Application>()
        withContext(Dispatchers.IO) { CollageExporter.saveToGallery(context, bitmap) }
        say(str(R.string.msg_saved), long = true)
    }

    /**
     * Genera la imagen final con lo que se ve en pantalla y se la entrega a [deliver].
     * No se exporta un collage vacío y solo hay una exportación a la vez: un toque repetido no
     * lanza otra imagen grande en paralelo.
     */
    private fun export(@StringRes errorMessage: Int, deliver: suspend (Bitmap) -> Unit) {
        if (!requireNotEmpty()) return
        if (exportJob?.isActive == true) return
        exportJob = viewModelScope.launch {
            beginWork()
            try {
                awaitPendingRenders()
                val bitmap = renderCollage() ?: return@launch
                try {
                    deliver(bitmap)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // MediaStore y FileProvider pueden fallar con IOException, SecurityException o
                    // IllegalArgumentException: en todos los casos se avisa al usuario sin cerrar la app.
                    Log.w(TAG, "No se pudo exportar el collage", e)
                    say(str(errorMessage))
                } finally {
                    bitmap.recycle() // el collage final es un Bitmap grande que ya no hace falta
                }
            } finally {
                endWork()
            }
        }
    }

    /** Dibuja la imagen final con todas las fotos y su edición actual. */
    private suspend fun renderCollage(): Bitmap? {
        val current = _state.value
        if (current.photos.isEmpty()) { // se vació el collage mientras se esperaba
            say(str(R.string.msg_collage_empty))
            return null
        }
        return try {
            withContext(Dispatchers.Default) { CollageExporter.render(current.photos, current.columns) }
        } catch (e: OutOfMemoryError) {
            say(str(R.string.msg_out_of_memory))
            null
        }
    }

    // ------------------------------------------------------------------ Utilidades

    /** El indicador de progreso se ve mientras haya alguna tarea larga (leer fotos o generar el collage). */
    private fun beginWork() {
        activeWork++
        _state.update { it.copy(loading = true) }
    }

    private fun endWork() {
        activeWork--
        _state.update { it.copy(loading = activeWork > 0) }
    }

    private fun say(text: String, long: Boolean = false) {
        events.trySend(UiEvent.Message(text, long))
    }

    private fun str(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    private fun plural(@PluralsRes id: Int, count: Int): String =
        getApplication<Application>().resources.getQuantityString(id, count, count)

    private companion object {
        const val TAG = "CollageViewModel"
    }
}
