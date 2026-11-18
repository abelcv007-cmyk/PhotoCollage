package com.abelcarballeira.photocollage

import android.Manifest
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.abelcarballeira.photocollage.databinding.ActivityMainBinding
import com.abelcarballeira.photocollage.databinding.DialogTextBinding
import com.abelcarballeira.photocollage.databinding.PanelControlsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Única pantalla de la app. Solo conecta las vistas con el [CollageViewModel]:
 * el estado y la lógica están en el ViewModel y el tratamiento de imágenes en [ImageProcessor].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var controls: PanelControlsBinding

    private val viewModel: CollageViewModel by viewModels()
    private val adapter = PhotoAdapter { photoId -> viewModel.toggleSelection(photoId) }

    private var dialog: Dialog? = null
    private var lastPhotoCount = 0
    private var firstRender = true

    /** Mientras el usuario mueve un deslizador no se le recoloca desde el estado. */
    private var draggingSlider = false

    /** Selector de fotos del sistema (Photo Picker) con selección múltiple. No necesita permisos. */
    private val pickPhotos = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> viewModel.addPhotos(uris) }

    /** Solo se usa en Android 9 o inferior para poder guardar el collage en la galería. */
    private val requestStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.saveToGallery() else showToast(getString(R.string.msg_permission_denied), true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // La interfaz es siempre clara: los iconos de las barras del sistema deben ser oscuros.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.argb(0x80, 0x1B, 0x1B, 0x1B))
        )
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        controls = binding.controls
        setContentView(binding.root)

        setupInsets()
        setupGrid()
        setupButtons()
        setupSliders()
        observeViewModel()
    }

    override fun onDestroy() {
        // Evita que un diálogo abierto se "escape" al girar la pantalla.
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Configuración

    /** La app se dibuja detrás de las barras del sistema: se deja el espacio que ocupan. */
    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
    }

    private fun setupGrid() {
        binding.rvPhotos.apply {
            layoutManager = GridLayoutManager(this@MainActivity, viewModel.state.value.columns)
            adapter = this@MainActivity.adapter
            // Sin animación de cambio: al mover el brillo la imagen se actualiza al instante, sin parpadeos.
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        }
    }

    private fun setupButtons() {
        controls.btnAdd.setOnClickListener {
            pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        controls.btnDelete.setOnClickListener { confirmDeleteSelected() }
        controls.btnDeleteAll.setOnClickListener { confirmDeleteAll() }

        controls.btnGray.setOnClickListener { viewModel.applyFilter(PhotoFilter.GRAYSCALE) }
        controls.btnSepia.setOnClickListener { viewModel.applyFilter(PhotoFilter.SEPIA) }
        controls.btnRestore.setOnClickListener { viewModel.restoreOriginal() }
        controls.btnRotate.setOnClickListener { viewModel.rotate() }
        controls.btnText.setOnClickListener { showTextDialog() }

        controls.btnCols2.setOnClickListener { viewModel.setColumns(2) }
        controls.btnCols3.setOnClickListener { viewModel.setColumns(3) }
        controls.btnColsCustom.setOnClickListener { showColumnsDialog() }

        controls.btnShare.setOnClickListener { viewModel.share() }
        controls.btnSave.setOnClickListener { saveCollage() }
    }

    private fun setupSliders() {
        controls.seekBrightness.setOnSeekBarChangeListener(sliderListener(R.string.msg_select_to_adjust) { progress ->
            // El deslizador va de 0 a 200 y su valor central (100) equivale al brillo original.
            viewModel.setBrightness(progress - BRIGHTNESS_CENTER)
        })
        controls.seekSaturation.setOnSeekBarChangeListener(sliderListener(R.string.msg_select_to_adjust) { progress ->
            viewModel.setSaturation(progress)
        })
    }

    private fun sliderListener(noSelectionMessage: Int, onValue: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (viewModel.state.value.selected == null) {
                    // Sin foto seleccionada el deslizador no cambia nada: vuelve a su valor neutro.
                    seekBar.progress = if (seekBar === controls.seekBrightness) BRIGHTNESS_CENTER else SATURATION_CENTER
                    return
                }
                updateSliderLabels(
                    brightness = if (seekBar === controls.seekBrightness) progress - BRIGHTNESS_CENTER else null,
                    saturation = if (seekBar === controls.seekSaturation) progress else null
                )
                onValue(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                draggingSlider = true
                viewModel.requireSelection(noSelectionMessage)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                draggingSlider = false
            }
        }

    // ------------------------------------------------------------------ Estado → pantalla

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(it) } }
                launch { viewModel.uiEvents.collect { handleEvent(it) } }
            }
        }
    }

    private fun render(state: CollageState) {
        // En el primer dibujado (por ejemplo, tras girar la pantalla) no se mueve el scroll.
        val grew = !firstRender && state.photos.size > lastPhotoCount
        firstRender = false
        lastPhotoCount = state.photos.size
        adapter.submitList(state.photos) {
            // Al añadir fotos se baja hasta la última para que se vea que ha entrado.
            if (grew && state.photos.isNotEmpty()) binding.rvPhotos.scrollToPosition(state.photos.size - 1)
        }

        val layoutManager = binding.rvPhotos.layoutManager as GridLayoutManager
        if (layoutManager.spanCount != state.columns) layoutManager.spanCount = state.columns

        binding.emptyState.isVisible = state.photos.isEmpty()
        binding.progress.isVisible = state.loading
        controls.tvColumns.text = getString(R.string.columns_label, state.columns)

        if (!draggingSlider) {
            val selected = state.selected
            val brightness = selected?.brightness ?: CollageLogic.DEFAULT_BRIGHTNESS
            val saturation = selected?.saturation ?: CollageLogic.DEFAULT_SATURATION
            controls.seekBrightness.progress = brightness + BRIGHTNESS_CENTER
            controls.seekSaturation.progress = saturation
            updateSliderLabels(brightness, saturation)
        }
    }

    private fun updateSliderLabels(brightness: Int? = null, saturation: Int? = null) {
        if (brightness != null) controls.tvBrightness.text = getString(R.string.brightness_label, brightness)
        if (saturation != null) controls.tvSaturation.text = getString(R.string.saturation_label, saturation)
    }

    private fun handleEvent(event: UiEvent) {
        when (event) {
            is UiEvent.Message -> showToast(event.text, event.long)
            is UiEvent.Share -> try {
                startActivity(CollageExporter.shareIntent(event.uri, getString(R.string.share_title)))
            } catch (e: ActivityNotFoundException) {
                showToast(getString(R.string.msg_share_error), true)
            }
        }
    }

    private fun showToast(text: String, long: Boolean = false) {
        Toast.makeText(this, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ Diálogos

    /** Pide confirmación antes de eliminar la foto seleccionada (si no hay ninguna, el ViewModel avisa). */
    private fun confirmDeleteSelected() {
        if (!viewModel.requireSelection(R.string.msg_select_to_delete)) return
        val number = viewModel.state.value.selected?.number ?: return
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_delete_title, number))
            .setMessage(R.string.dialog_delete_message)
            .setPositiveButton(R.string.dialog_delete_confirm) { _, _ -> viewModel.deleteSelected() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun confirmDeleteAll() {
        if (!viewModel.requireNotEmpty()) return
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_delete_all_title)
            .setMessage(R.string.dialog_delete_all_message)
            .setPositiveButton(R.string.dialog_delete_all_confirm) { _, _ -> viewModel.deleteAll() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showColumnsDialog() {
        val options = Array<CharSequence>(CollageLogic.MAX_COLUMNS) { (it + 1).toString() }
        val current = viewModel.state.value.columns - 1
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_columns_title)
            .setSingleChoiceItems(options, current) { chooser, which ->
                viewModel.setColumns(which + 1)
                chooser.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /** Texto sobre la foto seleccionada: contenido, tamaño y posición. */
    private fun showTextDialog() {
        val selected = viewModel.state.value.selected
        if (selected == null) {
            viewModel.requireSelection(R.string.msg_select_to_text)
            return
        }
        val form = DialogTextBinding.inflate(layoutInflater)
        val current = selected.text
        form.etText.setText(current?.text.orEmpty())
        form.seekTextSize.progress = (current?.sizePercent ?: DEFAULT_TEXT_SIZE) - MIN_TEXT_SIZE
        form.rgPosition.check(
            when (current?.position ?: TextPosition.BOTTOM) {
                TextPosition.TOP -> R.id.rbTop
                TextPosition.CENTER -> R.id.rbCenter
                TextPosition.BOTTOM -> R.id.rbBottom
            }
        )

        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_text_title)
            .setView(form.root)
            .setPositiveButton(R.string.dialog_text_apply) { _, _ ->
                val content = form.etText.text.toString().trim()
                if (content.isEmpty()) {
                    viewModel.setText(null)
                } else {
                    val position = when (form.rgPosition.checkedRadioButtonId) {
                        R.id.rbTop -> TextPosition.TOP
                        R.id.rbCenter -> TextPosition.CENTER
                        else -> TextPosition.BOTTOM
                    }
                    viewModel.setText(PhotoText(content, form.seekTextSize.progress + MIN_TEXT_SIZE, position))
                }
            }
            .setNeutralButton(R.string.dialog_text_remove) { _, _ -> viewModel.setText(null) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ Guardar en la galería

    private fun saveCollage() {
        if (!viewModel.requireNotEmpty()) return
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            viewModel.saveToGallery()
        }
    }

    private companion object {
        const val BRIGHTNESS_CENTER = 100
        const val SATURATION_CENTER = 100
        const val MIN_TEXT_SIZE = 4
        const val DEFAULT_TEXT_SIZE = 8
    }
}
