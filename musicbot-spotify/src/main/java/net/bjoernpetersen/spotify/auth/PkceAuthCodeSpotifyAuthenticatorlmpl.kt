package net.bjoernpetersen.spotify.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.submitForm
import io.ktor.client.response.HttpResponse
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.toURI
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

    private lateinit var authHandler: AuthHandler

    override fun requireScopes(vararg scopes: SpotifyScope) {
        this.scopes.addAll(scopes)
    }

    override suspend fun getToken(): String = authHandler.getToken()

    override fun invalidateToken() = authHandler.invalidateToken()

    override suspend fun initialize(progressFeedback: ProgressFeedback) {
        authHandler = AuthHandler(
            browserOpener,
            secret.refreshToken,
            scopes.toList(),
            config.port.get()!!
        )
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
    val verifier: String = RandomString.generate(128, AllowedChars)
    val challenge: String = createChallenge(verifier)

    private companion object {
        val AllowedChars = CharacterSet.Alphanumeric + CharacterSet.of("~_-.")

        fun createChallenge(verifier: String): String {
            val sha = MessageDigest.getInstance("SHA-256")
            val digest = sha.digest(verifier.toByteArray())
            val base64 = Base64.getUrlEncoder()
            val encodedDigest = base64.encode(digest)
            return encodedDigest.toString(Charsets.US_ASCII)
        }
    }
}

private class AuthHandler(
    private val browserOpener: BrowserOpener,
    private val refreshTokenEntry: Config.StringEntry,
    private val scopes: List<SpotifyScope>,
    private val port: Int
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
            if (accessToken == null || accessToken.isExpired(60)) {
                return obtainAccessToken()
            }
            return accessToken.value
        }
    }

    suspend fun obtainAccessToken(): String {
        val refreshToken = refreshTokenEntry.get()
        return if (refreshToken == null) {
            doInteractiveAuth()
        } else {
            refresh(refreshToken)
        }
    }

    suspend fun refresh(refreshToken: String): String {
        TODO("Retrieve new tokens")
    }

    suspend fun doInteractiveAuth(): String {
        val proofKey = ProofKey()
        val state = RandomString.generate()
        val callback = KtorCallback(port)
        val callbackJob = withContext(Dispatchers.IO) {
            async(Dispatchers.IO) { callback.start(state) }
        }
        val url = buildSpotifyPkceUrl(
            callback.callbackUrl,
            codeChallenge = proofKey.challenge,
            state = state,
            scopes = scopes
        )

        val params = try {
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
        return retrieveTokenPair(
            code = code,
            redirectUrl = url,
            verifier = proofKey.verifier
        )
    }

    private suspend fun retrieveTokenPair(
        code: String,
        redirectUrl: Url,
        verifier: String
    ): String {
        val client = HttpClient(CIO) {
            //TODO:    install(JsonFeature)
        }
        val result: HttpResponse = client.use { client ->
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

        TODO("parse response, update refresh token, return access token")
    }

    /**
     * Invalidates the cached token (if any) without retrieving a new one.
     * This method may not perform any IO operations.
     */
    fun invalidateToken() {
        accessToken = null
    }
}
