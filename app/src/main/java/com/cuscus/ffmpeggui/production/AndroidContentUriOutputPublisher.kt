package com.cuscus.ffmpeggui.production

import android.content.ContentResolver
import android.net.Uri
import com.cuscus.ffmpeggui.core.production.render.FfmpegProductionOutputPublisher
import java.io.File
import java.io.IOException

/**
 * Copies a completed private FFmpeg export into the exact URI selected by the
 * user (normally a Storage Access Framework document URI).
 */
class AndroidContentUriOutputPublisher(
    private val contentResolver: ContentResolver,
) : FfmpegProductionOutputPublisher {

    override suspend fun publish(localOutputPath: String, destinationUri: String): String {
        val source = File(localOutputPath)
        if (!source.isFile || !source.canRead() || source.length() <= 0L) {
            throw IOException("FFmpeg output is missing, unreadable, or empty.")
        }

        val destination = Uri.parse(destinationUri)
        if (destination.scheme != ContentResolver.SCHEME_CONTENT) {
            throw IOException("The selected output destination must be a writable content URI.")
        }

        val copied = contentResolver.openOutputStream(destination, "wt")?.use { output ->
            source.inputStream().buffered().use { input ->
                input.copyTo(output)
            }
        } ?: throw IOException("Android could not open the selected output destination.")

        if (copied != source.length()) {
            throw IOException("Output copy was incomplete: copied $copied of ${source.length()} bytes.")
        }
        return destination.toString()
    }
}
