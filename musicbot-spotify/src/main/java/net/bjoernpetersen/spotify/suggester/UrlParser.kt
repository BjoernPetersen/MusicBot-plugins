package net.bjoernpetersen.spotify.suggester

import java.net.MalformedURLException
import java.net.URL

internal fun getSongId(url: String): String? {
    return try {
        val parsed = URL(url)
        val pathParts = parsed.path.split("/".toRegex())
            .dropLastWhile { it.isEmpty() }
            .toTypedArray()
        if (pathParts.size != 3) {
            null
        } else pathParts[2]
    } catch (e: MalformedURLException) {
        null
    }
}
