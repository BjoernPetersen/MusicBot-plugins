package net.bjoernpetersen.spotify.auth

import io.ktor.client.HttpClient
import io.ktor.client.call.call
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.parameter
import io.ktor.client.response.HttpResponse
import io.ktor.util.KtorExperimentalAPI
import io.ktor.util.url
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.time.Duration
import java.util.LinkedList
import java.util.function.Supplier

@KtorExperimentalAPI
@ExtendWith(PortExtension::class)
@Execution(ExecutionMode.CONCURRENT)
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
class KtorCallbackTest {

    private suspend fun send(
        port: Int,
        state: String? = null,
        token: String? = null,
        expirationTime: Int? = null
    ): HttpResponse {
        val queryParams: MutableList<Pair<String, String>> = LinkedList()
        if (state != null) {
            queryParams.add(KtorCallback.STATE_KEY to state)
        }
        if (token != null) {
            queryParams.add(KtorCallback.ACCESS_TOKEN_KEY to token)
        }
        if (expirationTime != null) {
            queryParams.add(KtorCallback.EXPIRATION_KEY to expirationTime.toString())
        }

        return HttpClient(CIO).use { client ->
            client.call(urlString = url {
                this.port = port
                path(KtorCallback.REDIRECTED_PATH)
            }) {
                queryParams.forEach { (key, value) ->
                    parameter(key, value)
                }
            }.response
        }
    }

    private fun test(
        port: Int,
        shouldSucceed: Boolean,
        code: Int,
        state: String? = null,
        token: String? = null,
        expirationTime: Int? = null
    ) {
        runBlocking {
            val tokenResult = async { KtorCallback(port).start(CORRECT_STATE) }

            val response = send(port, state, token, expirationTime)
            assertEquals(code, response.status.value)

            tokenResult.await().also {
                if (shouldSucceed)
                    assertEquals(VALID_TOKEN, it.value) {
                        "Token was not extracted correctly"
                    }
            }
        }
    }

    @Test
    fun success(port: Int) {
        assertDoesNotThrow {
            test(port, true, 200, CORRECT_STATE, VALID_TOKEN, 1800)
        }
    }

    @TestFactory
    fun invalidState(portSupplier: Supplier<Int>): List<DynamicTest> {
        return listOf(null, VALID_TOKEN)
            .flatMap { token -> listOf(null, 1800).map { token to it } }
            .flatMap { (token, expirationTime) ->
                listOf(null, WRONG_STATE).map { Triple(token, expirationTime, it) }
            }
            .map { (token, expirationTime, state) ->
                dynamicTest("token: $token, expirationTime: $expirationTime, state: $state") {
                    assertTimeoutPreemptively(Duration.ofSeconds(80)) {
                        assertThrows<TimeoutTokenException> {
                            test(
                                portSupplier.get(),
                                false, 401, state, token, expirationTime
                            )
                        }
                    }
                }
            }
    }

    @Test
    fun noToken(port: Int) {
        assertThrows<InvalidTokenException> {
            test(port, true, 400, CORRECT_STATE)
        }
    }

    private companion object {
        const val CORRECT_STATE = "fsgd8769345khj"
        const val WRONG_STATE = "dsfkhjl34587"

        const val VALID_TOKEN = "kdjfhg324ljk"
    }
}
