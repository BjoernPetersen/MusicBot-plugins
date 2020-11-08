package net.bjoernpetersen.musicbot.mpv.control

import org.newsclub.net.unix.AFUNIXSocket
import org.newsclub.net.unix.AFUNIXSocketAddress
import java.io.EOFException
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class UnixPipe(
    path: Path
) : Pipe {
    private val lock = ReentrantLock()
    private val socketAddress = AFUNIXSocketAddress(path.toFile())
    private val socket = AFUNIXSocket.newInstance().also {
        it.connect(socketAddress)
        it.keepAlive = true
    }
    private val inputReader = socket.inputStream.bufferedReader()
    private val outputWriter = socket.outputStream.bufferedWriter()

    override fun readLine(): String? {
        if (!inputReader.ready()) {
            return null
        }
        return inputReader.readLine() ?: throw EOFException()
    }

    override fun writeLine(line: String) {
        val terminated = if (line.endsWith('\n')) line else "$line\n"
        lock.withLock {
            outputWriter.write(terminated)
            outputWriter.flush()
        }
    }

    override fun close() {
        socket.close()
    }
}
