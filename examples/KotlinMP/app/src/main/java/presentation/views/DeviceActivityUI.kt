package presentation.views

import android.widget.TextView
import org.jetbrains.anko.*
import org.jetbrains.anko.sdk27.coroutines.onItemClick
import presentation.activities.DeviceActivity
import presentation.activities.DeviceServiceActivity
import presentation.adapters.DeviceAdapter
import presentation.viewmodels.device.DeviceViewModel

class DeviceActivityUI(
    private val deviceActivity: DeviceActivity,
    private val viewModel: DeviceViewModel
): AnkoComponent<DeviceActivity> {

    private val deviceAdapter = DeviceAdapter(viewModel.deviceServiceViewModels())
    private lateinit var rssiTextView: TextView

    fun refresh() {
        deviceActivity.runOnUiThread {
            viewModel.rssi?.let {
                rssiTextView.text = "RSSI: ${viewModel.rssi}"
            }
            deviceAdapter.deviceServiceViewModels = viewModel.deviceServiceViewModels()
            deviceAdapter.notifyDataSetChanged()
        }
    }

    override fun createView(ui: AnkoContext<DeviceActivity>) = with(ui) {
        verticalLayout {
            relativeLayout {
                textView("${viewModel.displayName} Services").lparams {
                    centerHorizontally()
                }
            }
            rssiTextView = textView("RSSI: ${viewModel.rssi}")
            listView {
                adapter = deviceAdapter
            }.onItemClick { _, _, index, _ ->
                owner.startActivity<DeviceServiceActivity>(
                    "device" to viewModel.bluetoothDevice.bluetoothDevice,
                    "service" to viewModel.services[index].service
                )
            }
        }
    }

}