package dev.bluefalcon.views

import android.util.Log
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
                viewModel.devicesAdapter.getItem(index).let {
                    Log.d("BlueFalcon", "Clicked item")
                    owner.startActivity<DeviceActivity>(
                        "device" to viewModel.devicesAdapter.getItem(index).bluetoothDevice
                    )
                }
            }
        }
    }
}