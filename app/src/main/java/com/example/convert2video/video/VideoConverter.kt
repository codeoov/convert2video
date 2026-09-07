@file:UnstableApi

package com.example.convert2video.video

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.example.convert2video.R
import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Frames per second for the still-image video track: static content needs no more than this to look correct. */
private const val VIDEO_FRAME_RATE = 2

/** Caps the image's shorter side so a large photo doesn't inflate the encoded video bitrate. */
private const val VIDEO_SHORT_SIDE_PX = 720

private const val PROGRESS_POLL_INTERVAL_MS = 300L

/**
 * Trimmed off the audio clip's end to stay under Mp3Extractor's post-EOF duration correction
 * for headerless MP3s (e.g. Easy Voice Recorder Pro output), which otherwise crashes
 * ExoPlayerAssetLoader's duration-consistency check.
 *
 * **SSOT (plan length ≠ export length):** [VideoSegmentPlanner.MIN_SEGMENT_DURATION_US] is the
 * *planned* half-open range length. Export uses [effectiveClipDurationUs], which subtracts this
 * margin from the planned end. A 60s plan therefore exports ~59.7s of media.
 */
internal const val AUDIO_CLIP_SAFETY_MARGIN_US = 300_000L

/**
 * Media3 Composition (still-image sequence + audio) often jumps to ~50% the moment the
 * near-instant image track finishes. Treat that floor as "not started" and stretch the
 * remaining half (audio encode) across the full 0–100% UI bar.
 */
private const val IMAGE_TRACK_PROGRESS_FLOOR = 50

/** Maps Media3 raw progress so the UI starts at 0% and tracks audio encode only. */
internal fun remapConversionProgress(rawPercent: Int): Int {
    val remaining = 100 - IMAGE_TRACK_PROGRESS_FLOOR
    return (((rawPercent - IMAGE_TRACK_PROGRESS_FLOOR) * 100) / remaining).coerceIn(0, 100)
}

/** User-facing segment failure reasons (no English raw strings to UI). */
internal enum class ConversionSegmentError(val userMessage: String) {
    SEGMENT_RANGE_INVALID("선택한 구간이 올바르지 않아 변환할 수 없습니다."),
    SEGMENT_TOO_SHORT_AFTER_MARGIN("선택한 구간이 너무 짧아 변환할 수 없습니다."),
}

internal class ConversionSegmentException(
    val error: ConversionSegmentError,
) : Exception(error.userMessage)

/** Clipped audio window after applying [AUDIO_CLIP_SAFETY_MARGIN_US]. */
internal data class AudioClipWindow(
    val startUs: Long,
    val endUs: Long,
) {
    val durationUs: Long get() = endUs - startUs
}

/**
 * Planned half-open duration after safety-margin trim (export length).
 * May be shorter than `endUs - startUs` by up to [marginUs]; see [AUDIO_CLIP_SAFETY_MARGIN_US] SSOT.
 */
internal fun effectiveClipDurationUs(
    startUs: Long,
    endUs: Long,
    marginUs: Long = AUDIO_CLIP_SAFETY_MARGIN_US,
): Long = (endUs - marginUs).coerceAtLeast(startUs) - startUs

/**
 * Validates `0 <= segmentStartUs < segmentEndUs <= audioDurationUs`.
 * Pure — no Android / Transformer dependency.
 */
internal fun validateSegmentRange(
    segmentStartUs: Long,
    segmentEndUs: Long,
    audioDurationUs: Long,
): Result<Unit> {
    if (segmentStartUs < 0 ||
        segmentStartUs >= segmentEndUs ||
        segmentEndUs > audioDurationUs
    ) {
        return Result.failure(ConversionSegmentException(ConversionSegmentError.SEGMENT_RANGE_INVALID))
    }
    return Result.success(Unit)
}

/**
 * Builds the export clip window by subtracting [marginUs] from [segmentEndUs].
 * Fails when the resulting duration is zero (range shorter than or equal to the margin).
 */
internal fun computeClipWindow(
    segmentStartUs: Long,
    segmentEndUs: Long,
    marginUs: Long = AUDIO_CLIP_SAFETY_MARGIN_US,
): Result<AudioClipWindow> {
    val clipEndUs = (segmentEndUs - marginUs).coerceAtLeast(segmentStartUs)
    val durationUs = clipEndUs - segmentStartUs
    if (durationUs <= 0L) {
        return Result.failure(
            ConversionSegmentException(ConversionSegmentError.SEGMENT_TOO_SHORT_AFTER_MARGIN),
        )
    }
    return Result.success(AudioClipWindow(startUs = segmentStartUs, endUs = clipEndUs))
}

sealed class ConversionResult {
    data class Success(val outputFile: File) : ConversionResult()
    data class Failure(val message: String) : ConversionResult()
}

sealed class ConversionEvent {
    data class Progress(val percent: Int) : ConversionEvent()
    data class Done(val result: ConversionResult) : ConversionEvent()
}

/** Internal callback seam used by JVM tests without replacing the production Media3 API. */
internal interface VideoConverterExportListener {
    fun onCompleted()
    fun onError(exportException: ExportException)
}

/** Internal handle for the small part of Transformer that [VideoConverter] drives. */
internal interface VideoConverterExport {
    fun start(composition: Composition, outputPath: String)
    fun cancel()
    fun readProgressPercent(): Int?
}

/** Internal platform seam; the default implementation is the real Media3/Android path. */
internal interface VideoConverterPlatform {
    suspend fun applyWatermark(sourceFile: File, cacheDir: File): File
    suspend fun detectImageMimeType(file: File): String
    fun createExport(listener: VideoConverterExportListener): VideoConverterExport

    /** Observes terminal attempts before the event is offered to the callbackFlow channel. */
    fun recordTerminalAttempt(result: ConversionResult) = Unit
}

/**
 * Serializes terminal commitment against cancellation. The callback observer is deliberately
 * separate from channel delivery so tests can assert attempted Done emissions even when a channel
 * is already closed by collector cancellation.
 */
internal class ConversionTerminalGate(
    private val onTerminalAttempt: (ConversionResult) -> Unit,
) {
    private val lock = Any()
    private var isCancelled = false
    private var isCommitted = false
    private var committedSuccess = false

    fun tryCommit(result: ConversionResult): Boolean {
        val committed = synchronized(lock) {
            if (isCancelled || isCommitted) {
                false
            } else {
                isCommitted = true
                committedSuccess = result is ConversionResult.Success
                true
            }
        }
        if (committed) {
            runCatching { onTerminalAttempt(result) }
                .onFailure { exception ->
                    AppLogger.w(
                        "VideoConverter",
                        "terminal attempt observer failed: ${exception.javaClass.simpleName}",
                        exception,
                    )
                }
        }
        return committed
    }

    fun cancel() {
        synchronized(lock) {
            if (!isCommitted) {
                isCancelled = true
            }
        }
    }

    fun hasCommittedSuccess(): Boolean = synchronized(lock) { committedSuccess }
}

private class Media3VideoConverterPlatform(
    private val context: Context,
) : VideoConverterPlatform {
    override suspend fun applyWatermark(sourceFile: File, cacheDir: File): File =
        withContext(Dispatchers.IO) { WatermarkCompositor.applyWatermark(sourceFile, cacheDir) }

    override suspend fun detectImageMimeType(file: File): String = withContext(Dispatchers.IO) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        options.outMimeType ?: run {
            AppLogger.w(TAG, "outMimeType null for ${file.name}, falling back to JPEG")
            MimeTypes.IMAGE_JPEG
        }
    }

    override fun createExport(listener: VideoConverterExportListener): VideoConverterExport {
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(
                object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        listener.onCompleted()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        listener.onError(exportException)
                    }
                },
            )
            .build()

        return object : VideoConverterExport {
            private val progressHolder = ProgressHolder()

            override fun start(composition: Composition, outputPath: String) {
                transformer.start(composition, outputPath)
            }

            override fun cancel() {
                transformer.cancel()
            }

            override fun readProgressPercent(): Int? {
                return if (transformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    remapConversionProgress(progressHolder.progress)
                } else {
                    null
                }
            }
        }
    }

    private companion object {
        const val TAG = "VideoConverter"
    }
}

/** Combines a still background image with an audio track into an MP4 using Media3 Transformer. */
class VideoConverter internal constructor(
    private val context: Context,
    private val platform: VideoConverterPlatform,
) {

    constructor(context: Context) : this(context, Media3VideoConverterPlatform(context))

    /** Returns the audio's duration in microseconds, or null if it can't be read. */
    fun audioDurationUsOrNull(audioUri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, audioUri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.let { it * 1000 }
        } catch (e: Exception) {
            AppLogger.w(TAG, "audioDurationUsOrNull failed: ${e.javaClass.simpleName}", e)
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * Builds a localized user-facing failure message; diagnostic details stay in [AppLogger].
     *
     * Special case: if any [Throwable] in [exportException]'s cause chain has a stack frame for
     * `ExoPlayerAssetLoader.onTimelineChanged` (the known symptom of Media3 failing to reconcile
     * audio duration metadata), returns [VIDEO_CONVERTER_AUDIO_DURATION_INCONSISTENT_MESSAGE]
     * from [ConversionWorker] — which must stay in sync with this detection logic.
     * All other failures fall back to [R.string.conversion_failed_fallback].
     */
    private fun describeExportFailure(exportException: ExportException): String {
        var current: Throwable? = exportException
        val visited = mutableSetOf<Throwable>()
        while (current != null && visited.add(current)) {
            for (frame in current.stackTrace) {
                if (frame.className == "androidx.media3.transformer.ExoPlayerAssetLoader" &&
                    frame.methodName == "onTimelineChanged"
                ) {
                    return VIDEO_CONVERTER_AUDIO_DURATION_INCONSISTENT_MESSAGE
                }
            }
            current = current.cause
        }
        return context.getString(R.string.conversion_failed_fallback)
    }

    /**
     * Starts the export and emits progress until [ConversionEvent.Done]. Can be collected from any
     * thread: [Transformer] itself is built, started, polled and cancelled on [Dispatchers.Main]
     * (it requires a thread with a Looper), decoupled from the collector via [flowOn].
     *
     * Segment range must satisfy `0 <= segmentStartUs < segmentEndUs <= audioDurationUs`.
     * Defaults (`0` .. [audioDurationUs]) keep the legacy full-file path for existing callers.
     *
     * **Image ↔ audio duration policy:** both tracks use the same clipped length from
     * [computeClipWindow] / [effectiveClipDurationUs] (planned end minus
     * [AUDIO_CLIP_SAFETY_MARGIN_US]). Planner MIN is the planned length; export is shorter by
     * the margin (see glossary SSOT).
     */
    fun convert(
        backgroundImageFile: File,
        audioUri: Uri,
        audioDurationUs: Long,
        outputFile: File,
        segmentStartUs: Long = 0,
        segmentEndUs: Long = audioDurationUs,
        // Phase 1: replace applyWatermark=true with !isPro
        applyWatermark: Boolean = true,
    ): Flow<ConversionEvent> = callbackFlow {
        val terminalGate = ConversionTerminalGate { result -> platform.recordTerminalAttempt(result) }
        var tempWatermarkedFile: File? = null
        var export: VideoConverterExport? = null
        var pollingJob: Job? = null
        var outputStartAttempted = false
        var outputExistedBeforeStart = false
        val cleanupStarted = AtomicBoolean(false)

        fun deleteSafely(file: File?, label: String) {
            runCatching {
                if (file == null || !file.exists()) return@runCatching
                if (!file.delete()) {
                    AppLogger.w(TAG, "$label cleanup delete failed")
                }
            }.onFailure { exception ->
                AppLogger.w(TAG, "$label cleanup failed: ${exception.javaClass.simpleName}", exception)
            }
        }

        fun cleanup() {
            if (!cleanupStarted.compareAndSet(false, true)) return
            runCatching { terminalGate.cancel() }
                .onFailure { exception ->
                    AppLogger.w(TAG, "terminal cancellation failed: ${exception.javaClass.simpleName}", exception)
                }
            runCatching { pollingJob?.cancel() }
                .onFailure { exception ->
                    AppLogger.w(TAG, "progress polling cancel failed: ${exception.javaClass.simpleName}", exception)
                }
            runCatching { export?.cancel() }
                .onFailure { exception ->
                    AppLogger.w(TAG, "export cancel failed: ${exception.javaClass.simpleName}", exception)
                }
            runCatching {
                if (outputStartAttempted && !outputExistedBeforeStart && !terminalGate.hasCommittedSuccess()) {
                    deleteSafely(outputFile, "partial output")
                }
            }.onFailure { exception ->
                AppLogger.w(TAG, "partial output cleanup failed: ${exception.javaClass.simpleName}", exception)
            }
            runCatching { deleteSafely(tempWatermarkedFile, "temporary watermark") }
                .onFailure { exception ->
                    AppLogger.w(TAG, "temporary watermark cleanup failed: ${exception.javaClass.simpleName}", exception)
                }
        }

        fun emitDone(result: ConversionResult) {
            if (!terminalGate.tryCommit(result)) return
            trySend(ConversionEvent.Done(result))
            close()
        }

        try {
            coroutineContext.ensureActive()
            val rangeError = validateSegmentRange(segmentStartUs, segmentEndUs, audioDurationUs)
                .exceptionOrNull()
            if (rangeError != null) {
                val message = (rangeError as? ConversionSegmentException)?.error?.userMessage
                    ?: ConversionSegmentError.SEGMENT_RANGE_INVALID.userMessage
                emitDone(ConversionResult.Failure(message))
                return@callbackFlow
            }

            // Some recorders emit MP3s without an accurate Xing/VBR seek header. Clipping the
            // audio pins the exposed duration while the safety margin stays ahead of EOF correction.
            val clipResult = computeClipWindow(segmentStartUs, segmentEndUs)
            val clipWindow = clipResult.getOrNull()
            if (clipWindow == null) {
                val message = (clipResult.exceptionOrNull() as? ConversionSegmentException)
                    ?.error?.userMessage
                    ?: ConversionSegmentError.SEGMENT_TOO_SHORT_AFTER_MARGIN.userMessage
                emitDone(ConversionResult.Failure(message))
                return@callbackFlow
            }
            val clipDurationUs = clipWindow.durationUs

            val effectiveImageFile = if (applyWatermark) {
                val composited = platform.applyWatermark(backgroundImageFile, context.cacheDir)
                if (composited.absolutePath != backgroundImageFile.absolutePath) {
                    tempWatermarkedFile = composited
                }
                composited
            } else {
                backgroundImageFile
            }

            // Image duration matches the clipped audio duration. The explicit image MIME and
            // duration keep Media3 on ImageAssetLoader instead of treating the image as audio.
            val imageMediaItem = MediaItem.Builder()
                .setUri(Uri.fromFile(effectiveImageFile))
                .setMimeType(platform.detectImageMimeType(effectiveImageFile))
                .setImageDurationMs(clipDurationUs / 1000)
                .build()
            val imageItem = EditedMediaItem.Builder(imageMediaItem)
                .setDurationUs(clipDurationUs)
                .setFrameRate(VIDEO_FRAME_RATE)
                .setEffects(Effects(emptyList(), listOf(Presentation.createForShortSide(VIDEO_SHORT_SIDE_PX))))
                .build()
            val audioMediaItem = MediaItem.Builder()
                .setUri(audioUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionUs(clipWindow.startUs)
                        .setEndPositionUs(clipWindow.endUs)
                        .build(),
                )
                .build()
            val audioItem = EditedMediaItem.Builder(audioMediaItem)
                .setRemoveVideo(true)
                .build()
            val composition = Composition.Builder(
                EditedMediaItemSequence.Builder(listOf(imageItem)).build(),
                EditedMediaItemSequence.Builder(listOf(audioItem)).build(),
            ).build()

            val listener = object : VideoConverterExportListener {
                override fun onCompleted() {
                    emitDone(ConversionResult.Success(outputFile))
                }

                override fun onError(exportException: ExportException) {
                    AppLogger.e(
                        TAG,
                        "Export failed: ${exportException.errorCodeName} (${exportException.errorCode})",
                        exportException,
                    )
                    emitDone(ConversionResult.Failure(describeExportFailure(exportException)))
                }
            }

            export = platform.createExport(listener)
            outputExistedBeforeStart = outputFile.exists()
            outputStartAttempted = true
            export!!.start(composition, outputFile.absolutePath)
            trySend(ConversionEvent.Progress(0))

            pollingJob = launch {
                while (isActive) {
                    export?.readProgressPercent()?.let { percent ->
                        trySend(ConversionEvent.Progress(percent))
                    }
                    delay(PROGRESS_POLL_INTERVAL_MS)
                }
            }

            awaitClose {
                cleanup()
            }
        } finally {
            cleanup()
        }
    }.flowOn(Dispatchers.Main)

    private companion object {
        const val TAG = "VideoConverter"
    }
}
