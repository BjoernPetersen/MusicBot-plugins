package net.bjoernpetersen.musicbot.mpv.control

import java.io.EOFException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class NamedPipe(path: Path) : Pipe {
    private val lock = ReentrantLock()
    private val file = RandomAccessFile(path.toFile(), "rw")
    private val reader =
        RandomAccessReader(file)

    override fun readLine(): String? {
        lock.withLock {
            return reader.readLine()
        }
    }

    override fun writeLine(line: String) {
        val isTerminated = line.endsWith('\n')
        lock.withLock {
            file.write(line.toByteArray())
            if (!isTerminated) {
                file.write(LINE_END)
            }
        }
    }

    override fun close() {
        lock.withLock {
            file.close()
        }
    }

    private companion object {
        val LINE_END: ByteArray by lazy { Charsets.UTF_8.encode("\n").array() }
    }
}

private class RandomAccessReader(
    private val file: RandomAccessFile
) {
    private val bytes = ByteBuffer.allocate(2048)
    private val chars = CharBuffer.allocate(8192)
    private val lineBuilder = StringBuilder()
    private val lineQueue: Queue<String> = LinkedList()
    private val decoder = Charsets.UTF_8.newDecoder().apply {
        this.onUnmappableCharacter(CodingErrorAction.REPORT)
    }

    @Suppress("ControlFlowWithEmptyBody")
    fun readLine(): String? {
        if (!lineQueue.isEmpty()) {
            return lineQueue.poll()
        }
        val read = readBytes()
        if (read == 0) return null
        while (decodeBytes(read == -1)) {
        }
        val line = lineQueue.poll()
        if (line == null && read == -1) {
            throw EOFException()
        } else {
            return line
        }
    }

    private fun readBytes(): Int {
        return if (file.hasByte()) {
            val position = bytes.position()
            val length = bytes.capacity() - position
            file.read(bytes.array(), position, length).also {
                bytes.limit(it)
            }
        } else 0
    }

    private fun decodeBytes(isEof: Boolean): Boolean {
        val result = decoder.decode(bytes, chars, isEof)
        if (result.isError) {
            result.throwException()
            // Previous statement should not return
            throw IllegalStateException()
        } else {
            transferChars()
            if (result.isUnderflow) {
                bytes.compact()
            }
            return result.isOverflow
        }
    }

    private fun transferChars() {
        chars.flip()
        chars.forEach { char ->
            lineBuilder.append(char)
            if (char == '\n') {
                val line = lineBuilder.toString()
                lineQueue.offer(line)
                lineBuilder.clear()
            }
        }
        chars.clear()
    }
}

private fun RandomAccessFile.hasByte(): Boolean {
    val position = filePointer
    val length = length()
    return position < length
}
