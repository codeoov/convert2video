package com.example.convert2video.ui.shared

import java.util.concurrent.TimeUnit

/**
 * UI duration display styles that share the same millisecond→second flooring.
 *
 * - [Timer]: Record elapsed — `mm:ss` under 1h (zero-padded minutes), `h:mm:ss` at/above 1h.
 * - [ListRow]: AudioPick (and similar list rows) — `m:ss` without hour split; minutes may exceed 59
 *   to keep historical row density. Intentional difference from [Timer], not a second bug.
 */
enum class MediaDurationStyle {
    Timer,
    ListRow,
}

/**
 * Shared media-duration formatter for Compose UI (Record timer / AudioPick list).
 *
 * Non-negative input is floored to whole seconds and formatted as-is — including very large
 * values (hours / many minutes). Callers own any UI-side capping or truncation; this function
 * does not clamp an upper bound.
 */
fun formatMediaDurationMs(
    durationMs: Long,
    style: MediaDurationStyle,
): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(durationMs.coerceAtLeast(0L))
    return when (style) {
        MediaDurationStyle.Timer -> {
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%02d:%02d".format(minutes, seconds)
            }
        }
        MediaDurationStyle.ListRow -> {
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            "%d:%02d".format(minutes, seconds)
        }
    }
}

/**
 * 분당 대략적인 용량을 1MB 단위로 반올림해 "1MB" / "5MB" 형태 문자열로 반환한다
 * ([com.example.convert2video.record.RecordingStorageEstimator.approxBytesPerMinute] 결과 포맷).
 * 최소 1MB로 하한 — 0MB로 보이는 것을 방지(반올림으로 0이 되는 극단값이어도 "대략적인 용량" 표시 의도상 0은 부적절).
 */
fun formatApproxSizePerMinute(bytesPerMinute: Long): String {
    val megabytes = ((bytesPerMinute + BYTES_PER_MB / 2) / BYTES_PER_MB).coerceAtLeast(1L)
    return "${megabytes}MB"
}

private const val BYTES_PER_MB = 1_000_000L
