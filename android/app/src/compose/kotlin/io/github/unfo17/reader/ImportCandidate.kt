package io.github.unfo17.reader

import android.net.Uri
import org.mewx.wenku8_flutter.BookZipSources

internal data class ImportCandidate(
    val uri: Uri, val name: String, val entry: BookZipSources.Entry? = null,
    val charset: String = "UTF-8", val archiveDigest: String? = null
) {
    val displayName get() = entry?.let { "$name · ${it.name}" } ?: name
}
