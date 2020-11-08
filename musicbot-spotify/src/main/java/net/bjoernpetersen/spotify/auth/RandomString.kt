package net.bjoernpetersen.spotify.auth

import java.security.SecureRandom

internal object RandomString {
    private val random = SecureRandom()

    fun generate(length: Int = 16, allowed: CharacterSet = CharacterSet.Alphanumeric): String {
        val builder = StringBuilder(length)
        repeat(length) {
            val index = random.nextInt(allowed.length)
            builder.append(allowed[index])
        }
        return builder.toString()
    }
}
