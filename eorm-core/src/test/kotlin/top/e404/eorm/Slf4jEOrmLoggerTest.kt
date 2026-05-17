package top.e404.eorm

import org.junit.jupiter.api.Test
import org.slf4j.Logger
import top.e404.eorm.log.Slf4jEOrmLogger
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.test.assertSame
import kotlin.test.assertTrue

class Slf4jEOrmLoggerTest {

    @Test
    fun `slf4j adapter delegates sql and levels`() {
        val recorder = RecordingSlf4jLogger()
        val logger = recorder.proxy()
        val adapter = Slf4jEOrmLogger(logger)
        val exception = RuntimeException("boom")

        adapter.logSql("SELECT * FROM user WHERE id = ?", listOf(1))
        adapter.debug("debug message")
        adapter.info("info message")
        adapter.warn("warn message")
        adapter.warn("warn with exception", exception)
        adapter.error("error message", exception)

        assertTrue(recorder.calls.any { it.name == "debug" && it.args.firstOrNull() == "SQL: {} | args: {}" })
        assertTrue(recorder.calls.any { it.name == "debug" && it.args.firstOrNull() == "debug message" })
        assertTrue(recorder.calls.any { it.name == "info" && it.args.firstOrNull() == "info message" })
        assertTrue(recorder.calls.any { it.name == "warn" && it.args.firstOrNull() == "warn message" })
        assertTrue(recorder.calls.any { it.name == "warn" && it.args.firstOrNull() == "warn with exception" })
        val errorCall = recorder.calls.first { it.name == "error" && it.args.firstOrNull() == "error message" }
        assertSame(exception, errorCall.args[1])
    }
}

private data class Slf4jCall(
    val name: String,
    val args: List<Any?>
)

private class RecordingSlf4jLogger : InvocationHandler {
    val calls = ArrayList<Slf4jCall>()

    fun proxy(): Logger {
        return Proxy.newProxyInstance(
            Logger::class.java.classLoader,
            arrayOf(Logger::class.java),
            this
        ) as Logger
    }

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        val arguments = args?.toList() ?: emptyList()
        return when (method.name) {
            "getName" -> "test"
            "toString" -> "RecordingSlf4jLogger"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments.firstOrNull()
            else -> {
                calls.add(Slf4jCall(method.name, arguments))
                defaultValue(method.returnType)
            }
        }
    }

    private fun defaultValue(type: Class<*>): Any? {
        return when (type) {
            java.lang.Boolean.TYPE -> true
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> 0.toChar()
            else -> null
        }
    }
}
