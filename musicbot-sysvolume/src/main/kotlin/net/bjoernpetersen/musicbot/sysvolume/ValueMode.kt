package net.bjoernpetersen.musicbot.sysvolume

import net.bjoernpetersen.musicbot.api.config.ConfigSerializer
import net.bjoernpetersen.musicbot.api.config.SerializationException
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

internal enum class ValueMode(val description: String) {
    Percent("Percent (0-100)") {
        override fun toPercent(value: String): Int {
            return value.toInt()
        }

        override fun fromPercent(percent: Int): String {
            return percent.toString()
        }
    },

    Decimal("Decimal (0.0-1.0)") {
        override fun toPercent(value: String): Int {
            return (value.toFloat() * 100).roundToInt()
        }

        override fun fromPercent(percent: Int): String {
            return DecimalFormat("#0.0##", DecimalFormatSymbols.getInstance(Locale.US))
                .apply { roundingMode = RoundingMode.HALF_UP }
                .format(percent.toFloat() / 100)
        }
    },

    SixteenBit("16-bit (0-65535)") {
        override fun toPercent(value: String): Int {
            val decimal = value.toFloat() / 65535
            // Converting back to string isn't great, but it's probably acceptable
            return Decimal.toPercent(decimal.toString())
        }

        override fun fromPercent(percent: Int): String {
            return (655.35 * percent).roundToInt().toString()
        }
    };

    abstract fun toPercent(value: String): Int
    abstract fun fromPercent(percent: Int): String

    object Serializer : ConfigSerializer<ValueMode> {
        override fun serialize(obj: ValueMode): String {
            return obj.name
        }

        override fun deserialize(string: String): ValueMode {
            return try {
                ValueMode.valueOf(string)
            } catch (e: IllegalArgumentException) {
                throw SerializationException()
            }
        }
    }
}
