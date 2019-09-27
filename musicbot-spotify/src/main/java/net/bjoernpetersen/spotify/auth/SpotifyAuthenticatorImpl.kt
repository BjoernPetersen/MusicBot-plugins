package net.bjoernpetersen.spotify.auth

import com.wrapper.spotify.exceptions.SpotifyWebApiException
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.config.ActionButton
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.ConfigSerializer
import net.bjoernpetersen.musicbot.api.config.ExperimentalConfigDsl
import net.bjoernpetersen.musicbot.api.config.IntSerializer
import net.bjoernpetersen.musicbot.api.config.NonnullConfigChecker
import net.bjoernpetersen.musicbot.api.config.NumberBox
import net.bjoernpetersen.musicbot.api.config.PasswordBox
import net.bjoernpetersen.musicbot.api.config.SerializationException
import net.bjoernpetersen.musicbot.api.config.serialized
import net.bjoernpetersen.musicbot.api.plugin.PluginScope
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.musicbot.spi.plugin.predefined.TokenRefreshException
import net.bjoernpetersen.musicbot.spi.plugin.predefined.spotify.SpotifyAuthenticator
import net.bjoernpetersen.musicbot.spi.util.BrowserOpener
import java.io.IOException
import java.net.MalformedURLException
import java.net.URL
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

@UseExperimental(ObsoleteCoroutinesApi::class)
class SpotifyAuthenticatorImpl : SpotifyAuthenticator,
    CoroutineScope by PluginScope(newSingleThreadContext("SpotifyAuth")) {

    private val logger = KotlinLogging.logger { }

    override val name: String = "Desktop OAuth"
    override val description: String = "Performs Implicit Grant OAuth flow by opening a browser"

    @Inject
    private lateinit var browserOpener: BrowserOpener

    private lateinit var port: Config.SerializedEntry<Int>
    private lateinit var clientId: Config.StringEntry

    private lateinit var tokenExpiration: Config.SerializedEntry<Instant>
    private lateinit var accessToken: Config.StringEntry
    private var currentToken: Token? = null
        set(value) {
            field = value
            tokenExpiration.set(value?.expiration)
            accessToken.set(value?.value)
        }

    override suspend fun getToken(): String {
        return withContext(coroutineContext) {
            val current = currentToken
            val refreshed = when {
                current == null -> initAuth()
                current.isExpired() -> authorize()
                else -> current
            }
            currentToken = refreshed
            refreshed.value
        }
    }

    override fun invalidateToken() {
        currentToken = null
    }

    private suspend fun initAuth(): Token {
        if (accessToken.get() != null && tokenExpiration.get() != null) {
            val token = accessToken.get()!!
            val expirationDate = tokenExpiration.get()!!
            val result = Token(token, expirationDate)
            // if it's expired, this call will refresh the token
            if (!result.isExpired()) return result
        }
        return authorize()
    }

    private fun getSpotifyUrl(state: String, redirectUrl: URL): URL {
        try {
            return URL(SPOTIFY_URL.let { base ->
                listOf(
                    "client_id=$CLIENT_ID",
                    "redirect_uri=${redirectUrl.toExternalForm().encodeURLParameter()}",
                    "response_type=token",
                    "scope=${SCOPES.joinToString(" ").encodeURLParameter()}",
                    "state=$state"
                ).joinToString("&", prefix = "$base?")
            })
        } catch (e: MalformedURLException) {
            throw IllegalArgumentException(e)
        }
    }

    private suspend fun authorize(): Token {
        val prevToken = currentToken
        logger.debug("Acquiring auth lock...")
        return withContext(coroutineContext) {
            if (currentToken !== prevToken) currentToken!!
            else try {
                val state = generateRandomString()
                val callback = KtorCallback(port.get()!!)
                val callbackJob = async(Dispatchers.IO) { callback.start(state) }
                val url = getSpotifyUrl(state, callback.callbackUrl)
                browserOpener.openDocument(url)
                callbackJob.await()
            } catch (e: TimeoutTokenException) {
                logger.error { "No token received within one minute" }
                throw TokenRefreshException(e)
            } catch (e: InvalidTokenException) {
                logger.error(e) { "Invalid token response received" }
                throw TokenRefreshException(e)
            }
        }
    }

    override suspend fun initialize(initStateWriter: InitStateWriter) {
        initStateWriter.state("Retrieving token...")
        withContext(coroutineContext) {
            getToken().also { initStateWriter.state("Retrieved token.") }
        }
    }

    @UseExperimental(ExperimentalConfigDsl::class)
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
        tokenExpiration = secrets.SerializedEntry(
            "tokenExpiration",
            "Token expiration date",
            InstantSerializer,
            NonnullConfigChecker,
            ActionButton("Refresh", ::toTimeString) {
                withContext(coroutineContext) {
                    try {
                        val token = authorize()
                        currentToken = token
                        true
                    } catch (e: IOException) {
                        logger.error(e) {}
                        false
                    } catch (e: SpotifyWebApiException) {
                        logger.error(e) {}
                        false
                    }
                }
            })

        accessToken = secrets.StringEntry(
            "accessToken",
            "OAuth access token",
            { null },
            null
        )

        clientId = secrets.StringEntry(
            key = "clientId",
            description = "OAuth client ID. Only required if there is a custom port.",
            configChecker = { null },
            uiNode = PasswordBox,
            default = CLIENT_ID
        )

        return listOf(tokenExpiration, clientId)
    }

    override fun createStateEntries(state: Config) = Unit

    override suspend fun close() {
        run { cancel() }
    }

    private companion object {
        private const val SPOTIFY_URL = " https://accounts.spotify.com/authorize"
        private const val CLIENT_ID = "902fe6b9a4b6421caf88ee01e809939a"
        private val SCOPES = listOf(
            "user-read-private",
            "user-library-read",
            "user-modify-playback-state",
            "user-read-playback-state",
            "playlist-read-private",
            "playlist-read-collaborative"
        )

        const val DEFAULT_PORT = 58642
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        private fun toTimeString(instant: Instant) = DateTimeFormatter
            .ofLocalizedTime(FormatStyle.SHORT)
            .format(ZonedDateTime.ofInstant(instant, ZoneId.systemDefault()))
    }
}

private val random = SecureRandom()
private fun generateRandomString(): String {
    return random.nextInt(Int.MAX_VALUE).toString()
}

private object InstantSerializer : ConfigSerializer<Instant> {
    @Throws(SerializationException::class)
    override fun deserialize(string: String): Instant {
        return string.toLongOrNull()?.let(Instant::ofEpochSecond) ?: throw SerializationException()
    }

    override fun serialize(obj: Instant): String = obj.epochSecond.toString()
}
