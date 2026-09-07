package com.example.convert2video

import android.app.Application
import android.content.Context
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.SerialExecutor
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Non-null WorkManager collaborators for direct Worker.doWork() unit tests.
 * WorkerParameters exposes these types in its public constructor, but WorkManager restricts
 * TaskExecutor to the library group and this project has no work-testing fake dependency.
 */
internal fun workerParametersForTest(inputData: Data): WorkerParameters = WorkerParameters(
    UUID.randomUUID(),
    inputData,
    emptyList(),
    WorkerParameters.RuntimeExtras(),
    0,
    0,
    DirectExecutor,
    EmptyCoroutineContext,
    DirectTaskExecutor,
    NoOpWorkerFactory,
    NoOpProgressUpdater,
    NoOpForegroundUpdater,
)

/** Unattached Application has no onCreate/Room lifecycle and is sufficient for the gate path. */
internal fun workerContextForTest(): Context = Application()

private object DirectExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}

private object DirectSerialExecutor : SerialExecutor {
    override fun execute(command: Runnable) = command.run()

    override fun hasPendingTasks(): Boolean = false
}

private object DirectTaskExecutor : TaskExecutor {
    override fun getMainThreadExecutor(): Executor = DirectExecutor

    override fun getSerialTaskExecutor(): SerialExecutor = DirectSerialExecutor
}

private object NoOpWorkerFactory : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = null
}

private object NoOpProgressUpdater : ProgressUpdater {
    override fun updateProgress(
        context: Context,
        id: UUID,
        data: Data,
    ): ListenableFuture<Void> = completedVoidFuture()
}

private object NoOpForegroundUpdater : ForegroundUpdater {
    override fun setForegroundAsync(
        context: Context,
        id: UUID,
        foregroundInfo: androidx.work.ForegroundInfo,
    ): ListenableFuture<Void> = completedVoidFuture()
}

private fun completedVoidFuture(): ListenableFuture<Void> =
    SettableFuture.create<Void>().also { it.set(null) }
