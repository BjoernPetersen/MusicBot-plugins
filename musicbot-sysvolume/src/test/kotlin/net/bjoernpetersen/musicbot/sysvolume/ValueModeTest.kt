package net.bjoernpetersen.musicbot.sysvolume

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

class ValueModeTest {
    private val ValueMode.min: String
        get() = when (this) {
            ValueMode.Percent, ValueMode.SixteenBit -> "0"
            ValueMode.Decimal -> "0.0"
        }

    private val ValueMode.max: String
        get() = when (this) {
            ValueMode.Percent -> "100"
            ValueMode.Decimal -> "1.0"
            ValueMode.SixteenBit -> ((1 shl 16) - 1).toString()
        }

    @TestFactory
    fun toReversesFrom(): List<DynamicTest> {
        return (0..100)
            .flatMap { percent -> ValueMode.values().map { percent to it } }
            .map { (percent: Int, mode: ValueMode) ->
                dynamicTest("Mode $mode for $percent percent") {
                    val fromPercent = mode.fromPercent(percent)
                    println("String value: $fromPercent")
                    val reversed = mode.toPercent(fromPercent)
                    assertEquals(percent, reversed)
                }
            }
    }

    @TestFactory
    fun reachesMin() = ValueMode.values()
        .map {
            dynamicTest("Mode $it") {
                assertEquals(it.min, it.fromPercent(0))
            }
        }

    @TestFactory
    fun reachesMax() = ValueMode.values()
        .map {
            dynamicTest("Mode $it") {
                assertEquals(it.max, it.fromPercent(100))
            }
        }
}
