package com.example.convert2video.drive

import com.example.convert2video.R
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the pure-Kotlin JSON helpers in GoogleDriveApiClient.kt directly (no OkHttp/Android).
 * Needs testImplementation(libs.json) for a real org.json impl — the Android SDK stub used by
 * default in JVM unit tests (isReturnDefaultValues) would make these assertions vacuously pass.
 */
class GoogleDriveApiClientJsonTest {

    @Test
    fun success_buildDriveFileMetadataJson_includesParentsWhenProvided() {
        // Given / When
        val json = buildDriveFileMetadataJson(
            name = "recording.m4a",
            mimeType = "audio/mp4",
            parentFolderId = "folder123",
        )

        // Then
        val root = JSONObject(json)
        assertEquals("recording.m4a", root.getString("name"))
        assertEquals("audio/mp4", root.getString("mimeType"))
        assertEquals("folder123", root.getJSONArray("parents").getString(0))
    }

    @Test
    fun success_buildDriveFileMetadataJson_omitsParentsWhenNull() {
        // Given / When
        val json = buildDriveFileMetadataJson(
            name = "Convert2Video Recordings",
            mimeType = "application/vnd.google-apps.folder",
            parentFolderId = null,
        )

        // Then
        val root = JSONObject(json)
        assertEquals("Convert2Video Recordings", root.getString("name"))
        assertEquals("application/vnd.google-apps.folder", root.getString("mimeType"))
        assertFalse(root.has("parents"))
    }

    @Test
    fun success_parseDriveFileId_readsTopLevelId() {
        // Given / When / Then
        assertEquals(
            "fileAbc",
            parseDriveFileId("""{"id":"fileAbc","kind":"drive#file"}"""),
        )
    }

    @Test
    fun failure_parseDriveFileId_returnsNullWhenMissing() {
        // Given / When / Then
        assertNull(parseDriveFileId("""{"kind":"drive#file"}"""))
    }

    @Test
    fun failure_parseDriveFileId_malformedJson() {
        // Given / When / Then
        assertNull(parseDriveFileId("{ not valid json }"))
    }

    @Test
    fun success_parseDriveWebViewLink_readsTopLevelLink() {
        // Given / When / Then
        assertEquals(
            "https://drive.google.com/file/d/fileAbc/view",
            parseDriveWebViewLink(
                """{"id":"fileAbc","webViewLink":"https://drive.google.com/file/d/fileAbc/view"}""",
            ),
        )
    }

    @Test
    fun failure_parseDriveWebViewLink_returnsNullWhenMissing() {
        // Given / When / Then
        assertNull(parseDriveWebViewLink("""{"id":"fileAbc"}"""))
    }

    @Test
    fun failure_parseDriveWebViewLink_malformedJson() {
        // Given / When / Then
        assertNull(parseDriveWebViewLink("{ not valid json }"))
    }

    @Test
    fun success_parseDriveUserEmail_readsUserEmailAddress() {
        // Given
        val body = """{"user":{"emailAddress":"user@example.com","displayName":"User"}}"""

        // When / Then
        assertEquals("user@example.com", parseDriveUserEmail(body))
    }

    @Test
    fun failure_parseDriveUserEmail_returnsNullWhenMissing() {
        // Given / When / Then
        assertNull(parseDriveUserEmail("""{"user":{}}"""))
        assertNull(parseDriveUserEmail("""{}"""))
    }

    @Test
    fun failure_parseDriveUserEmail_blankEmail_returnsNull() {
        // Given: user present but emailAddress blank / whitespace
        val blank = """{"user":{"emailAddress":""}}"""
        val whitespace = """{"user":{"emailAddress":"   "}}"""

        // When / Then
        assertNull(parseDriveUserEmail(blank))
        assertNull(parseDriveUserEmail(whitespace))
    }

    @Test
    fun failure_parseDriveUserEmail_malformedJson() {
        // Given / When / Then
        assertNull(parseDriveUserEmail("{ not valid json }"))
    }

    @Test
    fun success_parseDriveStorageQuota_readsUsageAndLimit() {
        // Given
        val body = """{"storageQuota":{"usage":"100","limit":"200"}}"""

        // When
        val quota = parseDriveStorageQuota(body)

        // Then
        assertEquals(100L, quota?.usageBytes)
        assertEquals(200L, quota?.limitBytes)
    }

    @Test
    fun success_parseDriveStorageQuota_unlimitedWhenLimitAbsent() {
        // Given
        val body = """{"storageQuota":{"usage":"12345"}}"""

        // When
        val quota = parseDriveStorageQuota(body)

        // Then
        assertEquals(12345L, quota?.usageBytes)
        assertNull(quota?.limitBytes)
    }

    @Test
    fun success_parseDriveStorageQuota_readsNumericUsageAndLimit() {
        // Given — Drive about may emit JSON numbers instead of int64 strings
        val body = """{"storageQuota":{"usage":100,"limit":200}}"""

        // When
        val quota = parseDriveStorageQuota(body)

        // Then
        assertEquals(100L, quota?.usageBytes)
        assertEquals(200L, quota?.limitBytes)
    }

    @Test
    fun success_parseDriveStorageQuota_unlimitedWhenLimitJsonNull() {
        // Given
        val body = """{"storageQuota":{"usage":"12345","limit":null}}"""

        // When
        val quota = parseDriveStorageQuota(body)

        // Then
        assertEquals(12345L, quota?.usageBytes)
        assertNull(quota?.limitBytes)
    }

    @Test
    fun failure_parseDriveStorageQuota_malformedOrMissingUsage() {
        // Given / When / Then
        assertNull(parseDriveStorageQuota("{ not valid json }"))
        assertNull(parseDriveStorageQuota("""{}"""))
        assertNull(parseDriveStorageQuota("""{"storageQuota":{}}"""))
        assertNull(parseDriveStorageQuota("""{"storageQuota":{"limit":"200"}}"""))
    }

    @Test
    fun failure_parseDriveStorageQuota_unparseableLimitReturnsNull() {
        // Given / When / Then
        assertNull(parseDriveStorageQuota("""{"storageQuota":{"usage":"100","limit":"not-a-number"}}"""))
        assertNull(parseDriveStorageQuota("""{"storageQuota":{"usage":"100","limit":""}}"""))
    }

    @Test
    fun failure_parseDriveStorageQuota_usageJsonNullReturnsNull() {
        // Given / When / Then
        assertNull(parseDriveStorageQuota("""{"storageQuota":{"usage":null,"limit":"200"}}"""))
    }

    @Test
    fun success_parseDriveStorageQuota_readsJsonDoubleWholeNumbers() {
        // Given
        val body = """{"storageQuota":{"usage":100.0,"limit":200.0}}"""

        // When
        val quota = parseDriveStorageQuota(body)

        // Then
        assertEquals(100L, quota?.usageBytes)
        assertEquals(200L, quota?.limitBytes)
    }

    @Test
    fun success_formatDriveStorageBytes_siBoundariesAndGbCap() {
        // Given / When / Then — unitRes is l10n key, never a "GB" literal
        val unitB = R.string.options_drive_storage_unit_b
        val unitKb = R.string.options_drive_storage_unit_kb
        val unitGb = R.string.options_drive_storage_unit_gb
        assertEquals(DriveStorageByteFormat("0", unitB), formatDriveStorageBytes(0L))
        assertEquals(DriveStorageByteFormat("999", unitB), formatDriveStorageBytes(999L))
        assertEquals(DriveStorageByteFormat("1", unitKb), formatDriveStorageBytes(1000L))
        val oneE12 = formatDriveStorageBytes(1_000_000_000_000L)
        assertEquals("1000", oneE12.numberLabel)
        assertEquals(unitGb, oneE12.unitRes)
        assertEquals(DriveStorageByteFormat("0", unitB), formatDriveStorageBytes(-7L))
    }

    @Test
    fun success_isAllowedDriveUploadSessionUrl_acceptsGoogleApisHttps() {
        // Given / When / Then
        assertTrue(
            isAllowedDriveUploadSessionUrl(
                "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=abc",
            ),
        )
    }

    @Test
    fun failure_isAllowedDriveUploadSessionUrl_rejectsHttpOrForeignHost() {
        // Given / When / Then
        assertFalse(isAllowedDriveUploadSessionUrl("http://www.googleapis.com/upload/drive/v3/files"))
        assertFalse(isAllowedDriveUploadSessionUrl("https://evil.example.com/upload"))
        assertFalse(isAllowedDriveUploadSessionUrl(""))
    }

    @Test
    fun success_parseDriveFolderIdFromSearch_readsFirstFileId() {
        // Given
        val body = """{"files":[{"id":"folderId1","name":"Convert2Video Recordings"}]}"""

        // When / Then
        assertEquals("folderId1", parseDriveFolderIdFromSearch(body))
    }

    @Test
    fun failure_parseDriveFolderIdFromSearch_returnsNullWhenEmptyArray() {
        // Given / When / Then
        assertNull(parseDriveFolderIdFromSearch("""{"files":[]}"""))
    }

    @Test
    fun failure_parseDriveFolderIdFromSearch_returnsNullWhenFilesMissing() {
        // Given / When / Then
        assertNull(parseDriveFolderIdFromSearch("""{}"""))
    }

    @Test
    fun failure_parseDriveFolderIdFromSearch_malformedJson() {
        // Given / When / Then
        assertNull(parseDriveFolderIdFromSearch("{ not valid json }"))
    }

    @Test
    fun success_parseDriveFolderIdFromSearch_skipsBlankId() {
        // Given: first item present but blank id
        val body = """{"files":[{"id":"","name":"x"}]}"""

        // When / Then
        assertNull(parseDriveFolderIdFromSearch(body))
        assertTrue(JSONObject(body).getJSONArray("files").length() == 1)
    }

    @Test
    fun success_escapeDriveQueryString_escapesSingleQuote() {
        // Given / When / Then
        assertEquals("John\\'s", escapeDriveQueryString("John's"))
    }

    @Test
    fun success_escapeDriveQueryString_escapesBackslashThenQuote() {
        // Given: backslash must be escaped before quote so `\'` stays a quote escape
        // When
        val result = escapeDriveQueryString("a\\b'c")

        // Then
        assertEquals("a\\\\b\\'c", result)
    }

    @Test
    fun success_escapeDriveQueryString_leavesPlainName() {
        // Given / When / Then
        assertEquals("Convert2Video Recordings", escapeDriveQueryString("Convert2Video Recordings"))
    }
}
