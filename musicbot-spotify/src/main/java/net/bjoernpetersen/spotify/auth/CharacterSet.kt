package net.bjoernpetersen.spotify.auth

import java.nio.CharBuffer

/**
 * A set of distinct characters.
 *
 * @param allowed the allowed characters in this set.
 * The string is guaranteed to be sorted and non-empty.
 */
internal class CharacterSet private constructor(
    val allowed: String
) {
    val length: Int
        get() = allowed.length

    operator fun get(index: Int): Char = allowed[index]

    operator fun contains(char: Char): Boolean {
        // allowed is sorted, so we do a binary search
        // (yes, this is overkill and quite possibly slower than just iterating once)
        var left = 0
        var right = length

        while (left != right) {
            val pivot = left + ((right - left) / 2)
            val element = this[pivot]
            when {
                char == element -> return true
                char < element -> {
                    right = pivot
                }
                else -> {
                    left = pivot
                }
            }
        }

        return false
    }

    /**
     * Creates a new character set allowing all characters that are in this or
     * the other set (or both).
     */
    operator fun plus(other: CharacterSet): CharacterSet {
        if (this == other) return this
        val charBuffer = CharBuffer.allocate(length + other.length)
        var thisIndex = 0
        var otherIndex = 0
        repeat(length + other.length) {
            val nextChar = if (thisIndex >= length) {
                other[otherIndex++]
            } else if (otherIndex >= other.length) {
                this[thisIndex++]
            } else {
                val thisChar = this[thisIndex]
                val otherChar = other[otherIndex]
                if (thisChar < otherChar) {
                    thisIndex += 1
                    thisChar
                } else {
                    otherIndex += 1
                    otherChar
                }
            }

            val position = charBuffer.position()
            if (position == 0 || nextChar != charBuffer.get(position - 1)) {
                charBuffer.put(nextChar)
            }
        }
        charBuffer.flip()
        val combinedString = charBuffer.toString()
        return CharacterSet(combinedString)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CharacterSet

        if (allowed != other.allowed) return false

        return true
    }

    override fun hashCode(): Int {
        return allowed.hashCode()
    }

    override fun toString(): String = allowed

    companion object {
        val AlphabetLower = CharacterSet("abcdefghijklmnopqrstuvwxyz")
        val AlphabetUpper = CharacterSet("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        val Alphabet = AlphabetLower + AlphabetUpper
        val Numbers = CharacterSet("0123456789")
        val Alphanumeric = Alphabet + Numbers

        /**
         * Creates a custom character set containing the specified characters.
         *
         * The given string will be sorted and duplicate chars will be discarded.
         *
         * @param chars a non-empty string
         * @throws IllegalArgumentException if [chars] is empty
         */
        fun of(chars: String): CharacterSet {
            require(chars.isNotEmpty())
            val set = chars.toSortedSet()
            val builder = StringBuilder(set.size)
            set.forEach { builder.append(it) }
            return CharacterSet(builder.toString())
        }

        /**
         * A list of all defined character sets for testing.
         */
        internal fun allSets() = listOf(
            AlphabetLower,
            AlphabetUpper,
            Alphabet,
            Numbers,
            Alphanumeric
        )
    }
}
