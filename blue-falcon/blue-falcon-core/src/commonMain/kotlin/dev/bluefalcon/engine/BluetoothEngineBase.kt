package dev.bluefalcon.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlin.coroutines.CoroutineContext

abstract class BluetoothEngineBase(private val engineName: String) : BluetoothEngine {

    override val dispatcher: CoroutineDispatcher
        by lazy {
//            createDispatcher() }
            throw NotImplementedError("createDispatcher() is not implemented")
        }

    override val coroutineContext: CoroutineContext
        by lazy {
            dispatcher + CoroutineName("$engineName-context")
        }
}
