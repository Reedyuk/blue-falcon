package dev.bluefalcon.views

import dev.bluefalcon.activities.DeviceActivity
import dev.bluefalcon.activities.DevicesActivity
import dev.bluefalcon.viewModels.DevicesViewModel
import org.jetbrains.anko.*
import org.jetbrains.anko.sdk27.coroutines.onItemClick

class DevicesActivityUI(private val viewModel: DevicesViewModel) : AnkoComponent<DevicesActivity> {

    override fun createView(ui : AnkoContext<DevicesActivity>) = with(ui) {
        verticalLayout {
            relativeLayout {
                textView("Blue Falcon Devices").lparams {
                    centerHorizontally()
                }
            }
            listView {
                adapter = viewModel.devicesAdapter
            }.onItemClick { _, _, index, _ ->
                owner.startActivity<DeviceActivity>(
                    "device" to viewModel.devices[index].bluetoothDevice
                )
            }
        }
    }
}