package com.example.convert2video.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

class YoutubeAuthPrefsContractTest {

    @Test
    fun success_youtubeAuthPrefs_nameAndAuthorizedKey() {
        // Given / When / Then — lock SharedPreferences identity used by
        // isYoutubeAuthorizedFromPrefs and the YouTube auth gateway's isAuthorized
        assertEquals("youtube_auth", YOUTUBE_AUTH_PREFS_NAME)
        assertEquals("authorized", YOUTUBE_AUTH_KEY_AUTHORIZED)
    }
}
