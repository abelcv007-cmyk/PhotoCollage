package com.abelcarballeira.photocollage

import android.graphics.Bitmap
import android.net.Uri

enum class PhotoFilter { NONE, GRAYSCALE, SEPIA }

enum class TextPosition { TOP, CENTER, BOTTOM }

/** Texto que se dibuja sobre una foto. [sizePercent] es el tamaño respecto al lado menor de la foto. */
data class PhotoText(
    val text: String,
    val sizePercent: Int = 8,
    val position: TextPosition = TextPosition.BOTTOM
)

/** Todos los ajustes de edición de una foto: lo único que hace falta para calcular su imagen. */
data class EditSettings(
    val filter: PhotoFilter = PhotoFilter.NONE,
    val rotation: Int = 0,
    val brightness: Int = CollageLogic.DEFAULT_BRIGHTNESS,
    val saturation: Int = CollageLogic.DEFAULT_SATURATION,
    val text: PhotoText? = null
)

/**
 * Una foto del collage.
 *
 * [original] nunca se modifica: [display] se calcula siempre a partir de ella aplicando los
 * ajustes (filtro, giro, brillo, saturación y texto). Si no hay ajustes, [display] es la misma
 * imagen que [original], así que no se gasta memoria extra.
 *
 * Los ajustes cambian al instante, pero [display] se calcula en un hilo secundario y puede ir
 * un paso por detrás: [renderedWith] guarda con qué ajustes se calculó el [display] actual.
 */
data class Photo(
    val id: Long,
    val number: Int,
    val uri: Uri,
    val original: Bitmap,
    val display: Bitmap,
    val filter: PhotoFilter = PhotoFilter.NONE,
    val rotation: Int = 0,
    val brightness: Int = CollageLogic.DEFAULT_BRIGHTNESS,
    val saturation: Int = CollageLogic.DEFAULT_SATURATION,
    val text: PhotoText? = null,
    val selected: Boolean = false,
    val renderedWith: EditSettings = EditSettings()
) {
    /** Ajustes pedidos ahora mismo. */
    val edit: EditSettings get() = EditSettings(filter, rotation, brightness, saturation, text)

    /** true si [display] todavía no refleja los últimos ajustes. */
    val needsRender: Boolean get() = edit != renderedWith

    fun withEdit(settings: EditSettings): Photo = copy(
        filter = settings.filter,
        rotation = settings.rotation,
        brightness = settings.brightness,
        saturation = settings.saturation,
        text = settings.text
    )
}

data class CollageState(
    val photos: List<Photo> = emptyList(),
    val columns: Int = 2,
    val loading: Boolean = false
) {
    /** Como mucho hay una foto seleccionada. */
    val selected: Photo? get() = photos.firstOrNull { it.selected }
}
