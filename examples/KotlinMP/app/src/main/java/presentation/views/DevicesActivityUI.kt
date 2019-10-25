package presentation.views

import org.jetbrains.anko.*
import org.jetbrains.anko.sdk27.coroutines.onItemClick
import presentation.activities.DeviceActivity
import presentation.activities.DevicesActivity
import presentation.adapters.DevicesAdapter
import presentation.viewmodels.devices.DevicesViewModel

class DevicesActivityUI(private val viewModel: DevicesViewModel) : AnkoComponent<DevicesActivity> {

    private val devicesAdapter = DevicesAdapter(viewModel.deviceViewModels())

    fun refresh() {
        devicesAdapter.deviceViewModels = viewModel.deviceViewModels()
        devicesAdapter.notifyDataSetChanged()
    }

    override fun createView(ui : AnkoContext<DevicesActivity>) = with(ui) {
        verticalLayout {
            relativeLayout {
                textView("Blue Falcon Devices").lparams {
                    centerHorizontally()
                }
            }
            listView {
                adapter = devicesAdapter
            }.onItemClick { _, _, index, _ ->
                owner.startActivity<DeviceActivity>(
                    "device" to viewModel.devices[index].bluetoothDevice
                )
            }
        }
    }
}