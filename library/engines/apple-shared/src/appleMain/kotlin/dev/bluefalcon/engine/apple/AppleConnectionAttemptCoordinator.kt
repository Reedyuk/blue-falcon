package dev.bluefalcon.engine.apple

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes native attempts for one UUID without holding up other devices. */
internal class AppleConnectionAttemptCoordinator {
    private class Entry {
        val mutex = Mutex()
        var users = 0
    }
    private val registryMutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withAttempt(uuid: String, action: suspend () -> T): T {
        val entry = registryMutex.withLock {
            entries.getOrPut(uuid) { Entry() }.also { it.users++ }
        }
        try {
            return entry.mutex.withLock { action() }
        } finally {
            withContext(NonCancellable) {
                registryMutex.withLock {
                    entry.users--
                    if (entry.users == 0) entries.remove(uuid)
                }
            }
        }
    }
}
