/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.arturo254.opentune.playback

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import com.arturo254.opentune.db.MusicDatabase
import com.arturo254.opentune.di.DownloadCache
import com.arturo254.opentune.utils.reportException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PublicStorageExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    @DownloadCache private val downloadCache: Cache,
) {

    companion object {
        const val PUBLIC_SUBDIRECTORY = "Download/OpenTune"
        private val INVALID_FILENAME_CHARS = Regex("[\\\\/:*?\"<>|]")

        fun sanitizeFileName(name: String): String {
            return name.replace(INVALID_FILENAME_CHARS, "_").trim().ifEmpty { "unnamed" }
        }

        fun resolveMimeAndExtension(mimeType: String?, codecs: String?): Pair<String, String> {
            val lowerMime = mimeType?.lowercase().orEmpty()
            val lowerCodecs = codecs?.lowercase().orEmpty()

            return when {
                lowerMime.contains("webm") || lowerCodecs.contains("opus") -> {
                    "audio/opus" to "opus"
                }
                lowerMime.contains("mp4") || lowerMime.contains("m4a") || lowerCodecs.contains("mp4a") -> {
                    "audio/mp4" to "m4a"
                }
                lowerMime.contains("ogg") -> {
                    "audio/ogg" to "ogg"
                }
                lowerMime.contains("mpeg") || lowerMime.contains("mp3") -> {
                    "audio/mpeg" to "mp3"
                }
                else -> {
                    "audio/mp4" to "m4a"
                }
            }
        }
    }

    /**
     * Exports a cached song from [downloadCache] to Android's public Downloads directory
     * at "Internal storage/Download/OpenTune/<Title> - <Artist>.<ext>".
     *
     * Uses MediaStore.Downloads on Android 10+ (API 29+) with Scoped Storage.
     * Uses Environment.getExternalStoragePublicDirectory on legacy Android (API < 29).
     */
    suspend fun exportSong(songId: String): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val song = database.song(songId).firstOrNull() ?: error("Song $songId not found in database")
            val format = database.format(songId).firstOrNull()

            val title = sanitizeFileName(song.song.title)
            val artist = sanitizeFileName(song.artists.joinToString(", ") { it.name }.ifBlank { "Unknown Artist" })
            val (mimeType, extension) = resolveMimeAndExtension(format?.mimeType, format?.codecs)
            val fileName = "$title - $artist.$extension"

            val cacheDataSource = CacheDataSource.Factory()
                .setCache(downloadCache)
                .setUpstreamDataSourceFactory(null)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()

            val dataSpec = DataSpec.Builder()
                .setUri(songId.toUri())
                .setCustomCacheKey(songId)
                .build()

            // Open stream from downloadCache
            try {
                cacheDataSource.open(dataSpec)
            } catch (e: Exception) {
                throw IllegalStateException("Song $songId is not cached in downloadCache", e)
            }

            var outputUri: Uri? = null
            var outputStream: OutputStream? = null

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(MediaStore.Downloads.RELATIVE_PATH, PUBLIC_SUBDIRECTORY)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }

                    val resolver = context.contentResolver
                    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    outputUri = resolver.insert(collection, contentValues)
                        ?: error("Failed to insert MediaStore record for $fileName")

                    outputStream = resolver.openOutputStream(outputUri)
                        ?: error("Failed to open output stream for $outputUri")

                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = cacheDataSource.read(buffer, 0, buffer.size)
                        if (read == -1) break
                        outputStream.write(buffer, 0, read)
                    }
                    outputStream.flush()

                    contentValues.clear()
                    contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(outputUri, contentValues, null, null)
                } else {
                    @Suppress("DEPRECATION")
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val targetDir = File(downloadsDir, "OpenTune").apply { if (!exists()) mkdirs() }
                    val targetFile = File(targetDir, fileName)

                    outputStream = FileOutputStream(targetFile)
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = cacheDataSource.read(buffer, 0, buffer.size)
                        if (read == -1) break
                        outputStream.write(buffer, 0, read)
                    }
                    outputStream.flush()
                    outputUri = Uri.fromFile(targetFile)

                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(targetFile.absolutePath),
                        arrayOf(mimeType),
                        null
                    )
                }
            } catch (e: Exception) {
                // Cleanup partial file on error if MediaStore URI was created
                outputUri?.let { uri ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        runCatching { context.contentResolver.delete(uri, null, null) }
                    }
                }
                throw e
            } finally {
                outputStream?.close()
                cacheDataSource.close()
            }

            outputUri ?: error("Export produced null URI")
        }
    }
}
