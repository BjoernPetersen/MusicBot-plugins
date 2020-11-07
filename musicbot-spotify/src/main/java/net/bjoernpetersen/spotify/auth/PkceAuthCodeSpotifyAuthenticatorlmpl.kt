package net.bjoernpetersen.spotify.auth

import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.cancel
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.IntSerializer
import net.bjoernpetersen.musicbot.api.config.NonnullConfigChecker
import net.bjoernpetersen.musicbot.api.config.NumberBox
import net.bjoernpetersen.musicbot.api.config.serialized
import net.bjoernpetersen.musicbot.api.plugin.PluginScope
import net.bjoernpetersen.musicbot.spi.plugin.management.ProgressFeedback
import net.bjoernpetersen.musicbot.spi.plugin.predefined.spotify.SpotifyAuthenticator
import net.bjoernpetersen.musicbot.spi.plugin.predefined.spotify.SpotifyScope
import net.bjoernpetersen.musicbot.spi.util.BrowserOpener
import java.net.MalformedURLException
import java.net.URL
import javax.inject.Inject

@Suppress("TooManyFunctions")
@OptIn(ObsoleteCoroutinesApi::class)
class PkceAuthCodeSpotifyAuthenticatorImpl :
    SpotifyAuthenticator,
    CoroutineScope by PluginScope() {

    private val logger = KotlinLogging.logger { }

    override val name: String = "Desktop OAuth"
    override val description: String = "Performs the Authorization Code Flow with PKCE"

    @Inject
    private lateinit var browserOpener: BrowserOpener

    private lateinit var port: Config.SerializedEntry<Int>
    private lateinit var clientId: Config.StringEntry

    private val scopes: MutableSet<SpotifyScope> = HashSet(SpotifyScope.values().size * 2)

    override fun requireScopes(vararg scopes: SpotifyScope) {
        this.scopes.addAll(scopes)
    }

    override suspend fun getToken(): String {
        TODO()
    }

    override fun invalidateToken() {
        TODO()
    }

    private fun getSpotifyUrl(state: String, redirectUrl: URL): URL {
        try {
            return URL(
                SPOTIFY_URL.let { base ->
                    listOf(
                        "client_id=$CLIENT_ID",
                        "redirect_uri=${redirectUrl.toExternalForm().encodeURLParameter()}",
                        "response_type=token",
                        "scope=${scopes.joinToString(" ").encodeURLParameter()}",
                        "state=$state"
                    ).joinToString("&", prefix = "$base?")
                }
            )
        } catch (e: MalformedURLException) {
            throw IllegalArgumentException(e)
        }
    }

    override suspend fun initialize(progressFeedback: ProgressFeedback) {
        TODO()
    }

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        port = config.serialized("port") {
            description = "OAuth callback port"
            serializer = IntSerializer
            check(NonnullConfigChecker)
            uiNode = NumberBox(MIN_PORT, MAX_PORT)
            default(DEFAULT_PORT)
        }
        return listOf(port)
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> {
        TODO()
    }

    override fun createStateEntries(state: Config) = Unit

    override suspend fun close() {
        run { cancel() }
    }

    private companion object {
        private const val SPOTIFY_URL = " https://accounts.spotify.com/authorize"
        private const val CLIENT_ID = "902fe6b9a4b6421caf88ee01e809939a"

        const val DEFAULT_PORT = 58642
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
    }
}
