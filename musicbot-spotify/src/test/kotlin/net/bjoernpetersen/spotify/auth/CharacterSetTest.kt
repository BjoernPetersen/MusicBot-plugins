package net.bjoernpetersen.spotify.auth

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class CharacterSetTest {

    @TestFactory
    fun checkSorted(): List<DynamicTest> {
        return CharacterSet.allSets().map { characterSet ->
            dynamicTest("$characterSet is sorted") {
                val original = characterSet.allowed.toCharArray()
                val sorted = original.sortedArray()
                assertArrayEquals(original, sorted)
            }
        }
    }

    @TestFactory
    fun checkDistinct(): List<DynamicTest> {
        return CharacterSet.allSets().map { characterSet ->
            dynamicTest("$characterSet doesn't contain duplicates") {
                val sorted = characterSet.allowed.toSet()
                assertEquals(characterSet.length, sorted.size)
            }
        }
    }

    @TestFactory
    fun alphabetSize(): List<DynamicTest> {
        val alphabetLength = 26
        return listOf(CharacterSet.AlphabetLower, CharacterSet.AlphabetUpper)
            .map {
                dynamicTest("$it has length $alphabetLength") {
                    assertEquals(alphabetLength, it.length)
                }
            }
    }

    @Test
    fun numberSize() {
        assertEquals(10, CharacterSet.Numbers.length)
    }

    @Test
    fun factoryDeduplication() {
        val testString = "abcddefah1"
        val characterSet = CharacterSet.of(testString)
        assertEquals(testString.length - 2, characterSet.length)
    }

    @Test
    fun factorySorting() {
        val testString = "zaZ109A"
        val characterSet = CharacterSet.of(testString)
        assertEquals("019AZaz", characterSet.allowed)
    }

    @TestFactory
    fun `contains is true for all elements`(): List<DynamicTest> {
        return CharacterSet.allSets().map { characterSet ->
            dynamicTest("$characterSet contains all its elements") {
                for (char in characterSet.allowed) {
                    assertTrue(characterSet.contains(char)) {
                        "$characterSet doesn't contain $char"
                    }
                }
            }
        }
    }
}
