package com.example.convert2video.ui.screens.recordings_list

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.convert2video.data.AudioItem
import com.example.convert2video.data.AppDatabase
import com.example.convert2video.ui.screens.audio_pick.AudioSourceFilter
import androidx.lifecycle.ViewModelStore
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecordingsListViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val viewModelStore = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        AppDatabase.clearInstance()
        Dispatchers.resetMain()
    }

    @Test
    fun success_initializationAndMyRecordingsSelection_doNotQueryMediaStore() = runTest {
        // Given
        val queryCount = AtomicInteger(0)
        val viewModel = viewModel { queryCount.incrementAndGet(); emptyList() }

        // When
        runCurrent()
        viewModel.setAudioFilter(AudioSourceFilter.MyRecordings)
        advanceUntilIdle()

        // Then
        assertEquals(0, queryCount.get())
    }

    @Test
    fun success_allFilter_lazyLoadsOnceAndMergesRepeatedEntry() = runTest {
        // Given
        val queryCount = AtomicInteger(0)
        val mediaItem = audioItem(1L, "content://media/1")
        val viewModel = viewModel {
            queryCount.incrementAndGet()
            listOf(mediaItem)
        }

        // When
        viewModel.setAudioFilter(AudioSourceFilter.All)
        advanceUntilIdle()
        viewModel.setAudioFilter(AudioSourceFilter.MyRecordings)
        viewModel.setAudioFilter(AudioSourceFilter.All)
        advanceUntilIdle()

        // Then
        assertEquals(1, queryCount.get())
    }

    @Test
    fun success_allFilter_concurrentEntrySharesOneActiveLoad() = runTest {
        // Given
        val queryCount = AtomicInteger(0)
        val result = CompletableDeferred<List<AudioItem>>()
        val viewModel = viewModel {
            queryCount.incrementAndGet()
            result.await()
        }

        // When
        viewModel.setAudioFilter(AudioSourceFilter.All)
        viewModel.setAudioFilter(AudioSourceFilter.All)
        runCurrent()

        // Then — active 요청이 끝나기 전 재진입은 새 쿼리를 만들지 않는다.
        assertEquals(1, queryCount.get())

        result.complete(emptyList())
        advanceUntilIdle()
    }

    @Test
    fun success_allFilter_actualConcurrentCalls_shareOneActiveLoad() = runTest {
        // Given
        val queryCount = AtomicInteger(0)
        val result = CompletableDeferred<List<AudioItem>>()
        val callersReady = CompletableDeferred<Unit>()
        val releaseCallers = CompletableDeferred<Unit>()
        val readyCount = AtomicInteger(0)
        val viewModel = viewModel {
            queryCount.incrementAndGet()
            result.await()
        }
        suspend fun callAllConcurrently() {
            if (readyCount.incrementAndGet() == 2) callersReady.complete(Unit)
            callersReady.await()
            releaseCallers.await()
            viewModel.setAudioFilter(AudioSourceFilter.All)
        }

        // When — 서로 다른 Dispatcher 스레드에서 실제로 동시에 All 진입
        val callers = listOf(
            async(Dispatchers.Default) { callAllConcurrently() },
            async(Dispatchers.Default) { callAllConcurrently() },
        )
        callersReady.await()
        releaseCallers.complete(Unit)
        callers.awaitAll()
        runCurrent()

        // Then
        assertEquals(1, queryCount.get())
        result.complete(emptyList())
        advanceUntilIdle()
    }

    @Test
    fun success_allFilter_concurrentQueries_haveAtMostOneActiveQuery() = runTest {
        // Given
        val activeQueries = AtomicInteger(0)
        val maxActiveQueries = AtomicInteger(0)
        val result = CompletableDeferred<List<AudioItem>>()
        val callersReady = CompletableDeferred<Unit>()
        val releaseCallers = CompletableDeferred<Unit>()
        val readyCount = AtomicInteger(0)
        val viewModel = viewModel {
            val active = activeQueries.incrementAndGet()
            maxActiveQueries.updateAndGet { current -> maxOf(current, active) }
            try {
                result.await()
            } finally {
                activeQueries.decrementAndGet()
            }
        }
        suspend fun callAllConcurrently() {
            if (readyCount.incrementAndGet() == 2) callersReady.complete(Unit)
            callersReady.await()
            releaseCallers.await()
            viewModel.setAudioFilter(AudioSourceFilter.All)
        }

        // When
        val callers = listOf(
            async(Dispatchers.Default) { callAllConcurrently() },
            async(Dispatchers.Default) { callAllConcurrently() },
        )
        callersReady.await()
        releaseCallers.complete(Unit)
        callers.awaitAll()
        runCurrent()

        // Then
        assertEquals(1, activeQueries.get())
        assertEquals(1, maxActiveQueries.get())
        result.complete(emptyList())
        advanceUntilIdle()
    }

    @Test
    fun success_loadMediaAudio_forceRefresh_keepsOnlyLatestResult() = runTest {
        // Given
        val queryCount = AtomicInteger(0)
        val oldResult = CompletableDeferred<List<AudioItem>>()
        val newResult = CompletableDeferred<List<AudioItem>>()
        val oldItem = audioItem(1L, "content://media/old")
        val newItem = audioItem(2L, "content://media/new")
        val viewModel = viewModel {
            if (queryCount.incrementAndGet() == 1) {
                withContext(NonCancellable) { oldResult.await() }
            } else {
                newResult.await()
            }
        }
        val observed = mutableListOf<List<AudioItem>>()
        val collector = backgroundScope.launch {
            viewModel.audioItems.collect { observed += it }
        }
        viewModel.setAudioFilter(AudioSourceFilter.All)
        runCurrent()

        // When — 첫 쿼리가 취소에 협조하지 않아도 두 번째 쿼리가 최신 요청이다.
        viewModel.loadMediaAudio()
        runCurrent()
        oldResult.complete(listOf(oldItem))
        advanceUntilIdle()
        newResult.complete(listOf(newItem))
        advanceUntilIdle()

        // Then
        assertEquals(2, queryCount.get())
        assertTrue(observed.none { it == listOf(oldItem) })
        assertEquals(listOf(newItem), viewModel.audioItems.value)
        collector.cancel()
    }

    @Test
    fun success_forceRefresh_invalidatesGenerationBeforeQueuedReplacementRuns() = runTest {
        // Given — query 1 ignores cancellation until its deferred result is released.
        val queryCount = AtomicInteger(0)
        val oldResult = CompletableDeferred<List<AudioItem>>()
        val newResult = CompletableDeferred<List<AudioItem>>()
        val oldItem = audioItem(21L, "content://media/boundary-old")
        val newItem = audioItem(22L, "content://media/boundary-new")
        val viewModel = viewModel {
            if (queryCount.incrementAndGet() == 1) {
                withContext(NonCancellable) { oldResult.await() }
            } else {
                newResult.await()
            }
        }
        val collector = backgroundScope.launch { viewModel.audioItems.collect {} }
        viewModel.setAudioFilter(AudioSourceFilter.All)
        runCurrent()

        // When — force refresh reserves its generation synchronously. The old
        // query then completes before the replacement coroutine gets to run.
        viewModel.loadMediaAudio()
        oldResult.complete(listOf(oldItem))
        assertEquals(1, queryCount.get())
        runCurrent()

        // Then — old completion cannot commit under the replaced generation.
        assertEquals(2, queryCount.get())
        newResult.complete(listOf(newItem))
        advanceUntilIdle()
        assertEquals(listOf(newItem), viewModel.audioItems.value)
        collector.cancel()
    }

    @Test
    fun success_forceRefresh_replacesPendingAllLoadWithoutDuplicateQuery() = runTest {
        // Given — All lazy load is queued but has not started yet.
        val queryCount = AtomicInteger(0)
        val result = CompletableDeferred<List<AudioItem>>()
        val item = audioItem(23L, "content://media/pending-force")
        val viewModel = viewModel {
            queryCount.incrementAndGet()
            result.await()
        }
        val collector = backgroundScope.launch { viewModel.audioItems.collect {} }

        // When — force refresh replaces the pending lazy generation before it runs.
        viewModel.setAudioFilter(AudioSourceFilter.All)
        viewModel.loadMediaAudio()
        runCurrent()

        // Then — the canceled pending load did not create a duplicate query.
        assertEquals(1, queryCount.get())
        result.complete(listOf(item))
        advanceUntilIdle()
        assertEquals(listOf(item), viewModel.audioItems.value)
        collector.cancel()
    }

    @Test
    fun success_forceRefresh_callsFromDefault_overlap_withoutStaleOverwrite() = runTest {
        // Given — query 1은 최초 요청, query 2는 첫 force refresh, query 3이 최신 요청
        val queryCount = AtomicInteger(0)
        val activeQueries = AtomicInteger(0)
        val maxActiveQueries = AtomicInteger(0)
        val oldResult = CompletableDeferred<List<AudioItem>>()
        val middleResult = CompletableDeferred<List<AudioItem>>()
        val latestResult = CompletableDeferred<List<AudioItem>>()
        val middleStarted = CompletableDeferred<Unit>()
        val oldItem = audioItem(6L, "content://media/force-old")
        val latestItem = audioItem(7L, "content://media/force-latest")
        val viewModel = viewModel {
            val active = activeQueries.incrementAndGet()
            maxActiveQueries.updateAndGet { current -> maxOf(current, active) }
            try {
                when (queryCount.incrementAndGet()) {
                    1 -> withContext(NonCancellable) { oldResult.await() }
                    2 -> {
                        middleStarted.complete(Unit)
                        withContext(NonCancellable) { middleResult.await() }
                    }
                    else -> latestResult.await()
                }
            } finally {
                activeQueries.decrementAndGet()
            }
        }
        val observed = mutableListOf<List<AudioItem>>()
        val collector = backgroundScope.launch {
            viewModel.audioItems.collect { observed += it }
        }
        viewModel.setAudioFilter(AudioSourceFilter.All)
        runCurrent()

        // When — 두 force refresh 호출 자체는 서로 다른 Default worker에서 겹친다.
        val firstForceEntered = CompletableDeferred<Unit>()
        val releaseFirstForce = CompletableDeferred<Unit>()
        val firstForce = async(Dispatchers.Default) {
            viewModel.loadMediaAudio()
            firstForceEntered.complete(Unit)
            releaseFirstForce.await()
        }
        firstForceEntered.await()
        runCurrent()
        // query 2 must begin without waiting for the non-cooperative query 1.
        middleStarted.await()
        assertEquals(2, queryCount.get())
        val secondForce = async(Dispatchers.Default) {
            viewModel.loadMediaAudio()
        }
        secondForce.await()
        releaseFirstForce.complete(Unit)
        firstForce.await()
        runCurrent()

        latestResult.complete(listOf(latestItem))
        advanceUntilIdle()
        middleResult.complete(listOf(oldItem))
        advanceUntilIdle()

        // Then
        assertEquals(3, queryCount.get())
        assertTrue(maxActiveQueries.get() >= 2)
        assertEquals(listOf(latestItem), viewModel.audioItems.value)
        assertTrue(observed.none { it == listOf(oldItem) })
        collector.cancel()
    }

    @Test
    fun success_staleSuccess_doesNotOverwriteLatestFailure() = runTest {
        // Given
        val queryCount = AtomicInteger(0)
        val oldResult = CompletableDeferred<List<AudioItem>>()
        val oldItem = audioItem(4L, "content://media/stale-success")
        val viewModel = viewModel {
            if (queryCount.incrementAndGet() == 2) {
                error("latest query failed")
            } else {
                withContext(NonCancellable) { oldResult.await() }
            }
        }
        val collector = backgroundScope.launch { viewModel.audioItems.collect {} }
        viewModel.setAudioFilter(AudioSourceFilter.All)
        runCurrent()

        // When
        viewModel.loadMediaAudio()
        runCurrent()
        advanceUntilIdle()
        oldResult.complete(listOf(oldItem))
        advanceUntilIdle()

        // Then — 최신 실패가 만든 빈 결과를 stale 성공이 덮지 않는다.
        assertEquals(emptyList<AudioItem>(), viewModel.audioItems.value)
        collector.cancel()
    }

    @Test
    fun success_staleFailure_doesNotOverwriteLatestSuccessOrEmitStaleResult() = runTest {
        // Given
        val queryCount = AtomicInteger(0)
        val oldFailure = CompletableDeferred<Unit>()
        val newResult = CompletableDeferred<List<AudioItem>>()
        val newItem = audioItem(5L, "content://media/latest-success")
        val errors = mutableListOf<String>()
        val viewModel = viewModel {
            if (queryCount.incrementAndGet() == 1) {
                withContext(NonCancellable) {
                    oldFailure.await()
                }
                error("stale query failed")
            } else {
                newResult.await()
            }
        }
        val audioCollector = backgroundScope.launch { viewModel.audioItems.collect {} }
        val errorCollector = backgroundScope.launch {
            viewModel.errorMessage.collect { errors += it }
        }
        viewModel.setAudioFilter(AudioSourceFilter.All)
        runCurrent()

        // When
        viewModel.loadMediaAudio()
        runCurrent()
        newResult.complete(listOf(newItem))
        advanceUntilIdle()
        oldFailure.complete(Unit)
        advanceUntilIdle()

        // Then
        assertEquals(listOf(newItem), viewModel.audioItems.value)
        assertEquals(0, errors.size)
        audioCollector.cancel()
        errorCollector.cancel()
    }

    @Test
    fun failure_allFilter_emitsExistingErrorAndAllowsRetry() = runTest {
        // Given
        val queryCount = AtomicInteger(0)
        val errors = mutableListOf<String>()
        val viewModel = viewModel {
            if (queryCount.incrementAndGet() == 1) {
                error("first query failed")
            }
            listOf(audioItem(3L, "content://media/retry"))
        }
        val collector = backgroundScope.launch {
            viewModel.errorMessage.collect { errors += it }
        }

        // When
        viewModel.setAudioFilter(AudioSourceFilter.All)
        advanceUntilIdle()
        viewModel.setAudioFilter(AudioSourceFilter.All)
        advanceUntilIdle()

        // Then
        assertEquals(2, queryCount.get())
        assertEquals(1, errors.size)
        collector.cancel()
    }

    @Test
    fun success_latestFailure_immediateRetrySuccess_dropsPendingFailureError() = runTest {
        // Given
        val queryCount = AtomicInteger(0)
        val dispatchEntered = CompletableDeferred<Unit>()
        val releaseDispatch = CompletableDeferred<Unit>()
        val latestItem = audioItem(8L, "content://media/retry-latest")
        val errors = mutableListOf<String>()
        val viewModel = viewModel {
            if (queryCount.incrementAndGet() == 1) {
                error("latest query failed")
            } else {
                listOf(latestItem)
            }
        }
        viewModel.setMediaLoadErrorDispatchHookForTests {
            dispatchEntered.complete(Unit)
            releaseDispatch.await()
        }
        val audioCollector = backgroundScope.launch { viewModel.audioItems.collect {} }
        val errorCollector = backgroundScope.launch {
            viewModel.errorMessage.collect { errors += it }
        }

        // When — 실패 이벤트 dispatch 직전 retry가 최신 성공 요청으로 교체된다.
        viewModel.setAudioFilter(AudioSourceFilter.All)
        runCurrent()
        dispatchEntered.await()
        viewModel.loadMediaAudio()
        runCurrent()
        releaseDispatch.complete(Unit)
        advanceUntilIdle()

        // Then — 취소된 이전 실패가 retry 성공 뒤 error event로 남지 않는다.
        assertEquals(2, queryCount.get())
        assertEquals(listOf(latestItem), viewModel.audioItems.value)
        assertEquals(0, errors.size)
        audioCollector.cancel()
        errorCollector.cancel()
    }

    @Test
    fun success_audioPipelineMappings_preserveFilterConversionSortOrder() = runTest {
        // Given
        val convertedMedia = audioItem(10L, "content://media/converted", durationMs = 1_000L)
        val convertedRecording = audioItem(11L, "content://recording/converted", durationMs = 3_000L)
        val convertedImported = audioItem(12L, "content://imported/converted", durationMs = 2_000L)
        val notConvertedMedia = audioItem(13L, "content://media/not-converted", durationMs = 4_000L)
        val viewModel = viewModel { listOf(convertedMedia, notConvertedMedia) }
        viewModel.setAudioPipelineFixturesForTests(
            recordings = listOf(convertedRecording),
            imported = listOf(convertedImported),
            convertedUris = setOf(
                convertedMedia.uri.toString(),
                convertedRecording.uri.toString(),
                convertedImported.uri.toString(),
            ),
        )
        val collector = backgroundScope.launch { viewModel.audioItems.collect {} }

        // When
        viewModel.setAudioFilter(AudioSourceFilter.All)
        viewModel.setConversionFilter(RecordingsListConversionFilter.Converted)
        viewModel.setSortOrder(RecordingsListSortOrder.Duration)
        advanceUntilIdle()

        // Then — source filter excludes recordings, then conversion filter → sort remain intact.
        assertEquals(
            listOf(convertedImported, convertedMedia),
            viewModel.audioItems.value,
        )

        // When — 같은 파이프라인의 NotConverted 분기
        viewModel.setConversionFilter(RecordingsListConversionFilter.NotConverted)
        advanceUntilIdle()

        // Then
        assertEquals(listOf(notConvertedMedia), viewModel.audioItems.value)
        collector.cancel()
    }

    @Test
    fun success_onCleared_invalidatesActiveGenerationAndCancelsJob() = runTest {
        // Given
        val result = CompletableDeferred<List<AudioItem>>()
        val staleItem = audioItem(20L, "content://media/after-clear")
        val viewModel = viewModel { result.await() }
        val collector = backgroundScope.launch { viewModel.audioItems.collect {} }
        viewModel.setAudioFilter(AudioSourceFilter.All)
        runCurrent()

        // When
        viewModelStore.clear()
        result.complete(listOf(staleItem))
        advanceUntilIdle()

        // Then
        assertEquals(emptyList<AudioItem>(), viewModel.audioItems.value)
        collector.cancel()
    }

    @Test
    fun success_onCleared_cancelsActiveQuery_andReleasesCancellationLatch() = runTest {
        // Given
        val queryStarted = CompletableDeferred<Unit>()
        val cancellationLatch = CompletableDeferred<Unit>()
        val viewModel = viewModel {
            queryStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancellationLatch.complete(Unit)
            }
        }
        viewModel.setAudioFilter(AudioSourceFilter.All)
        runCurrent()
        queryStarted.await()

        // When
        viewModelStore.clear()

        // Then — onCleared가 실제 query coroutine을 취소했다.
        cancellationLatch.await()
    }

    private fun viewModel(query: suspend () -> List<AudioItem>): RecordingsListViewModel {
        val viewModel = RecordingsListViewModel(application)
            .also { it.setMediaAudioQueryForTests(query) }
        viewModel.setAudioPipelineFixturesForTests(
            recordings = emptyList(),
            imported = emptyList(),
            convertedUris = emptySet(),
        )
        viewModelStore.put("recordings-list-test", viewModel)
        return viewModel
    }

    private fun audioItem(
        id: Long,
        uri: String,
        durationMs: Long = 1_000L,
    ): AudioItem = AudioItem(
        id = id,
        title = "Track $id",
        artist = null,
        durationMs = durationMs,
        uri = Uri.parse(uri),
        fileName = "track_$id.m4a",
        dateAdded = id,
    )
}
