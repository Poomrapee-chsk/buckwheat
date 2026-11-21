package com.danilkinkin.buckwheat.slip.watch

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

data class SlipFolder(
    val bucketId: Long,
    val name: String,
    val relativePath: String,
    val imageCount: Int,
    /** Looks like a bank app's folder */
    val suggested: Boolean,
)

data class SlipImage(
    val mediaId: Long,
    val uri: Uri,
    /** Seconds, as in MediaStore.DATE_ADDED */
    val dateAdded: Long,
)

/** Finds slip images in the phone's photo library (MediaStore). */
object SlipMedia {
    val imagesUri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    val readPermission: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    /**
     * Full photo access. With Android 14+ "Select photos" only, new images in folders
     * stay invisible to the app, so watching can't work.
     */
    fun hasAccess(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, readPermission) == PackageManager.PERMISSION_GRANTED

    fun isSuggested(name: String, relativePath: String) = SlipFolderHints.isBankFolder(name, relativePath)

    fun listFolders(context: Context): List<SlipFolder> {
        if (!hasAccess(context)) return emptyList()

        data class Acc(val name: String, val path: String, var count: Int)

        val folders = linkedMapOf<Long, Acc>()
        val projection = arrayOf(
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        context.contentResolver.query(
            imagesUri, projection, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                folders.getOrPut(id) {
                    Acc(cursor.getString(nameColumn).orEmpty(), cursor.getString(pathColumn).orEmpty(), 0)
                }.count += 1
            }
        }

        return folders
            .map { (id, acc) ->
                SlipFolder(id, acc.name, acc.path, acc.count, isSuggested(acc.name, acc.path))
            }
            // Bank folders first, then most recently used (insertion order)
            .sortedByDescending { it.suggested }
    }

    /**
     * When an image was taken or saved, from the photo library. Bank apps save a slip at payment time,
     * so it fills in a day that OCR lost. Null when the image's source doesn't say.
     */
    fun imageDate(context: Context, uri: Uri): java.time.LocalDateTime? {
        val toLocal = { millis: Long ->
            java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneId.systemDefault())
        }
        // Picker URIs only have some columns: ask for each separately
        listOf(MediaStore.MediaColumns.DATE_TAKEN to 1L, MediaStore.MediaColumns.DATE_ADDED to 1000L).forEach { (column, scale) ->
            try {
                context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) {
                        val value = cursor.getLong(0)
                        if (value > 0) return toLocal(value * scale)
                    }
                }
            } catch (e: Exception) {
                // Column not supported by this URI
            }
        }
        return null
    }

    /** Images in [bucketIds] added after [addedAfterSeconds], oldest first */
    fun queryImages(context: Context, bucketIds: Collection<Long>, addedAfterSeconds: Long): List<SlipImage> {
        if (bucketIds.isEmpty() || !hasAccess(context)) return emptyList()

        val images = mutableListOf<SlipImage>()
        val selection = "${MediaStore.Images.Media.BUCKET_ID} IN (${bucketIds.joinToString(",") { "?" }}) " +
                "AND ${MediaStore.Images.Media.DATE_ADDED} > ?"
        val args = (bucketIds.map { it.toString() } + addedAfterSeconds.toString()).toTypedArray()

        context.contentResolver.query(
            imagesUri,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED),
            selection,
            args,
            "${MediaStore.Images.Media.DATE_ADDED} ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                images += SlipImage(id, ContentUris.withAppendedId(imagesUri, id), cursor.getLong(dateColumn))
            }
        }

        return images
    }
}
