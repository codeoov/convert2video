package com.example.convert2video.record

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** Glance 1×1 QuickRecord 위젯 [android.appwidget.AppWidgetProvider] 진입점. */
class QuickRecordWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = quickRecordGlanceWidget
}

/** Receiver·ActionCallback·StateSync가 공유하는 단일 [GlanceAppWidget] 인스턴스. */
internal val quickRecordGlanceWidget: GlanceAppWidget = QuickRecordWidget()
