package com.olyphototagger.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olyphototagger.app.ui.AppNavigation
import com.olyphototagger.app.ui.theme.OlyPhotoTaggerTheme

class MainActivity : ComponentActivity() {

    private var pendingShareUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingShareUri = extractSharedGpxUri(intent)
        enableEdgeToEdge()
        setContent {
            OlyPhotoTaggerTheme {
                AppNavigation(
                    pendingShareUri = pendingShareUri,
                    onPendingShareConsumed = { pendingShareUri = null }
                )
            }
        }
    }

    // The app is already running (e.g. backgrounded) and receives a new share — onCreate
    // won't fire again for that, only this.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractSharedGpxUri(intent)?.let { pendingShareUri = it }
    }

    /**
     * GPSLogger for Android — and presumably other logger apps — fire
     * ACTION_SEND_MULTIPLE even when sharing a single file (confirmed by reading its
     * source: GpsMainActivity's file-share dialog always calls
     * `intent.setAction(Intent.ACTION_SEND_MULTIPLE)`, never plain ACTION_SEND, for its
     * file-sharing path). Handling only ACTION_SEND would silently never match real
     * share traffic. Only the first shared file is offered for import — the
     * confirmation dialog this feeds is a single-file "import this?" prompt, not a
     * batch-import UI.
     */
    private fun extractSharedGpxUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_SEND -> intent.parcelableExtra(Intent.EXTRA_STREAM)
        Intent.ACTION_SEND_MULTIPLE -> intent.parcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.firstOrNull()
        else -> null
    }

    private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(name: String): T? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(name, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }

    private inline fun <reified T : android.os.Parcelable> Intent.parcelableArrayListExtra(name: String): ArrayList<T>? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableArrayListExtra(name, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableArrayListExtra(name)
        }
}
