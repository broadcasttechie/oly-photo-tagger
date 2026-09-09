package com.olyphototagger.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.olyphototagger.app.cache.AppDatabase
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

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(getApplication())

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // See GeotagWorkflowViewModel.events' doc for why errors are a SharedFlow, not state.
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            val gapMinutes = settingsRepository.gapThresholdMinutes.first()
            val recentHours = settingsRepository.dawarichCacheRecentHours.first()
            _uiState.update {
                it.copy(gapThresholdMinutes = gapMinutes.toString(), dawarichCacheRecentHours = recentHours.toString())
            }
        }
    }

    fun setGapThresholdMinutes(value: String) {
        _uiState.update { it.copy(gapThresholdMinutes = value, saveMessage = null) }
    }

    fun setDawarichCacheRecentHours(value: String) {
        _uiState.update { it.copy(dawarichCacheRecentHours = value, saveMessage = null) }
    }

    fun save() {
        val minutes = _uiState.value.gapThresholdMinutes.toIntOrNull()
        if (minutes == null || minutes <= 0) {
            _events.tryEmit("Gap threshold must be a positive number of minutes.")
            return
        }
        val recentHours = _uiState.value.dawarichCacheRecentHours.toIntOrNull()
        if (recentHours == null || recentHours < 0) {
            _events.tryEmit("Recent-hours safeguard must be zero or a positive number of hours.")
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveMessage = null) }
            try {
                settingsRepository.saveGapThresholdMinutes(minutes)
                settingsRepository.saveDawarichCacheRecentHours(recentHours)
                _uiState.update { it.copy(isSaving = false, saveMessage = "Settings saved") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false) }
                _events.tryEmit("Could not save: ${e.message}")
            }
        }
    }

    /** Manual escape hatch for [com.olyphototagger.app.dawarich.CachingDawarichSource]'s
     *  cache — there's no automatic invalidation (Dawarich history for a past range is
     *  never expected to change), so this is the only way to force a fresh fetch, e.g.
     *  after backfilling older location data into Dawarich itself. */
    fun clearGpsCache() {
        viewModelScope.launch {
            AppDatabase.getInstance(getApplication()).dawarichCacheDao().clear()
            _events.tryEmit("Cached GPS data cleared")
        }
    }
}
