package dev.bluefalcon.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

abstract class BluetoothEngineBase(private val engineName: String) : BluetoothEngine {

    override val dispatcher: CoroutineDispatcher
        by lazy { config.dispatcher ?: ioDispatcher() }

    override val coroutineContext: CoroutineContext
        by lazy {
            silentSupervisor() + dispatcher + CoroutineName("$engineName-context")
        }
}

fun silentSupervisor(parent: Job? = null): CoroutineContext =
    SupervisorJob(parent) + CoroutineExceptionHandler { _, _ -> }

internal expect fun ioDispatcher(): CoroutineDispatcher
