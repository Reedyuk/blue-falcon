package dev.bluefalcon.observables

class ObservableReducer<R, T>(
    vararg observates: StandardObservableProperty<R>,
    reducer: (List<R>) -> T
): StandardObservableProperty<T>(reducer(observates.map { it.value })) {
    init {
        value = reducer(observates.map { it.value })
        observates.forEach {
            it.addValueChangeListener {
                value = reducer(observates.map { it.value })
            }
        }
    }

}