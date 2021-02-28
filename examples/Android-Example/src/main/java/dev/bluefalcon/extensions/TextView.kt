package dev.bluefalcon.extensions

import android.text.Editable
import android.text.TextWatcher
import android.widget.TextView
import dev.bluefalcon.observables.StandardObservableProperty


@Suppress("NOTHING_TO_INLINE")
inline fun TextView.bindString(bond: StandardObservableProperty<String>) {
    text = bond.value

    addTextChangedListener(object: TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            if (bond.value != s.toString()) {
                bond.value = s.toString()
            }
        }

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        }

    })
    bond.addValueChangeListener {
        text = it
    }

}