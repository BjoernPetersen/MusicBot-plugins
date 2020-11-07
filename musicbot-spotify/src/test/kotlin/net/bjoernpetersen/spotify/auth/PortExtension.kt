package net.bjoernpetersen.spotify.auth

import com.google.common.collect.Multimap
import com.google.common.collect.MultimapBuilder
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.Extension
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import java.net.ServerSocket
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Supplier
import kotlin.concurrent.withLock

class PortExtension : Extension, ParameterResolver, AfterEachCallback {
    private val lock: Lock = ReentrantLock()
    private val portById: Multimap<String, Int> = MultimapBuilder.hashKeys().hashSetValues().build()
    private val used = HashSet<Int>()

    override fun afterEach(context: ExtensionContext) {
        lock.withLock {
            used.removeAll(portById.removeAll(context.uniqueId))
        }
    }

    override fun supportsParameter(
        parameterContext: ParameterContext,
        extensionContext: ExtensionContext
    ): Boolean {
        return Int::class.java.isAssignableFrom(parameterContext.parameter.type) ||
            Supplier::class.java.isAssignableFrom(parameterContext.parameter.type)
    }

    override fun resolveParameter(
        parameterContext: ParameterContext,
        extensionContext: ExtensionContext
    ): Any {
        if (Int::class.java.isAssignableFrom(parameterContext.parameter.type))
            lock.withLock { return findPort(extensionContext.uniqueId) }
        else {
            return Supplier {
                lock.withLock { findPort(extensionContext.uniqueId) }
            }
        }
    }

    private fun findPort(id: String): Int {
        var port: Int
        do {
            port = ServerSocket(0).use { it.localPort }
        } while (!used.add(port))
        portById.put(id, port)
        return port
    }
}
