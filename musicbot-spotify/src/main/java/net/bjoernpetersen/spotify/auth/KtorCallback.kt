package net.bjoernpetersen.spotify.auth

import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.BadRequestException
import io.ktor.features.MissingRequestParameterException
import io.ktor.features.StatusPages
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.util.KtorExperimentalAPI
import io.ktor.util.getOrFail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

@KtorExperimentalAPI
internal class KtorCallback(private val port: Int) {

    val callbackUrl = URL("http", LOCALHOST, port, CALLBACK_PATH)

    suspend fun start(state: String): Token {
        val result = CompletableDeferred<Token>()
        val server = embeddedServer(CIO, port = port, host = LOCALHOST) {
            routing {
                install(StatusPages) {
                    exception<AuthenticationException> {
                        call.respond(HttpStatusCode.Unauthorized)
                    }
                    exception<MissingRequestParameterException> {
                        call.respond(HttpStatusCode.BadRequest)

                        result.completeExceptionally(InvalidTokenException())
                    }
                }

                get(CALLBACK_PATH) {
                    call.respondText(
                        redirectPageContent,
                        ContentType.Text.Html
                    )
                }
                get(REDIRECTED_PATH) {
                    val params = call.request.queryParameters

                    if (params[STATE_KEY] != state)
                        throw AuthenticationException("Invalid state")

                    val token = params.getOrFail(ACCESS_TOKEN_KEY)
                    val expirationTime = params.getOrFail(EXPIRATION_KEY)
                        .let { Integer.parseUnsignedInt(it).toLong() }
                        .let { Instant.now().plusSeconds(it) }

                    call.respondText("Received OAuth token. You may close this window now.")

                    result.complete(Token(token, expirationTime))
                }
            }
        }

        return coroutineScope {
            val serverJob = launch { server.start(wait = true) }

            val cancelJob = launch {
                delay(Duration.ofMinutes(1).toMillis())
                if (serverJob.isActive) {
                    result.completeExceptionally(TimeoutTokenException())
                }
            }

            try {
                result.await()
            } finally {
                cancelJob.cancel()
                server.stop(50, 200, TimeUnit.MILLISECONDS)
            }
        }
    }

    companion object {
        const val STATE_KEY = "state"
        const val ACCESS_TOKEN_KEY = "access_token"
        const val EXPIRATION_KEY = "expires_in"

        private const val CALLBACK_PATH = "/redirect"
        const val REDIRECTED_PATH = "/callback"
        const val LOCALHOST = "localhost"

        private const val REDIRECT_PAGE_FILE = "RedirectPage.html"
        private val redirectPageContent = loadHtml(REDIRECT_PAGE_FILE)

        private fun loadHtml(fileName: String): String {
            return this::class.java
                .getResourceAsStream(fileName)
                .bufferedReader()
                .readText()
        }
    }
}

@KtorExperimentalAPI
private class AuthenticationException(
    message: String,
    cause: Throwable? = null
) : BadRequestException(message, cause)

class InvalidTokenException : Exception()
class TimeoutTokenException : Exception()
