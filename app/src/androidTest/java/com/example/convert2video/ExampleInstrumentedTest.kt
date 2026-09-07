package com.example.convert2video

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.content.FileProvider

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.convert2video", appContext.packageName)

        val providerAuthority = "${appContext.packageName}.fileprovider"
        val providerInfo = appContext.packageManager.resolveContentProvider(providerAuthority, 0)
        assertNotNull("FileProvider must be registered for the runtime application ID", providerInfo)
        assertEquals(FileProvider::class.java.name, providerInfo?.name)
        assertEquals(appContext.packageName, providerInfo?.applicationInfo?.packageName)
    }
}
