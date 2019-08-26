package dev.bluefalcon.views

import android.graphics.Typeface
import android.os.Build
import androidx.annotation.RequiresApi
import dev.bluefalcon.activities.DeviceServiceActivity
import dev.bluefalcon.viewModels.DeviceServiceViewModel
import org.jetbrains.anko.*
import org.jetbrains.anko.sdk27.coroutines.onItemClick

class DeviceServiceActivityUI(private val viewModel: DeviceServiceViewModel) : AnkoComponent<DeviceServiceActivity> {

    @RequiresApi(Build.VERSION_CODES.ECLAIR)
    override fun createView(ui : AnkoContext<DeviceServiceActivity>) = with(ui) {
        verticalLayout {
            relativeLayout {
                textView(viewModel.service.uuid.toString()).lparams {
                    centerHorizontally()
                }
            }
            textView("Characteristics") {
                typeface = Typeface.DEFAULT_BOLD
                padding = 10
            }
            listView {
                adapter = viewModel.deviceServiceAdapter
            }.onItemClick { _, _, index, _ ->

            }
        }
    }

}