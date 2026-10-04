package dev.bluefalcon.engine.apple

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AppleConnectionAttemptStorageStatus(val retainedPeers: Int = 0, val retainedUsers: Int = 0, val rejectedAttempts: Long = 0)

/** Serializes native attempts for one UUID without holding up other devices. */
internal class AppleConnectionAttemptCoordinator(private val maximumPeers: Int = 32, private val maximumUsers: Int = 128) {
    init { require(maximumPeers > 0); require(maximumUsers > 0) }
    private val mutableStatus = MutableStateFlow(AppleConnectionAttemptStorageStatus())
    val status = mutableStatus.asStateFlow()
    private fun publishStatus() { mutableStatus.value = mutableStatus.value.copy(retainedPeers = entries.size, retainedUsers = entries.values.sumOf { it.users }) }
    private class Entry {
        val mutex = Mutex()
        var users = 0
    }
    private val registryMutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withAttempt(uuid: String, action: suspend () -> T): T {
        val entry = registryMutex.withLock {
            val current = mutableStatus.value
            if ((uuid !in entries && entries.size >= maximumPeers) || current.retainedUsers >= maximumUsers) {
                mutableStatus.value = current.copy(rejectedAttempts = if (current.rejectedAttempts == Long.MAX_VALUE) Long.MAX_VALUE else current.rejectedAttempts + 1)
                throw IllegalStateException("Apple connection attempt capacity exceeded")
            }
            entries.getOrPut(uuid) { Entry() }.also { it.users++; publishStatus() }
        }
        try {
            return entry.mutex.withLock { action() }
        } finally {
            withContext(NonCancellable) {
                registryMutex.withLock {
                    entry.users--
                    if (entry.users == 0) entries.remove(uuid)
                    publishStatus()
                }
            }
        }
    }
}
