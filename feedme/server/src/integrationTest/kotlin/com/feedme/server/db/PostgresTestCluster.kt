package com.feedme.server.db

import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.postgresql.ds.PGSimpleDataSource

/**
 * An actual, disposable PostgreSQL server. Every path and database managed here belongs to this
 * instance; no connection settings from the application or an existing PostgreSQL service are used.
 *
 * Call [database] for each test, and close the cluster after the suite. Data sources and connections
 * must not be used after close. Synthetic cluster files are retained in the private temporary
 * directory for diagnosis, but the plaintext initialization password is always removed.
 */
class PostgresTestCluster private constructor(
    private val binDirectory: Path,
    private val directory: Path,
    private val port: Int,
    private var password: String?,
) : AutoCloseable {
    private val dataDirectory = directory.resolve("data")
    private val socketDirectory = directory.resolve("socket")
    private val passwordFile = directory.resolve("initdb.pw")
    private var closed = false
    private var shutdownHookRegistered = false
    private val shutdownHook = Thread({ runCatching { close() } }, "feedme-postgres-test-shutdown")

    /** Creates a uniquely named, empty UTF-8 database on this instance's server. */
    @Synchronized
    fun database(): DataSource {
        check(!closed) { "The isolated PostgreSQL test cluster has already been closed." }
        val name = "feedme_test_${UUID.randomUUID().toString().replace("-", "")}"
        try {
            dataSource("postgres").connection.use { connection ->
                connection.createStatement().use { statement ->
                    // The identifier contains only a fixed prefix and a generated hexadecimal UUID.
                    statement.executeUpdate("CREATE DATABASE \"$name\" TEMPLATE template0 ENCODING 'UTF8'")
                }
            }
        } catch (_: Exception) {
            // Never propagate a JDBC exception that could expose connection configuration.
            throw IllegalStateException("Could not create a database on the isolated PostgreSQL test cluster.")
        }
        return dataSource(name)
    }

    @Synchronized
    private fun initialize() {
        Files.createDirectory(socketDirectory, OWNER_DIRECTORY)
        Files.createFile(passwordFile, OWNER_FILE)
        try {
            Files.writeString(passwordFile, password!! + "\n", StandardCharsets.UTF_8, StandardOpenOption.WRITE)
            runCommand(
                "initialization", "initdb", listOf(
                    "-D", dataDirectory.toString(),
                    "--username=$OWNER",
                    "--pwfile=$passwordFile",
                    "--auth-local=trust",
                    "--auth-host=scram-sha-256",
                    "--encoding=UTF8",
                    "--locale=C",
                ),
            )
        } finally {
            Files.deleteIfExists(passwordFile)
        }

        Files.writeString(
            dataDirectory.resolve("postgresql.conf"),
            """

            # This configuration belongs only to this disposable integration-test cluster.
            listen_addresses = '127.0.0.1'
            port = $port
            unix_socket_directories = '${socketDirectory.toString().replace("\\", "\\\\").replace("'", "''")}'
            unix_socket_permissions = 0700
            password_encryption = 'scram-sha-256'
            fsync = on
            synchronous_commit = on
            full_page_writes = on
            max_connections = 30
            shared_buffers = '16MB'
            logging_collector = off
            log_statement = 'none'
            log_min_error_statement = 'panic'

            """.trimIndent(),
            StandardCharsets.UTF_8,
            StandardOpenOption.APPEND,
        )
        // pg_ctl needs a log file so that its detached server does not inherit a tool/test pipe.
        runCommand(
            "startup", "pg_ctl", listOf(
                "-D", dataDirectory.toString(), "-l", directory.resolve("server.log").toString(),
                "-w", "-t", "30", "start",
            ),
        )
        try {
            dataSource("postgres").connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT 1").use { result ->
                        check(result.next() && result.getInt(1) == 1)
                    }
                }
            }
        } catch (failure: Exception) {
            val code = (failure as? java.sql.SQLException)?.sqlState?.takeIf { it.matches(Regex("[A-Z0-9]{5}")) } ?: "unavailable"
            throw IllegalStateException("The isolated PostgreSQL test cluster did not pass its connection check (${failure.javaClass.simpleName}, SQLSTATE=$code).")
        }
    }

    private fun dataSource(name: String): PGSimpleDataSource = PGSimpleDataSource().apply {
        setServerNames(arrayOf("127.0.0.1"))
        setPortNumbers(intArrayOf(port))
        setDatabaseName(name)
        setUser(OWNER)
        setPassword(checkNotNull(this@PostgresTestCluster.password))
        setConnectTimeout(5)
        setSocketTimeout(15)
        setLoginTimeout(5)
        setSslMode("disable")
        setGssEncMode("disable")
        setApplicationName("feedme-integration-tests")
    }

    /** Stops only the server whose data directory this instance created. Safe to call repeatedly. */
    @Synchronized
    override fun close() {
        if (closed) return
        // A cancelled test must still be able to wait for its own server to shut down.
        var interrupted = Thread.interrupted()
        try {
            val passwordRemoval = runCatching { Files.deleteIfExists(passwordFile) }
            val pidFile = dataDirectory.resolve("postmaster.pid")
            if (Files.exists(pidFile)) {
                // Fast shutdown rolls back open test transactions and terminates their connections.
                runCatching {
                    runCommand(
                        "shutdown", "pg_ctl",
                        listOf("-D", dataDirectory.toString(), "-m", "fast", "-w", "-t", "25", "stop"),
                    )
                }
                interrupted = Thread.interrupted() || interrupted
                if (Files.exists(pidFile)) {
                    runCatching {
                        runCommand(
                            "immediate shutdown", "pg_ctl",
                            listOf("-D", dataDirectory.toString(), "-m", "immediate", "-w", "-t", "10", "stop"),
                            timeoutSeconds = 20,
                        )
                    }
                }
                check(!Files.exists(pidFile)) {
                    "Could not stop the isolated PostgreSQL test cluster at $dataDirectory. " +
                        "Only this private test cluster may require manual cleanup."
                }
            }
            password = null
            check(passwordRemoval.isSuccess) {
                "The isolated PostgreSQL test server stopped, but its private initialization password file could not be removed."
            }
            closed = true
            if (shutdownHookRegistered && Thread.currentThread() !== shutdownHook) {
                // Removing a hook is disallowed once JVM shutdown has begun.
                runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            }
        } finally {
            if (Thread.interrupted() || interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun runCommand(
        phase: String,
        executable: String,
        arguments: List<String>,
        timeoutSeconds: Long = 45,
    ) {
        val process = try {
            ProcessBuilder(listOf(binDirectory.resolve(executable).toString()) + arguments).apply {
                redirectErrorStream(true)
                redirectOutput(ProcessBuilder.Redirect.DISCARD)
                // Ambient PostgreSQL settings must never select or configure another service.
                environment().keys.filter { it.startsWith("PG") }.forEach { environment().remove(it) }
            }.start()
        } catch (_: Exception) {
            throw IllegalStateException(
                "Could not launch PostgreSQL test $phase. Check FEEDME_POSTGRES_BIN and binary permissions.",
            )
        }
        try {
            check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                "PostgreSQL test $phase exceeded ${timeoutSeconds}s; command output is redacted."
            }
            check(process.exitValue() == 0) {
                "PostgreSQL test $phase failed (exit ${process.exitValue()}); command output is redacted. " +
                    "Private test files: $directory."
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("PostgreSQL test $phase was interrupted; command output is redacted.")
        } finally {
            if (process.isAlive) {
                // Only descendants of the command just launched here are eligible for termination.
                val descendants = process.descendants().use { it.toList() }
                descendants.asReversed().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
            process.inputStream.close()
            process.errorStream.close()
            process.outputStream.close()
        }
    }

    companion object {
        private const val OWNER = "feedme_test_owner"
        private val OWNER_DIRECTORY = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        private val OWNER_FILE = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))

        /** Uses local PostgreSQL binaries, optionally selected with FEEDME_POSTGRES_BIN. */
        fun start(): PostgresTestCluster {
            val binDirectory = findBinaries()
            val tempParent = if (Files.isDirectory(Path.of("/private/tmp"))) Path.of("/private/tmp") else Path.of("/tmp")
            val directory = Files.createTempDirectory(tempParent, "feedme-postgres-test.", OWNER_DIRECTORY)
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val secretBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val password = try {
                Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes)
            } finally {
                secretBytes.fill(0)
            }
            val cluster = PostgresTestCluster(binDirectory, directory, port, password)
            try {
                Runtime.getRuntime().addShutdownHook(cluster.shutdownHook)
                cluster.shutdownHookRegistered = true
                cluster.initialize()
                return cluster
            } catch (failure: Throwable) {
                try {
                    cluster.close()
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
                throw failure
            }
        }

        private fun findBinaries(): Path {
            val required = listOf("initdb", "pg_ctl", "postgres")
            val configured = System.getenv("FEEDME_POSTGRES_BIN")?.takeIf { it.isNotBlank() }
            val candidates = if (configured != null) {
                listOf(Path.of(configured))
            } else {
                listOf(Path.of("/opt/homebrew/opt/postgresql@15/bin"), Path.of("/usr/local/opt/postgresql@15/bin")) +
                    System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator)
                        .filter { it.isNotBlank() }.map { Path.of(it) }
            }
            return candidates.firstOrNull { candidate ->
                required.all { Files.isRegularFile(candidate.resolve(it)) && Files.isExecutable(candidate.resolve(it)) }
            }?.toAbsolutePath()?.normalize() ?: throw IllegalStateException(
                "PostgreSQL integration tests require local initdb, pg_ctl, and postgres executables. " +
                    "Install PostgreSQL 15 or newer and set FEEDME_POSTGRES_BIN to its bin directory. " +
                    "Tests never use an existing PostgreSQL service and are not silently skipped.",
            )
        }
    }
}
