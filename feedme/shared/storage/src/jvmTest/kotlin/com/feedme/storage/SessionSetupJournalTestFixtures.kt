package com.feedme.storage

import com.feedme.core.ports.*
import com.feedme.session.SessionSetupPlan
import kotlin.test.assertIs

/** Structural protocol fixture only. These MAC bytes are synthetic and grant no native authority. */
internal fun syntheticSetupJournal(scope: StorageScope, configuration: String, abort: Boolean = false): PrivateBytes {
    fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val owner = """{"environment":${quote(scope.environment)},"actorKind":"${scope.actorKind.name}","actorId":${quote(scope.actorId)}}"""
    val id = "00000000-0000-4000-8000-000000000381"
    val credential = """{"version":1,"purpose":"credential-create","expectedSlotRevision":1,"incarnation":"$id","target":"${"a".repeat(64)}","payloadMac":"${"b".repeat(64)}","authenticationMac":"${"c".repeat(64)}"}"""
    val data = StateActivationPlanCodec.encode(StateActivationPlanRecord("a".repeat(64), 0, null,
        "b".repeat(32), ByteArray(32))).copyForStorage()
    val work = """{"version":1,"expectedRevision":1,"scope":$owner,"origin":"00000000-0000-4000-8000-000000000382","proof":"${"0".repeat(128)}"}"""
    val record = ("""{"version":1,"purpose":"session-setup","operationId":"00000000-0000-4000-8000-000000000383","scope":$owner,"configurationBinding":${quote(configuration)},"credentialPlan":"${hex(credential.encodeToByteArray())}","dataPlan":"${hex(data)}","workOriginPlan":"${hex(work.encodeToByteArray())}"}""").encodeToByteArray()
    val plan = assertIs<PortResult.Value<SessionSetupPlan>>(SessionSetupPlan.fromStorage(PrivateBytes(record))).value
    return PrivateBytes("""{"version":1,"state":"session-setup-pending","plan":"${hex(plan.copyForStorage().copyForCodec())}","abortRequested":$abort}""".encodeToByteArray())
}
