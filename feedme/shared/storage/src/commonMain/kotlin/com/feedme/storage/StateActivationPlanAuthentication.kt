package com.feedme.storage

import com.feedme.core.ports.ActorKind
import com.feedme.core.ports.StorageScope

/** Native existing-only factories authenticate before SQLite can recover or modify a journal. */
internal fun validateStateActivationPlan(scope: StorageScope, plan: StateActivationPlan, vault: StateVault) {
    if (scope.actorKind == ActorKind.DEMO) throw StateActivationPlanFormatException()
    val fields = try {
        listOf("feedme.owner-index.v1", scope.environment, scope.actorKind.name, scope.actorId)
            .map { it.encodeToByteArray(throwOnInvalidSequence = true) }
    } catch (_: Exception) { throw StateActivationPlanFormatException() }
    val input = ByteArray(4 + fields.sumOf { 4 + it.size })
    var offset = 0
    fun length(value: Int) {
        for (shift in 24 downTo 0 step 8) input[offset++] = (value ushr shift).toByte()
    }
    length(fields.size)
    for (field in fields) {
        length(field.size); field.copyInto(input, offset); offset += field.size; field.fill(0)
    }
    val ownerMac = try { vault.index(input) } finally { input.fill(0) }
    if (ownerMac.size != 32) { ownerMac.fill(0); throw StateVaultException() }
    val ownerTag = try {
        val hex = "0123456789abcdef"
        buildString(64) { for (byte in ownerMac) { append(hex[(byte.toInt() ushr 4) and 15]); append(hex[byte.toInt() and 15]) } }
    } finally { ownerMac.fill(0) }
    val record = StateActivationPlanCodec.decode(plan)
    val unsigned = StateActivationPlanCodec.encodeUnsigned(record)
    val authenticated = "feedme.activation-plan.v1\u0000".encodeToByteArray() + unsigned
    unsigned.fill(0)
    val expected = try { vault.index(authenticated) } finally { authenticated.fill(0) }
    try {
        if (expected.size != 32) throw StateVaultException()
        var mismatch = 0
        for (index in expected.indices) mismatch = mismatch or (expected[index].toInt() xor record.authenticationMac[index].toInt())
        if (mismatch != 0 || record.ownerTag != ownerTag) throw StateActivationPlanFormatException()
    } finally { expected.fill(0); record.authenticationMac.fill(0) }
}
