package dev.bluefalcon.engine.apple

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/** Exact owner/timer identity survives cancellation of an already executing timer. */
internal class AppleTerminalWatchdogs<T : Any>(
    private val schedule: (() -> Unit) -> Job,
    private val maximumOwners: Int = 32,
) {
    private class Ticket { var job: Job? = null }
    private val tickets = MutableStateFlow<Map<T, Ticket>>(emptyMap())
    fun isPending(owner: T): Boolean = owner in tickets.value
    fun start(owner: T, stillCurrent: () -> Boolean, expire: () -> Unit): Boolean {
        val ticket = Ticket()
        while (true) {
            val current = tickets.value
            if (owner in current) return false
            check(current.size < maximumOwners) { "Apple terminal watchdog limit reached" }
            if (tickets.compareAndSet(current, current + (owner to ticket))) break
        }
        ticket.job = schedule {
            if (remove(owner, ticket) && stillCurrent()) expire()
        }
        if (tickets.value[owner] !== ticket) ticket.job?.cancel()
        return true
    }
    fun complete(owner: T) {
        val ticket = tickets.value[owner] ?: return
        if (remove(owner, ticket)) ticket.job?.cancel()
    }
    private fun remove(owner: T, ticket: Ticket): Boolean {
        while (true) {
            val current = tickets.value
            if (current[owner] !== ticket) return false
            if (tickets.compareAndSet(current, current - owner)) return true
        }
    }
}
