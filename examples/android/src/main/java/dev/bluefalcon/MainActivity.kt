package dev.bluefalcon

import android.Manifest
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import org.jetbrains.anko.*

class MainActivity : AppCompatActivity() {

    internal val devices: MutableList<BluetoothPeripheral> = arrayListOf()
    private val bluetoothDelegate = BluetoothDelegate()
    private val mainActivityUI = MainActivityUI()

    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupBluetooth()
        mainActivityUI.setContentView(this)
    }

    private fun setupBluetooth() {
        try {
            val blueFalcon = BlueFalcon(this)
            blueFalcon.delegates.add(bluetoothDelegate)
            blueFalcon.scan()
        } catch (exception: PermissionException) {
            requestLocationPermission()
        }
    }

    private fun requestLocationPermission() {
        val permission = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
        ActivityCompat.requestPermissions(this, permission, 0)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        setupBluetooth()
    }

    inner class BluetoothDelegate: BlueFalconDelegate {

        override fun didDiscoverDevice(bluetoothPeripheral: BluetoothPeripheral) {
            if (!devices.contains(bluetoothPeripheral)) {
                devices.add(bluetoothPeripheral)
                mainActivityUI.mAdapter?.notifyDataSetChanged()
            }
        }

        override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {
        }

        override fun didDisconnect(bluetoothPeripheral: BluetoothPeripheral) {
        }

        override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

    }
}

class MainActivityUI : AnkoComponent<MainActivity> {
    var mAdapter : DeviceAdapter? = null;

    override fun createView(ui : AnkoContext<MainActivity>) = with(ui) {
        mAdapter = DeviceAdapter(owner)
        verticalLayout {
            relativeLayout {
                textView("Blue Falcon Devices").lparams {
                    centerHorizontally()
                }
            }
            listView {
                adapter = mAdapter
            }
        }
    }
}

class DeviceAdapter(activity : MainActivity) : BaseAdapter() {
    private var list : List<BluetoothPeripheral> = activity.devices

    @RequiresApi(Build.VERSION_CODES.ECLAIR)
    override fun getView(i : Int, v : View?, parent : ViewGroup?) : View {
        val item = getItem(i)
        return with(parent!!.context) {
            relativeLayout {
                textView(item.address) {
                    textSize = 32f
                }
            }
        }
    }

    override fun getItem(position : Int) : BluetoothPeripheral = list.get(position)

    override fun getCount() : Int = list.size

    override fun getItemId(position : Int) : Long = position.toLong()

}
