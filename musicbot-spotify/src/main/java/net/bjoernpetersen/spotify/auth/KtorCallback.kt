package net.bjoernpetersen.spotify.auth

import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.BadRequestException
import io.ktor.features.MissingRequestParameterException
import io.ktor.features.StatusPages
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.KtorExperimentalAPI
import io.ktor.util.toMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.concurrent.TimeUnit

@OptIn(KtorExperimentalAPI::class)
internal class KtorCallback(private val port: Int) {

    val callbackUrl = URLBuilder().apply {
        protocol = URLProtocol.HTTP
        host = LOCALHOST
        port = this@KtorCallback.port
        path(CALLBACK_PATH)
    }.build()

    suspend fun start(state: String): Map<String, String> {
        val result = CompletableDeferred<Map<String, String>>()
        val server = embeddedServer(Netty, port = port, host = LOCALHOST) {
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
                    val params = call.request.queryParameters

                    if (params[STATE_KEY] != state)
                        throw AuthenticationException("Invalid state")

                    val paramMap = params.toMap()
                        .filterKeys { it != STATE_KEY }
                        .mapValues { it.value.singleOrNull() ?: throw InvalidTokenException() }

                    call.respondText("Received OAuth token. You may close this window now.")

                    result.complete(paramMap)
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
                server.stop(SHUTDOWN_GRACE_MILLIS, SHUTDOWN_TIMEOUT_MILLIS)
            }
        }
    }

    companion object {
        const val STATE_KEY = "state"

        private const val CALLBACK_PATH = "redirect"
        const val LOCALHOST = "localhost"

        const val SHUTDOWN_GRACE_MILLIS: Long = 50
        const val SHUTDOWN_TIMEOUT_MILLIS: Long = 200
    }
}

@KtorExperimentalAPI
private class AuthenticationException(
    message: String,
    cause: Throwable? = null
) : BadRequestException(message, cause)

class InvalidTokenException : Exception()
class TimeoutTokenException : Exception()
