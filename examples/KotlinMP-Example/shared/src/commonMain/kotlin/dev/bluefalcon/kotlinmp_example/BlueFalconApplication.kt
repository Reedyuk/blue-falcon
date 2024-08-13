package dev.bluefalcon.kotlinmp_example

import dev.bluefalcon.ApplicationContext
import dev.bluefalcon.BlueFalcon
import dev.bluefalcon.kotlinmp_example.viewmodels.DevicesViewModel

class BlueFalconApplication(context: ApplicationContext) {

    val blueFalcon = BlueFalcon(context = context)

    fun createDevicesViewModel(): DevicesViewModel = DevicesViewModel(blueFalcon)
}