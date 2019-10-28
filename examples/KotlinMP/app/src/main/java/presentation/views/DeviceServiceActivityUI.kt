package presentation.views

import org.jetbrains.anko.*
import org.jetbrains.anko.sdk27.coroutines.onItemClick
import presentation.activities.DeviceServiceActivity
import presentation.adapters.DeviceServiceAdapter
import presentation.viewmodels.deviceservice.DeviceCharacteristicsViewModel

class DeviceServiceActivityUI(
    private val deviceServiceActivity: DeviceServiceActivity,
    private val viewModel: DeviceCharacteristicsViewModel
): AnkoComponent<DeviceServiceActivity> {

    private val deviceAdapter = DeviceServiceAdapter(viewModel.deviceCharacteristicViewModels())

    fun refresh() {
        deviceServiceActivity.runOnUiThread {
            deviceAdapter.deviceCharacteristicsViewModels = viewModel.deviceCharacteristicViewModels()
            deviceAdapter.notifyDataSetChanged()
        }
    }

    override fun createView(ui: AnkoContext<DeviceServiceActivity>) = with(ui) {
        verticalLayout {
            relativeLayout {
                textView("${viewModel.displayName} Characteristics").lparams {
                    centerHorizontally()
                }
            }
            listView {
                adapter = deviceAdapter
            }
        }
    }

}