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
    private val onReady: () -> Unit = {},
) {
    private val lock = Any()
    private val legacyPending = ArrayDeque<Operation>()
    private var current: Operation? = null

    val isIdle: Boolean
        get() = synchronized(lock) {
            current == null && legacyPending.isEmpty()
        }

    fun enqueueLegacy(
        key: CentralGattOperationKey,
        label: String,
        action: () -> Boolean,
    ) {
        val postActions = synchronized(lock) {
            legacyPending += Operation(
                key = key,
                label = label,
                action = action,
                onComplete = null,
            )
            dispatchNextLocked()
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
            if (current != null || legacyPending.isNotEmpty()) {
                return false
            }

            val operation = Operation(
                key = key,
                label = label,
                action = action,
                onComplete = onComplete,
            )
            current = operation
            dispatchCurrentLocked(operation)
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
            val operation = current ?: return false
            if (operation.key != key) return false
            finishCurrentLocked(
                operation,
                if (successful) {
                    CentralGattOperationOutcome.Success(status)
                } else {
                    CentralGattOperationOutcome.StatusFailure(status)
                },
            )
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

    private fun onTimeout(key: CentralGattOperationKey) {
        val postActions = synchronized(lock) {
            val operation = current ?: return
            if (operation.key != key) return
            finishCurrentLocked(operation, CentralGattOperationOutcome.TimedOut)
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
            onTimeout(operation.key)
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

    private inner class PostActions(
        val completion: (() -> Unit)? = null,
        val notifyReady: Boolean = false,
    ) {
        fun run() {
            completion?.invoke()
            if (notifyReady) onReady()
        }
    }

    private data class Operation(
        val key: CentralGattOperationKey,
        val label: String,
        val action: () -> Boolean,
        var onComplete: ((CentralGattOperationOutcome) -> Unit)?,
        var timeoutHandle: CentralGattTimeoutHandle? = null,
    )
}
