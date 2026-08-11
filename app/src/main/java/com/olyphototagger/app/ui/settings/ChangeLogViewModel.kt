package com.olyphototagger.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.olyphototagger.app.cache.AppDatabase
import com.olyphototagger.app.cache.WriteLogEntity
import com.olyphototagger.app.cache.WriteLogResultType
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

class ChangeLogViewModel(application: Application) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>()
    private val writeLogDao = AppDatabase.getInstance(context).writeLogDao()

    private val _uiState = MutableStateFlow(ChangeLogUiState())
    val uiState: StateFlow<ChangeLogUiState> = _uiState.asStateFlow()

    // See GeotagWorkflowViewModel.events' doc for why errors are a SharedFlow, not state.
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            writeLogDao.observeAll().collect { entries ->
                _uiState.update { it.copy(entries = entries.map(WriteLogEntity::toUiState)) }
            }
        }
    }

    fun requestClearLog() {
        _uiState.update { it.copy(pendingClearConfirmation = true) }
    }

    fun cancelClearLog() {
        _uiState.update { it.copy(pendingClearConfirmation = false) }
    }

    fun confirmClearLog() {
        _uiState.update { it.copy(pendingClearConfirmation = false) }
        viewModelScope.launch {
            try {
                writeLogDao.clear()
            } catch (e: Exception) {
                _events.tryEmit("Could not clear the log: ${e.message}")
            }
        }
    }
}

private fun WriteLogEntity.toUiState() = WriteLogEntryUiState(
    id = id,
    loggedAt = Instant.ofEpochMilli(loggedAtEpochMillis),
    folderName = folderName,
    displayName = displayName,
    resultType = WriteLogResultType.valueOf(resultType),
    previousLatLong = if (previousLatitude != null && previousLongitude != null) previousLatitude to previousLongitude else null,
    newLatLong = if (newLatitude != null && newLongitude != null) newLatitude to newLongitude else null,
    newAltitudeMeters = newAltitudeMeters,
    detail = detail
)
