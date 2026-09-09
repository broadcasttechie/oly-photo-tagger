package com.olyphototagger.app.ui.workflow

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.olyphototagger.app.dcim.PhotoPair
import com.olyphototagger.app.image.ThumbnailImageLoader
import com.olyphototagger.app.pipeline.ScanResult

/**
 * The pair's JPEG, downsampled by Coil — a RAW file alone (Olympus .ORF) isn't a format
 * Android's own decoders understand, so a JPEG-less pair falls back to a plain icon
 * rather than asking Coil to load something that can only fail.
 *
 * Shared by [DryRunScreen] (every row in the dry-run list) and [ProgressScreen] (the pair
 * currently being written) — the same [ThumbnailImageLoader] singleton backs both, so a
 * photo already reviewed in the dry-run list is normally already cached by the time its
 * write shows up here, no new decode needed. [size]/[modifier] are the caller's own
 * spacing/sizing concern (a 12dp end-padding row icon here, a larger centered one there);
 * this composable only owns the image itself.
 */
@Composable
fun PhotoThumbnail(scanResult: ScanResult, pair: PhotoPair, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val jpeg = pair.jpeg
    if (jpeg == null) {
        Box(modifier.size(size), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.PhotoCamera,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size / 2)
            )
        }
        return
    }
    val context = LocalContext.current
    AsyncImage(
        model = remember(jpeg) { scanResult.resolve(jpeg)?.uri },
        contentDescription = null,
        imageLoader = ThumbnailImageLoader.get(context),
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(RoundedCornerShape(size / 10))
    )
}
