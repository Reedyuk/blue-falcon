package dev.bluefalcon.adapters

import android.graphics.Typeface
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import androidx.annotation.RequiresApi
import dev.bluefalcon.viewModels.DeviceCharacteristicViewModel
import org.jetbrains.anko.*
import org.jetbrains.anko.sdk27.coroutines.onClick

class DeviceServiceAdapter(var viewModels : List<DeviceCharacteristicViewModel>) : BaseAdapter() {

    @RequiresApi(Build.VERSION_CODES.ECLAIR)
    override fun getView(i : Int, v : View?, parent : ViewGroup?) : View {
        val item = getItem(i)
        return with(parent!!.context) {
            verticalLayout {
                verticalLayout {
                    linearLayout {
                        textView("ID: ") {
                            typeface = Typeface.DEFAULT_BOLD
                        }
                        textView(item.id.toString())
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
                            item.writeCharactersticTapped()
                        }
                    }
                }
                if (item.descriptors.isNotEmpty()) {
                    verticalLayout {
                        textView("Descriptors") {
                            typeface = Typeface.DEFAULT_BOLD
                        }
                        linearLayout {
                            textView("Values")
                            item.descriptorValues.forEach {
                                textView(" ${it.key}: ${it.value} ")
                            }
                        }
                        linearLayout {
                            button("Read") {
                                onClick {
                                    item.readDescriptorTapped()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun getItem(position : Int) : DeviceCharacteristicViewModel = viewModels[position]

    override fun getCount() : Int = viewModels.size

    override fun getItemId(position : Int) : Long = position.toLong()

}