package dev.bluefalcon.engine.android

internal enum class CentralGattOperationType {
    DiscoverServices,
    ChangeMtu,
    ReadRssi,
    ReadCharacteristic,
    WriteCharacteristic,
    ReadDescriptor,
    WriteDescriptor,
}

internal data class CentralGattOperationKey(
    val generation: Long,
    val type: CentralGattOperationType,
    val identity: String?,
)

internal sealed interface CentralGattOperationOutcome {
    data class Success(
        val status: Int,
    ) : CentralGattOperationOutcome

    data class StatusFailure(
        val status: Int,
    ) : CentralGattOperationOutcome

    data class Rejected(
        val cause: Throwable?,
    ) : CentralGattOperationOutcome

    data object TimedOut : CentralGattOperationOutcome

    data object Disconnected : CentralGattOperationOutcome
}

internal fun interface CentralGattTimeoutHandle {
    fun cancel()
}

internal fun interface CentralGattTimeoutScheduler {
    fun schedule(
        delayMillis: Long,
        onTimeout: () -> Unit,
    ): CentralGattTimeoutHandle
}

internal class CentralGattOperationGate(
    private val timeoutMillis: Long,
    private val timeoutScheduler: CentralGattTimeoutScheduler,
    private val onBusy: () -> Unit = {},
    private val onReady: () -> Unit = {},
    private val onPoisoned: () -> Unit = {},
) {
    private val lock = Any()
    // Readiness callbacks only publish write-state. Keep them ordered without holding
    // the operation monitor or retaining an unbounded queue of deferred post-actions.
    private val publicationLock = Any()
    private var nextPublicationRevision = 0L
    private var publishedRevision = 0L
    private val legacyPending = ArrayDeque<Operation>()
    private var current: Operation? = null
    private var poisoned = false

    val isIdle: Boolean
        get() = synchronized(lock) {
            !poisoned && current == null && legacyPending.isEmpty()
        }

    val isPoisoned: Boolean
        get() = synchronized(lock) { poisoned }

    // Includes active work: moving a closure out of the queue does not release its payload.
    val retainedOperationCount: Int get() = synchronized(lock) { legacyPending.size + if (current == null) 0 else 1 }
    val retainedPayloadBytes: Long get() = synchronized(lock) { retainedPayloadBytesLocked() }
    private fun retainedPayloadBytesLocked(): Long =
        (current?.payloadBytes?.toLong() ?: 0L) + legacyPending.sumOf { it.payloadBytes.toLong() }

    fun enqueueLegacy(
        key: CentralGattOperationKey,
        label: String,
        payloadBytes: Int = 0,
        action: () -> Boolean,
    ) {
        val postActions = synchronized(lock) {
            if (poisoned) return
            require(payloadBytes >= 0) { "Negative GATT payload byte count" }
            if (legacyPending.size + (if (current == null) 0 else 1) >= MAX_RETAINED_OPERATIONS ||
                payloadBytes.toLong() > MAX_RETAINED_PAYLOAD_BYTES - retainedPayloadBytesLocked()) {
                val operation = current
                current = null
                legacyPending.clear()
                operation?.timeoutHandle?.cancel()
                poisoned = true
                return@synchronized PostActions(
                    completion = operation?.onComplete?.let { callback ->
                        { callback(CentralGattOperationOutcome.Rejected(IllegalStateException("GATT operation storage capacity exceeded"))) }
                    },
                    notifyPoisoned = true,
                )
            }
            val wasIdle = current == null && legacyPending.isEmpty()
            // Reserve before invoking native code: a synchronous callback may
            // complete this operation and publish newer replacement readiness.
            val busyRevision = if (wasIdle) ++nextPublicationRevision else null
            legacyPending += Operation(
                key = key,
                label = label,
                action = action,
                onComplete = null,
                payloadBytes = payloadBytes,
            )
            dispatchNextLocked().withBusy(wasIdle, busyRevision).ordered()
        }
        postActions.run()
    }

    fun trySubmitTyped(
        key: CentralGattOperationKey,
        label: String,
        action: () -> Boolean,
        onComplete: (CentralGattOperationOutcome) -> Unit,
    ): Boolean {
        val postActions = synchronized(lock) {
            if (poisoned || current != null || legacyPending.isNotEmpty()) {
                return false
            }

            val operation = Operation(
                key = key,
                label = label,
                action = action,
                onComplete = onComplete,
            )
            current = operation
            val dispatched = dispatchCurrentLocked(operation)
            dispatched.withBusy(current === operation || dispatched.notifyReady).ordered()
        }
        postActions.run()
        return true
    }

    fun complete(
        key: CentralGattOperationKey,
        status: Int,
        successful: Boolean,
    ): Boolean {
        val postActions = synchronized(lock) {
            if (poisoned) return false
            val operation = current ?: return false
            if (operation.key != key) return false
            finishCurrentLocked(
                operation,
                if (successful) {
                    CentralGattOperationOutcome.Success(status)
                } else {
                    CentralGattOperationOutcome.StatusFailure(status)
                },
            ).ordered()
        }
        postActions.run()
        return true
    }

    fun abandon(key: CentralGattOperationKey): Boolean = synchronized(lock) {
        val operation = current ?: return false
        if (operation.key != key || operation.onComplete == null) return false
        operation.onComplete = null
        true
    }

    fun disconnect() {
        val postActions = synchronized(lock) {
            legacyPending.clear()
            val operation = current ?: return
            operation.timeoutHandle?.cancel()
            current = null
            PostActions(
                completion = operation.onComplete?.let { callback ->
                    { callback(CentralGattOperationOutcome.Disconnected) }
                },
                notifyReady = false,
            )
        }
        postActions.run()
    }

    private fun onTimeout(expected: Operation) {
        val postActions = synchronized(lock) {
            val operation = current ?: return
            if (operation !== expected) return
            current = null
            legacyPending.clear()
            poisoned = true
            PostActions(
                completion = operation.onComplete?.let { callback ->
                    { callback(CentralGattOperationOutcome.TimedOut) }
                },
                notifyPoisoned = true,
            )
        }
        postActions.run()
    }

    private fun dispatchNextLocked(): PostActions {
        while (current == null) {
            val operation = legacyPending.removeFirstOrNull()
                ?: return PostActions(notifyReady = true)
            current = operation
            val postActions = dispatchCurrentLocked(operation)
            if (current != null || postActions.completion != null) {
                return postActions
            }
        }
        return PostActions()
    }

    private fun dispatchCurrentLocked(operation: Operation): PostActions {
        val rejection = try {
            if (operation.action()) null else CentralGattOperationOutcome.Rejected(cause = null)
        } catch (failure: Throwable) {
            CentralGattOperationOutcome.Rejected(failure)
        }

        // Native submission may synchronously complete and reenter submission.
        // Its callback has already terminalized this operation; neither a later
        // return value nor a watchdog may clear or poison its replacement.
        if (current !== operation) return PostActions()

        if (rejection != null) {
            current = null
            val completion = operation.onComplete?.let { callback ->
                { callback(rejection) }
            }
            val next = dispatchNextLocked()
            return PostActions(
                completion = combine(completion, next.completion),
                notifyReady = next.notifyReady,
            )
        }

        operation.timeoutHandle = timeoutScheduler.schedule(timeoutMillis) {
            onTimeout(operation)
        }
        return PostActions()
    }

    private fun finishCurrentLocked(
        operation: Operation,
        outcome: CentralGattOperationOutcome,
    ): PostActions {
        operation.timeoutHandle?.cancel()
        current = null
        val completion = operation.onComplete?.let { callback ->
            { callback(outcome) }
        }
        val next = dispatchNextLocked()
        return PostActions(
            completion = combine(completion, next.completion),
            notifyReady = next.notifyReady,
        )
    }

    private fun combine(
        first: (() -> Unit)?,
        second: (() -> Unit)?,
    ): (() -> Unit)? = when {
        first == null -> second
        second == null -> first
        else -> {
            {
                first()
                second()
            }
        }
    }

    private fun PostActions.withBusy(
        enabled: Boolean = true,
        reservedRevision: Long? = null,
    ): PostActions =
        PostActions(
            completion = completion,
            notifyBusy = enabled,
            notifyReady = notifyReady,
            notifyPoisoned = notifyPoisoned,
        ).apply { busyRevision = reservedRevision }

    private inner class PostActions(
        val completion: (() -> Unit)? = null,
        val notifyBusy: Boolean = false,
        val notifyReady: Boolean = false,
        val notifyPoisoned: Boolean = false,
    ) {
        var busyRevision: Long? = null
        private var readyRevision: Long? = null

        // Called under the operation monitor, after the transition is final.
        fun ordered(): PostActions = apply {
            if (notifyBusy && busyRevision == null) busyRevision = ++nextPublicationRevision
            if (notifyReady) readyRevision = ++nextPublicationRevision
        }

        fun run() {
            busyRevision?.let { publish(it, onBusy) }
            // A completion may reenter submission. Its newer busy revision must win
            // over this transition's older ready revision.
            completion?.invoke()
            readyRevision?.let { publish(it, onReady) }
            if (notifyPoisoned) onPoisoned()
        }
    }

    private fun publish(revision: Long, callback: () -> Unit) = synchronized(publicationLock) {
        if (revision <= publishedRevision) return@synchronized
        // Advance before invocation so reentrant publications cannot be overwritten
        // when this callback returns. Completion/native callbacks never use this lock.
        publishedRevision = revision
        callback()
    }

    private companion object {
        const val MAX_RETAINED_OPERATIONS = 128
        const val MAX_RETAINED_PAYLOAD_BYTES = 1024L * 1024L
    }

    private data class Operation(
        val key: CentralGattOperationKey,
        val label: String,
        val action: () -> Boolean,
        var onComplete: ((CentralGattOperationOutcome) -> Unit)?,
        var timeoutHandle: CentralGattTimeoutHandle? = null,
        val payloadBytes: Int = 0,
    )
}
