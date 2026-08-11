package com.olyphototagger.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.olyphototagger.app.ui.theme.OlyPhotoTaggerTheme

/**
 * Shown once, before anything else, until acknowledged — see
 * SettingsRepository.hasAcknowledgedDisclaimer. Deliberately has no dismiss button and a
 * no-op onDismissRequest (blocks the back button and tapping outside too): this is a
 * warranty/responsibility notice, not a routine confirmation, so it shouldn't be
 * skippable by accident.
 */
@Composable
fun DisclaimerDialog(onAcknowledge: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Before you start") },
        text = {
            Text(
                "Oly Photo Tagger writes GPS data directly into your photo files. It's built " +
                    "with real safeguards — a dry-run preview before anything is written, " +
                    "crash-safe writes that never remove an original until its replacement is " +
                    "verified, and automatic recovery if the app is ever interrupted mid-write.\n\n" +
                    "Even so, this software comes with no warranty. You're responsible for " +
                    "keeping your own backups of anything irreplaceable, and no responsibility " +
                    "is accepted for any data loss."
            )
        },
        confirmButton = {
            TextButton(onClick = onAcknowledge) { Text("I Understand") }
        }
    )
}

@Preview(showBackground = true)
@Composable
private fun DisclaimerDialogPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        DisclaimerDialog(onAcknowledge = {})
    }
}
