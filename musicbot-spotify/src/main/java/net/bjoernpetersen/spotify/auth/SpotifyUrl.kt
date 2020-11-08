package net.bjoernpetersen.spotify.auth

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import net.bjoernpetersen.musicbot.spi.plugin.predefined.spotify.SpotifyScope

private const val SPOTIFY_PKCE_URL = " https://accounts.spotify.com/authorize"
internal const val CLIENT_ID = "902fe6b9a4b6421caf88ee01e809939a"

internal fun buildSpotifyPkceUrl(
    redirectUrl: Url,
    codeChallenge: String,
    state: String,
    scopes: Collection<SpotifyScope>
): Url {
    val builder = URLBuilder(SPOTIFY_PKCE_URL)

    builder.parameters.apply {
        append("client_id", CLIENT_ID)
        append("response_type", "code")
        append("redirect_uri", redirectUrl.toString())
        append("code_challenge_method", "S256")
        append("code_challenge", codeChallenge)
        append("state", state)
        append("scope", scopes.joinToString(" ").encodeURLParameter())
    }

    return builder.build()
}
