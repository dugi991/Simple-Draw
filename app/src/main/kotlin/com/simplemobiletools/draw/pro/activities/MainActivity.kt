package com.simplemobiletools.draw.pro.activities

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.SeekBar
import com.simplemobiletools.commons.dialogs.ColorPickerDialog
import com.simplemobiletools.commons.dialogs.FilePickerDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.LICENSE_GLIDE
import com.simplemobiletools.commons.helpers.PERMISSION_WRITE_STORAGE
import com.simplemobiletools.commons.models.FAQItem
import com.simplemobiletools.commons.models.FileDirItem
import com.simplemobiletools.commons.models.Release
import com.simplemobiletools.draw.pro.BuildConfig
import com.simplemobiletools.draw.pro.R
import com.simplemobiletools.draw.pro.dialogs.SaveImageDialog
import com.simplemobiletools.draw.pro.extensions.config
import com.simplemobiletools.draw.pro.helpers.JPG
import com.simplemobiletools.draw.pro.helpers.PNG
import com.simplemobiletools.draw.pro.helpers.SVG
import com.simplemobiletools.draw.pro.interfaces.CanvasListener
import com.simplemobiletools.draw.pro.models.Svg
import kotlinx.android.synthetic.main.activity_main.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

class MainActivity : SimpleActivity(), CanvasListener {
    private val FOLDER_NAME = "images"
    private val FILE_NAME = "simple-draw.png"
    private val BITMAP_PATH = "bitmap_path"

    private var defaultPath = ""
    private var defaultFilename = ""
    private var defaultExtension = PNG

    private var intentUri: Uri? = null
    private var color = 0
    private var brushSize = 0f
    private var isEraserOn = false
    private var isImageCaptureIntent = false
    private var lastBitmapPath = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        appLaunched(BuildConfig.APPLICATION_ID)
        my_canvas.mListener = this
        stroke_width_bar.setOnSeekBarChangeListener(onStrokeWidthBarChangeListener)

        setBackgroundColor(config.canvasBackgroundColor)
        setColor(config.brushColor)
        defaultPath = config.lastSaveFolder

        brushSize = config.brushSize
        updateBrushSize()
        stroke_width_bar.progress = brushSize.toInt()

        color_picker.setOnClickListener { pickColor() }
        undo.setOnClickListener { my_canvas.undo() }
        eraser.setOnClickListener { eraserClicked() }
        redo.setOnClickListener { my_canvas.redo() }

        checkIntents()
        if (!isImageCaptureIntent) {
            checkWhatsNewDialog()
        }
    }

    override fun onResume() {
        super.onResume()

        val isShowBrushSizeEnabled = config.showBrushSize
        stroke_width_bar.beVisibleIf(isShowBrushSizeEnabled)
        stroke_width_preview.beVisibleIf(isShowBrushSizeEnabled)
        my_canvas.setAllowZooming(config.allowZoomingCanvas)
        updateTextColors(main_holder)
        if (config.preventPhoneFromSleeping) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        requestedOrientation = if (config.forcePortraitMode) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    override fun onPause() {
        super.onPause()
        config.brushColor = color
        config.brushSize = brushSize
        if (config.preventPhoneFromSleeping) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        my_canvas.mListener = null
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu, menu)
        menu.apply {
            findItem(R.id.menu_confirm).isVisible = isImageCaptureIntent
            findItem(R.id.menu_save).isVisible = !isImageCaptureIntent
            findItem(R.id.menu_share).isVisible = !isImageCaptureIntent
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_confirm -> confirmImage()
            R.id.menu_save -> trySaveImage()
            R.id.menu_share -> shareImage()
            R.id.clear -> clearCanvas()
            R.id.open_file -> tryOpenFile()
            R.id.change_background -> changeBackgroundClicked()
            R.id.settings -> launchSettings()
            R.id.about -> launchAbout()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun launchSettings() {
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {
        val licenses = LICENSE_GLIDE

        val faqItems = arrayListOf(
                FAQItem(R.string.faq_2_title_commons, R.string.faq_2_text_commons),
                FAQItem(R.string.faq_6_title_commons, R.string.faq_6_text_commons)
        )

        startAboutActivity(R.string.app_name, licenses, BuildConfig.VERSION_NAME, faqItems, false)
    }

    private fun tryOpenFile() {
        getStoragePermission {
            openFile()
        }
    }

    private fun openFile() {
        val path = if (isImageCaptureIntent) "" else defaultPath
        FilePickerDialog(this, path) {
            openPath(it)
        }
    }

    private fun checkIntents() {
        if (intent?.action == Intent.ACTION_SEND && intent.type.startsWith("image/")) {
            getStoragePermission {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                tryOpenUri(uri)
            }
        }

        if (intent?.action == Intent.ACTION_SEND_MULTIPLE && intent.type.startsWith("image/")) {
            getStoragePermission {
                val imageUris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                imageUris.any { tryOpenUri(it) }
            }
        }

        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            getStoragePermission {
                val path = getRealPathFromURI(intent.data) ?: intent.dataString
                openPath(path)
            }
        }

        if (intent?.action == MediaStore.ACTION_IMAGE_CAPTURE) {
            val output = intent.extras?.get(MediaStore.EXTRA_OUTPUT)
            if (output != null && output is Uri) {
                isImageCaptureIntent = true
                intentUri = output
                defaultPath = output.path
                invalidateOptionsMenu()
            }
        }
    }

    private fun getStoragePermission(callback: () -> Unit) {
        handlePermission(PERMISSION_WRITE_STORAGE) {
            if (it) {
                callback()
            } else {
                toast(R.string.no_storage_permissions)
            }
        }
    }

    private fun tryOpenUri(uri: Uri) = when {
        uri.scheme == "file" -> openPath(uri.path)
        uri.scheme == "content" -> openUri(uri, intent)
        else -> false
    }

    private fun openPath(path: String) = when {
        path.endsWith(".svg") -> {
            my_canvas.mBackgroundBitmap = null
            Svg.loadSvg(this, File(path), my_canvas)
            defaultExtension = SVG
            true
        }
        File(path).isImageSlow() -> {
            lastBitmapPath = path
            my_canvas.drawBitmap(this, path)
            defaultExtension = JPG
            true
        }
        else -> {
            toast(R.string.invalid_file_format)
            false
        }
    }

    private fun openUri(uri: Uri, intent: Intent): Boolean {
        val mime = MimeTypeMap.getSingleton()
        val type = mime.getExtensionFromMimeType(contentResolver.getType(uri)) ?: intent.type
        return when (type) {
            "svg", "image/svg+xml" -> {
                my_canvas.mBackgroundBitmap = null
                Svg.loadSvg(this, uri, my_canvas)
                defaultExtension = SVG
                true
            }
            "jpg", "jpeg", "png" -> {
                my_canvas.drawBitmap(this, uri)
                defaultExtension = JPG
                true
            }
            else -> {
                toast(R.string.invalid_file_format)
                false
            }
        }
    }

    private fun eraserClicked() {
        isEraserOn = !isEraserOn
        updateEraserState()
    }

    private fun updateEraserState() {
        eraser.setImageDrawable(resources.getDrawable(if (isEraserOn) R.drawable.ic_eraser_on else R.drawable.ic_eraser_off))
        my_canvas.toggleEraser(isEraserOn)
    }

    private fun changeBackgroundClicked() {
        val oldColor = (my_canvas.background as ColorDrawable).color
        ColorPickerDialog(this, oldColor) { wasPositivePressed, color ->
            if (wasPositivePressed) {
                config.canvasBackgroundColor = color
                setBackgroundColor(color)
            }
        }
    }

    private fun confirmImage() {
        if (intentUri?.scheme == "content") {
            val outputStream = contentResolver.openOutputStream(intentUri)
            saveToOutputStream(outputStream, defaultPath.getCompressionFormat())
        } else {
            handlePermission(PERMISSION_WRITE_STORAGE) {
                val fileDirItem = FileDirItem(defaultPath, defaultPath.getFilenameFromPath())
                getFileOutputStream(fileDirItem, true) {
                    saveToOutputStream(it, defaultPath.getCompressionFormat())
                }
            }
        }
    }

    private fun saveToOutputStream(outputStream: OutputStream?, format: Bitmap.CompressFormat) {
        if (outputStream == null) {
            toast(R.string.unknown_error_occurred)
            return
        }

        outputStream.use {
            my_canvas.getBitmap().compress(format, 70, it)
        }
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun trySaveImage() {
        getStoragePermission {
            saveImage()
        }
    }

    private fun saveImage() {
        SaveImageDialog(this, defaultExtension, defaultPath, defaultFilename) {
            saveFile(it)
            defaultPath = it.getParentPath()
            defaultFilename = it.getFilenameFromPath()
            defaultFilename = defaultFilename.substring(0, defaultFilename.lastIndexOf("."))
            defaultExtension = it.getFilenameExtension()
            config.lastSaveFolder = defaultPath
        }
    }

    private fun saveFile(path: String) {
        when (path.getFilenameExtension()) {
            SVG -> Svg.saveSvg(this, path, my_canvas)
            else -> saveImageFile(path)
        }
        rescanPaths(arrayListOf(path)) {}
    }

    private fun saveImageFile(path: String) {
        val fileDirItem = FileDirItem(path, path.getFilenameFromPath())
        getFileOutputStream(fileDirItem, true) {
            if (it != null) {
                writeToOutputStream(path, it)
                toast(R.string.file_saved)
            } else {
                toast(R.string.unknown_error_occurred)
            }
        }
    }

    private fun writeToOutputStream(path: String, out: OutputStream) {
        out.use {
            my_canvas.getBitmap().compress(path.getCompressionFormat(), 70, out)
        }
    }

    private fun shareImage() {
        getImagePath(my_canvas.getBitmap()) {
            if (it != null) {
                sharePathIntent(it, BuildConfig.APPLICATION_ID)
            } else {
                toast(R.string.unknown_error_occurred)
            }
        }
    }

    private fun getImagePath(bitmap: Bitmap, callback: (path: String?) -> Unit) {
        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 0, bytes)

        val folder = File(cacheDir, FOLDER_NAME)
        if (!folder.exists()) {
            if (!folder.mkdir()) {
                callback(null)
                return
            }
        }

        val newPath = "$folder/$FILE_NAME"
        val fileDirItem = FileDirItem(newPath, FILE_NAME)
        getFileOutputStream(fileDirItem, true) {
            if (it != null) {
                try {
                    it.write(bytes.toByteArray())
                    callback(newPath)
                } catch (e: Exception) {
                } finally {
                    it.close()
                }
            } else {
                callback("")
            }
        }
    }

    private fun clearCanvas() {
        my_canvas.clearCanvas()
        defaultExtension = PNG
        defaultPath = ""
        lastBitmapPath = ""
    }

    private fun pickColor() {
        ColorPickerDialog(this, color) { wasPositivePressed, color ->
            if (wasPositivePressed) {
                setColor(color)
            }
        }
    }

    fun setBackgroundColor(pickedColor: Int) {
        val contrastColor = pickedColor.getContrastColor()
        undo.applyColorFilter(contrastColor)
        eraser.applyColorFilter(contrastColor)
        redo.applyColorFilter(contrastColor)
        my_canvas.updateBackgroundColor(pickedColor)
        defaultExtension = PNG
        getBrushPreviewView().setStroke(getBrushStrokeSize(), contrastColor)
    }

    private fun setColor(pickedColor: Int) {
        color = pickedColor
        color_picker.setFillWithStroke(color, config.canvasBackgroundColor.getContrastColor())
        my_canvas.setColor(color)
        isEraserOn = false
        updateEraserState()
        getBrushPreviewView().setColor(color)
    }

    private fun getBrushPreviewView() = stroke_width_preview.background as GradientDrawable

    private fun getBrushStrokeSize() = resources.getDimension(R.dimen.preview_dot_stroke_size).toInt()

    override fun toggleUndoVisibility(visible: Boolean) {
        undo.beVisibleIf(visible)
    }

    override fun toggleRedoVisibility(visible: Boolean) {
        redo.beVisibleIf(visible)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(BITMAP_PATH, lastBitmapPath)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        lastBitmapPath = savedInstanceState.getString(BITMAP_PATH)
        if (lastBitmapPath.isNotEmpty()) {
            openPath(lastBitmapPath)
        }
    }

    private var onStrokeWidthBarChangeListener: SeekBar.OnSeekBarChangeListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            brushSize = progress.toFloat()
            updateBrushSize()
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {}

        override fun onStopTrackingTouch(seekBar: SeekBar) {}
    }

    private fun updateBrushSize() {
        my_canvas.setBrushSize(brushSize)
        val scale = Math.max(0.03f, brushSize / 100f)
        stroke_width_preview.scaleX = scale
        stroke_width_preview.scaleY = scale
    }

    private fun checkWhatsNewDialog() {
        arrayListOf<Release>().apply {
            add(Release(18, R.string.release_18))
            add(Release(20, R.string.release_20))
            add(Release(38, R.string.release_38))
            checkWhatsNew(this, BuildConfig.VERSION_CODE)
        }
    }
}
