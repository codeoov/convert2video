package com.example.convert2video.billing

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.convert2video.store.StoreCapabilities
import com.example.convert2video.utils.AppLogger
import com.example.convert2video.utils.installId
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal const val BILLING_WORKFLOW_TIMEOUT_MILLIS = 2 * 60 * 1000L

/** Process-wide entitlement reconciliation and persistence boundary. */
internal interface EntitlementVerifier {
    suspend fun verify(
        installId: String,
        store: String,
        productId: String,
        purchaseToken: String,
    ): EntitlementApiResult

    suspend fun refresh(jwt: String, installId: String): EntitlementApiResult
}

/** Provider-neutral result of an explicit owned-purchase restore request. */
internal sealed interface RestoreResult {
    /** At least one owned Purchased item was backend-verified and committed. */
    data object Restored : RestoreResult

    /** Only provider Pending purchases or retryable verification state remains. */
    data object Pending : RestoreResult

    /** Restore cannot succeed for this gateway/install/purchase set. */
    data object Unavailable : RestoreResult

    /** A non-terminal provider, network, or storage failure occurred. */
    data class Failed(val kind: BillingFailureKind) : RestoreResult
}

/** Token-free terminal result exposed to the Options ViewModel after verification/commit. */
internal sealed interface PurchaseCompletionResult {
    data object Purchased : PurchaseCompletionResult
    data object Pending : PurchaseCompletionResult
    data object Cancelled : PurchaseCompletionResult
    data object Unavailable : PurchaseCompletionResult
    data class Failed(val kind: BillingFailureKind) : PurchaseCompletionResult
}

/** Opaque identity issued by the billing actor for one admitted purchase operation. */
@JvmInline
internal value class BillingOperationId private constructor(private val rawValue: String) {
    internal companion object {
        fun issue(): BillingOperationId = BillingOperationId(UUID.randomUUID().toString())
    }
}

/** Actor-issued purchase admission. The completion is never exposed outside this repository. */
internal class PurchaseLaunchSession internal constructor(
    internal val id: Long,
    internal val generation: Long,
    internal val store: String,
    internal val operationId: BillingOperationId,
    /** Provider purchase tokens already owned before this operation was launched. */
    internal val baselinePurchaseTokens: Set<String>,
) {
    internal val completion = CompletableDeferred<PurchaseCompletionResult>()
    internal var correlatedPurchaseToken: String? = null
}

/** Provider-neutral observations used to fold mixed owned-purchase restore results. */
internal sealed interface RestoreObservation {
    data object Verified : RestoreObservation
    data object Pending : RestoreObservation
    data object Unavailable : RestoreObservation
    data class Failed(val kind: BillingFailureKind) : RestoreObservation
}

/**
 * Folds restore observations without allowing an unavailable/failed item to hide a verified one.
 * Pending is intentionally stronger than an unavailable item, but never stronger than success.
 */
internal fun restoreResultFor(
    hasOwnedPro: Boolean,
    observations: List<RestoreObservation>,
): RestoreResult = when {
    observations.any { it === RestoreObservation.Verified } -> RestoreResult.Restored
    observations.any { it === RestoreObservation.Pending } -> RestoreResult.Pending
    else -> {
        val failure = observations.filterIsInstance<RestoreObservation.Failed>().firstOrNull()
        when {
            failure != null -> RestoreResult.Failed(failure.kind)
            !hasOwnedPro -> RestoreResult.Unavailable
            else -> RestoreResult.Unavailable
        }
    }
}

/** Non-success restore outcomes never revoke a still-valid cached entitlement. */
internal fun restoreProStatusFor(
    cachedPro: Boolean,
    result: RestoreResult,
): Boolean = when (result) {
    RestoreResult.Restored -> true
    RestoreResult.Pending, RestoreResult.Unavailable -> cachedPro
    is RestoreResult.Failed -> result.kind == BillingFailureKind.Network && cachedPro
}

/** Maps backend restore failures without leaking HTTP details beyond the billing layer. */
internal fun restoreFailureObservationFor(
    kind: EntitlementFailureKind,
    httpCode: Int?,
): RestoreObservation = when {
    httpCode == 403 || httpCode == 409 -> RestoreObservation.Unavailable
    httpCode == 400 || httpCode == 422 -> RestoreObservation.Unavailable
    kind == EntitlementFailureKind.Network ||
        httpCode == 503 ||
        kind == EntitlementFailureKind.Unavailable ->
        RestoreObservation.Failed(BillingFailureKind.Network)
    kind == EntitlementFailureKind.InvalidResponse ->
        RestoreObservation.Failed(BillingFailureKind.InvalidResponse)
    else -> RestoreObservation.Failed(BillingFailureKind.ProviderError)
}

/**
 * A provider update can complete a launch only when its opaque purchase token is not part of the
 * pre-launch baseline. The caller additionally serializes the first accepted token into the
 * operation session, so a second token cannot be relabeled as the same operation.
 */
internal fun isPurchaseTokenAdmissibleForOperation(
    session: PurchaseLaunchSession,
    purchase: OwnedPurchase,
): Boolean = purchase.store == session.store &&
    purchase.purchaseToken !in session.baselinePurchaseTokens &&
    (session.correlatedPurchaseToken == null ||
        session.correlatedPurchaseToken == purchase.purchaseToken)

/** Process-wide entitlement reconciliation and persistence boundary. */
internal class EntitlementRepository(
    context: Context,
    storeOverride: EntitlementStore? = null,
    apiClientOverride: EntitlementVerifier? = null,
    processScopeOverride: CoroutineScope? = null,
    private val observeProcessLifecycle: Boolean = true,
    private val mainDispatcherOverride: CoroutineDispatcher? = null,
) : DefaultLifecycleObserver {
    private val appContext = context.applicationContext
    private val store: EntitlementStore = storeOverride ?: AndroidKeystoreEntitlementStore(appContext)
    private val apiClient: EntitlementVerifier = apiClientOverride ?: EntitlementApiClient()
    private val processScope = processScopeOverride ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isProHot = MutableStateFlow<Boolean?>(null)
    val isProHot: StateFlow<Boolean?> = _isProHot.asStateFlow()

    /*
     * One actor owns all authoritative state and consumes one bounded FIFO command stream. The
     * collector uses a single purchase admission slot so lifecycle/control commands retain mailbox
     * capacity; corruption staging closes that slot and makes the collector suspend until the
     * actor processes a startup/foreground/reattach command.
     */
    private val commands = Channel<Command>(COMMAND_MAILBOX_CAPACITY)
    private val actorJob: Job = processScope.launch { runActor() }
    private val closedForTest = AtomicBoolean(false)
    private val startRequestVersion = java.util.concurrent.atomic.AtomicLong(0L)
    private val startCommandQueued = AtomicBoolean(false)
    private val expectedCollectorStop = AtomicReference<Any?>(null)

    // The processScope actor owns these values; the atomic teardown seams may clear only the
    // purchase/collector references when the actor cannot service an abort command in time.
    @Volatile
    private var actorSession: GatewaySession? = null
    private val attachedGateway = AtomicReference<BillingGateway?>(null)
    private var actorGeneration = 0L
    // These two references are also read by the synchronous teardown fallback. Keeping the
    // actor-owned state atomic lets a timed-out abort remove provider admission even when the
    // actor is currently blocked in provider I/O.
    private val activePurchase = AtomicReference<PurchaseLaunchSession?>(null)
    /** Serializes synchronous provider handoff with the atomic timeout/abort teardown seam. */
    private val purchaseLaunchLock = Any()
    private var nextPurchaseId = 0L
    private val collector = AtomicReference<CollectorHandle?>(null)
    private var storageCorruptionLatched = false
    private var reconcileDirty = false
    private var reconcileRetryRequired = false
    private var reconcileSignalSession: GatewaySession? = null
    private val googleRetryState = RetryState()
    private val huaweiRetryState = RetryState()
    private var lastCommittedIdentity: PurchaseIdentity? = null
    private val observedPurchaseStates = LinkedHashMap<PurchaseIdentity, PurchaseState>()

    init {
        if (observeProcessLifecycle) {
            val lifecycle = ProcessLifecycleOwner.get().lifecycle
            lifecycle.addObserver(this)
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                requestStartReconcile()
            }
        }
    }

    /** Test-only lifecycle cleanup; production ownership is process-wide. */
    internal fun closeForTest() {
        if (!closedForTest.compareAndSet(false, true)) return
        if (observeProcessLifecycle) {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        }
        activePurchase.getAndSet(null)?.completion?.complete(
            PurchaseCompletionResult.Cancelled,
        )
        collector.getAndSet(null)?.job?.cancel()
        processScope.cancel()
        closeGateway(attachedGateway.getAndSet(null))
        runBlocking(NonCancellable) {
            withTimeoutOrNull(CLOSE_TIMEOUT_MILLIS) {
                actorJob.cancelAndJoin()
            }
        }
        commands.close()
        clearInstance(this)
    }

    /** Reads the storage-backed entitlement through the serialized actor. */
    suspend fun isProSnapshot(): Boolean {
        val reply = CompletableDeferred<Boolean>()
        check(processScope.isActive) { "Entitlement repository is not active" }
        // A public suspend caller waits safely if the bounded control mailbox is temporarily full.
        commands.send(Command.ReadSnapshot(reply))
        return reply.await()
    }

    /**
     * Returns true only when the bounded actor mailbox accepted the request. Because the actor is
     * the sole owner of authoritative gateway state, an accepted request may later linearize as
     * the actor's same-instance no-op; this method never claims that reconciliation completed.
     */
    internal fun attachGateway(gateway: BillingGateway): Boolean {
        if (!processScope.isActive) return false
        return commands.trySend(Command.Attach(gateway)).isSuccess
    }

    /**
     * Returns true only when the bounded actor mailbox accepted the request. A request for a
     * non-current gateway is rejected as a no-op when it reaches the actor; this fast method does
     * not inspect or mutate actor-owned state and therefore cannot report that later result.
     */
    internal fun detachGateway(gateway: BillingGateway): Boolean {
        if (!processScope.isActive) return false
        return commands.trySend(Command.Detach(gateway)).isSuccess
    }

    /** Prepares the attached provider without launching provider UI. */
    internal suspend fun preparePurchaseIntent(): BillingGatewayResult<Unit> {
        val reply = CompletableDeferred<BillingGatewayResult<Unit>>()
        check(processScope.isActive) { "Entitlement repository is not active" }
        commands.send(Command.PreparePurchase(reply))
        return reply.await()
    }

    /** Admission is actor-ordered so a launch can never borrow a stale/detached session. */
    internal suspend fun beginPurchaseSession(): PurchaseLaunchSession? {
        val reply = CompletableDeferred<PurchaseLaunchSession?>()
        check(processScope.isActive) { "Entitlement repository is not active" }
        return try {
            commands.send(Command.BeginPurchase(reply))
            reply.await()
        } catch (error: CancellationException) {
            // A timeout can cancel the caller between actor admission and reply delivery. Recover
            // the just-admitted session through the atomic teardown seam so it cannot outlive the
            // workflow that owns the purchase admission.
            withContext(NonCancellable) {
                activePurchase.get()?.let { orphaned ->
                    abortPurchase(orphaned, PurchaseCompletionResult.Cancelled)
                }
            }
            throw error
        }
    }

    /** Actor-owned launch command with a safe provider main-thread hand-off. */
    internal suspend fun launchPreparedPurchase(
        handoff: PurchaseLaunchSession,
        activity: android.app.Activity,
    ): BillingGatewayResult<Unit> {
        val reply = CompletableDeferred<BillingGatewayResult<Unit>>()
        check(processScope.isActive) { "Entitlement repository is not active" }
        return try {
            commands.send(Command.LaunchPurchase(handoff, activity, reply))
            reply.await()
        } catch (error: CancellationException) {
            // The caller can time out after the command is queued but before the main-thread
            // provider handoff. Remove admission synchronously so the queued command fails closed.
            forceAbortPurchaseCleanup(handoff, PurchaseCompletionResult.Cancelled)
            throw error
        }
    }

    internal suspend fun awaitPurchaseCompletion(
        session: PurchaseLaunchSession,
    ): PurchaseCompletionResult = session.completion.await()

    /** Idempotent actor-owned cleanup for launch failure, timeout, cancellation, and teardown. */
    internal suspend fun abortPurchase(
        session: PurchaseLaunchSession,
        result: PurchaseCompletionResult = PurchaseCompletionResult.Failed(
            BillingFailureKind.LaunchFailed,
        ),
    ) {
        if (!processScope.isActive || !actorJob.isActive) {
            session.completion.complete(result)
            return
        }
        val reply = CompletableDeferred<Unit>()
        val completed = try {
            withTimeoutOrNull(ABORT_COMMAND_TIMEOUT_MILLIS) {
                commands.send(Command.AbortPurchase(session, result, reply))
                reply.await()
            }
        } catch (error: Exception) {
            logError("purchase abort command failed", error)
            null
        }
        if (completed == null) {
            forceAbortPurchaseCleanup(session, result)
            AppLogger.w(TAG, "purchase abort command timed out")
        }
    }

    /** Non-suspending lifecycle seam used before ViewModel scope cancellation. */
    internal fun abortPurchaseImmediately(
        session: PurchaseLaunchSession,
        result: PurchaseCompletionResult = PurchaseCompletionResult.Cancelled,
    ): Boolean {
        if (!processScope.isActive || !actorJob.isActive) {
            forceAbortPurchaseCleanup(session, result)
            return false
        }
        val reply = CompletableDeferred<Unit>()
        return try {
            // onCleared runs outside the repository actor's suspend boundary. Waiting for the
            // actor to linearize this command is intentional: a dropped mailbox write could
            // leave a provider launch armed after the ViewModel has gone away.
            runBlocking(NonCancellable) {
                withTimeoutOrNull(ABORT_COMMAND_TIMEOUT_MILLIS) {
                    commands.send(Command.AbortPurchase(session, result, reply))
                    reply.await()
                } ?: run {
                    forceAbortPurchaseCleanup(session, result)
                    AppLogger.w(TAG, "purchase abort during teardown timed out")
                }
            }
            true
        } catch (error: CancellationException) {
            logError("purchase abort during teardown was cancelled", error)
            false
        } catch (error: Exception) {
            logError("purchase abort during teardown failed", error)
            forceAbortPurchaseCleanup(session, result)
            false
        }
    }

    /** Converts only Huawei's resolution ActivityResult; Google completion is callback-driven. */
    internal suspend fun handlePurchaseActivityResult(
        session: PurchaseLaunchSession,
        operationId: BillingOperationId?,
        resultCode: Int,
        intent: android.content.Intent?,
    ): PurchaseCompletionResult {
        val reply = CompletableDeferred<PurchaseCompletionResult>()
        check(processScope.isActive) { "Entitlement repository is not active" }
        if (operationId == null) return PurchaseCompletionResult.Unavailable
        commands.send(Command.PurchaseActivityResult(session, operationId, resultCode, intent, reply))
        return reply.await()
    }

    /** Compatibility bridge for legacy callers; an unadmitted/late result is rejected. */
    internal suspend fun handlePurchaseActivityResult(
        resultCode: Int,
        intent: android.content.Intent?,
    ): PurchaseCompletionResult {
        return PurchaseCompletionResult.Unavailable
    }

    /** Explicit restore entry point. The actor owns gateway lookup, query, verify, and commit. */
    internal suspend fun restorePurchase(): RestoreResult {
        val reply = CompletableDeferred<RestoreResult>()
        check(processScope.isActive) { "Entitlement repository is not active" }
        commands.send(Command.Restore(reply))
        return reply.await()
    }

    override fun onStart(owner: LifecycleOwner) {
        requestStartReconcile()
    }

    private fun requestStartReconcile() {
        if (!processScope.isActive) return
        startRequestVersion.incrementAndGet()
        enqueueStartIfNeeded()
    }

    private fun enqueueStartIfNeeded() {
        if (startRequestVersion.get() == 0L) return
        if (!startCommandQueued.compareAndSet(false, true)) return
        if (commands.trySend(Command.Start(startRequestVersion.get())).isFailure) {
            startCommandQueued.set(false)
        }
    }

    private suspend fun runActor() {
        while (true) {
            val command = commands.receiveCatching().getOrNull() ?: return
            processCommandBoundary(command)
            if (command is Command.StagePurchase) {
                retryStateFor(command.purchase.store).ingressSlots.release()
            }
            releaseBlockedStageIfPossible()
            actorSession?.let { openPurchaseIngress(it) }
            enqueueStartIfNeeded()
        }
    }

    private suspend fun processCommandBoundary(command: Command) {
        try {
            when (command) {
                is Command.Attach -> handleAttach(command.gateway)
                is Command.Detach -> handleDetach(command.gateway)
                is Command.PreparePurchase -> handlePreparePurchase(command.reply)
                is Command.BeginPurchase -> handleBeginPurchase(command.reply)
                is Command.LaunchPurchase -> handleLaunchPurchase(command)
                is Command.AbortPurchase -> handleAbortPurchase(command)
                is Command.Start -> handleStart()
                is Command.ReconcileSignal -> handleReconcileSignal(command.session)
                is Command.StagePurchase -> handleStagePurchase(command)
                is Command.PurchaseSignal -> handlePurchaseSignal(command)
                is Command.CollectorStopped -> handleCollectorStopped(command)
                is Command.ReadSnapshot -> handleReadSnapshot(command.reply)
                is Command.Restore -> handleRestore(command.reply)
                is Command.PurchaseActivityResult -> handlePurchaseActivityResult(command)
            }
        } catch (error: CancellationException) {
            // Child-operation cancellation is contained here. Process-scope cancellation reaches
            // runActor and terminates the actor with the process, as required.
            if (command is Command.ReadSnapshot) {
                command.reply.completeExceptionally(error)
            }
            if (command is Command.Restore) {
                command.reply.completeExceptionally(error)
            }
            if (command is Command.PreparePurchase) {
                command.reply.completeExceptionally(error)
            }
            if (command is Command.BeginPurchase) {
                command.reply.completeExceptionally(error)
            }
            if (command is Command.LaunchPurchase) {
                command.reply.completeExceptionally(error)
            }
            if (command is Command.AbortPurchase) {
                command.reply.completeExceptionally(error)
            }
            if (command is Command.PurchaseActivityResult) {
                command.reply.completeExceptionally(error)
            }
            if (!processScope.isActive) throw error
            if (command is Command.Start) {
                startCommandQueued.set(false)
            }
            logError("entitlement command cancelled", error)
        } catch (error: Exception) {
            when (command) {
                is Command.ReadSnapshot -> command.reply.complete(false)
                is Command.Restore -> command.reply.complete(
                    RestoreResult.Failed(BillingFailureKind.ProviderError),
                )
                is Command.PreparePurchase -> command.reply.complete(
                    BillingGatewayResult.Failure(BillingFailureKind.ProviderError),
                )
                is Command.BeginPurchase -> command.reply.complete(null)
                is Command.LaunchPurchase -> command.reply.complete(
                    BillingGatewayResult.Failure(BillingFailureKind.ProviderError),
                )
                is Command.AbortPurchase -> command.reply.complete(Unit)
                is Command.PurchaseActivityResult -> command.reply.complete(
                    PurchaseCompletionResult.Failed(BillingFailureKind.ProviderError),
                )
                else -> Unit
            }
            failClosed(error)
        } finally {
            serviceDeferredRequests(command)
        }
    }

    private suspend fun releaseBlockedStageIfPossible() {
        if (storageCorruptionLatched) return
        val session = actorSession ?: return
        val state = retryStateFor(session.gateway.store)
        val blocked = state.blocked ?: return
        if (!isRetryCompatible(session, blocked)) return
        state.blocked = null
        processCommandBoundary(Command.StagePurchase(session, blocked.purchase))
    }

    private suspend fun serviceDeferredRequests(command: Command) {
        if (command !is Command.Start) enqueueStartIfNeeded()
        if (command is Command.ReconcileSignal ||
            !reconcileRetryRequired ||
            actorSession === null ||
            storageCorruptionLatched ||
            reconcileSignalSession !== null
        ) return
        val session = actorSession ?: return
        reconcileSignalSession = session
        if (commands.trySend(Command.ReconcileSignal(session)).isSuccess) {
            reconcileRetryRequired = false
        } else {
            reconcileSignalSession = null
        }
    }

    private suspend fun handleAttach(gateway: BillingGateway) {
        val startVersion = startRequestVersion.get()
        val previous = actorSession
        if (previous !== null && previous.gateway === gateway) {
            // Same-instance attach is a no-op and must not reset a corruption latch.
            startCollectorIfNeeded(previous)
            return
        }

        // A genuinely new generation is the actor-ordered latch reset and retry point.
        terminateActivePurchase(PurchaseCompletionResult.Unavailable)
        storageCorruptionLatched = false
        previous?.let { closePurchaseIngress(it.gateway.store) }
        if (previous !== null) {
            collector.get()?.let { handle ->
                expectedCollectorStop.set(handle.token)
                handle.job.cancelAndJoin()
                expectedCollectorStop.compareAndSet(handle.token, null)
            }
            collector.set(null)
            actorSession = null
            attachedGateway.compareAndSet(previous.gateway, null)
            observedPurchaseStates.clear()
            closeGateway(previous.gateway)
        }
        actorGeneration += 1L
        val session = GatewaySession(gateway, actorGeneration)
        actorSession = session
        attachedGateway.set(gateway)
        openPurchaseIngress(session)
        reconcileSignalSession = null
        reconcileDirty = false
        reconcileRetryRequired = false
        startCollectorIfNeeded(session)
        drainCorruptionRetryStaging(session)
        reconcile(session)
        completeStartRequest(startVersion)
    }

    private suspend fun handleDetach(gateway: BillingGateway) {
        val session = actorSession
        if (session !== null && session.gateway === gateway) {
            terminateActivePurchase(PurchaseCompletionResult.Unavailable)
            closePurchaseIngress(session.gateway.store)
            collector.get()?.let { handle ->
                expectedCollectorStop.set(handle.token)
                handle.job.cancelAndJoin()
                expectedCollectorStop.compareAndSet(handle.token, null)
            }
            collector.set(null)
            actorSession = null
            attachedGateway.compareAndSet(gateway, null)
            observedPurchaseStates.clear()
            reconcileSignalSession = null
            reconcileDirty = false
            reconcileRetryRequired = false
            closeGateway(gateway)
        }
    }

    private suspend fun handleStart() {
        startCommandQueued.set(false)
        val requestedVersion = startRequestVersion.get()
        if (requestedVersion == 0L) return
        // Startup/foreground is the only non-reattach latch reset.
        storageCorruptionLatched = false
        val session = actorSession
        drainCorruptionRetryStaging(session)
        session?.let { openPurchaseIngress(it) }
        if (session !== null) startCollectorIfNeeded(session)
        reconcileRetryRequired = false
        reconcileDirty = false
        reconcile(session)
        completeStartRequest(requestedVersion)
    }

    private fun completeStartRequest(requestedVersion: Long) {
        startRequestVersion.compareAndSet(requestedVersion, 0L)
    }

    private suspend fun handleReconcileSignal(session: GatewaySession) {
        if (reconcileSignalSession === session) reconcileSignalSession = null
        if (!isActorSession(session) || storageCorruptionLatched || !reconcileDirty) return
        reconcileDirty = false
        reconcile(session)
    }

    private suspend fun handleStagePurchase(command: Command.StagePurchase) {
        val session = command.session
        if (!isActorSession(session)) return
        val purchase = command.purchase
        if (!isCompatible(session, purchase)) return
        val identity = purchase.identity()

        if (storageCorruptionLatched) {
            holdCorruptionRetry(command)
            return
        }

        // This write is serialized by the actor and precedes every reconcile request.
        try {
            if (!upsertPendingCancellationSafe(purchase.pending())) {
                if (storageCorruptionLatched) holdCorruptionRetry(command)
                return
            }
        } catch (error: CancellationException) {
            if (storageCorruptionLatched) holdCorruptionRetry(command)
            throw error
        }
        // Purchased is staged as Pending until authoritative verification commits it.
        rememberObserved(identity, PurchaseState.Pending)
        val admittedPurchase = activePurchase.get()?.takeIf {
            it.generation == session.generation &&
                it.store == session.gateway.store &&
                isPurchaseTokenAdmissibleForOperation(it, purchase)
        }
        if (admittedPurchase !== null) {
            // The first new token binds this provider callback stream to this operation. Later
            // Pending/Purchased transitions must carry the same token; unrelated updates remain
            // reconciliation input but cannot terminate the current launch.
            admittedPurchase.correlatedPurchaseToken = purchase.purchaseToken
            val completion = when (purchase.state) {
                PurchaseState.Pending -> PurchaseCompletionResult.Pending
                PurchaseState.Purchased -> purchaseCompletionFor(session, purchase)
            }
            terminateActivePurchase(completion)
        }
        requestReconcile(session)
    }

    private fun holdCorruptionRetry(command: Command.StagePurchase) {
        val entry = RetryEntry(command.session, command.purchase)
        val state = retryStateFor(entry.purchase.store)
        if (mergeRetryEntry(state.queue, entry)) {
            if (state.queue.size >= MAX_RETRY_STAGING) closePurchaseIngress(entry.purchase.store)
            return
        }
        if (state.blocked?.identity() == entry.identity()) {
            state.blocked = strongerRetryEntry(state.blocked, entry)
            return
        }
        if (state.queue.size < MAX_RETRY_STAGING) {
            state.queue.addLast(entry)
            if (state.queue.size >= MAX_RETRY_STAGING) closePurchaseIngress(entry.purchase.store)
        } else {
            // The per-store admission slot is closed as soon as that store's queue is full. The
            // one-item blocked handoff retains only the already-admitted event; other stores remain
            // independently able to emit.
            closePurchaseIngress(entry.purchase.store)
            // With one ingress permit and a closed gate, this is the only accepted event outside
            // the queue for this store until a lifecycle command drains capacity.
            state.blocked = entry
        }
    }

    private fun mergeRetryEntry(
        queue: ArrayDeque<RetryEntry>,
        incoming: RetryEntry,
    ): Boolean {
        val size = queue.size
        repeat(size) {
            val existing = queue.removeFirst()
            if (existing.identity() == incoming.identity()) {
                val merged = strongerRetryEntry(existing, incoming)
                queue.addLast(merged)
                repeat(size - it - 1) { queue.addLast(queue.removeFirst()) }
                return true
            }
            queue.addLast(existing)
        }
        return false
    }

    private fun strongerRetryEntry(
        existing: RetryEntry?,
        incoming: RetryEntry,
    ): RetryEntry = when {
        existing === null -> incoming
        incoming.purchase.state == PurchaseState.Purchased -> incoming
        existing.purchase.state == PurchaseState.Purchased -> existing
        else -> incoming
    }

    private fun closePurchaseIngress(store: String) {
        val state = retryStateFor(store)
        if (state.ingressOpen.compareAndSet(true, false)) {
            state.ingressSignal.set(CompletableDeferred())
        }
    }

    private fun openPurchaseIngress(session: GatewaySession) {
        val state = retryStateFor(session.gateway.store)
        if (state.queue.size >= MAX_RETRY_STAGING ||
            (state.blocked?.let { isRetryCompatible(session, it) } == true)
        ) {
            closePurchaseIngress(session.gateway.store)
            return
        }
        state.ingressOpen.set(true)
        state.ingressSignal.get().complete(Unit)
    }

    private suspend fun drainCorruptionRetryStaging(session: GatewaySession?) {
        if (storageCorruptionLatched || session === null) return
        val state = retryStateFor(session.gateway.store)
        val entriesToInspect = state.queue.size
        repeat(entriesToInspect) {
            val entry = state.queue.removeFirst()
            if (!isRetryCompatible(session, entry)) {
                // Retry entries retain their originating session/store metadata. They are rebound
                // only to a current gateway for the same store and remain queued otherwise.
                state.queue.addLast(entry)
                return@repeat
            }
            try {
                if (!upsertPendingOrLatch(entry.purchase.pending())) {
                    state.queue.addFirst(entry)
                    return
                }
            } catch (error: CancellationException) {
                state.queue.addFirst(entry)
                throw error
            }
        }
    }

    private fun handleCollectorStopped(command: Command.CollectorStopped) {
        val current = collector.get()
        if (isActorSession(command.session) && current?.token === command.token) {
            collector.compareAndSet(current, null)
        }
    }

    private suspend fun handleReadSnapshot(reply: CompletableDeferred<Boolean>) {
        val snapshot = readStoreOrLatch()
        if (snapshot == null) {
            reply.complete(false)
            return
        }
        _isProHot.value = snapshot.isCurrentlyActive()
        reply.complete(snapshot.isCurrentlyActive())
    }

    private suspend fun handlePreparePurchase(reply: CompletableDeferred<BillingGatewayResult<Unit>>) {
        val session = actorSession
        if (session === null) {
            reply.complete(BillingGatewayResult.Failure(BillingFailureKind.Unavailable))
            return
        }
        val result = try {
            session.gateway.preparePurchaseIntent()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logError("purchase preparation failed", error)
            BillingGatewayResult.Failure(BillingFailureKind.ProviderError)
        }
        reply.complete(
            if (isActorSession(session)) result else BillingGatewayResult.Failure(
                BillingFailureKind.Network,
            ),
        )
    }

    private suspend fun handleLaunchPurchase(command: Command.LaunchPurchase) {
        val session = actorSession
        if (session === null ||
            activePurchase.get() !== command.handoff ||
            session.generation != command.handoff.generation ||
            session.gateway.store != command.handoff.store
        ) {
            command.reply.complete(BillingGatewayResult.Failure(BillingFailureKind.Unavailable))
            return
        }
        val result = try {
            withContext(mainDispatcherOverride ?: Dispatchers.Main.immediate) {
                // Only the ownership check is serialized. Holding the cleanup mutex while a
                // provider call blocks would prevent timeout/onCleared fallback from invalidating
                // this session and releasing the gateway.
                val admitted = synchronized(purchaseLaunchLock) {
                    isActorSession(session) && activePurchase.get() === command.handoff
                }
                if (!admitted) {
                    BillingGatewayResult.Failure(BillingFailureKind.Unavailable)
                } else {
                    session.gateway.launchPurchase(command.activity)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logError("purchase launch failed", error)
            BillingGatewayResult.Failure(BillingFailureKind.ProviderError)
        }
        // The actor remains the authority for this session while the provider hand-off runs;
        // lifecycle commands queued by a callback cannot interleave before this command returns.
        command.reply.complete(
            if (isActorSession(session) && activePurchase.get() === command.handoff) {
                result
            } else {
                BillingGatewayResult.Failure(BillingFailureKind.Unavailable)
            },
        )
    }

    private suspend fun handleBeginPurchase(reply: CompletableDeferred<PurchaseLaunchSession?>) {
        val session = actorSession
        if (session === null || activePurchase.get() !== null) {
            reply.complete(null)
            return
        }
        val baselinePurchaseTokens = baselinePurchaseTokens(session)
        if (baselinePurchaseTokens === null) {
            // Google has no operation id in purchaseUpdates. If the provider baseline cannot be
            // established, accepting an opaque callback would be able to relabel stale A as B.
            reply.complete(null)
            return
        }
        nextPurchaseId += 1L
        val handoff = PurchaseLaunchSession(
            id = nextPurchaseId,
            generation = session.generation,
            store = session.gateway.store,
            operationId = BillingOperationId.issue(),
            baselinePurchaseTokens = baselinePurchaseTokens,
        )
        activePurchase.set(handoff)
        reply.complete(handoff)
    }

    /** Captures the strongest provider identity available before a purchase launch. */
    private suspend fun baselinePurchaseTokens(session: GatewaySession): Set<String>? {
        val storedTokens = readStoreOrLatch()?.pendingPurchases
            ?.asSequence()
            ?.filter { it.store == session.gateway.store }
            ?.map { it.purchaseToken }
            ?.toSet()
            ?: return null
        val observedTokens = observedPurchaseStates.keys
            .asSequence()
            .filter { it.store == session.gateway.store }
            .map { it.purchaseToken }
            .toSet()
        val committedTokens = lastCommittedIdentity
            ?.takeIf { it.store == session.gateway.store }
            ?.let { setOf(it.purchaseToken) }
            .orEmpty()
        if (session.gateway.store != StoreCapabilities.GOOGLE_PLAY_STORE_ID) {
            return storedTokens + observedTokens + committedTokens
        }
        val ownedResult = try {
            session.gateway.queryOwnedPurchases()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logError("purchase identity baseline unavailable", error)
            return null
        }
        val providerTokens = when (ownedResult) {
            is BillingGatewayResult.Success -> ownedResult.value
                .asSequence()
                .filter { isCompatible(session, it) }
                .map { it.purchaseToken }
                .toSet()
            is BillingGatewayResult.Failure,
            is BillingGatewayResult.NeedsResolution,
            -> return null
        }
        return storedTokens + observedTokens + committedTokens + providerTokens
    }

    private suspend fun handleAbortPurchase(command: Command.AbortPurchase) {
        if (activePurchase.get() === command.session) {
            terminateActivePurchase(command.result)
        }
        command.reply.complete(Unit)
    }

    private suspend fun handlePurchaseSignal(command: Command.PurchaseSignal) {
        if (!isActorSession(command.session)) return
        if (command.session.gateway.store == StoreCapabilities.GOOGLE_PLAY_STORE_ID &&
            activePurchase.get() !== null
        ) {
            // Google error/cancel callbacks carry no purchase token. They cannot be correlated to
            // this operation, so waiting for the bounded VM timeout is safer than relabeling a
            // stale provider signal as the current purchase result.
            return
        }
        val completion = when (val result = command.result) {
            PurchaseResult.UserCancelled,
            PurchaseResult.Cancelled,
            -> PurchaseCompletionResult.Cancelled
            PurchaseResult.AlreadyOwned -> PurchaseCompletionResult.Unavailable
            PurchaseResult.Duplicate ->
                PurchaseCompletionResult.Failed(BillingFailureKind.ProviderError)
            PurchaseResult.Malformed,
            PurchaseResult.ProductMismatch,
            -> PurchaseCompletionResult.Failed(BillingFailureKind.InvalidResponse)
            is PurchaseResult.Failed -> PurchaseCompletionResult.Failed(result.kind)
            is PurchaseResult.NeedsResolution ->
                PurchaseCompletionResult.Failed(BillingFailureKind.InvalidResponse)
            is PurchaseResult.Purchased,
            is PurchaseResult.Pending,
            -> return
        }
        activePurchase.get()?.takeIf { it.generation == command.session.generation }
            ?.let { terminateActivePurchase(completion) }
    }

    private suspend fun handlePurchaseActivityResult(
        command: Command.PurchaseActivityResult,
    ) {
        val session = actorSession
        if (session === null ||
            activePurchase.get() !== command.session ||
            command.operationId != command.session.operationId ||
            session.generation != command.session.generation ||
            session.gateway.store != command.session.store
        ) {
            command.reply.complete(PurchaseCompletionResult.Unavailable)
            return
        }
        // Google reports purchase state through purchaseUpdates. ActivityResult is not an
        // entitlement signal for that provider and must never finish a Google purchase.
        if (session.gateway.store == StoreCapabilities.GOOGLE_PLAY_STORE_ID) {
            command.reply.complete(PurchaseCompletionResult.Unavailable)
            return
        }
        val providerResult = try {
            session.gateway.resultFromActivityResult(command.resultCode, command.intent)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logError("purchase activity result parsing failed", error)
            command.reply.complete(
                PurchaseCompletionResult.Failed(BillingFailureKind.InvalidResponse),
            )
            terminateActivePurchase(PurchaseCompletionResult.Failed(BillingFailureKind.InvalidResponse))
            return
        }
        val completion = purchaseCompletionForProviderResult(session, providerResult)
        terminateActivePurchase(completion)
        command.reply.complete(completion)
    }

    private suspend fun purchaseCompletionForProviderResult(
        session: GatewaySession,
        providerResult: PurchaseResult,
    ): PurchaseCompletionResult = when (providerResult) {
        is PurchaseResult.Purchased -> purchaseCompletionFor(session, providerResult.purchase)
        is PurchaseResult.Pending -> purchaseCompletionFor(session, providerResult.purchase)
        PurchaseResult.UserCancelled,
        PurchaseResult.Cancelled,
        -> PurchaseCompletionResult.Cancelled
        PurchaseResult.AlreadyOwned -> PurchaseCompletionResult.Unavailable
        PurchaseResult.Duplicate -> PurchaseCompletionResult.Failed(BillingFailureKind.ProviderError)
        PurchaseResult.Malformed,
        PurchaseResult.ProductMismatch,
        -> PurchaseCompletionResult.Failed(BillingFailureKind.InvalidResponse)
        is PurchaseResult.Failed -> PurchaseCompletionResult.Failed(providerResult.kind)
        is PurchaseResult.NeedsResolution ->
            PurchaseCompletionResult.Failed(BillingFailureKind.InvalidResponse)
    }

    private suspend fun purchaseCompletionFor(
        session: GatewaySession,
        purchase: OwnedPurchase,
    ): PurchaseCompletionResult {
        if (!isActorSession(session) || !isCompatible(session, purchase)) {
            return PurchaseCompletionResult.Unavailable
        }
        return when (val observation = restoreObservationFor(session, purchase)) {
            RestoreObservation.Verified -> PurchaseCompletionResult.Purchased
            RestoreObservation.Pending -> PurchaseCompletionResult.Pending
            RestoreObservation.Unavailable -> PurchaseCompletionResult.Unavailable
            is RestoreObservation.Failed -> PurchaseCompletionResult.Failed(observation.kind)
        }
    }

    private suspend fun handleRestore(reply: CompletableDeferred<RestoreResult>) {
        val result = try {
            withTimeoutOrNull(BILLING_WORKFLOW_TIMEOUT_MILLIS) {
                restoreFromAttachedGateway()
            } ?: RestoreResult.Failed(BillingFailureKind.Network)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logError("entitlement restore failed", error)
            RestoreResult.Failed(BillingFailureKind.ProviderError)
        }
        reply.complete(result)
    }

    private suspend fun restoreFromAttachedGateway(): RestoreResult {
        val session = actorSession ?: return RestoreResult.Unavailable
        if (!isActorSession(session)) return RestoreResult.Unavailable

        val initial = readStoreOrLatch()
            ?: return RestoreResult.Failed(BillingFailureKind.ProviderError)
        val cachedPro = initial.isCurrentlyActive()
        _isProHot.value = cachedPro
        fun finish(result: RestoreResult): RestoreResult {
            _isProHot.value = restoreProStatusFor(cachedPro, result)
            return result
        }

        val queryResult = try {
            session.gateway.queryOwnedPurchases()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logError("owned purchase restore query failed", error)
            return finish(RestoreResult.Failed(BillingFailureKind.Network))
        }
        val ownedPurchases = when (queryResult) {
            is BillingGatewayResult.Success -> queryResult.value
            is BillingGatewayResult.NeedsResolution -> return finish(RestoreResult.Unavailable)
            is BillingGatewayResult.Failure -> return finish(restoreResultFor(
                hasOwnedPro = false,
                observations = listOf(queryFailureObservation(queryResult.kind)),
            ))
        }

        val purchases = LinkedHashMap<PurchaseIdentity, OwnedPurchase>()
        ownedPurchases.forEach { purchase ->
            if (!isCompatible(session, purchase)) return@forEach
            val identity = purchase.identity()
            val previous = purchases[identity]
            if (previous == null || purchase.state == PurchaseState.Purchased) {
                purchases[identity] = purchase
            }
        }
        if (purchases.isEmpty()) return finish(RestoreResult.Unavailable)

        val observations = buildList {
            purchases.values.forEach { purchase ->
                add(restoreObservationFor(session, purchase))
            }
        }
        val result = restoreResultFor(hasOwnedPro = true, observations = observations)
        return finish(result)
    }

    private fun queryFailureObservation(kind: BillingFailureKind): RestoreObservation = when (kind) {
        BillingFailureKind.Unavailable,
        BillingFailureKind.InvalidProduct
        -> RestoreObservation.Unavailable
        else -> RestoreObservation.Failed(kind)
    }

    private suspend fun restoreObservationFor(
        session: GatewaySession,
        purchase: OwnedPurchase,
    ): RestoreObservation = when (purchase.state) {
        PurchaseState.Pending -> {
            if (upsertPendingOrLatch(purchase.pending())) {
                rememberObserved(purchase.identity(), PurchaseState.Pending)
                RestoreObservation.Pending
            } else {
                RestoreObservation.Failed(BillingFailureKind.ProviderError)
            }
        }
        PurchaseState.Purchased -> verifyPurchasedForRestore(session, purchase)
    }

    private suspend fun verifyPurchasedForRestore(
        session: GatewaySession,
        purchase: OwnedPurchase,
    ): RestoreObservation {
        val pending = purchase.pending()
        if (storageCorruptionLatched || !isActorSession(session)) {
            return RestoreObservation.Failed(BillingFailureKind.ProviderError)
        }
        if (!stagePendingBeforeVerify(pending)) {
            return RestoreObservation.Failed(BillingFailureKind.ProviderError)
        }
        rememberObserved(purchase.identity(), PurchaseState.Pending)

        val result = try {
            apiClient.verify(
                installId = appContext.installId(),
                store = purchase.store,
                productId = purchase.productId,
                purchaseToken = purchase.purchaseToken,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logError("entitlement restore verification unavailable", error)
            return RestoreObservation.Failed(BillingFailureKind.Network)
        }
        if (storageCorruptionLatched || !isActorSession(session)) {
            return RestoreObservation.Failed(BillingFailureKind.ProviderError)
        }
        return when (result) {
            is EntitlementApiResult.Success -> {
                if (result.expiresAtEpochSeconds <= nowEpochSeconds()) {
                    removePendingOrLatch(pending)
                    _isProHot.value = false
                    RestoreObservation.Failed(BillingFailureKind.InvalidResponse)
                } else if (!commitVerifiedOrLatch(result, purchase)) {
                    RestoreObservation.Failed(BillingFailureKind.ProviderError)
                } else {
                    lastCommittedIdentity = purchase.identity()
                    rememberObserved(purchase.identity(), PurchaseState.Purchased)
                    _isProHot.value = result.expiresAtEpochSeconds > nowEpochSeconds()
                    RestoreObservation.Verified
                }
            }
            is EntitlementApiResult.Failure -> restoreVerificationObservation(result)
        }
    }

    private fun restoreVerificationObservation(
        result: EntitlementApiResult.Failure,
    ): RestoreObservation = restoreFailureObservationFor(result.kind, result.httpCode)

    private fun startCollectorIfNeeded(session: GatewaySession) {
        if (!isActorSession(session)) return
        val current = collector.get()
        if (current?.session === session && current.job.isActive) return

        val token = Any()
        val job = processScope.launch {
            try {
                // No conflate before staging: each purchase identity is handed to the actor. The
                // bounded reconcile signal below is the only coalesced part.
                val ingress = retryStateFor(session.gateway.store)
                session.gateway.purchaseUpdates.collect { result ->
                    val purchase = result.purchaseOrNull()
                    if (purchase === null) {
                        commands.send(Command.PurchaseSignal(session, result))
                        return@collect
                    }
                    awaitPurchaseIngress(ingress)
                    ingress.ingressSlots.acquire()
                    try {
                        if (!ingress.ingressOpen.get()) {
                            ingress.ingressSlots.release()
                            awaitPurchaseIngress(ingress)
                            ingress.ingressSlots.acquire()
                        }
                        commands.send(Command.StagePurchase(session, purchase))
                    } catch (error: CancellationException) {
                        ingress.ingressSlots.release()
                        throw error
                    }
                }
                publishCollectorStopped(session, token)
            } catch (error: CancellationException) {
                if (!processScope.isActive) throw error
                if (expectedCollectorStop.get() === token) return@launch
                publishCollectorStopped(session, token)
                logError("entitlement collector cancelled", error)
            } catch (error: Exception) {
                publishCollectorStopped(session, token)
                logError("entitlement collector failed", error)
            }
        }
        collector.set(CollectorHandle(session, token, job))
    }

    private suspend fun awaitPurchaseIngress(state: RetryState) {
        while (!state.ingressOpen.get()) {
            state.ingressSignal.get().await()
        }
    }

    private suspend fun publishCollectorStopped(session: GatewaySession, token: Any) {
        val command = Command.CollectorStopped(session, token)
        commands.send(command)
    }

    private fun requestReconcile(session: GatewaySession) {
        if (!isActorSession(session) || storageCorruptionLatched) return
        reconcileDirty = true
        if (reconcileSignalSession !== null) return
        reconcileSignalSession = session
        if (commands.trySend(Command.ReconcileSignal(session)).isFailure) {
            reconcileSignalSession = null
            reconcileRetryRequired = true
        }
    }

    private suspend fun reconcile(session: GatewaySession?) {
        if (storageCorruptionLatched) return
        val initial = readStoreOrLatch() ?: return
        if (storageCorruptionLatched) return
        _isProHot.value = initial.isCurrentlyActive()

        if (session !== null) {
            val queryResult = try {
                session.gateway.queryOwnedPurchases()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logError("owned purchase query unavailable", error)
                null
            }
            if (storageCorruptionLatched) return
            when (queryResult) {
                is BillingGatewayResult.Success -> reconcileOwnedPurchases(session, queryResult.value)
                is BillingGatewayResult.Failure,
                is BillingGatewayResult.NeedsResolution,
                null,
                -> Unit
            }
        }
        if (storageCorruptionLatched) return
        val latest = readStoreOrLatch() ?: return
        if (storageCorruptionLatched) return
        refreshIfNeeded(latest)
    }

    private suspend fun reconcileOwnedPurchases(
        session: GatewaySession,
        queriedPurchases: List<OwnedPurchase>,
    ) {
        if (storageCorruptionLatched) return
        val current = readStoreOrLatch() ?: return
        val pendingIdentities = current.pendingPurchases.mapTo(mutableSetOf()) { it.identity() }
        val purchases = LinkedHashMap<PurchaseIdentity, OwnedPurchase>()
        queriedPurchases.forEach { purchase ->
            if (!isCompatible(session, purchase)) return@forEach
            val identity = purchase.identity()
            val previous = purchases[identity]
            if (previous == null || purchase.state == PurchaseState.Purchased) {
                purchases[identity] = purchase
            }
        }

        var hasValidEntitlement = current.isCurrentlyActive()
        for (purchase in purchases.values) {
            if (storageCorruptionLatched) return
            val identity = purchase.identity()
            when (purchase.state) {
                PurchaseState.Pending -> {
                    if (!upsertPendingOrLatch(purchase.pending())) return
                    rememberObserved(identity, PurchaseState.Pending)
                    pendingIdentities += identity
                }
                PurchaseState.Purchased -> {
                    val wasPending = identity in pendingIdentities ||
                        observedPurchaseStates[identity] == PurchaseState.Pending
                    rememberObserved(identity, PurchaseState.Purchased)
                    if (wasPending || !hasValidEntitlement || lastCommittedIdentity != identity) {
                        if (verifyPurchased(session, purchase)) {
                            hasValidEntitlement = true
                        }
                    }
                }
            }
        }
    }

    private suspend fun verifyPurchased(
        session: GatewaySession,
        purchase: OwnedPurchase,
    ): Boolean {
        val pending = purchase.pending()
        if (storageCorruptionLatched || !isActorSession(session)) {
            if (storageCorruptionLatched) holdCorruptionRetry(Command.StagePurchase(session, purchase))
            return false
        }

        val staged = try {
            stagePendingBeforeVerify(pending)
        } catch (error: CancellationException) {
            if (storageCorruptionLatched) holdCorruptionRetry(Command.StagePurchase(session, purchase))
            throw error
        }
        if (!staged) {
            if (storageCorruptionLatched) holdCorruptionRetry(Command.StagePurchase(session, purchase))
            return false
        }

        val result = try {
            apiClient.verify(
                installId = appContext.installId(),
                store = purchase.store,
                productId = purchase.productId,
                purchaseToken = purchase.purchaseToken,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logError("entitlement verify unavailable", error)
            null
        }
        if (storageCorruptionLatched || !isActorSession(session)) return false
        when (result) {
            is EntitlementApiResult.Success -> {
                if (result.expiresAtEpochSeconds <= nowEpochSeconds()) {
                    removePendingOrLatch(pending)
                    _isProHot.value = false
                    return false
                }
                if (!commitVerifiedOrLatch(result, purchase)) return false
                lastCommittedIdentity = purchase.identity()
                rememberObserved(purchase.identity(), PurchaseState.Purchased)
                _isProHot.value = result.expiresAtEpochSeconds > nowEpochSeconds()
                return true
            }
            is EntitlementApiResult.Failure -> {
                if (result.httpCode == HTTP_BAD_REQUEST || result.httpCode == HTTP_UNPROCESSABLE_ENTITY) {
                    removePendingOrLatch(pending)
                } else {
                    upsertPendingOrLatch(pending)
                }
            }
            null -> Unit
        }
        return false
    }

    private suspend fun stagePendingBeforeVerify(pending: PendingPurchase): Boolean =
        upsertPendingCancellationSafe(pending)

    private suspend fun upsertPendingCancellationSafe(pending: PendingPurchase): Boolean {
        if (storageCorruptionLatched) return false
        try {
            store.upsertPendingPurchase(pending)
            return true
        } catch (error: CancellationException) {
            if (processScope.isActive) {
                try {
                    withContext(NonCancellable) { store.upsertPendingPurchase(pending) }
                } catch (preserveError: Exception) {
                    latchStorageCorruption(preserveError)
                }
            }
            throw error
        } catch (error: Exception) {
            latchStorageCorruption(error)
            return false
        }
    }

    private suspend fun upsertPendingOrLatch(pending: PendingPurchase): Boolean {
        if (storageCorruptionLatched) return false
        return try {
            store.upsertPendingPurchase(pending)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            latchStorageCorruption(error)
            false
        }
    }

    private suspend fun removePendingOrLatch(pending: PendingPurchase): Boolean {
        if (storageCorruptionLatched) return false
        return try {
            store.removePendingPurchase(pending)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            latchStorageCorruption(error)
            false
        }
    }

    private suspend fun commitVerifiedOrLatch(
        result: EntitlementApiResult.Success,
        purchase: OwnedPurchase,
    ): Boolean {
        if (storageCorruptionLatched || result.expiresAtEpochSeconds <= nowEpochSeconds()) {
            return false
        }
        return try {
            store.commitVerifiedEntitlement(
                jwt = result.jwt,
                expiresAtEpochSeconds = result.expiresAtEpochSeconds,
                isActive = true,
                processedPurchase = purchase,
            )
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            latchStorageCorruption(error)
            false
        }
    }

    private suspend fun readStoreOrLatch(): Snapshot? {
        if (storageCorruptionLatched) return null
        return try {
            store.read()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            latchStorageCorruption(error)
            null
        }
    }

    private suspend fun refreshIfNeeded(snapshot: Snapshot) {
        if (storageCorruptionLatched) return
        val jwt = snapshot.jwt
        val expiry = snapshot.expiresAtEpochSeconds
        if (jwt.isNullOrBlank() || expiry == null || expiry <= nowEpochSeconds()) {
            _isProHot.value = false
            return
        }
        if (expiry - nowEpochSeconds() > REFRESH_WINDOW_SECONDS) {
            _isProHot.value = snapshot.isCurrentlyActive()
            return
        }

        val result = try {
            apiClient.refresh(jwt = jwt, installId = appContext.installId())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logError("entitlement refresh unavailable", error)
            null
        }
        if (storageCorruptionLatched) return
        when (result) {
            is EntitlementApiResult.Success -> {
                if (result.expiresAtEpochSeconds <= nowEpochSeconds()) {
                    // A refresh response can arrive after the response JWT has already expired.
                    // Persist that terminal state; otherwise a later snapshot read could revive
                    // the previously active entitlement from storage.
                    if (!commitRefreshInactiveOrLatch(
                            jwt = result.jwt,
                            expiry = result.expiresAtEpochSeconds,
                        )
                    ) return
                    _isProHot.value = false
                    return
                }
                if (!commitRefreshOrLatch(result)) return
                _isProHot.value = result.expiresAtEpochSeconds > nowEpochSeconds()
            }
            is EntitlementApiResult.Failure -> {
                if (result.httpCode == HTTP_UNAUTHORIZED ||
                    result.httpCode == HTTP_FORBIDDEN ||
                    result.httpCode == HTTP_CONFLICT ||
                    result.httpCode == HTTP_UNPROCESSABLE_ENTITY
                ) {
                    if (!commitRefreshInactiveOrLatch(jwt, expiry)) return
                    _isProHot.value = false
                } else {
                    _isProHot.value = snapshot.isCurrentlyActive()
                }
            }
            null -> _isProHot.value = snapshot.isCurrentlyActive()
        }
    }

    private suspend fun commitRefreshOrLatch(result: EntitlementApiResult.Success): Boolean {
        if (storageCorruptionLatched || result.expiresAtEpochSeconds <= nowEpochSeconds()) return false
        return try {
            store.commit(
                jwt = result.jwt,
                expiresAtEpochSeconds = result.expiresAtEpochSeconds,
                isActive = true,
            )
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            latchStorageCorruption(error)
            false
        }
    }

    private suspend fun commitRefreshInactiveOrLatch(jwt: String, expiry: Long): Boolean {
        if (storageCorruptionLatched) return false
        return try {
            store.commit(jwt = jwt, expiresAtEpochSeconds = expiry, isActive = false)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            latchStorageCorruption(error)
            false
        }
    }

    private fun rememberObserved(identity: PurchaseIdentity, state: PurchaseState) {
        observedPurchaseStates.remove(identity)
        observedPurchaseStates[identity] = state
        while (observedPurchaseStates.size > MAX_IN_MEMORY_IDENTITIES) {
            val oldest = observedPurchaseStates.entries.firstOrNull()?.key ?: break
            observedPurchaseStates.remove(oldest)
        }
    }

    private fun terminateActivePurchase(
        result: PurchaseCompletionResult,
    ) {
        val operation = synchronized(purchaseLaunchLock) {
            activePurchase.getAndSet(null)
        } ?: return
        operation.completion.complete(result)
        val gateway = actorSession?.gateway
        if (gateway !== null) {
            try {
                // Cleanup is deliberately routed through the gateway's cancellation parser. It
                // is idempotent for providers that already cleared their launch state, while it
                // also clears Huawei resolution state for cancellation/timeout/failure paths.
                gateway.resultFromActivityResult(
                    android.app.Activity.RESULT_CANCELED,
                    null,
                )
            } catch (error: Exception) {
                logError("purchase launch cleanup failed", error)
            }
        }
    }

    /** Synchronous fail-safe used when the actor cannot linearize an abort command in time. */
    private fun forceAbortPurchaseCleanup(
        session: PurchaseLaunchSession,
        result: PurchaseCompletionResult,
    ) {
        var collectorJob: Job? = null
        var gatewayToClose: BillingGateway? = null
        val removed = synchronized(purchaseLaunchLock) {
            if (!activePurchase.compareAndSet(session, null)) {
                false
            } else {
                // Invalidate every ownership edge before releasing the provider. This makes any
                // queued actor command fail closed even if the actor is blocked in provider I/O.
                val current = actorSession
                if (current?.generation == session.generation &&
                    current.gateway.store == session.store
                ) {
                    actorSession = null
                }
                collector.get()?.takeIf {
                    it.session.generation == session.generation &&
                        it.session.gateway.store == session.store
                }?.let { handle ->
                    if (collector.compareAndSet(handle, null)) {
                        collectorJob = handle.job
                        expectedCollectorStop.set(handle.token)
                    }
                }
                val gateway = current?.takeIf {
                    it.generation == session.generation && it.gateway.store == session.store
                }?.gateway ?: attachedGateway.get()?.takeIf { it.store == session.store }
                val mayReleaseGateway = current === null ||
                    (current.generation == session.generation &&
                        current.gateway.store == session.store)
                if (mayReleaseGateway && gateway !== null &&
                    attachedGateway.compareAndSet(gateway, null)
                ) {
                    gatewayToClose = gateway
                }
                true
            }
        }
        if (!removed) {
            session.completion.complete(result)
            return
        }
        session.completion.complete(result)
        collectorJob?.cancel()
        closeGateway(gatewayToClose)
    }

    private fun latchStorageCorruption(error: Exception) {
        storageCorruptionLatched = true
        _isProHot.value = false
        logError("entitlement storage unavailable", error)
    }

    private fun failClosed(error: Exception) {
        _isProHot.value = false
        logError("entitlement reconciliation failed", error)
    }

    private fun isActorSession(session: GatewaySession): Boolean =
        actorSession?.generation == session.generation && actorSession?.gateway === session.gateway

    private fun isCompatible(session: GatewaySession, purchase: OwnedPurchase): Boolean =
        purchase.store == session.gateway.store && purchase.productId == PRO_PRODUCT_ID

    private fun isRetryCompatible(session: GatewaySession, entry: RetryEntry): Boolean =
        entry.originSession.gateway.store == entry.purchase.store &&
            session.gateway.store == entry.originSession.gateway.store &&
            isCompatible(session, entry.purchase)

    private fun logError(message: String, error: Throwable) {
        AppLogger.e(TAG, "$message: ${error.javaClass.simpleName}")
    }

    private fun closeGateway(gateway: BillingGateway?) {
        if (gateway === null) return
        try {
            gateway.close()
        } catch (error: Exception) {
            logError("billing gateway close failed", error)
        }
    }

    private class RetryState {
        val queue = ArrayDeque<RetryEntry>(MAX_RETRY_STAGING)
        var blocked: RetryEntry? = null
        val ingressSlots = Semaphore(PURCHASE_INGRESS_CAPACITY)
        val ingressOpen = AtomicBoolean(true)
        val ingressSignal = AtomicReference(
            CompletableDeferred<Unit>().also { it.complete(Unit) },
        )
    }

    private fun retryStateFor(store: String): RetryState = when (store) {
        StoreCapabilities.GOOGLE_PLAY_STORE_ID -> googleRetryState
        StoreCapabilities.HUAWEI_STORE_ID -> huaweiRetryState
        else -> error("Unsupported billing store")
    }

    private data class GatewaySession(
        val gateway: BillingGateway,
        val generation: Long,
    )

    private data class PurchaseIdentity(
        val store: String,
        val purchaseToken: String,
    )

    private data class CollectorHandle(
        val session: GatewaySession,
        val token: Any,
        val job: Job,
    )

    private data class RetryEntry(
        val originSession: GatewaySession,
        val purchase: OwnedPurchase,
    )

    private fun RetryEntry.identity(): PurchaseIdentity = purchase.identity()

    private sealed interface Command {
        data class Attach(val gateway: BillingGateway) : Command
        data class Detach(val gateway: BillingGateway) : Command
        data class PreparePurchase(
            val reply: CompletableDeferred<BillingGatewayResult<Unit>>,
        ) : Command
        data class BeginPurchase(
            val reply: CompletableDeferred<PurchaseLaunchSession?>,
        ) : Command
        data class LaunchPurchase(
            val handoff: PurchaseLaunchSession,
            val activity: android.app.Activity,
            val reply: CompletableDeferred<BillingGatewayResult<Unit>>,
        ) : Command
        data class AbortPurchase(
            val session: PurchaseLaunchSession,
            val result: PurchaseCompletionResult,
            val reply: CompletableDeferred<Unit>,
        ) : Command
        data class Start(val requestVersion: Long) : Command
        data class ReconcileSignal(val session: GatewaySession) : Command
        data class StagePurchase(val session: GatewaySession, val purchase: OwnedPurchase) : Command
        data class PurchaseSignal(val session: GatewaySession, val result: PurchaseResult) : Command
        data class CollectorStopped(val session: GatewaySession, val token: Any) : Command
        data class ReadSnapshot(val reply: CompletableDeferred<Boolean>) : Command
        data class Restore(val reply: CompletableDeferred<RestoreResult>) : Command
        data class PurchaseActivityResult(
            val session: PurchaseLaunchSession,
            val operationId: BillingOperationId,
            val resultCode: Int,
            val intent: android.content.Intent?,
            val reply: CompletableDeferred<PurchaseCompletionResult>,
        ) : Command
    }

    private fun OwnedPurchase.identity(): PurchaseIdentity =
        PurchaseIdentity(store, purchaseToken)

    private fun PendingPurchase.identity(): PurchaseIdentity =
        PurchaseIdentity(store, purchaseToken)

    private fun OwnedPurchase.pending(): PendingPurchase =
        PendingPurchase(store, purchaseToken)

    private fun PurchaseResult.purchaseOrNull(): OwnedPurchase? = when (this) {
        is PurchaseResult.Pending -> purchase
        is PurchaseResult.Purchased -> purchase
        PurchaseResult.UserCancelled,
        PurchaseResult.AlreadyOwned,
        PurchaseResult.Cancelled,
        PurchaseResult.Duplicate,
        PurchaseResult.Malformed,
        PurchaseResult.ProductMismatch,
        is PurchaseResult.Failed,
        is PurchaseResult.NeedsResolution,
        -> null
    }

    private fun Snapshot.isCurrentlyActive(): Boolean =
        jwt != null &&
            expiresAtEpochSeconds != null &&
            expiresAtEpochSeconds > nowEpochSeconds() &&
            isActive

    companion object {
        private const val TAG = "EntitlementRepository"
        private const val COMMAND_MAILBOX_CAPACITY = 64
        private const val ABORT_COMMAND_TIMEOUT_MILLIS = 2_000L
        private const val CLOSE_TIMEOUT_MILLIS = 5_000L
        // One purchase admission slot leaves the FIFO mailbox available for lifecycle/control
        // commands while corruption retry staging applies backpressure.
        private const val PURCHASE_INGRESS_CAPACITY = 1
        private const val MAX_RETRY_STAGING = 64
        private const val MAX_IN_MEMORY_IDENTITIES = 256
        private const val REFRESH_WINDOW_SECONDS = 24L * 60L * 60L
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_CONFLICT = 409
        private const val HTTP_UNPROCESSABLE_ENTITY = 422

        private fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1_000L

        @Volatile
        private var instance: EntitlementRepository? = null

        fun getInstance(context: Context): EntitlementRepository =
            instance ?: synchronized(this) {
                instance ?: EntitlementRepository(context.applicationContext).also { instance = it }
            }

        /** Test-only cleanup for the process singleton created by an Application fixture. */
        internal fun closeInstanceForTest() {
            instance?.closeForTest()
        }

        private fun clearInstance(repository: EntitlementRepository) {
            synchronized(this) {
                if (instance === repository) instance = null
            }
        }
    }
}
