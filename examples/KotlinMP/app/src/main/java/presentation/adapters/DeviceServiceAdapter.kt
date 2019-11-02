package presentation.adapters

import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import org.jetbrains.anko.*
import org.jetbrains.anko.sdk27.coroutines.onClick
import presentation.activities.DeviceServiceActivity
import presentation.viewmodels.deviceservice.DeviceCharacteristicViewModel

class DeviceServiceAdapter(
    private val deviceServiceActivity: DeviceServiceActivity,
    var deviceCharacteristicsViewModels : List<DeviceCharacteristicViewModel>
) : BaseAdapter() {

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
                        textView(item.value)
                    }
                    linearLayout {
                        textView("Notify: ") {
                            typeface = Typeface.DEFAULT_BOLD
                        }
                        textView(item.notify.toString())
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
                            val builder = AlertDialog.Builder(deviceServiceActivity)
                            builder.setTitle("Input value to send to characteristic")
                            val input = EditText(deviceServiceActivity)
                            input.inputType = InputType.TYPE_CLASS_TEXT
                            builder.setView(input)
                            builder.setPositiveButton("OK") { _, _ ->
                                item.writeCharactersticTapped(input.text.toString())
                            }
                            builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
                            builder.show()
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