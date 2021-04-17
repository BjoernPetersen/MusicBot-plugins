package net.bjoernpetersen.musicbot.mpv.control

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

typealias EventListener = (Map<String, Any>) -> Unit
typealias PropertyListener = (Any) -> Unit

@Suppress("BlockingMethodInNonBlockingContext", "unused")
class MpvApi(
    private val pipe: Pipe
) : CoroutineScope by CoroutineScope(Dispatchers.IO), AutoCloseable {
    private val logger = KotlinLogging.logger { }

    private val moshi = Moshi.Builder().build()
    private val requests = HashMap<Int, CompletableDeferred<Any?>>()
    private val eventListeners = HashMap<String, EventListener>()
    private val propertyListeners = HashMap<String, PropertyListener>()

    init {
        launch { listen() }
    }

    @Suppress("LoopWithTooManyJumpStatements", "ComplexMethod", "NestedBlockDepth")
    private suspend fun listen() {
        val commandResponseAdapter =
            CommandResponseJsonAdapter(
                moshi
            )
        val eventAdapter = moshi.adapter<Map<String, Any>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        )
        while (isActive) {
            try {
                val line = pipe.readLine()
                if (line == null) {
                    delay(WAIT_ON_NULL_LINE_MILLIS)
                    continue
                }
                @Suppress("SwallowedException")
                try {
                    val response = commandResponseAdapter.fromJson(line) ?: continue
                    val requestId = response.requestId
                    val completable = requests.remove(requestId) ?: continue
                    val isSuccessful = response.error == "success"
                    if (isSuccessful) {
                        completable.complete(response.data)
                    } else {
                        completable.completeExceptionally(
                            MpvApiException(
                                response.error
                            )
                        )
                    }
                } catch (e: JsonDataException) {
                    val event = eventAdapter.fromJson(line) ?: continue
                    val name = event["event"] as String
                    if (name == "property-change") {
                        val propertyName = event["name"] as String
                        val listener = propertyListeners[propertyName]
                        if (listener != null) {
                            val data = event["data"]
                            if (data == null) {
                                // TODO log null data
                            } else {
                                listener(data)
                            }
                        }
                    } else {
                        val listener = eventListeners[name]
                        if (listener != null) listener(event)
                    }
                }
            } catch (e: EOFException) {
                logger.debug(e) { "Reached end of file" }
            } catch (e: IOException) {
                logger.warn(e) {}
            }
        }
    }

    suspend fun addPropertyListener(property: MpvProperty, listener: PropertyListener) {
        addPropertyListener(property.externalName, listener)
    }

    suspend fun addPropertyListener(propertyName: String, listener: PropertyListener) {
        val existing = propertyListeners.putIfAbsent(propertyName, listener)
        if (existing != null) throw IllegalStateException("Only one listener per property allowed")
        withContext(coroutineContext) {
            runCommand(
                listOf(
                    "observe_property",
                    createRequestId(), propertyName
                )
            )
        }
    }

    fun addEventListener(event: MpvEvent, listener: EventListener) {
        addEventListener(event.externalName, listener)
    }

    fun addEventListener(eventName: String, listener: EventListener) {
        val existing = eventListeners.putIfAbsent(eventName, listener)
        if (existing != null) throw IllegalStateException("Only one listener per event allowed")
    }

    private suspend fun runCommand(args: List<Any>): Any? {
        val requestId =
            createRequestId()
        val deferred = CompletableDeferred<Any?>()
        requests[requestId] = deferred

        val command = Command(
            args,
            requestId
        )
        withContext(coroutineContext) {
            pipe.writeLine(
                CommandJsonAdapter(
                    moshi
                ).indent("").toJson(command)
            )
        }
        return deferred.await()
    }

    suspend fun setProperty(property: MpvProperty, value: Any) {
        setProperty(property.externalName, value)
    }

    suspend fun setProperty(propertyName: String, value: Any) {
        runCommand(listOf("set_property", propertyName, value))
    }

    suspend fun exit() {
        runCommand(listOf("quit"))
    }

    override fun close() {
        cancel()
    }

    private companion object {
        const val WAIT_ON_NULL_LINE_MILLIS = 500L
        private val requestId = AtomicInteger()
        fun createRequestId(): Int {
            return requestId.getAndIncrement()
        }
    }
}

class MpvApiException(message: String) : Exception(message)
