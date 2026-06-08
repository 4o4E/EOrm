package top.e404.eorm.migration

import top.e404.eorm.EOrm
import java.io.File
import java.net.JarURLConnection
import java.net.URL
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Collections
import java.util.stream.Collectors
import java.util.jar.JarEntry
import java.util.jar.JarFile
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
        eOrm.logger.info("Migration start scripts=${allScripts.size}")
        val applied = ArrayList<MigrationScript>()
        val skipped = ArrayList<MigrationScript>()

        for (script in allScripts) {
            try {
                val checksum = checksum(script.sql)
                val existing = findApplied(script.version)
                if (existing != null) {
                    if (existing != checksum) {
                        val exception = IllegalStateException("Migration ${script.version} checksum mismatch")
                        eOrm.logger.error("Migration checksum mismatch version=${script.version} script=${script.name}", exception)
                        throw exception
                    }
                    eOrm.logger.debug("Migration skip version=${script.version} script=${script.name}")
                    skipped.add(script)
                    continue
                }

                apply(script, checksum)
                applied.add(script)
            } catch (e: Exception) {
                eOrm.logger.error("Migration failed version=${script.version} script=${script.name}", e)
                throw e
            }
        }

        eOrm.logger.info("Migration complete applied=${applied.size}, skipped=${skipped.size}")
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
        eOrm.logger.info("Migration apply version=${script.version} script=${script.name}")
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
        eOrm.logger.info("Migration applied version=${script.version} script=${script.name} executionMs=${System.currentTimeMillis() - started}")
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
            loadClasspathLocation(location)
        } else {
            loadDirectory(Paths.get(location))
        }
    }

    private fun loadClasspathLocation(location: String): List<MigrationScript> {
        val resource = location.removePrefix("classpath:").trimStart('/').trimEnd('/')
        val candidates = if (resource.isEmpty()) listOf(resource) else listOf(resource, "$resource/")
        val sources = LinkedHashMap<String, ClasspathScriptSource>()
        candidates
            .flatMap { Collections.list(classLoader.getResources(it)) }
            .map { classpathUrlSource(location, it) }
            .forEach { sources.putIfAbsent(it.key, it) }
        fallbackClasspathSources(resource)
            .forEach { sources.putIfAbsent(it.key, it) }
        require(sources.isNotEmpty()) { "Migration location not found: $location" }
        return sources.values.flatMap { it.load() }
    }

    private fun classpathUrlSource(location: String, url: URL): ClasspathScriptSource {
        return when (url.protocol) {
            "file" -> {
                val path = Paths.get(url.toURI()).toAbsolutePath().normalize()
                ClasspathScriptSource("file:${path.toRealPathIfExists()}") { loadDirectory(path) }
            }
            "jar" -> {
                val connection = url.openConnection()
                require(connection is JarURLConnection) { "Invalid jar classpath migration location: $location" }
                val entryName = connection.entryName?.trimEnd('/')
                    ?: throw IllegalArgumentException("Jar migration location is missing entry name: $location")
                ClasspathScriptSource(jarSourceKey(connection, entryName)) { loadJarDirectory(location, url) }
            }
            else -> throw IllegalArgumentException("Unsupported classpath migration location protocol ${url.protocol}: $location")
        }
    }

    private fun jarSourceKey(connection: JarURLConnection, entryName: String): String {
        val jarFileUrl = connection.jarFileURL
        val jarKey = if (jarFileUrl.protocol == "file") {
            Paths.get(jarFileUrl.toURI()).toAbsolutePath().normalize().toRealPathIfExists().toString()
        } else {
            jarFileUrl.toExternalForm()
        }
        return "jar:$jarKey!/$entryName"
    }

    private fun loadJarDirectory(location: String, url: URL): List<MigrationScript> {
        val connection = url.openConnection()
        require(connection is JarURLConnection) { "Invalid jar classpath migration location: $location" }
        connection.useCaches = false
        val entryName = connection.entryName?.trimEnd('/')
            ?: throw IllegalArgumentException("Jar migration location is missing entry name: $location")
        val prefix = "$entryName/"
        return connection.jarFile.use { jarFile ->
            Collections.list(jarFile.entries())
                .filter { isDirectSqlEntry(it, prefix) }
                .map { parseJarEntry(jarFile, it, prefix) }
        }
    }

    private fun fallbackClasspathSources(resource: String): List<ClasspathScriptSource> {
        return classpathRootUrls().mapNotNull { url ->
            if (url.protocol != "file") return@mapNotNull null
            val path = Paths.get(url.toURI()).toAbsolutePath().normalize()
            when {
                path.isDirectory() -> fallbackDirectorySource(path, resource)
                Files.isRegularFile(path) && path.name.endsWith(".jar", ignoreCase = true) -> fallbackJarSource(path, resource)
                else -> null
            }
        }
    }

    private fun classpathRootUrls(): List<URL> {
        val result = ArrayList<URL>()
        var loader: ClassLoader? = classLoader
        while (loader != null) {
            if (loader is URLClassLoader) result.addAll(loader.urLs)
            loader = loader.parent
        }
        System.getProperty("java.class.path")
            ?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?.map { Paths.get(it).toUri().toURL() }
            ?.let { result.addAll(it) }
        return result.distinctBy { it.toExternalForm() }
    }

    private fun fallbackDirectorySource(root: Path, resource: String): ClasspathScriptSource? {
        val path = if (resource.isEmpty()) root else root.resolve(resource)
        if (!path.isDirectory()) return null
        return ClasspathScriptSource("file:${path.toRealPathIfExists()}") { loadDirectory(path) }
    }

    private fun fallbackJarSource(jar: Path, resource: String): ClasspathScriptSource? {
        val prefix = if (resource.isEmpty()) "" else "$resource/"
        if (!jarContainsDirectSqlEntry(jar, prefix)) return null
        return ClasspathScriptSource("jar:${jar.toRealPathIfExists()}!/${resource.trimEnd('/')}") {
            loadJarFileDirectory(jar, prefix)
        }
    }

    private fun loadJarFileDirectory(jar: Path, prefix: String): List<MigrationScript> {
        return JarFile(jar.toFile()).use { jarFile ->
            Collections.list(jarFile.entries())
                .filter { isDirectSqlEntry(it, prefix) }
                .map { parseJarEntry(jarFile, it, prefix) }
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
        return parseScript(path.name, path.readText())
    }

    private fun parseJarEntry(jarFile: JarFile, entry: JarEntry, prefix: String): MigrationScript {
        val name = entry.name.removePrefix(prefix)
        val sql = jarFile.getInputStream(entry).use { input -> String(input.readBytes(), StandardCharsets.UTF_8) }
        return parseScript(name, sql)
    }

    private fun parseScript(name: String, sql: String): MigrationScript {
        val match = FILE_PATTERN.matchEntire(name)
            ?: throw IllegalArgumentException("Invalid migration filename: $name. Expected V<version>__<description>.sql")
        val version = match.groupValues[1]
        val description = match.groupValues[2].removeSuffix(".sql").replace('_', ' ')
        return MigrationScript(version, description, name, sql)
    }

    private fun isDirectSqlEntry(entry: JarEntry, prefix: String): Boolean {
        if (entry.isDirectory || !entry.name.startsWith(prefix)) return false
        val relative = entry.name.removePrefix(prefix)
        return relative.isNotEmpty() && !relative.contains("/") && relative.endsWith(".sql", ignoreCase = true)
    }

    private fun jarContainsDirectSqlEntry(jar: Path, prefix: String): Boolean {
        return JarFile(jar.toFile()).use { jarFile ->
            Collections.list(jarFile.entries()).any { isDirectSqlEntry(it, prefix) }
        }
    }

    private fun Path.toRealPathIfExists(): Path = if (Files.exists(this)) toRealPath() else this

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

    private data class ClasspathScriptSource(
        val key: String,
        val load: () -> List<MigrationScript>
    )
}
