package com.feedme.server.staff

import com.feedme.core.ports.PortResult
import com.feedme.server.db.PgTransactions
import com.feedme.server.db.checkedMigrationDataSource
import java.time.Clock
import java.util.concurrent.CancellationException
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/** Separate explicit operator entry point. No HTTP route, migration, enrollment, provider
 * setup, review or publish runs during app startup. Credentials must not be CLI arguments. */
fun main(args: Array<String>) {
    val result = runStaffPublication(args, System::getenv)
    (if (result.code == 0) System.out else System.err).println(result.message)
    if (result.code != 0) exitProcess(result.code)
}

internal enum class StaffPublicationExit(val code: Int, val message: String) {
    HELP(0, "Use exactly --approve, --publish or --revoke-approval followed by an absolute operation JSON path. Configure explicit FEEDME_STAFF_ENVIRONMENT, DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD, remote DB_SSL_ROOT_CERT, AUTH_CONFIG and ACCESS_TOKEN through protected environment setup. Approval records an authenticated human decision; it does not publish. Revoking an approval blocks future writes/replays, not already published recipes/copy rights. The canonical admin HTTP UI is separate. Never put credentials in arguments or logs."),
    REFUSED(2, "Staff publication operation not started: invalid or missing explicit operation/configuration."),
    UNAUTHENTICATED(3, "Staff publication operation not started: current workforce access authentication is unavailable or rejected."),
    APPROVED(0, "The exact independent approval was recorded or reconciled. Nothing was published."),
    PUBLISHED(0, "The exact publication was committed or reconciled. Preserve its original bytes and approval ID."),
    REVOKED(0, "The exact approval revocation was committed or reconciled. Existing content and copy grants were not changed."),
    FAILED(1, "Staff operation failed or its commit outcome is unknown. Preserve the exact operation/original IDs and inspect before retrying; no automatic replacement or retry was attempted by this process."),
    INTERRUPTED(130, "Staff operation interrupted; its outcome may be unknown. Preserve the exact operation/original IDs and inspect before retrying."),
}

internal fun runStaffPublication(args: Array<String>, environment: () -> Map<String, String>,
    execute: (StaffPublicationConfig, StaffPublicationCommand) -> StaffPublicationExit = ::executeStaffPublication): StaffPublicationExit {
    if (args.contentEquals(arrayOf("--help"))) return StaffPublicationExit.HELP
    if (args.size != 2 || args[0] !in setOf("--approve", "--publish", "--revoke-approval")) return StaffPublicationExit.REFUSED
    if (Thread.currentThread().isInterrupted) return StaffPublicationExit.INTERRUPTED
    return try {
        val command: StaffPublicationCommand
        val config: StaffPublicationConfig
        try {
            command = StaffPublicationCommand.read(args[1], args[0])
            config = StaffPublicationConfig.fromEnvironment(environment())
        } catch (failure: InterruptedException) { throw failure }
          catch (failure: CancellationException) { throw failure }
          catch (_: Exception) { return StaffPublicationExit.REFUSED }
        execute(config, command)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt(); StaffPublicationExit.INTERRUPTED
    } catch (_: CancellationException) {
        StaffPublicationExit.INTERRUPTED
    } catch (_: Exception) {
        if (Thread.currentThread().isInterrupted) StaffPublicationExit.INTERRUPTED else StaffPublicationExit.FAILED
    }
}

private fun executeStaffPublication(config: StaffPublicationConfig, command: StaffPublicationCommand): StaffPublicationExit = runBlocking {
    val clock = Clock.systemUTC()
    // Single explicit short-lived operation; no provider token is passed to the public-key
    // transport and no key cache survives the process. All network I/O precedes DB locks.
    val subject = HttpsWorkforceJwksSource.create(config.authentication,
        WorkforceJwksHttpPolicy(5_000, 10_000, 15_000, 1_000, 1), clock).use { keys ->
        when (val verified = WorkforceAccessVerifier(config.authentication, keys, clock).verify(config.accessToken)) {
            is PortResult.Value -> verified.value
            is PortResult.Failure -> return@runBlocking StaffPublicationExit.UNAUTHENTICATED
        }
    }
    val source = config.database.dataSource().also { it.setApplicationName("feedme-staff-publication") }
    try {
        // One attempt per process prevents an operational wrapper from resubmitting a
        // human action. Unknown commit acknowledgement keeps the unchanged original.
        val transactions = PgTransactions(checkedMigrationDataSource(source, config.database.database), attempts = 1)
        val registry = StaffPublicationRegistry(config.database.environment, transactions)
        when (command) {
            is StaffPublicationCommand.Approve -> {
                registry.approve(subject, command.approvalId, command.original.intent, command.policyVersion,
                    command.expiresAt, command.reason, command.rightsAttestationReference)
                StaffPublicationExit.APPROVED
            }
            is StaffPublicationCommand.Publish -> {
                StaffCatalogPublisher(config.database.environment, transactions, registry)
                    .publish(subject, command.approvalId, command.original)
                StaffPublicationExit.PUBLISHED
            }
            is StaffPublicationCommand.RevokeApproval -> {
                registry.revoke(subject, command.approvalId, command.revocationId, command.reason)
                StaffPublicationExit.REVOKED
            }
        }
    } finally { source.setPassword(null) }
}
