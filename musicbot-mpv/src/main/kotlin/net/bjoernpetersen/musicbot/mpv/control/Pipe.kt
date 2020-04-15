package net.bjoernpetersen.musicbot.mpv.control

import java.io.EOFException
import java.io.IOException

interface Pipe : AutoCloseable {
    /**
     * Reads a line from the pipe and may block.
     * @return a line from the pipe. Returns `null` if there is nothing to read.
     * @throws IOException if any IO errors occur
     * @throws EOFException if the end of the file has been reached
     */
    @Throws(IOException::class, EOFException::class)
    fun readLine(): String?
    fun writeLine(line: String)
}
