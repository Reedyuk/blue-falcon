package sample

import dev.bluefalcon.ApplicationContext
import dev.bluefalcon.BlueFalcon

actual class Sample {
    actual fun checkMe() = 7
}

actual object Platform {
    actual val name: String = "iOS"
}

actual class Blue actual constructor(context: ApplicationContext) {
    actual val blueFalcon: BlueFalcon
        get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.
}