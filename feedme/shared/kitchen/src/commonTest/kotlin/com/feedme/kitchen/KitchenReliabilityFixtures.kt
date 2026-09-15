package com.feedme.kitchen

import com.feedme.core.ports.*
import kotlinx.coroutines.CoroutineDispatcher

/** Deliberately adversarial common port fixture, not native storage or identity verification. */
internal class KitchenReliabilityFixture(val dispatcher: CoroutineDispatcher) {
    val scope = StorageScope("kitchen-ack-test", ActorKind.ACCOUNT, "synthetic-owner")
    val boundary = SessionBoundary()
    val lease = boundary.activate(scope)
    val store = KitchenReliabilityStore(scope)
    val calls = mutableListOf<ApiCall>()
    var exchange: suspend (ApiCall) -> PortResult<ApiReply> = { PortResult.Value(cookReply()) }
    val transport = object : AccountTransport {
        override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
            calls += call
            return exchange(call)
        }
    }
    fun context() = KitchenContext(scope, store, boundary, dispatcher, EpochClock { 1_800_000_000_000L }, transport)
    val context = context()
    companion object {
        const val ID = "123e4567-e89b-12d3-a456-426614174001"
        const val PLAN = "123e4567-e89b-12d3-a456-426614174002"
        const val ORIGIN = "123e4567-e89b-12d3-a456-426614174003"
        const val COMMAND = "123e4567-e89b-12d3-a456-426614174004"
        val KEY = RecordKey("feedme.kitchen.test", ID)
        val OTHER = RecordKey("feedme.kitchen.test", PLAN)
        fun bytes(value: String) = PrivateBytes(value.encodeToByteArray())
        fun cookReply() = ApiReply(200, bytes("""{"id":"$ID","version":1,"createdAt":"2026-09-13T07:00:00Z","updatedAt":"2026-09-13T08:00:00Z","planId":"$PLAN","status":"active","currentStepId":"step-one","completedStepIds":[],"deviceSequence":0,"timers":[]}"""),
            etag = "\"1\"", contentType = "application/json")
    }
}

internal class KitchenReliabilityStore(private val owner: StorageScope) : PrivateStateStore {
    val records = mutableMapOf<RecordKey, PrivateRecord>()
    private val counters = mutableMapOf<RecordKey, Long>()
    val commits = mutableListOf<List<StoreMutation>>()
    val reads = mutableListOf<RecordKey>()
    var write = true
    var receipt: (Map<RecordKey, Long?>) -> PortResult<Map<RecordKey, Long?>> = { PortResult.Value(it) }
    var observation: (RecordKey, PrivateRecord?) -> PortResult<PrivateRecord?> = { _, value -> PortResult.Value(value) }
    var beforeCommit: suspend (List<StoreMutation>) -> Unit = {}
    var afterCommit: suspend () -> Unit = {}
    var afterRead: suspend () -> Unit = {}
    fun seed(key: RecordKey, revision: Long = 1, schema: Int = 1, payload: PrivateBytes = KitchenReliabilityFixture.bytes("old")) {
        records[key] = PrivateRecord(revision, schema, payload); counters[key] = revision
    }
    override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> {
        check(scope == owner)
        reads += key
        val result = observation(key, records[key])
        afterRead()
        return result
    }
    override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
        check(scope == owner)
        commits += mutations.toList()
        beforeCommit(mutations)
        if (mutations.any { records[it.key]?.revision != it.expectedRevision }) return PortResult.Failure(FailureReason.CONFLICT)
        val result = mutations.associate { change ->
            val revision = (counters[change.key] ?: 0) + 1
            if (write) {
                counters[change.key] = revision
                when (change) {
                    is StoreMutation.Put -> records[change.key] = PrivateRecord(revision, change.schemaVersion, change.payload)
                    is StoreMutation.Delete -> records.remove(change.key)
                }
            }
            change.key to if (change is StoreMutation.Put) revision else null
        }
        afterCommit()
        return receipt(result)
    }
    override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> = error("Kitchen composition has no erasure authority")
}
