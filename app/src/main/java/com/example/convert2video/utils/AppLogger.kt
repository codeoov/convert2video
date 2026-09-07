package com.example.convert2video.utils

import android.util.Log
import com.example.convert2video.BuildConfig

/** Thin wrapper around [android.util.Log].
 *
 * All app code must route through this object — direct `Log.*` calls are forbidden by
 * scripts/check-logging-forbidden.ps1 in every file except this one.
 */
object AppLogger {

    /**
     * 영속 싱크(persist sink). 설치 후 e/w 호출 시 비동기 저장용으로 함께 호출된다.
     * null이면 비활성. @Volatile로 스레드-세이프 읽기를 보장한다.
     */
    @Volatile
    private var persistSink: ((level: String, tag: String, msg: String, throwable: Throwable?) -> Unit)? = null

    /**
     * 영속 싱크를 설치한다. [sink]는 e/w 호출마다 level("E"/"W"), tag, msg, throwable 을 받는다.
     * d() 호출 시에는 싱크가 호출되지 않는다(오버헤드 절감 + 계약 오염 방지).
     * 싱크 내부에서 다시 AppLogger를 호출하면 무한 재귀가 발생하므로 sink 본문에서는 직접 DB insert만 할 것.
     */
    fun installPersistSink(sink: (level: String, tag: String, msg: String, throwable: Throwable?) -> Unit) {
        persistSink = sink
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, msg, throwable)
        } else {
            Log.e(tag, msg)
        }
        // runCatching: sink 내부 예외가 호출자 스레드로 전파되지 않도록 AppLogger 레이어 자체가 방어
        runCatching { persistSink?.invoke("E", tag, msg, throwable) }
    }

    fun w(tag: String, msg: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(tag, msg, throwable)
        } else {
            Log.w(tag, msg)
        }
        // runCatching: sink 내부 예외가 호출자 스레드로 전파되지 않도록 AppLogger 레이어 자체가 방어
        runCatching { persistSink?.invoke("W", tag, msg, throwable) }
    }

    /**
     * Test-only: uninstall persist sink (`persistSink = null`).
     * Production uses [installPersistSink] only — do not call from Application.onCreate.
     */
    fun clearPersistSinkForTests() {
        persistSink = null
    }

    fun d(tag: String, msg: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable != null) {
                Log.d(tag, msg, throwable)
            } else {
                Log.d(tag, msg)
            }
        }
        // d() 레벨은 영속 싱크 대상이 아님 — E/W만 저장
    }

    fun i(tag: String, msg: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable != null) {
                Log.i(tag, msg, throwable)
            } else {
                Log.i(tag, msg)
            }
        }
        // i() 레벨은 영속 싱크 대상이 아님 — E/W만 저장
    }

    /**
     * persistSink를 타지 않는 로컬 전용 에러 로그.
     *
     * 사용 대상: sink 자기 참조 재귀를 유발할 수 있는 코드(ErrorLogViewModel 삭제 실패 등).
     * raw [Log] 호출은 AppLogger.kt 밖으로 나가지 않도록, 이 함수를 경유한다.
     */
    fun eLocal(tag: String, msg: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, msg, throwable)
        } else {
            Log.e(tag, msg)
        }
        // persistSink 호출 없음 — 의도적 설계
    }
}
