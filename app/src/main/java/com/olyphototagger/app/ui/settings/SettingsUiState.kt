package com.olyphototagger.app.ui.settings

/**
 * Errors are deliberately NOT part of this state — see [SettingsViewModel.events] and
 * [com.olyphototagger.app.ui.workflow.WorkflowUiState]'s doc for why a one-shot
 * notification doesn't belong in a StateFlow-backed field.
 */
data class SettingsUiState(
    val gapThresholdMinutes: String = "",
    val isSaving: Boolean = false,
    val saveMessage: String? = null
)
