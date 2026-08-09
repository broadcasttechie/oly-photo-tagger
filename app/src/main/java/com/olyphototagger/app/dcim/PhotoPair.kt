package com.olyphototagger.app.dcim

/** A RAW+JPEG unit that must be tagged together with identical GPS data. */
data class PhotoPair(
    val folderName: String,
    val baseName: String,
    val jpeg: CameraFile?,
    val raw: CameraFile?
) {
    init {
        require(jpeg != null || raw != null) { "PhotoPair needs at least one file" }
    }

    val isComplete: Boolean get() = jpeg != null && raw != null
}
