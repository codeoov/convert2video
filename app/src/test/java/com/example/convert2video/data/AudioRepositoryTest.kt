package com.example.convert2video.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AudioRepositoryTest {

    @After
    fun tearDown() {
        ShadowContentResolver.reset()
        AppDatabase.clearInstance()
    }

    @Test
    fun success_api29QueryPlan_usesFilesCollectionAndExactProjection() {
        // Given
        val sdkInt = Build.VERSION_CODES.Q

        // When
        val plan = audioMediaStoreQueryPlan(sdkInt)

        // Then
        assertEquals(AudioMediaStoreCollection.FilesExternal, plan.collection)
        assertEquals(
            listOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
            ),
            plan.projection,
        )
    }

    @Test
    fun success_preQQueryPlan_usesAudioMediaCollectionAndExactProjection() {
        // Given
        val sdkInt = Build.VERSION_CODES.P

        // When
        val plan = audioMediaStoreQueryPlan(sdkInt)

        // Then
        assertEquals(AudioMediaStoreCollection.AudioExternal, plan.collection)
        assertEquals(
            listOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DISPLAY_NAME,
            ),
            plan.projection,
        )
        assertFalse(MediaStore.MediaColumns.RELATIVE_PATH in plan.projection)
    }

    @Test
    fun success_api29QueryPlan_selectsAudioAndExcludesPendingRows() {
        // Given
        val plan = audioMediaStoreQueryPlan(Build.VERSION_CODES.Q)

        // When
        val selection = plan.selection
        val selectionArgs = plan.selectionArgs

        // Then
        assertEquals(
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? AND " +
                "${MediaStore.MediaColumns.IS_PENDING} = 0",
            selection,
        )
        assertEquals(
            listOf(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString()),
            selectionArgs,
        )
    }

    @Test
    fun success_preQQueryPlan_hasNoModernSelectionPredicates() {
        // Given / When
        val plan = audioMediaStoreQueryPlan(Build.VERSION_CODES.P)

        // Then
        assertNull(plan.selection)
        assertNull(plan.selectionArgs)
    }

    @Test
    fun success_api29RowUri_usesFilesCollectionBase() {
        // Given
        val plan = audioMediaStoreQueryPlan(Build.VERSION_CODES.Q)
        val rowId = 42L

        // When
        val uri = audioMediaStoreItemUri(plan.collection, rowId)

        // Then
        assertEquals("content://media/external/file/$rowId", uri.toString())
    }

    @Test
    fun success_preQRowUri_usesAudioMediaCollectionBase() {
        // Given
        val plan = audioMediaStoreQueryPlan(Build.VERSION_CODES.P)
        val rowId = 42L

        // When
        val uri = audioMediaStoreItemUri(plan.collection, rowId)

        // Then
        assertEquals("content://media/external/audio/media/$rowId", uri.toString())
    }

    @Test
    @Config(sdk = [29])
    fun success_api29QueryAudioFiles_mapsAllProviderAudioRowsAndProviderFiltersPendingAndMediaType() =
        runBlocking {
            // Given
            val rows = listOf(
                MediaStoreRow(
                    id = 101L,
                    title = "Unsupported extension",
                    artist = "Artist",
                    durationMs = 1_000L,
                    mimeType = "application/octet-stream",
                    sizeBytes = 10L,
                    dateAdded = 11L,
                    displayName = "voice.bin",
                    relativePath = "Music/",
                ),
                MediaStoreRow(
                    id = 102L,
                    title = "",
                    artist = "<unknown>",
                    durationMs = 2_000L,
                    mimeType = "video/mp4",
                    sizeBytes = 20L,
                    dateAdded = 12L,
                    displayName = "clip.mp3",
                    relativePath = "Recordings/",
                ),
                MediaStoreRow(
                    id = 103L,
                    title = "Call recording",
                    artist = "   ",
                    durationMs = 3_000L,
                    mimeType = "application/octet-stream",
                    sizeBytes = 30L,
                    dateAdded = 13L,
                    displayName = "call recording.bin",
                    relativePath = "Phone/Call Recordings///",
                ),
                MediaStoreRow(
                    id = 104L,
                    title = null,
                    artist = null,
                    durationMs = 4_000L,
                    mimeType = null,
                    sizeBytes = 40L,
                    dateAdded = 14L,
                    displayName = null,
                    relativePath = null,
                ),
                MediaStoreRow(
                    id = 105L,
                    title = "Pending",
                    artist = "Artist",
                    durationMs = 5_000L,
                    mimeType = "audio/mpeg",
                    sizeBytes = 50L,
                    dateAdded = 15L,
                    displayName = "pending.mp3",
                    relativePath = "Music/",
                    isPending = true,
                ),
                MediaStoreRow(
                    id = 106L,
                    title = "Image media type",
                    artist = "Artist",
                    durationMs = 6_000L,
                    mimeType = "audio/mpeg",
                    sizeBytes = 60L,
                    dateAdded = 16L,
                    displayName = "image.mp3",
                    relativePath = "Pictures/",
                    mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
                ),
            )
            val (context, provider) = contextWithProvider(rows)

            // When
            val result = AudioRepository(context).queryAudioFiles()
            val outputRowCount = result.size

            // Then
            assertEquals(rows.size, provider.fixtureRowCount)
            assertEquals(4, provider.providerReturnCount)
            assertEquals(provider.providerReturnCount, outputRowCount)
            assertEquals(
                listOf(101L, 102L, 103L, 104L),
                result.map { it.id },
            )
            assertEquals(
                listOf("Unsupported extension", "", "Call recording", "Unknown title"),
                result.map { it.title },
            )
            assertEquals(listOf("Artist", null, null, null), result.map { it.artist })
            assertEquals(
                listOf("voice.bin", "clip.mp3", "call recording.bin", ""),
                result.map { it.fileName },
            )
            assertEquals(
                listOf("Music", "Recordings", "Phone/Call Recordings", null),
                result.map { it.folderLabel },
            )
            assertEquals(
                listOf(1_000L, 2_000L, 3_000L, 4_000L),
                result.map { it.durationMs },
            )
            assertEquals(listOf(11_000L, 12_000L, 13_000L, 14_000L), result.map { it.dateAdded })
            assertEquals(
                listOf(
                    "content://media/external/file/101",
                    "content://media/external/file/102",
                    "content://media/external/file/103",
                    "content://media/external/file/104",
                ),
                result.map { it.uri.toString() },
            )
            assertEquals(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                provider.queryUri,
            )
            assertEquals(
                audioMediaStoreQueryPlan(Build.VERSION_CODES.Q).projection,
                provider.queryProjection,
            )
            assertEquals(
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? AND " +
                    "${MediaStore.MediaColumns.IS_PENDING} = 0",
                provider.querySelection,
            )
            assertEquals(
                listOf(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString()),
                provider.querySelectionArgs,
            )
            assertEquals(
                "${MediaStore.Audio.Media.DATE_ADDED} DESC",
                provider.querySortOrder,
            )
        }

    @Test
    @Config(sdk = [28])
    fun success_api28QueryAudioFiles_mapsEveryAudioExternalRowWithoutModernFiltering() = runBlocking {
        // Given
        val rows = listOf(
            MediaStoreRow(
                id = 201L,
                title = "Legacy title",
                artist = "Legacy artist",
                durationMs = 7_000L,
                mimeType = "application/octet-stream",
                sizeBytes = 70L,
                dateAdded = 21L,
                displayName = "legacy.bin",
                relativePath = "Ignored/",
            ),
            MediaStoreRow(
                id = 202L,
                title = null,
                artist = " ",
                durationMs = 8_000L,
                mimeType = "video/mp4",
                sizeBytes = 80L,
                dateAdded = 22L,
                displayName = null,
                relativePath = "Also ignored/",
                isPending = true,
                mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
            ),
        )
        val (context, provider) = contextWithProvider(rows)

        // When
        val result = AudioRepository(context).queryAudioFiles()
        val outputRowCount = result.size

        // Then
        assertEquals(rows.size, provider.fixtureRowCount)
        assertEquals(rows.size, provider.providerReturnCount)
        assertEquals(provider.providerReturnCount, outputRowCount)
        assertEquals(listOf(201L, 202L), result.map { it.id })
        assertEquals(listOf("Legacy title", "Unknown title"), result.map { it.title })
        assertEquals(listOf("Legacy artist", null), result.map { it.artist })
        assertEquals(listOf("legacy.bin", ""), result.map { it.fileName })
        assertEquals(listOf(null, null), result.map { it.folderLabel })
        assertEquals(listOf(7_000L, 8_000L), result.map { it.durationMs })
        assertEquals(listOf(21_000L, 22_000L), result.map { it.dateAdded })
        assertEquals(
            audioMediaStoreQueryPlan(Build.VERSION_CODES.P).projection,
            provider.queryProjection,
        )
        assertEquals(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            provider.queryUri,
        )
        assertNull(provider.querySelection)
        assertNull(provider.querySelectionArgs)
        assertFalse(MediaStore.MediaColumns.RELATIVE_PATH in provider.queryProjection)
        assertEquals(
            "${MediaStore.Audio.Media.DATE_ADDED} DESC",
            provider.querySortOrder,
        )
        assertEquals(
            "content://media/external/audio/media/201",
            result.first().uri.toString(),
        )
    }

    private fun contextWithProvider(
        rows: List<MediaStoreRow>,
    ): Pair<Context, RecordingMediaStoreProvider> {
        val provider = RecordingMediaStoreProvider(rows)
        ShadowContentResolver.registerProviderInternal("media", provider)
        return ApplicationProvider.getApplicationContext<Context>() to provider
    }

    private data class MediaStoreRow(
        val id: Long,
        val title: String?,
        val artist: String?,
        val durationMs: Long,
        val mimeType: String?,
        val sizeBytes: Long,
        val dateAdded: Long,
        val displayName: String?,
        val relativePath: String?,
        val isPending: Boolean = false,
        val mediaType: Int = MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO,
    )

    private class RecordingMediaStoreProvider(
        private val rows: List<MediaStoreRow>,
    ) : ContentProvider() {
        val fixtureRowCount: Int = rows.size
        var providerReturnCount: Int = 0
            private set
        var queryUri: Uri? = null
            private set
        var queryProjection: List<String> = emptyList()
            private set
        var querySelection: String? = null
            private set
        var querySelectionArgs: List<String>? = null
            private set
        var querySortOrder: String? = null
            private set

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            queryUri = uri
            queryProjection = projection?.toList().orEmpty()
            querySelection = selection
            querySelectionArgs = selectionArgs?.toList()
            querySortOrder = sortOrder

            val providerRows = when (selection) {
                null -> rows
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? AND " +
                    "${MediaStore.MediaColumns.IS_PENDING} = 0" -> {
                    check(selectionArgs?.contentEquals(arrayOf(
                        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString(),
                    )) == true) {
                        "Unexpected API29+ selection args: $selectionArgs"
                    }
                    rows.filter {
                        it.mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO &&
                            !it.isPending
                    }
                }
                else -> error("Unexpected MediaStore selection: $selection")
            }
            providerReturnCount = providerRows.size

            return MatrixCursor(queryProjection.toTypedArray()).apply {
                providerRows.forEach { row ->
                    addRow(queryProjection.map { column ->
                        when (column) {
                            MediaStore.Audio.Media._ID -> row.id
                            MediaStore.Audio.Media.TITLE -> row.title
                            MediaStore.Audio.Media.ARTIST -> row.artist
                            MediaStore.Audio.Media.DURATION -> row.durationMs
                            MediaStore.MediaColumns.MIME_TYPE -> row.mimeType
                            MediaStore.MediaColumns.SIZE -> row.sizeBytes
                            MediaStore.Audio.Media.DATE_ADDED -> row.dateAdded
                            MediaStore.Audio.Media.DISPLAY_NAME -> row.displayName
                            MediaStore.MediaColumns.RELATIVE_PATH -> row.relativePath
                            else -> null
                        }
                    }.toTypedArray())
                }
            }
        }

        override fun onCreate(): Boolean = true

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }
}
