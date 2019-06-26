package net.bjoernpetersen.musicbot.sysvolume

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import kotlin.concurrent.thread

internal class GetVolume(
    command: List<String>,
    private val pattern: String,
    private val valueMode: ValueMode
) {

    private val logger = KotlinLogging.logger { }

    private val process = ProcessBuilder(command).start()

    private val result = CompletableDeferred<Int>()

    init {
        thread(name = "GetVolume-error", isDaemon = true) {
            process.errorStream.bufferedReader().forEachLine {}
        }
        runBlocking {
            val started = CompletableDeferred<Unit>()
            thread(name = "GetVolume-out") {
                started.complete(Unit)
                val output = process.inputStream.bufferedReader().readText()
                try {
                    val parsed = parse(output)
                    result.complete(parsed)
                } catch (e: IllegalStateException) {
                    result.completeExceptionally(e)
                }
            }
            started.await()
        }
    }

    private fun parse(output: String): Int {
        val value = Regex(pattern)
            .find(output)
            ?.groupValues
            ?.getOrNull(1)
            ?: throw IllegalStateException("No pattern match in output: $output")

        logger.debug { "GetVolume match: $value" }

        return valueMode.toPercent(value)
    }

    suspend fun get(): Int {
        return result.await()
    }
}

private fun buildProcess(command: List<String>, valueMode: ValueMode, volume: Int): Process {
    val parsedCommand = command
        .map { it.replace("<volume>", valueMode.fromPercent(volume)) }
    return ProcessBuilder(parsedCommand).start()
}

internal class SetVolume(command: List<String>, valueMode: ValueMode, volume: Int) {

    private val process = buildProcess(command, valueMode, volume)
    private val exitCode = CompletableDeferred<Int>()

    init {
        thread(name = "SetVolume-error", isDaemon = true) {
            process.errorStream.bufferedReader().forEachLine {}
        }
        runBlocking {
            val started = CompletableDeferred<Unit>()
            thread(name = "SetVolume-out") {
                started.complete(Unit)
                process.inputStream.bufferedReader().forEachLine {}
                exitCode.complete(process.exitValue())
            }
            started.await()
        }
    }

    suspend fun await(): Boolean {
        return exitCode.await() == 0
    }
}
