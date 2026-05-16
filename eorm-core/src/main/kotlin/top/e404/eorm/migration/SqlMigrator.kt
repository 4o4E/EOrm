package top.e404.eorm.migration

import top.e404.eorm.EOrm
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.stream.Collectors
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

data class MigrationScript(
    val version: String,
    val description: String,
    val name: String,
    val sql: String
)

data class MigrationResult(
    val applied: List<MigrationScript>,
    val skipped: List<MigrationScript>
)

/**
 * SQL migration runner. Executes versioned scripts such as `V1__create_user.sql`.
 */
class SqlMigrator(
    private val eOrm: EOrm,
    private val classLoader: ClassLoader = Thread.currentThread().contextClassLoader ?: SqlMigrator::class.java.classLoader
) {
    private val locations = ArrayList<String>()
    private val scripts = ArrayList<MigrationScript>()

    fun locations(vararg locations: String): SqlMigrator {
        this.locations.addAll(locations)
        return this
    }

    fun scripts(vararg scripts: MigrationScript): SqlMigrator {
        this.scripts.addAll(scripts)
        return this
    }

    fun migrate(): MigrationResult {
        ensureHistoryTable()
        val allScripts = (scripts + loadScripts()).sortedWith(compareBy<MigrationScript> { VersionKey(it.version) }.thenBy { it.name })
        val applied = ArrayList<MigrationScript>()
        val skipped = ArrayList<MigrationScript>()

        for (script in allScripts) {
            val checksum = checksum(script.sql)
            val existing = findApplied(script.version)
            if (existing != null) {
                if (existing != checksum) {
                    throw IllegalStateException("Migration ${script.version} checksum mismatch")
                }
                skipped.add(script)
                continue
            }

            apply(script, checksum)
            applied.add(script)
        }

        return MigrationResult(applied, skipped)
    }

    private fun ensureHistoryTable() {
        eOrm.executor.execute(
            """
            CREATE TABLE IF NOT EXISTS eorm_schema_history (
                version VARCHAR(100) PRIMARY KEY,
                description VARCHAR(255) NOT NULL,
                script VARCHAR(255) NOT NULL,
                checksum VARCHAR(64) NOT NULL,
                installed_at TIMESTAMP NOT NULL,
                execution_ms BIGINT NOT NULL,
                success BOOLEAN NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun findApplied(version: String): String? {
        val rows = eOrm.executor.queryMap(
            "SELECT checksum FROM eorm_schema_history WHERE version = ? AND success = TRUE",
            listOf(version)
        )
        return rows.firstOrNull()?.values?.firstOrNull()?.toString()
    }

    private fun apply(script: MigrationScript, checksum: String) {
        val started = System.currentTimeMillis()
        eOrm.transaction {
            for (statement in SqlScriptParser.splitStatements(script.sql)) {
                executor.execute(statement)
            }
            val executionMs = System.currentTimeMillis() - started
            executor.executeUpdate(
                "INSERT INTO eorm_schema_history (version, description, script, checksum, installed_at, execution_ms, success) VALUES (?, ?, ?, ?, ?, ?, ?)",
                listOf(script.version, script.description, script.name, checksum, LocalDateTime.now(), executionMs, true)
            )
        }
    }

    private fun loadScripts(): List<MigrationScript> {
        val result = ArrayList<MigrationScript>()
        for (location in locations) {
            result.addAll(loadLocation(location))
        }
        return result
    }

    private fun loadLocation(location: String): List<MigrationScript> {
        return if (location.startsWith("classpath:")) {
            val resource = location.removePrefix("classpath:").trimStart('/')
            val url = classLoader.getResource(resource) ?: throw IllegalArgumentException("Migration location not found: $location")
            if (url.protocol != "file") {
                throw IllegalArgumentException("Only file based classpath migration directories are supported: $location")
            }
            loadDirectory(Paths.get(URLDecoder.decode(url.path, StandardCharsets.UTF_8.name())))
        } else {
            loadDirectory(Paths.get(location))
        }
    }

    private fun loadDirectory(path: Path): List<MigrationScript> {
        require(path.isDirectory()) { "Migration location is not a directory: $path" }
        return Files.list(path).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.name.endsWith(".sql", ignoreCase = true) }
                .map { parseFile(it) }
                .collect(Collectors.toList())
        }
    }

    private fun parseFile(path: Path): MigrationScript {
        val match = FILE_PATTERN.matchEntire(path.name)
            ?: throw IllegalArgumentException("Invalid migration filename: ${path.name}. Expected V<version>__<description>.sql")
        val version = match.groupValues[1]
        val description = match.groupValues[2].removeSuffix(".sql").replace('_', ' ')
        return MigrationScript(version, description, path.name, path.readText())
    }

    private fun checksum(sql: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(sql.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private data class VersionKey(private val version: String) : Comparable<VersionKey> {
        private val parts = Regex("\\d+|\\D+").findAll(version).map { it.value }.toList()

        override fun compareTo(other: VersionKey): Int {
            val max = maxOf(parts.size, other.parts.size)
            for (i in 0 until max) {
                val left = parts.getOrNull(i) ?: return -1
                val right = other.parts.getOrNull(i) ?: return 1
                val compared = comparePart(left, right)
                if (compared != 0) return compared
            }
            return 0
        }

        private fun comparePart(left: String, right: String): Int {
            val leftNumber = left.toLongOrNull()
            val rightNumber = right.toLongOrNull()
            return if (leftNumber != null && rightNumber != null) {
                leftNumber.compareTo(rightNumber)
            } else {
                left.compareTo(right)
            }
        }
    }

    companion object {
        private val FILE_PATTERN = Regex("""V(.+)__(.+)\.sql""", RegexOption.IGNORE_CASE)
    }
}
