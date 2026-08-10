package com.olyphototagger.app.ui.settings

/**
 * Errors are deliberately NOT part of this state — see [SettingsViewModel.events] and
 * [com.olyphototagger.app.ui.workflow.WorkflowUiState]'s doc for why a one-shot
 * notification doesn't belong in a StateFlow-backed field.
 */
data class SettingsUiState(
    val dawarichBaseUrl: String = "",
    val dawarichApiToken: String = "",
    /** Whether a token is already saved — the field above never gets pre-filled with the
     *  real decrypted secret, so this is how the UI shows "already configured" without
     *  round-tripping it into view state unnecessarily. */
    val hasExistingToken: Boolean = false,
    val gapThresholdMinutes: String = "",
    val isSaving: Boolean = false,
    val saveMessage: String? = null
)
