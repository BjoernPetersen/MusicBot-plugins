package net.bjoernpetersen.spotify.auth

import java.time.Instant

internal data class Token(val value: String, val expiration: Instant) {
    fun isExpired(offset: Long = 600) = Instant.now().plusSeconds(offset).isAfter(expiration)
}
