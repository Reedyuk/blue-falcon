package dev.bluefalcon.view

import dev.bluefalcon.controller.MainController
import tornadofx.*

class MainView : View() {
    val ctrl: MainController by inject()

    init {
        this.title = ctrl.title
    }

    override val root = borderpane {
        style {
            minWidth = ctrl.minWidth
            minHeight = ctrl.minHeight
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



        }
    }
}
