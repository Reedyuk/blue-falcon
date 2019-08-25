package dev.bluefalcon.observables

import kotlin.properties.ObservableProperty

open class StandardObservableProperty<T>(
    initialValue: T
) : ObservableProperty<T>(initialValue) {

    private val valueChangeListeners = HashSet<(newVal: T) -> Unit>()

    fun addValueChangeListener(listener: (newVal: T) -> Unit) {
        valueChangeListeners.add(listener)
    }
    fun removeValueChangeListener(listener: (newVal: T) -> Unit) {
        valueChangeListeners.remove(listener)
    }
    open var value: T = initialValue
        set(newValue: T) {
            val oldVal = field
            super.beforeChange(::value, oldVal, newValue)
            field = newValue
            super.afterChange(::value, oldVal, newValue)

            valueChangeListeners.forEach {
                it(newValue)
            }
        }
}