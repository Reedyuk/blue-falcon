package sample

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.widget.TextView
import dev.bluefalcon.ApplicationContext
import dev.bluefalcon.BlueFalcon
import presentation.AppApplication

actual class Sample {
    actual fun checkMe() = 44
}

actual object Platform {
    actual val name: String = "Android"
}

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Sample().checkMe()
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.main_text).text = hello()
        try {
            AppApplication.bluetoothService.scan()
        } catch (exception: Exception) {
            findViewById<TextView>(R.id.main_text).text = exception.localizedMessage
        }
    }
}
