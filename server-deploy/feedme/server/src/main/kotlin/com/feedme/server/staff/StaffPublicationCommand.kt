package com.feedme.server.staff

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/** Local operator envelope. This is deliberately NOT the public admin API request schema.
 * The complete, strictly decoded domain original is carried verbatim, not reconstructed from
 * loose form fields or altered on a retry. Approval does not attest safety or rights by itself. */
internal sealed class StaffPublicationCommand {
    abstract val approvalId: UUID
    class Approve(override val approvalId: UUID, val original: StaffPublicationOriginal,
        val policyVersion: String, val expiresAt: Instant, val reason: String,
        val rightsAttestationReference: String) : StaffPublicationCommand()
    class Publish(override val approvalId: UUID, val original: StaffPublicationOriginal) : StaffPublicationCommand()
    class RevokeApproval(override val approvalId: UUID, val revocationId: UUID,
        val reason: String) : StaffPublicationCommand()
    final override fun toString() = "StaffPublicationCommand(<redacted>)"

    companion object {
        // The JSON string envelope escapes the independently bounded <=1 MiB domain original.
        internal const val MAX_COMMAND_BYTES = 4 * 1_048_576
        fun read(pathText: String, operation: String): StaffPublicationCommand {
            val path = Path.of(pathText)
            require(path.isAbsolute && path.normalize() == path && Files.isRegularFile(path, NOFOLLOW_LINKS))
            return Files.newInputStream(path, NOFOLLOW_LINKS).use { stream ->
                val bytes = stream.readNBytes(MAX_COMMAND_BYTES + 1)
                require(bytes.size in 1..MAX_COMMAND_BYTES)
                decode(bytes, operation)
            }
        }
        fun decode(bytes: ByteArray, operation: String): StaffPublicationCommand {
            val root = Json.parseToJsonElement(WireDocument.decode(bytes,
                WireLimits(MAX_COMMAND_BYTES, 8, 20)).encodeUtf8().decodeToString()).jsonObject
            val base = setOf("formatVersion", "approvalId")
            val fields = when (operation) {
                "--approve" -> base + setOf("kind", "original", "policyVersion", "expiresAt", "reason", "rightsAttestationReference")
                "--publish" -> base + setOf("kind", "original")
                "--revoke-approval" -> base + setOf("revocationId", "reason")
                else -> throw IllegalArgumentException("Unsupported publication operation")
            }
            require(root.keys == fields && root["formatVersion"] == JsonPrimitive(1))
            val approvalId = root.uuid("approvalId")
            if (operation == "--revoke-approval") return RevokeApproval(approvalId, root.uuid("revocationId"), root.reference("reason", 2048))
            val kind = StaffPublicationKind.entries.single { it.wire == root.text("kind") }
            val original = StaffPublicationOriginal.decode(kind, root.text("original").encodeToByteArray(throwOnInvalidSequence = true))
            if (operation == "--publish") return Publish(approvalId, original)
            val expiresAt = Instant.parse(root.text("expiresAt"))
            require(expiresAt.nano % 1_000_000 == 0 && expiresAt.toString() == root.text("expiresAt"))
            return Approve(approvalId, original, root.reference("policyVersion", 256), expiresAt,
                root.reference("reason", 2048), root.reference("rightsAttestationReference", 256))
        }
        private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.let { require(it.isString); it.content }
        private fun JsonObject.uuid(key: String) = UUID.fromString(text(key)).also { require(it.toString() == text(key)) }
        private fun JsonObject.reference(key: String, max: Int) = text(key).also {
            require(it.length in 1..max && it.isNotBlank() && it.none(Char::isISOControl))
        }
    }
}
