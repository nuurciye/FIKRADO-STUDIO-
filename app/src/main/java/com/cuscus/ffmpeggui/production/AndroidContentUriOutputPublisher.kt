package com.cuscus.ffmpeggui.production

import android.content.ContentResolver
import android.net.Uri
import com.cuscus.ffmpeggui.core.production.render.FfmpegProductionOutputPublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Copies a completed private FFmpeg export into the exact URI selected by the
 * user (normally a Storage Access Framework document URI).
 *
 * Stream I/O runs on Dispatchers.IO and checks coroutine cancellation between
 * chunks so a cancelled render does not continue copying a large output file.
 */
class AndroidContentUriOutputPublisher(
    private val contentResolver: ContentResolver,
) : FfmpegProductionOutputPublisher {

    override suspend fun publish(localOutputPath: String, destinationUri: String): String =
        withContext(Dispatchers.IO) {
            val source = File(localOutputPath)
            if (!source.isFile || !source.canRead() || source.length() <= 0L) {
                throw IOException("FFmpeg output is missing, unreadable, or empty.")
            }

            val destination = Uri.parse(destinationUri)
            if (destination.scheme != ContentResolver.SCHEME_CONTENT ||
                destination.authority.isNullOrBlank()
            ) {
                throw IOException("The selected output destination must be a valid writable content URI.")
            }

            val expectedBytes = source.length()
            var copied = 0L
            contentResolver.openOutputStream(destination, "wt")?.use { output ->
                source.inputStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                    }
                    output.flush()
                }
            } ?: throw IOException("Android could not open the selected output destination.")

            if (copied != expectedBytes || source.length() != expectedBytes) {
                throw IOException(
                    "Output copy was incomplete or the source changed: copied $copied of $expectedBytes bytes."
                )
            }
            destination.toString()
        }
}
