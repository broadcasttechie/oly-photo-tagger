package com.olyphototagger.app.ui.settings

import com.olyphototagger.app.settings.GpsSourceType
import java.time.Instant

/** One row in the imported-GPX-files list — a view-layer projection of [com.olyphototagger.app.cache.GpxImportedFileEntity]. */
data class GpxFileUiState(
    val id: Long,
    val displayName: String,
    val pointCount: Int,
    val earliest: Instant,
    val latest: Instant
)

/**
 * Errors are deliberately NOT part of this state — see [GpsSourcesViewModel.events] and
 * [com.olyphototagger.app.ui.workflow.WorkflowUiState]'s doc for why a one-shot
 * notification doesn't belong in a StateFlow-backed field.
 */
data class GpsSourcesUiState(
    val activeSource: GpsSourceType? = null,
    val dawarichBaseUrl: String = "",
    val dawarichApiToken: String = "",
    /** Whether a token is already saved — the field above never gets pre-filled with the
     *  real decrypted secret, so this is how the UI shows "already configured" without
     *  round-tripping it into view state unnecessarily. */
    val hasExistingDawarichToken: Boolean = false,
    val importedGpxFiles: List<GpxFileUiState> = emptyList(),
    val isSaving: Boolean = false,
    val saveMessage: String? = null
)
