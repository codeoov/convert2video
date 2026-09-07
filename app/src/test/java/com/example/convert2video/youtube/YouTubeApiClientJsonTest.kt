package com.example.convert2video.youtube

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exercises the pure-Kotlin JSON helpers in YouTubeApiClient.kt directly (no OkHttp/Android).
 * Needs testImplementation(libs.json) for a real org.json impl — the Android SDK stub used by
 * default in JVM unit tests (isReturnDefaultValues) would make these assertions vacuously pass.
 */
class YouTubeApiClientJsonTest {

    @Test
    fun buildUploadMetadataJsonIncludesAllFields() {
        val json = buildUploadMetadataJson(
            title = "제목",
            description = "설명",
            privacyStatus = "private",
            categoryId = "22",
        )

        val root = JSONObject(json)
        val snippet = root.getJSONObject("snippet")
        assertEquals("제목", snippet.getString("title"))
        assertEquals("설명", snippet.getString("description"))
        assertEquals("22", snippet.getString("categoryId"))
        assertEquals("private", root.getJSONObject("status").getString("privacyStatus"))
    }

    @Test
    fun buildUploadMetadataJsonAllowsBlankDescription() {
        val json = buildUploadMetadataJson(
            title = "제목",
            description = "",
            privacyStatus = "unlisted",
            categoryId = "22",
        )

        assertEquals("", JSONObject(json).getJSONObject("snippet").getString("description"))
    }

    @Test
    fun parseChannelTitleReadsFirstItemsSnippetTitle() {
        val body = """{"items":[{"snippet":{"title":"내 채널"}}]}"""
        assertEquals("내 채널", parseChannelTitle(body))
    }

    @Test
    fun parseChannelTitleReturnsNullWhenItemsMissing() {
        assertNull(parseChannelTitle("""{"items":[]}"""))
    }

    @Test
    fun parseChannelTitleReturnsNullWhenTitleBlank() {
        val body = """{"items":[{"snippet":{"title":""}}]}"""
        assertNull(parseChannelTitle(body))
    }

    @Test
    fun parseVideoIdReadsTopLevelId() {
        assertEquals("abc123", parseVideoId("""{"id":"abc123","kind":"youtube#video"}"""))
    }

    @Test
    fun parseVideoIdReturnsNullWhenMissing() {
        assertNull(parseVideoId("""{"kind":"youtube#video"}"""))
    }
}
