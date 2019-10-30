package presentation.adapters

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import org.jetbrains.anko.*
import org.jetbrains.anko.sdk27.coroutines.onClick
import presentation.viewmodels.deviceservice.DeviceCharacteristicViewModel

class DeviceServiceAdapter(var deviceCharacteristicsViewModels : List<DeviceCharacteristicViewModel>) : BaseAdapter() {

    override fun getView(i : Int, v : View?, parent : ViewGroup?) : View {
        val item = getItem(i)
        return with(parent!!.context) {
            verticalLayout {
                verticalLayout {
                    linearLayout {
                        textView("ID: ") {
                            typeface = Typeface.DEFAULT_BOLD
                        }
                        textView(item.displayName)
                    }
                    linearLayout {
                        textView("Value: ") {
                            typeface = Typeface.DEFAULT_BOLD
                        }
                        textView(item.value())
                    }
                    linearLayout {
                        textView("Notify: ") {
                            typeface = Typeface.DEFAULT_BOLD
                        }
                        //textView(item.notify.toString())
                    }
                }
                linearLayout {
                    button("Read") {
                        onClick {
                            item.readCharacteristicTapped()
                        }
                    }
                    button("Notify") {
                        onClick {
                            item.notifyCharacteristicTapped()
                        }
                    }
                    button("Write") {
                        onClick {
                            item.writeCharactersticTapped()
                        }
                    }
                }
            }
        }
    }

    override fun getItem(position : Int) : DeviceCharacteristicViewModel = deviceCharacteristicsViewModels[position]

    override fun getCount() : Int = deviceCharacteristicsViewModels.size

    override fun getItemId(position : Int) : Long = position.toLong()

}