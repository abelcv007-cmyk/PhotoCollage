package com.abelcarballeira.photocollage

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
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
 * Todo el estado se modifica en el hilo principal; las tareas pesadas (leer fotos, generar la
 * imagen final) se hacen en hilos secundarios.
 */
class CollageViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(CollageState())
    val state: StateFlow<CollageState> = _state.asStateFlow()

    private val events = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvents: Flow<UiEvent> = events.receiveAsFlow()

    private var nextId = 1L
    private var nextNumber = 1
    private var activeLoads = 0

    // ------------------------------------------------------------------ Añadir fotos

    /** Añade las fotos elegidas en el selector, ignorando las que ya están en el collage. */
    fun addPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return // el usuario cerró el selector sin elegir nada
        val fresh = CollageLogic.newItems(_state.value.photos.map { it.uri }, uris)
        var duplicates = uris.size - fresh.size
        var added = 0
        var failed = 0

        viewModelScope.launch {
            activeLoads++
            _state.update { it.copy(loading = true) }
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
                activeLoads--
                _state.update { it.copy(loading = activeLoads > 0) }
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
        _state.update { s -> s.copy(photos = s.photos.filterNot { it.id == selected.id }) }
        say(str(R.string.msg_deleted, selected.number))
    }

    fun deleteAll() {
        nextNumber = 1
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

    private fun editSelected(@StringRes noSelectionMessage: Int?, change: (Photo) -> Photo) {
        val selected = _state.value.selected
        if (selected == null) {
            if (noSelectionMessage != null) say(str(noSelectionMessage))
            return
        }
        val edited = change(selected)
        try {
            val display = ImageProcessor.render(
                edited.original, edited.filter, edited.rotation, edited.brightness, edited.saturation, edited.text
            )
            val updated = edited.copy(display = display)
            _state.update { s ->
                s.copy(photos = s.photos.map { if (it.id == updated.id) updated.copy(selected = it.selected) else it })
            }
        } catch (e: OutOfMemoryError) {
            say(str(R.string.msg_out_of_memory))
        }
    }

    // ------------------------------------------------------------------ Distribución

    fun setColumns(columns: Int) {
        _state.update { it.copy(columns = columns.coerceIn(CollageLogic.MIN_COLUMNS, CollageLogic.MAX_COLUMNS)) }
    }

    // ------------------------------------------------------------------ Exportar

    fun share() {
        if (!requireNotEmpty()) return
        viewModelScope.launch {
            val bitmap = renderCollage() ?: return@launch
            try {
                val context = getApplication<Application>()
                val uri = withContext(Dispatchers.IO) { CollageExporter.writeForSharing(context, bitmap) }
                events.send(UiEvent.Share(uri))
            } catch (e: IOException) {
                say(str(R.string.msg_share_error))
            }
        }
    }

    fun saveToGallery() {
        if (!requireNotEmpty()) return
        viewModelScope.launch {
            val bitmap = renderCollage() ?: return@launch
            try {
                val context = getApplication<Application>()
                withContext(Dispatchers.IO) { CollageExporter.saveToGallery(context, bitmap) }
                say(str(R.string.msg_saved), long = true)
            } catch (e: IOException) {
                say(str(R.string.msg_save_error))
            }
        }
    }

    /** Dibuja la imagen final con todas las fotos y su edición actual. */
    private suspend fun renderCollage(): Bitmap? {
        val current = _state.value
        return try {
            withContext(Dispatchers.Default) { CollageExporter.render(current.photos, current.columns) }
        } catch (e: OutOfMemoryError) {
            say(str(R.string.msg_out_of_memory))
            null
        }
    }

    // ------------------------------------------------------------------ Utilidades

    private fun say(text: String, long: Boolean = false) {
        events.trySend(UiEvent.Message(text, long))
    }

    private fun str(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    private fun plural(@PluralsRes id: Int, count: Int): String =
        getApplication<Application>().resources.getQuantityString(id, count, count)
}
