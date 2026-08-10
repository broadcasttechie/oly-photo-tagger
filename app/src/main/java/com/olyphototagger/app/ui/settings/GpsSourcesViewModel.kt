package com.olyphototagger.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.olyphototagger.app.cache.AppDatabase
import com.olyphototagger.app.cache.GpxImportedFileEntity
import com.olyphototagger.app.settings.GpsSourceType
import com.olyphototagger.app.settings.SettingsRepository
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

class GpsSourcesViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(getApplication())
    private val gpxTrackDao = AppDatabase.getInstance(getApplication()).gpxTrackDao()

    private val _uiState = MutableStateFlow(GpsSourcesUiState())
    val uiState: StateFlow<GpsSourcesUiState> = _uiState.asStateFlow()

    // See GeotagWorkflowViewModel.events' doc for why errors are a SharedFlow, not state.
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            val config = settingsRepository.dawarichConfig.first()
            val active = settingsRepository.activeGpsSource.first()
            _uiState.update {
                it.copy(
                    activeSource = active,
                    dawarichBaseUrl = config?.baseUrl.orEmpty(),
                    hasExistingDawarichToken = config != null
                )
            }
        }
        viewModelScope.launch {
            gpxTrackDao.observeImportedFiles().collect { files ->
                _uiState.update { it.copy(importedGpxFiles = files.map(GpxImportedFileEntity::toUiState)) }
            }
        }
    }

    fun selectSource(type: GpsSourceType) {
        _uiState.update { it.copy(activeSource = type) }
        viewModelScope.launch { settingsRepository.saveActiveGpsSource(type) }
    }

    fun setBaseUrl(value: String) {
        _uiState.update { it.copy(dawarichBaseUrl = value, saveMessage = null) }
    }

    fun setApiToken(value: String) {
        _uiState.update { it.copy(dawarichApiToken = value, saveMessage = null) }
    }

    /**
     * The token field is never pre-filled with an existing secret (see
     * [GpsSourcesUiState.hasExistingDawarichToken]), so changing the base URL alone —
     * without re-entering the token — isn't supported; both are saved together or
     * neither is. Same tradeoff the old SettingsViewModel made before this screen split.
     */
    fun saveDawarichConfig() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveMessage = null) }
            try {
                if (state.dawarichApiToken.isNotBlank()) {
                    if (state.dawarichBaseUrl.isBlank()) {
                        _uiState.update { it.copy(isSaving = false) }
                        _events.tryEmit("Enter the Dawarich base URL too.")
                        return@launch
                    }
                    settingsRepository.saveDawarichConfig(state.dawarichBaseUrl, state.dawarichApiToken)
                }
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        saveMessage = "Dawarich settings saved",
                        dawarichApiToken = "",
                        hasExistingDawarichToken = it.hasExistingDawarichToken || state.dawarichApiToken.isNotBlank()
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false) }
                _events.tryEmit("Could not save: ${e.message}")
            }
        }
    }

    fun deleteGpxFile(id: Long) {
        viewModelScope.launch {
            try {
                gpxTrackDao.deleteFile(id)
            } catch (e: Exception) {
                _events.tryEmit("Could not remove file: ${e.message}")
            }
        }
    }

    // The file picker itself lands in the next chunk — this just makes the entry point
    // visible and gives the user a clear signal it isn't wired up yet, rather than a
    // button that silently does nothing.
    fun onImportGpxFileRequested() {
        _events.tryEmit("GPX import is coming in the next update.")
    }
}

private fun GpxImportedFileEntity.toUiState() = GpxFileUiState(
    id = id,
    displayName = displayName,
    pointCount = pointCount,
    earliest = Instant.ofEpochSecond(earliestPointEpochSeconds),
    latest = Instant.ofEpochSecond(latestPointEpochSeconds)
)
