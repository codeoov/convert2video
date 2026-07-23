package com.example.convert2video.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Frames per second for the still-image video track: static content needs no more than this to look correct. */
private const val VIDEO_FRAME_RATE = 2

/** Caps the image's shorter side so a large photo doesn't inflate the encoded video bitrate. */
private const val VIDEO_SHORT_SIDE_PX = 720

private const val PROGRESS_POLL_INTERVAL_MS = 300L

sealed class ConversionResult {
    data class Success(val outputFile: File) : ConversionResult()
    data class Failure(val message: String) : ConversionResult()
}

sealed class ConversionEvent {
    data class Progress(val percent: Int) : ConversionEvent()
    data class Done(val result: ConversionResult) : ConversionEvent()
}

/** Combines a still background image with an audio track into an MP4 using Media3 Transformer. */
class VideoConverter(private val context: Context) {

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
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * Starts the export and emits progress until [ConversionEvent.Done]. Must be collected from
     * the main thread: [Transformer] requires being built and driven from a thread with a Looper.
     */
    fun convert(
        backgroundImageFile: File,
        audioUri: Uri,
        audioDurationUs: Long,
        outputFile: File,
    ): Flow<ConversionEvent> = callbackFlow {
        val imageItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(backgroundImageFile)))
            .setDurationUs(audioDurationUs)
            .setFrameRate(VIDEO_FRAME_RATE)
            .setEffects(Effects(emptyList(), listOf(Presentation.createForShortSide(VIDEO_SHORT_SIDE_PX))))
            .build()
        val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(audioUri))
            .setRemoveVideo(true)
            .build()
        val composition = Composition.Builder(
            EditedMediaItemSequence.Builder(listOf(imageItem)).build(),
            EditedMediaItemSequence.Builder(listOf(audioItem)).build(),
        ).build()

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                trySend(ConversionEvent.Done(ConversionResult.Success(outputFile)))
                close()
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                val message = exportException.message ?: exportException.errorCodeName
                trySend(ConversionEvent.Done(ConversionResult.Failure(message)))
                close()
            }
        }

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(listener)
            .build()

        transformer.start(composition, outputFile.absolutePath)

        val progressHolder = ProgressHolder()
        val pollingJob = launch {
            while (isActive) {
                val state = transformer.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    trySend(ConversionEvent.Progress(progressHolder.progress))
                }
                delay(PROGRESS_POLL_INTERVAL_MS)
            }
        }

        awaitClose {
            pollingJob.cancel()
            runCatching { transformer.cancel() }
        }
    }
}
