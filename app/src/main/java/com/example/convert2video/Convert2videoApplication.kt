package com.example.convert2video

import android.app.Application
import com.example.convert2video.analytics.AnalyticsReporter
import com.example.convert2video.analytics.CrashReporter
import com.example.convert2video.billing.EntitlementRepository
import com.example.convert2video.billing.createBillingGateway
import com.example.convert2video.data.ErrorLogEntry
import com.example.convert2video.data.ErrorLogRepository
import com.example.convert2video.data.SettingsRepository
import com.example.convert2video.data.TrashPurgeWorker
import com.example.convert2video.record.QuickRecordWidgetStateSync
import com.example.convert2video.utils.AppLogger
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Application 서브클래스.
 *
 * Activity·Worker 생명주기와 무관하게 persist sink를 설치한다.
 * ConversionWorker/YouTubeUploadWorker/TrashPurgeWorker 등 백그라운드 Worker가 Activity 없이 실행되는
 * 상황에서도 E/W 로그가 유실되지 않도록 Application.onCreate()에서 싱크를 등록한다.
 *
 * 코루틴 스코프: SupervisorJob + Dispatchers.IO — 단일 insert 실패가 다른 insert에 영향을 주지 않는다.
 */
class Convert2videoApplication : Application() {

    /** Application 수명과 일치하는 I/O 스코프. */
    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Glance QuickRecord 위젯 state sync — Main.immediate.
     * Application 수명과 동일; 프로세스 kill 시 cancel (표준 Android, onTerminate 미호출).
     */
    internal val widgetSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        private const val TAG = "Convert2videoApp"

        /** 결제 제거 전 사용하던 entitlement 갱신 unique work 이름 — 잔존 예약분 정리용. */
        private const val LEGACY_ENTITLEMENT_REFRESH_WORK_NAME = "entitlement_refresh"
    }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        appScope.launch {
            try {
                CrashReporter.flushPendingCrashIfAny(this@Convert2videoApplication) { stack ->
                    AnalyticsReporter.getInstance(this@Convert2videoApplication).postCrash(stack)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "flush pending crash failed: ${e.javaClass.simpleName}")
            }
        }
        val errorLogRepository = ErrorLogRepository.create(applicationContext)
        AppLogger.installPersistSink { level, tag, msg, throwable ->
            // E/W만 저장 — 필터링은 싱크 안에서도 재확인(AppLogger.d는 invoke하지 않지만 방어적으로)
            if (level == "E" || level == "W") {
                appScope.launch {
                    runCatching {
                        errorLogRepository.insert(
                            ErrorLogEntry(
                                level = level,
                                tag = tag,
                                message = msg,
                                stackTrace = throwable?.stackTraceToString(),
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            }
        }
        // 프로세스 시작 시점에 noiseReductionModeHot를 미리 시딩한다.
        // 예약 녹음/타일 트리거 시나리오에서 RecordingEngine.create()가 사용자 설정을 읽을 수 있도록.
        // noiseReductionMode Flow의 onEach가 hot cache를 채우는 부작용에 의존 — SettingsRepository.kt의 Flow 정의 변경 시 확인 필요
        appScope.launch {
            runCatching { SettingsRepository(applicationContext).noiseReductionMode.first() }
                .onFailure { e -> AppLogger.w(TAG, "noiseReductionMode warm-up failed: ${e.message}", e) }
        }
        QuickRecordWidgetStateSync.install(this, widgetSyncScope)
        TrashPurgeWorker.schedulePeriodicWork(this)
        try {
            WorkManager.getInstance(this)
                .cancelUniqueWork(LEGACY_ENTITLEMENT_REFRESH_WORK_NAME)
        } catch (e: Exception) {
            AppLogger.e(TAG, "cancelUniqueWork(legacy entitlement_refresh) failed: ${e.javaClass.simpleName}")
        }
        val entitlementRepository = EntitlementRepository.getInstance(applicationContext)
        val billingGateway = createBillingGateway(applicationContext)
        if (!entitlementRepository.attachGateway(billingGateway)) {
            AppLogger.w(TAG, "billing gateway attach failed")
            billingGateway.close()
        }
    }
}
