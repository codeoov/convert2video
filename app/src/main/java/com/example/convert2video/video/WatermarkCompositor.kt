package com.example.convert2video.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

private const val TAG = "WatermarkCompositor"
private const val MARGIN_FRACTION = 0.05f
private const val WATERMARK_ALPHA = 77
private const val WATERMARK_TEXT = "Created by convert2video"
private const val JPEG_QUALITY = 92

/**
 * Composites a text watermark onto a background image and writes the result to [cacheDir].
 *
 * Phase 1: text-only watermark at the bottom-right corner.
 * TODO: logo drawable (res/drawable/watermark_logo) — add asset before enabling.
 */
internal object WatermarkCompositor {

    /**
     * Decodes [sourceFile], draws the watermark, and writes the result as JPEG to [cacheDir].
     *
     * Returns the composited [File] on success, or [sourceFile] unchanged when decoding fails
     * (no exception is propagated for decode/IO failures; [CancellationException] is re-thrown).
     */
    fun applyWatermark(sourceFile: File, cacheDir: File): File {
        return try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val source = BitmapFactory.decodeFile(sourceFile.absolutePath, opts)
            if (source == null) {
                AppLogger.w(TAG, "watermark decode failed, using original")
                return sourceFile
            }

            // Copy first, recycle source regardless of copy outcome
            val mutable = source.copy(Bitmap.Config.ARGB_8888, true)
            source.recycle()
            if (mutable == null) {
                AppLogger.w(TAG, "watermark copy null, using original")
                return sourceFile
            }

            try {
                drawWatermark(mutable)

                // NEW-1: UUID prevents filename collision when two conversions overlap
                val outFile = File(cacheDir, "watermarked_${UUID.randomUUID()}.jpg")
                try {
                    val compressed = FileOutputStream(outFile).use { fos ->
                        mutable.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
                    }
                    if (!compressed) {
                        // NEW-6: check delete() return, log w on failure (never log path)
                        val deleted = outFile.delete()
                        if (!deleted) AppLogger.w(TAG, "watermark compress=false; outFile delete failed")
                        AppLogger.w(TAG, "watermark compress returned false, using original")
                        sourceFile
                    } else {
                        outFile
                    }
                } catch (e: IOException) {
                    // NEW-2: IO failure after outFile created → cleanup with return-check
                    val deleted = outFile.delete()
                    if (!deleted) AppLogger.w(TAG, "watermark IO cleanup: outFile delete failed")
                    AppLogger.w(TAG, "watermark IO error, using original", e)
                    sourceFile
                }
            } finally {
                mutable.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "watermark failed, using original", e)
            sourceFile
        }
    }

    private fun drawWatermark(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)
        val width = bitmap.width
        val height = bitmap.height
        val margin = height * MARGIN_FRACTION

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = WATERMARK_ALPHA
            textSize = height * 0.03f
        }

        val textWidth = paint.measureText(WATERMARK_TEXT)
        val x = (width - textWidth - margin).coerceAtLeast(margin)
        // NEW-3: subtract descent so bottom glyphs (g, y, p …) are not clipped
        val y = height - margin - paint.fontMetrics.descent

        canvas.drawText(WATERMARK_TEXT, x, y, paint)
    }
}
