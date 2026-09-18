package com.feedme.server.config

import java.nio.file.Path
import org.postgresql.ds.PGSimpleDataSource

/** Explicit operator-selected target, not tenant attestation or permission to run a migration.
 * No connection, file read, fallback database or environment lookup occurs while parsing.
 * Credentials are environment-only; immutable JVM/environment strings cannot be zeroized. */
class PlatformMigrationConfig private constructor(
    val environment: String,
    val host: String,
    val port: Int,
    val database: String,
    private val user: String,
    private val password: String,
    private val rootCertificate: String?,
) {
    internal fun dataSource(): PGSimpleDataSource = PGSimpleDataSource().also {
        it.setServerNames(arrayOf(host)); it.setPortNumbers(intArrayOf(port)); it.setDatabaseName(database)
        it.setUser(user); it.setPassword(password)
        it.setSslMode(if (environment == "local") "disable" else "verify-full")
        it.setGssEncMode("disable") // Do not negotiate an alternative to the explicit TLS profile.
        rootCertificate?.let(it::setSslRootCert)
        it.setConnectTimeout(10); it.setLoginTimeout(15); it.setSocketTimeout(60)
        it.setApplicationName("feedme-platform-migrations")
    }

    override fun toString() = "PlatformMigrationConfig(<redacted>)"

    companion object {
        private const val PREFIX = "FEEDME_MIGRATION_"
        private const val DB = "${PREFIX}DB_"
        private val allowed = setOf("${PREFIX}ENVIRONMENT", "${DB}HOST", "${DB}PORT", "${DB}NAME",
            "${DB}USER", "${DB}PASSWORD", "${DB}SSL_ROOT_CERT")
        private val conflicting = setOf("DATABASE_URL", "JDBC_DATABASE_URL", "JDBC_DATABASE_USERNAME", "JDBC_DATABASE_PASSWORD",
            "PGHOST", "PGHOSTADDR", "PGPORT", "PGDATABASE", "PGUSER", "PGPASSWORD", "PGSERVICE", "PGSERVICEFILE",
            "PGSSLMODE", "PGSSLROOTCERT", "PGOPTIONS", "PGPASSFILE")

        fun fromEnvironment(values: Map<String, String>): PlatformMigrationConfig {
            require(values.keys.none { (it.startsWith(PREFIX) && it !in allowed) || it in conflicting }) {
                "Unsupported or conflicting migration configuration"
            }
            fun required(key: String) = requireNotNull(values[key]) { "Explicit migration configuration is required" }
            val environment = required("${PREFIX}ENVIRONMENT")
            require(environment in setOf("local", "staging", "production")) { "Invalid migration environment" }
            val host = required("${DB}HOST")
            val certificate = values["${DB}SSL_ROOT_CERT"]
            if (environment == "local") {
                require(host == "127.0.0.1" && certificate == null) { "Invalid local migration transport" }
            } else {
                val labels = host.split('.')
                require(host.length in 3..253 && labels.size >= 2 && labels.all {
                    it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))
                } && labels.last().any { it in 'a'..'z' } && labels.last() !in setOf("localhost", "local", "localdomain")) {
                    "Invalid remote migration host"
                }
                require(certificate != null && certificate.length in 1..4096 && certificate.none(Char::isISOControl)) {
                    "Explicit migration trust root is required"
                }
                val path = try { Path.of(certificate) } catch (_: Exception) { throw IllegalArgumentException("Invalid migration trust root") }
                require(path.isAbsolute && path.fileName != null && path.normalize() == path) { "Invalid migration trust root" }
            }
            val portText = required("${DB}PORT")
            require(portText.matches(Regex("[1-9][0-9]{3,4}"))) { "Invalid migration port" }
            val port = portText.toInt()
            require(port in 1024..65535) { "Invalid migration port" }
            val database = required("${DB}NAME")
            require(database.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_-]{0,62}"))) { "Invalid migration database" }
            val user = required("${DB}USER")
            require(user.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,62}"))) { "Invalid migration user" }
            val password = required("${DB}PASSWORD")
            require(password.length in 1..4096 && !password.isBlank() && password.none(Char::isISOControl)) { "Invalid migration credential" }
            return PlatformMigrationConfig(environment, host, port, database, user, password, certificate)
        }
    }
}
