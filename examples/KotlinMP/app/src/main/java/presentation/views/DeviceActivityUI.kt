package presentation.views

import org.jetbrains.anko.*
import org.jetbrains.anko.sdk27.coroutines.onItemClick
import presentation.activities.DeviceActivity
import presentation.adapters.DeviceAdapter
import presentation.viewmodels.device.DeviceViewModel

class DeviceActivityUI(
    private val deviceActivity: DeviceActivity,
    private val viewModel: DeviceViewModel
): AnkoComponent<DeviceActivity> {

    private val deviceAdapter = DeviceAdapter(viewModel.deviceServiceViewModels())

    fun refresh() {
        deviceActivity.runOnUiThread {
            deviceAdapter.deviceServiceViewModels = viewModel.deviceServiceViewModels()
            deviceAdapter.notifyDataSetChanged()
        }
    }

    override fun createView(ui: AnkoContext<DeviceActivity>) = with(ui) {
        verticalLayout {
            relativeLayout {
                textView(viewModel.displayName).lparams {
                    centerHorizontally()
                }
            }
            listView {
                adapter = deviceAdapter
            }.onItemClick { _, _, index, _ ->
            }
        }
    }

}