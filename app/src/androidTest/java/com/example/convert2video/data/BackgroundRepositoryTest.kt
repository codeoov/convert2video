package com.example.convert2video.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackgroundRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: BackgroundRepository
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = BackgroundRepository(context, db.backgroundDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sourceImageUri(): Uri {
        val sourceFile = File(context.cacheDir, "source_${System.nanoTime()}.jpg")
        sourceFile.writeBytes(byteArrayOf(1, 2, 3, 4))
        return Uri.fromFile(sourceFile)
    }

    @Test
    fun firstAddedBackgroundIsAutoSelectedAndPersisted() = runTest {
        repository.addBackground(sourceImageUri())

        val list = db.backgroundDao().observeAll().first()
        assertEquals(1, list.size)
        assertTrue(list.single().isSelected)
        assertTrue(File(list.single().filePath).exists())
    }

    @Test
    fun secondAddedBackgroundIsNotAutoSelected() = runTest {
        repository.addBackground(sourceImageUri())
        repository.addBackground(sourceImageUri())

        val list = db.backgroundDao().observeAll().first()
        assertEquals(2, list.size)
        assertEquals(1, list.count { it.isSelected })
        assertTrue(list.first().isSelected)
    }

    @Test
    fun selectingABackgroundDeselectsEveryOther() = runTest {
        repository.addBackground(sourceImageUri())
        repository.addBackground(sourceImageUri())
        val list = db.backgroundDao().observeAll().first()
        val second = list[1]

        repository.selectBackground(second.id)

        val updated = db.backgroundDao().observeAll().first()
        assertEquals(1, updated.count { it.isSelected })
        assertTrue(updated.single { it.id == second.id }.isSelected)
    }

    @Test
    fun deletingTheSelectedBackgroundClearsSelectionAndFile() = runTest {
        repository.addBackground(sourceImageUri())
        val added = db.backgroundDao().observeAll().first().single()

        repository.deleteBackground(added)

        val remaining = db.backgroundDao().observeAll().first()
        assertTrue(remaining.isEmpty())
        assertNull(db.backgroundDao().getSelected())
        assertTrue(!File(added.filePath).exists())
    }
}
