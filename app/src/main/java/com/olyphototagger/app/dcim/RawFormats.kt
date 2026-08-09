package com.olyphototagger.app.dcim

/**
 * RAW file extensions this app recognizes, each tagged with whether ExifTool documents
 * write support for it. Source: https://exiftool.org/#supported (verified live 2026-08-09
 * against the real format-support table — see the "R/W" vs "R" column — not assumed).
 *
 * The read-only ones are still recognized here so DCIM pairing groups them correctly
 * with their JPEG sibling; [isWritable] is the separate, narrower check the actual GPS
 * write path uses to decide whether to even attempt ExifTool against a file.
 *
 * This app is tested against Olympus ORF specifically, but is not limited to it — any
 * RAW format ExifTool can write to works the same way, since the write path
 * (GpsExifToolCommand/ExifToolInvoker) never hardcodes a format.
 */
object RawFormats {

    private val WRITABLE = setOf(
        "ARW", // Sony
        "CR2", // Canon
        "CR3", // Canon
        "DNG", // Adobe Digital Negative
        "ERF", // Epson
        "IIQ", // Phase One
        "MEF", // Mamiya
        "MRW", // Minolta
        "NEF", // Nikon
        "NRW", // Nikon
        "ORF", // Olympus — what this app is primarily tested against
        "ORI", // Olympus
        "PEF", // Pentax
        "RAF", // FujiFilm
        "RW2", // Panasonic
        "RWL", // Leica
        "SR2", // Sony
        "SRW", // Samsung
        "X3F", // Sigma/Foveon
    )

    private val READ_ONLY = setOf(
        "3FR", // Hasselblad
        "DCR", // Kodak
        "K25", // Kodak
        "KDC", // Kodak
        "SRF", // Sony
    )

    fun isRaw(extension: String): Boolean {
        val upper = extension.uppercase()
        return upper in WRITABLE || upper in READ_ONLY
    }

    fun isWritable(extension: String): Boolean = extension.uppercase() in WRITABLE
}
