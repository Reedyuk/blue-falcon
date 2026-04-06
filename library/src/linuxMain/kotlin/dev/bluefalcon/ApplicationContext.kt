package dev.bluefalcon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

actual class ApplicationContext(
    val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
)
