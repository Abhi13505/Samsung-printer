package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.DitherMode
import com.example.model.ImagesPerPage
import com.example.model.PaperSize
import com.example.model.PhotoCutGuide
import com.example.model.PhotoGapPreset
import com.example.model.PhotoLayoutOptions
import com.example.model.PhotoMarginPreset
import com.example.model.PhotoPosition
import com.example.model.PhotoSizePreset
import com.example.model.PrintOrientation
import com.example.model.PrintQuality
import com.example.model.QpdlDialect
import com.example.model.ScaleMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.printerPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "scx_printer_prefs"
)

data class StoredPrinterDefaults(
    val paperSize: PaperSize = PaperSize.A4,
    val orientation: PrintOrientation = PrintOrientation.PORTRAIT,
    val scaleMode: ScaleMode = ScaleMode.FIT,
    val quality: PrintQuality = PrintQuality.STANDARD_600,
    val ditherMode: DitherMode = DitherMode.HALFTONE_PHOTO,
    val dialect: QpdlDialect = QpdlDialect.SCX3200_SPLIX_0X11,
    val photoLayout: PhotoLayoutOptions = PhotoLayoutOptions()
)

class PrinterPreferencesRepository(private val context: Context) {

    private object Keys {
        val PAPER_SIZE = stringPreferencesKey("paper_size")
        val ORIENTATION = stringPreferencesKey("orientation")
        val SCALE_MODE = stringPreferencesKey("scale_mode")
        val QUALITY = stringPreferencesKey("quality")
        val DITHER_MODE = stringPreferencesKey("dither_mode")
        val DIALECT = stringPreferencesKey("dialect_v2")
        val PHOTO_PER_PAGE = stringPreferencesKey("photo_per_page")
        val PHOTO_GAP = stringPreferencesKey("photo_gap")
        val PHOTO_SIZE = stringPreferencesKey("photo_size")
        val PHOTO_MARGIN = stringPreferencesKey("photo_margin")
        val PHOTO_POSITION = stringPreferencesKey("photo_position")
        val PHOTO_CUT_GUIDE = stringPreferencesKey("photo_cut_guide")
        val PHOTO_REPEAT = booleanPreferencesKey("photo_repeat_single")
        val PHOTO_AUTO_ROTATE = booleanPreferencesKey("photo_auto_rotate")
    }

    val defaultsFlow: Flow<StoredPrinterDefaults> = context.printerPrefsDataStore.data.map { prefs ->
        StoredPrinterDefaults(
            paperSize = prefs[Keys.PAPER_SIZE]?.let { runCatching { PaperSize.valueOf(it) }.getOrNull() }
                ?: PaperSize.A4,
            orientation = prefs[Keys.ORIENTATION]?.let { runCatching { PrintOrientation.valueOf(it) }.getOrNull() }
                ?: PrintOrientation.PORTRAIT,
            scaleMode = prefs[Keys.SCALE_MODE]?.let { runCatching { ScaleMode.valueOf(it) }.getOrNull() }
                ?: ScaleMode.FIT,
            quality = prefs[Keys.QUALITY]?.let { runCatching { PrintQuality.valueOf(it) }.getOrNull() }
                ?: PrintQuality.STANDARD_600,
            ditherMode = prefs[Keys.DITHER_MODE]?.let { runCatching { DitherMode.valueOf(it) }.getOrNull() }
                ?: DitherMode.HALFTONE_PHOTO,
            dialect = prefs[Keys.DIALECT]?.let { runCatching { QpdlDialect.valueOf(it) }.getOrNull() }
                ?: QpdlDialect.SCX3200_SPLIX_0X11,
            photoLayout = PhotoLayoutOptions(
                imagesPerPage = prefs[Keys.PHOTO_PER_PAGE]?.let { runCatching { ImagesPerPage.valueOf(it) }.getOrNull() }
                    ?: ImagesPerPage.ONE,
                gapPreset = prefs[Keys.PHOTO_GAP]?.let { runCatching { PhotoGapPreset.valueOf(it) }.getOrNull() }
                    ?: PhotoGapPreset.NORMAL,
                photoSize = prefs[Keys.PHOTO_SIZE]?.let { runCatching { PhotoSizePreset.valueOf(it) }.getOrNull() }
                    ?: PhotoSizePreset.AUTO,
                marginPreset = prefs[Keys.PHOTO_MARGIN]?.let { runCatching { PhotoMarginPreset.valueOf(it) }.getOrNull() }
                    ?: PhotoMarginPreset.NARROW,
                position = prefs[Keys.PHOTO_POSITION]?.let { runCatching { PhotoPosition.valueOf(it) }.getOrNull() }
                    ?: PhotoPosition.CENTER,
                repeatSinglePhoto = prefs[Keys.PHOTO_REPEAT] ?: true,
                autoRotateToCell = prefs[Keys.PHOTO_AUTO_ROTATE] ?: false,
                cutGuide = prefs[Keys.PHOTO_CUT_GUIDE]?.let { runCatching { PhotoCutGuide.valueOf(it) }.getOrNull() }
                    ?: PhotoCutGuide.NONE
            )
        )
    }

    suspend fun saveDefaults(
        paperSize: PaperSize,
        orientation: PrintOrientation,
        scaleMode: ScaleMode,
        quality: PrintQuality,
        ditherMode: DitherMode,
        dialect: QpdlDialect,
        photoLayout: PhotoLayoutOptions
    ) {
        context.printerPrefsDataStore.edit { prefs ->
            prefs[Keys.PAPER_SIZE] = paperSize.name
            prefs[Keys.ORIENTATION] = orientation.name
            prefs[Keys.SCALE_MODE] = scaleMode.name
            prefs[Keys.QUALITY] = quality.name
            prefs[Keys.DITHER_MODE] = ditherMode.name
            prefs[Keys.DIALECT] = dialect.name
            prefs[Keys.PHOTO_PER_PAGE] = photoLayout.imagesPerPage.name
            prefs[Keys.PHOTO_GAP] = photoLayout.gapPreset.name
            prefs[Keys.PHOTO_SIZE] = photoLayout.photoSize.name
            prefs[Keys.PHOTO_MARGIN] = photoLayout.marginPreset.name
            prefs[Keys.PHOTO_POSITION] = photoLayout.position.name
            prefs[Keys.PHOTO_CUT_GUIDE] = photoLayout.cutGuide.name
            prefs[Keys.PHOTO_REPEAT] = photoLayout.repeatSinglePhoto
            prefs[Keys.PHOTO_AUTO_ROTATE] = photoLayout.autoRotateToCell
        }
    }
}
