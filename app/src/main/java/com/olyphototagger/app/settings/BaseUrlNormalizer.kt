package com.olyphototagger.app.settings

/** Turns whatever a user types into a settings field into a usable base URL. */
object BaseUrlNormalizer {
    fun normalize(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }
}
