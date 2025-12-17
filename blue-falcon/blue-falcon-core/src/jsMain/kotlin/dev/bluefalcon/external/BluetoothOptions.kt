package dev.bluefalcon.external

data class BluetoothOptions(
    val acceptAllDevices: Boolean,
    val filters: Array<Filter>? = null,
    val optionalServices: Array<String> = emptyArray()
) {
    sealed class Filter {

        data class Name(val name: String) : Filter()

        data class NamePrefix(val namePrefix: String) : Filter()

        data class Services(val services: Array<String>) : Filter() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other == null || this::class.js != other::class.js) return false
                other as Services
                if (!services.contentEquals(other.services)) return false
                return true
            }

            override fun hashCode(): Int {
                return services.contentHashCode()
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class.js != other::class.js) return false

        other as BluetoothOptions

        if (acceptAllDevices != other.acceptAllDevices) return false
        if (filters != null) {
            if (other.filters == null) return false
            if (!filters.contentEquals(other.filters)) return false
        } else if (other.filters != null) return false
        if (!optionalServices.contentEquals(other.optionalServices)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = acceptAllDevices.hashCode()
        result = 31 * result + (filters?.contentHashCode() ?: 0)
        result = 31 * result + optionalServices.contentHashCode()
        return result
    }
}