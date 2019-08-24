package dev.bluefalcon.views

import dev.bluefalcon.activities.DevicesActivity
import dev.bluefalcon.viewModels.DevicesViewModel
import org.jetbrains.anko.*

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
            }
        }
    }
}