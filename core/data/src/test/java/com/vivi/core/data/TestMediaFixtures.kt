package com.vivi.core.data

import android.net.TestUri
import android.net.Uri
import com.vivi.core.data.model.MediaKey
import com.vivi.core.data.model.MediaSource
import com.vivi.core.data.model.VideoMedia

internal fun fakeUri(text: String): Uri = TestUri(text)

internal fun video(
    name: String,
    path: String = "/storage/emulated/0/Movies/$name",
    durationMicros: Long = 60L * 60_000_000L,
    sizeBytes: Long = 500L * 1024 * 1024,
    width: Int = 1920,
    height: Int = 1080,
    dateAddedMillis: Long = 1_000L,
    folderPath: String? = path.substringBeforeLast('/'),
): VideoMedia = VideoMedia(
    key = MediaKey.ofPath(path),
    uri = fakeUri(path),
    displayName = name,
    path = path,
    folderPath = folderPath,
    durationMicros = durationMicros,
    sizeBytes = sizeBytes,
    width = width,
    height = height,
    dateAddedMillis = dateAddedMillis,
    lastModifiedMillis = dateAddedMillis,
    source = MediaSource.FILE_SYSTEM,
)
