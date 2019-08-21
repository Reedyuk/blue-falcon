package dev.bluefalcon

import android.util.Log

actual fun log(message: String) {
    Log.i("BlueFalcon", message)
}