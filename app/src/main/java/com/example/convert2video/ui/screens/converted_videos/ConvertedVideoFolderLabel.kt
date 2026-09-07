package com.example.convert2video.ui.screens.converted_videos

import android.net.Uri
import com.example.convert2video.data.ConvertedVideo
import com.example.convert2video.video.C2vOutputNames
import java.io.File

/**
 * Wrapper over the file mapping using [ConvertedVideo.file].
 *
 * @see convertedVideoFolderLabel
 */
internal fun convertedVideoFolderLabel(video: ConvertedVideo): String =
    convertedVideoFolderLabel(video.file)

/**
 * Folder label for a converted-video list row.
 *
 * Mapping: [file] non-null → [C2vOutputNames.C2V_FOLDER];
 * null → [C2vOutputNames.LEGACY_FOLDER].
 *
 * MediaStore-indexed videos (including those under [C2vOutputNames.C2V_FOLDER]) currently
 * have `file == null` because `loadLegacyMediaStoreVideos` sets file=null for both C2V and
 * LEGACY paths; therefore this label is "app-owned file vs MediaStore-only" not a perfect
 * path parser. Do not change ConvertedVideoRepository to "fix" labeling.
 */
internal fun convertedVideoFolderLabel(file: File?): String =
    if (file != null) C2vOutputNames.C2V_FOLDER else C2vOutputNames.LEGACY_FOLDER

/**
 * Stable testTag token for a converted-video row.
 *
 * Prefers [Uri.lastPathSegment] when non-null/non-blank. Otherwise uses an unsigned
 * hash of the URI string so the token never contains `://`. Do not use
 * `uri.toString()` as a testTag key.
 */
internal fun convertedVideoRowId(uri: Uri): String {
    val segment = uri.lastPathSegment?.trim()
    if (!segment.isNullOrBlank()) return segment
    return uri.toString().hashCode().toUInt().toString()
}
