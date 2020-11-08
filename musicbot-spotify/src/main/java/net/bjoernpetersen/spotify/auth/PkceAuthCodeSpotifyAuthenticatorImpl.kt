package net.bjoernpetersen.spotify.auth

import io.ktor.client.HttpClient
import io.ktor.client.features.ClientRequestException
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.request.forms.submitForm
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.toURI
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.IntSerializer
import net.bjoernpetersen.musicbot.api.config.NonnullConfigChecker
import net.bjoernpetersen.musicbot.api.config.NumberBox
import net.bjoernpetersen.musicbot.api.config.actionButton
import net.bjoernpetersen.musicbot.api.config.serialized
import net.bjoernpetersen.musicbot.api.config.string
import net.bjoernpetersen.musicbot.api.plugin.InitializationException
import net.bjoernpetersen.musicbot.api.plugin.PluginScope
import net.bjoernpetersen.musicbot.spi.plugin.management.ProgressFeedback
import net.bjoernpetersen.musicbot.spi.plugin.predefined.TokenRefreshException
import net.bjoernpetersen.musicbot.spi.plugin.predefined.spotify.SpotifyAuthenticator
import net.bjoernpetersen.musicbot.spi.plugin.predefined.spotify.SpotifyScope
import net.bjoernpetersen.musicbot.spi.util.BrowserOpener
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.inject.Inject

class PkceAuthCodeSpotifyAuthenticatorImpl :
    SpotifyAuthenticator,
    CoroutineScope by PluginScope() {
    override val name: String = "Desktop OAuth"
    override val description: String = "Performs the Authorization Code Flow with PKCE"

    @Inject
    private lateinit var browserOpener: BrowserOpener

    private lateinit var config: PkceConfig
    private lateinit var secret: PkceSecrets

    private val scopes: MutableSet<SpotifyScope> = HashSet(SpotifyScope.values().size * 2)

    private val authHandler: AuthHandler by lazy {
        AuthHandler(
            browserOpener,
            secret.refreshToken,
            scopes.toList(),
            config.port
        )
    }

    override fun requireScopes(vararg scopes: SpotifyScope) {
        this.scopes.addAll(scopes)
    }

    override suspend fun getToken(): String = authHandler.getToken()

    override fun invalidateToken() = authHandler.invalidateToken()

    override suspend fun initialize(progressFeedback: ProgressFeedback) {

        progressFeedback.state("Trying to retrieve a token...")
        withContext(Dispatchers.IO) {
            try {
                getToken()
            } catch (e: TokenRefreshException) {
                progressFeedback.warning("Could not retrieve a token!")
                throw InitializationException(e)
            }
        }
        progressFeedback.state("Successfully retrieved token!")
    }

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        this.config = PkceConfig(config)
        return this.config.all
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> {
        this.secret = PkceSecrets(secrets)
        return this.secret.all
    }

    override fun createStateEntries(state: Config) = Unit

    override suspend fun close() {
        run { cancel() }
    }
}

private class PkceConfig(config: Config) {
    val all: List<Config.Entry<*>>
        get() = listOf(port)

    val port by config.serialized<Int> {
        description = "OAuth callback port"
        serializer = IntSerializer
        check(NonnullConfigChecker)
        uiNode = NumberBox(MIN_PORT, MAX_PORT)
        default(DEFAULT_PORT)
    }

    private companion object {
        const val DEFAULT_PORT = 58642
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
    }
}

private class PkceSecrets(secrets: Config) {
    val all: List<Config.Entry<*>>
        get() = listOf(refreshToken)

    val refreshToken by secrets.string {
        description = "A Spotify refresh token"
        check { null }
        actionButton {
            label = "Clear"
            describe { "Hidden" }
            action {
                it.set(null)
                true
            }
        }
    }
}

private class ProofKey {
    val verifier: String = RandomString.generate(MAX_VERIFIER_LENGTH, AllowedChars)
    val challenge: String = createChallenge(verifier)

    private companion object {
        const val MAX_VERIFIER_LENGTH = 128
        val AllowedChars = CharacterSet.Alphanumeric // + CharacterSet.of("~_-.")

        fun createChallenge(verifier: String): String {
            val sha = MessageDigest.getInstance("SHA-256")
            val digest = sha.digest(verifier.toByteArray())
            val base64 = Base64.getUrlEncoder().withoutPadding()
            val encodedDigest = base64.encode(digest)
            return encodedDigest.toString(Charsets.US_ASCII)
        }
    }
}

private class AuthHandler(
    private val browserOpener: BrowserOpener,
    private val refreshTokenEntry: Config.StringEntry,
    private val scopes: List<SpotifyScope>,
    private val port: Config.SerializedEntry<Int>
) {
    private val logger = KotlinLogging.logger { }

    private var refreshLock = Mutex()
    private var accessToken: Token? = null

    /**
     * Gets a token, either from cache or requesting a new one.
     *
     * This method should be called every time a token is needed.
     * Callers should not store the returned value anywhere.
     *
     * @return a valid (not expired) token
     * @throws TokenRefreshException if no valid token was cached and no new one could be obtained
     */
    @Throws(TokenRefreshException::class)
    suspend fun getToken(): String {
        refreshLock.withLock {
            val accessToken = accessToken
            if (accessToken == null || accessToken.isExpired()) {
                logger.info { "Retrieving new access token" }
                return obtainAccessToken()
            }
            return accessToken.value
        }
    }

    suspend fun obtainAccessToken(): String {
        val refreshToken = refreshTokenEntry.get()
        val accessToken = if (refreshToken == null) {
            doInteractiveAuth()
        } else {
            refresh(refreshToken)
        }
        this.accessToken = accessToken
        return accessToken.value
    }

    suspend fun refresh(refreshToken: String): Token {
        val response = withContext(Dispatchers.IO) {
            HttpClient { install(JsonFeature) }.use { client ->
                client.submitForm<RefreshResponse>(
                    url = "https://accounts.spotify.com/api/token",
                    formParameters = Parameters.build {
                        append("client_id", CLIENT_ID)
                        append("grant_type", "refresh_token")
                        append("refresh_token", refreshToken)
                    }
                )
            }
        }
        return processRefreshResponse(response)
    }

    @Suppress("ThrowsCount")
    suspend fun doInteractiveAuth(): Token {
        return withContext(Dispatchers.IO) {
            val proofKey = ProofKey()
            val state = RandomString.generate()
            val callback = KtorCallback(port.get()!!)
            logger.info { "Starting callback server" }
            val callbackJob = async { callback.start(state) }
            val redirectUrl = callback.callbackUrl
            val url = buildSpotifyPkceUrl(
                redirectUrl,
                codeChallenge = proofKey.challenge,
                state = state,
                scopes = scopes
            )

            val params = try {
                logger.info { "Opening browser" }
                browserOpener.openDocument(url.toURI().toURL())
                callbackJob.await()
            } catch (e: TimeoutTokenException) {
                logger.error { "No token received within one minute" }
                throw TokenRefreshException(e)
            } catch (e: InvalidTokenException) {
                logger.error(e) { "Invalid token response received" }
                throw TokenRefreshException(e)
            }

            val error = params["error"]
            if (error != null) throw TokenRefreshException(error)
            val code = params["code"] ?: throw TokenRefreshException("No code sent")
            retrieveTokenPair(
                code = code,
                redirectUrl = redirectUrl,
                verifier = proofKey.verifier
            )
        }
    }

    private suspend fun retrieveTokenPair(
        code: String,
        redirectUrl: Url,
        verifier: String
    ): Token {
        val response: RefreshResponse = try {
            HttpClient { install(JsonFeature) }.use { client ->
                client.submitForm(
                    url = "https://accounts.spotify.com/api/token",
                    formParameters = Parameters.build {
                        append("client_id", CLIENT_ID)
                        append("grant_type", "authorization_code")
                        append("code", code)
                        append("redirect_uri", redirectUrl.toString())
                        append("code_verifier", verifier)
                    }
                )
            }
        } catch (e: ClientRequestException) {
            val body = e.response.content.readUTF8Line()
            logger.error(e) { body }
            throw TokenRefreshException(e)
        }

        return processRefreshResponse(response)
    }

    private fun processRefreshResponse(response: RefreshResponse): Token {
        val refreshToken = response.refresh_token
        refreshTokenEntry.set(refreshToken)

        val expiration = Instant.now().plusSeconds(response.expires_in.toLong())
        return Token(response.access_token, expiration)
    }

    /**
     * Invalidates the cached token (if any) without retrieving a new one.
     * This method may not perform any IO operations.
     */
    fun invalidateToken() {
        accessToken = null
    }
}

@Suppress("ConstructorParameterNaming")
private data class RefreshResponse(
    val access_token: String,
    val token_type: String,
    val scope: String,
    val expires_in: Int,
    val refresh_token: String
)
