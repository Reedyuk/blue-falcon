package dev.bluefalcon.views

import android.graphics.Typeface.DEFAULT_BOLD
import android.os.Build
import androidx.annotation.RequiresApi
import dev.bluefalcon.activities.DeviceActivity
import dev.bluefalcon.activities.DeviceServiceActivity
import dev.bluefalcon.extensions.bindString
import dev.bluefalcon.viewModels.DeviceViewModel
import org.jetbrains.anko.*
import org.jetbrains.anko.sdk27.coroutines.onItemClick

class DeviceActivityUI(private val viewModel: DeviceViewModel) : AnkoComponent<DeviceActivity> {

    @RequiresApi(Build.VERSION_CODES.ECLAIR)
    override fun createView(ui : AnkoContext<DeviceActivity>) = with(ui) {
        verticalLayout {
            relativeLayout {
                textView(viewModel.title).lparams {
                    centerHorizontally()
                }
            }
            textView {
                bindString(viewModel.connectionStatus)
                padding = 10
            }
            textView("Services") {
                typeface = DEFAULT_BOLD
                padding = 10
            }
            listView {
                adapter = viewModel.deviceAdapter
            }.onItemClick { _, _, index, _ ->
                viewModel.deviceAdapter.getItem(index).let {
                    owner.startActivity<DeviceServiceActivity>(
                        "service" to viewModel.deviceAdapter.getItem(index).service,
                        "device" to viewModel.bluetoothPeripheral.bluetoothDevice
                    )
                }
            }
        }
    }
}