package com.example.cad.engine

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.cad.io.SampleDrawings
import com.example.cad.model.CadUnits
import com.example.data.CadDatabase
import com.example.data.CadFileRepository
import com.example.data.CadImportOutcome
import com.example.data.RecentDrawingEntity
import com.example.data.UserPreferences
import com.example.data.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val db = CadDatabase.getInstance(application)
    private val fileRepo = CadFileRepository(application, db.recentDrawingDao())
    private val prefsRepo = UserPreferencesRepository(application)

    val recentDrawings: StateFlow<List<RecentDrawingEntity>> = fileRepo.recentDrawings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val preferences: StateFlow<UserPreferences> = prefsRepo.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())

    private val _pendingRecovery = MutableStateFlow<RecentDrawingEntity?>(null)
    val pendingRecovery: StateFlow<RecentDrawingEntity?> = _pendingRecovery.asStateFlow()

    private val _importError = MutableStateFlow<Pair<String, String>?>(null)
    val importError: StateFlow<Pair<String, String>?> = _importError.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    init {
        viewModelScope.launch {
            fileRepo.seedInitialSamplesIfEmpty()
            _pendingRecovery.value = fileRepo.getPendingRecovery()
        }
    }

    fun refreshRecoveryStatus() {
        viewModelScope.launch {
            _pendingRecovery.value = fileRepo.getPendingRecovery()
        }
    }

    fun discardRecovery(id: String) {
        viewModelScope.launch {
            fileRepo.discardRecovery(id)
            _pendingRecovery.value = null
        }
    }

    fun createNewDrawing(
        name: String,
        projectGroup: String,
        units: CadUnits,
        onCreated: (String) -> Unit
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val doc = SampleDrawings.blankDrawing(
                    name = name.trim().ifEmpty { "Drawing_${System.currentTimeMillis() % 1000}" },
                    projectGroup = projectGroup.trim().ifEmpty { "Default Project" },
                    units = units
                )
                val saved = fileRepo.saveDrawingInternal(doc, clearRecovery = true)
                onCreated(saved.id)
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun importFile(uri: Uri, onImported: (String) -> Unit) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                when (val res = fileRepo.importFromUri(uri)) {
                    is CadImportOutcome.Success -> onImported(res.document.id)
                    is CadImportOutcome.Error -> _importError.value = res.title to res.message
                }
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun renameDrawing(id: String, newName: String) {
        viewModelScope.launch {
            if (newName.isNotBlank()) fileRepo.renameDrawing(id, newName)
        }
    }

    fun deleteDrawing(id: String) {
        viewModelScope.launch { fileRepo.deleteDrawing(id) }
    }

    fun dismissImportError() {
        _importError.value = null
    }
}
