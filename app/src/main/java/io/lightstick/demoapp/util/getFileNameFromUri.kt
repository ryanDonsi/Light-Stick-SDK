// File: io/lightstick/demoapp/util/FileUtils.kt
package io.lightstick.demoapp.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

fun getFileNameFromUri(context: Context, uri: Uri): String? {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst()) {
            return cursor.getString(nameIndex)
        }
    }
    return null
}
