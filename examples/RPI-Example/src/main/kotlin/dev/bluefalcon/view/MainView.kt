package dev.bluefalcon.view

import dev.bluefalcon.BluetoothPeripheral
import dev.bluefalcon.controller.MainController
import javafx.scene.control.ListView
import tornadofx.*

class MainView : View() {
    private val controller: MainController by inject()

    init {
        this.title = controller.title
    }

    var listview: ListView<BluetoothPeripheral>? = null

    override val root = borderpane {
        style {
            minWidth = controller.minWidth
            minHeight = controller.minHeight
        }
        top {
            hbox {
                style {
                    paddingTop = 20
                    paddingLeft = 20
                }
            }
        }
        center {
            listview = listview(controller.devices) {
                onUserSelect(1) {
                    println("selected ${selectedItem?.name}")
                }
            }
        }
    }
}
