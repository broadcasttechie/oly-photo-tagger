package com.olyphototagger.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
            val config = settingsRepository.dawarichConfig.first()
            val gapMinutes = settingsRepository.gapThresholdMinutes.first()
            _uiState.update {
                it.copy(
                    dawarichBaseUrl = config?.baseUrl.orEmpty(),
                    hasExistingToken = config != null,
                    gapThresholdMinutes = gapMinutes.toString()
                )
            }
        }
    }

    fun setBaseUrl(value: String) {
        _uiState.update { it.copy(dawarichBaseUrl = value, saveMessage = null) }
    }

    fun setApiToken(value: String) {
        _uiState.update { it.copy(dawarichApiToken = value, saveMessage = null) }
    }

    fun setGapThresholdMinutes(value: String) {
        _uiState.update { it.copy(gapThresholdMinutes = value, saveMessage = null) }
    }

    /**
     * The token field is never pre-filled with an existing secret (see
     * [SettingsUiState.hasExistingToken]), so changing the base URL alone — without
     * re-entering the token — isn't supported; both are saved together or neither is.
     * This is the deliberate tradeoff for never holding the decrypted token in view
     * state longer than a single save action.
     */
    fun save() {
        val state = _uiState.value
        val minutes = state.gapThresholdMinutes.toIntOrNull()
        if (minutes == null || minutes <= 0) {
            _events.tryEmit("Gap threshold must be a positive number of minutes.")
            return
        }

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
                settingsRepository.saveGapThresholdMinutes(minutes)
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        saveMessage = "Settings saved",
                        dawarichApiToken = "",
                        hasExistingToken = it.hasExistingToken || state.dawarichApiToken.isNotBlank()
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false) }
                _events.tryEmit("Could not save: ${e.message}")
            }
        }
    }
}
