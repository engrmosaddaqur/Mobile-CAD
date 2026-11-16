package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.cad.model.CadUnits
import com.example.cad.model.CanvasBackgroundTheme
import com.example.ui.theme.AppThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.cadPrefsDataStore by preferencesDataStore(name = "mobile_cad_prefs")

data class UserPreferences(
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val canvasTheme: CanvasBackgroundTheme = CanvasBackgroundTheme.CAD_BLACK,
    val defaultUnits: CadUnits = CadUnits.MILLIMETERS,
    val decimalPrecision: Int = 2,
    val autoSaveEnabled: Boolean = true,
    val autoSaveIntervalSec: Int = 20,
    val gridSpacing: Double = 10.0,
    val polarIncrementDeg: Double = 45.0,
    val showCrosshairCoords: Boolean = true
)

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val CANVAS_THEME = stringPreferencesKey("canvas_theme")
        val DEFAULT_UNITS = stringPreferencesKey("default_units")
        val PRECISION = intPreferencesKey("decimal_precision")
        val AUTOSAVE_ENABLED = booleanPreferencesKey("autosave_enabled")
        val AUTOSAVE_INTERVAL = intPreferencesKey("autosave_interval_sec")
        val GRID_SPACING = doublePreferencesKey("grid_spacing")
        val POLAR_INC = doublePreferencesKey("polar_increment_deg")
        val SHOW_COORDS = booleanPreferencesKey("show_crosshair_coords")
    }

    val preferencesFlow: Flow<UserPreferences> = context.cadPrefsDataStore.data.map { prefs ->
        UserPreferences(
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { AppThemeMode.valueOf(it) }.getOrNull() } ?: AppThemeMode.DARK,
            canvasTheme = prefs[Keys.CANVAS_THEME]?.let { runCatching { CanvasBackgroundTheme.valueOf(it) }.getOrNull() } ?: CanvasBackgroundTheme.CAD_BLACK,
            defaultUnits = prefs[Keys.DEFAULT_UNITS]?.let { runCatching { CadUnits.valueOf(it) }.getOrNull() } ?: CadUnits.MILLIMETERS,
            decimalPrecision = prefs[Keys.PRECISION] ?: 2,
            autoSaveEnabled = prefs[Keys.AUTOSAVE_ENABLED] ?: true,
            autoSaveIntervalSec = prefs[Keys.AUTOSAVE_INTERVAL] ?: 20,
            gridSpacing = prefs[Keys.GRID_SPACING] ?: 10.0,
            polarIncrementDeg = prefs[Keys.POLAR_INC] ?: 45.0,
            showCrosshairCoords = prefs[Keys.SHOW_COORDS] ?: true
        )
    }

    suspend fun updateThemeMode(mode: AppThemeMode) {
        context.cadPrefsDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun updateCanvasTheme(theme: CanvasBackgroundTheme) {
        context.cadPrefsDataStore.edit { it[Keys.CANVAS_THEME] = theme.name }
    }

    suspend fun updateDefaultUnits(units: CadUnits) {
        context.cadPrefsDataStore.edit { it[Keys.DEFAULT_UNITS] = units.name }
    }

    suspend fun updateDecimalPrecision(precision: Int) {
        context.cadPrefsDataStore.edit { it[Keys.PRECISION] = precision.coerceIn(0, 4) }
    }

    suspend fun updateAutoSave(enabled: Boolean, intervalSec: Int) {
        context.cadPrefsDataStore.edit {
            it[Keys.AUTOSAVE_ENABLED] = enabled
            it[Keys.AUTOSAVE_INTERVAL] = intervalSec.coerceIn(5, 120)
        }
    }

    suspend fun updateGridSpacing(spacing: Double) {
        context.cadPrefsDataStore.edit { it[Keys.GRID_SPACING] = spacing.coerceIn(0.5, 500.0) }
    }

    suspend fun updatePolarIncrement(deg: Double) {
        context.cadPrefsDataStore.edit { it[Keys.POLAR_INC] = deg }
    }
}
