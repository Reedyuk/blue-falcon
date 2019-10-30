import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

internal actual val Dispatchers.MainCompat: CoroutineContext
    get() = Main
