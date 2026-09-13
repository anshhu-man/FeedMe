# Durable command and event foundation — M1.02

Verified locally on 13 September 2026. **PostgreSQL transaction primitives, not a deployed service or completed cooking/social feature.** The database suite uses real PostgreSQL with synthetic records; provider auth, product tables, native persistence and queue delivery are not connected.

## Delivered small tasks

| Work package | Implemented behavior | Verification |
| --- | --- | --- |
| M1.02a — migrations | Transactional, checksum-verified V001; concurrent migrators serialize; existing data is never automatically rolled back or dropped | 5 real-database tests: concurrent first run, repeat preservation, checksum/version drift, atomic DDL failure |
| M1.02b — commands | Principal/operation/key namespace, canonical request fingerprint, one durable successful reply, separate execution/replay authorization hooks | 19 database tests, including 20 simultaneous identical requests, changed payload/target/ETag, isolated principals/operations, rollback, bounded retry and revoked-principal denial |
| M1.02c — outbox/inbox | Domain change + receipt + event commit together; bounded leased claims, fencing, retries/quarantine; consumer marker + effect + follow-up event commit together | 15 database tests: concurrent workers, locked-row skipping, worker-crash/redelivery simulation, stale lease rejection, poison envelopes, duplicate consumers, rollback and non-regressing fixture projection |
| M1.02d — uncertain commit and retention | An ambiguous commit never triggers an automatic replacement operation; expiry compacts responses without re-executing old keys | 6 database tests: injected failure after accepted commit, close failure, bounded/locked-row retention, expiry after replay authorization, conflicting concurrent payloads |

**45 PostgreSQL integration tests passed, plus 24 server tests and 46 existing shared Kotlin tests.** The local server distribution builds. [Evidence receipt and source/artifact hashes](verification/durable-storage/report.json).

The 24 server tests include the earlier public-health/unavailable-route suite. Event-name tests read the actual blueprint event catalog, including names with underscores. None of these numbers count as implemented feature/screen coverage, provider authentication evidence, a production security audit or a store release.

## How a command works

`DurableCommands.execute` requires a verified `PrincipalScope`, a canonical `CommandIdentity` and explicit callbacks for principal validation, new-command authorization, receipt authorization and mutation. There are no default permissive callbacks. They run on one transaction connection:

1. Revalidate the principal/account/device session **before reading its receipt**. The identity module must acquire the appropriate policy locks.
2. Insert the canonical idempotency key or wait for the competing transaction, then lock its row. PostgreSQL's primary key serializes identical commands across processes; no in-memory mutex is the authority.
3. A changed fingerprint returns `Mismatch` without a second effect. A completed matching key uses its distinct receipt-authorization callback; it does not rerun mutation preconditions. This permits an authorized successful-delete receipt after its target is gone, without bypassing current session/access policy.
4. For a new command, lock and authorize affected policy/audience/version roots in the blueprint's global order, then mutate product data and append any events on that same connection.
5. Store the successful status/JSON/ETag and commit. Any exception before commit rolls back the domain mutation, outbox and receipt together.

The test authorization callbacks use **synthetic database ACL rows**. Actual token verification, device-session registry, ownership, membership, recall and lifecycle decisions remain M1.03/M1.07 and feature work. The generic callback contract does not implement those policies or prove all revocation races safe. Every real module must lock the same roots as access-removal transactions.

`PgTransactions` uses read-committed isolation, dedicated connections and bounded lock/statement/idle timeouts. It retries only SQLSTATE `40001`/`40P01`, at most three attempts by default, with a fresh transaction. Callbacks must be database-only and must not commit, change connection ownership, make payments or send messages. Complex cross-row decisions still require correct root locks or a separately implemented serializable strategy; generic read-committed transactions do not infer business invariants. [PostgreSQL transaction isolation](https://www.postgresql.org/docs/15/transaction-iso.html).

A commit-time failure with an uncertain result raises `CommitOutcomeUnknown`; the caller retains the original key and reconciles through retry. It does not automatically invoke the mutation again. The test injects a connection error **after a real successful database commit**; this is targeted fault injection, not a physical network/power-loss drill.

## Explicit implementation decisions

These fill gaps in the proposed blueprint without changing canonical endpoints or weakening constraints:

- Scope includes environment + verified actor kind + principal UUID; account, guest and staff namespaces cannot collide. Public guest bootstrap has no verified principal yet and is deliberately excluded from this generic executor. RevenueCat uses its separate provider-event identity and is also excluded.
- Fingerprint v1 hashes operation/method/route, concrete path values, meaningful query values, expected `If-Match`, body presence and canonical JSON. Object-key order and equivalent decimal spelling normalize; array order, missing versus null and target/version changes remain distinct. Credentials and transport trace headers are not inputs, and raw requests are not stored in the idempotency table. This is **not** a request-schema validator; M0.07 must validate DTOs/headers/formats before execution.
- Only successful 2xx JSON/no-content results are cached. Explicit 204/205 preserve no body; JSON null remains distinct. `ETag` is the only retained response header. Current canonical JSON content type is reconstructed by the eventual HTTP adapter; trace IDs are per request, not cached. One-time secrets, signed URLs and provider tokens must never enter a cached reply.
- Full replies expire seven days after success, using database time. Replays check time after obtaining the receipt lock and again after receipt authorization. Expired keys become payload-free tombstones and return `ReceiptExpired`; they never silently become new commands.
- `compactExpired` provides a bounded `SKIP LOCKED` sweep for unrequested expired responses. **No scheduler is wired yet**, and tombstone purge duration/erasure integration require the retention-policy work. The foundation retains command identities until an explicit safe purge strategy exists; that is not an approved production forever-retention policy.
- Pending receipts are never intentionally committed by the executor. An imported/orphaned pending row returns `IncompleteReceipt`, not an invented success or a second mutation. Operational reconciliation remains necessary.

The HTTP adapter is not connected, so these results are not newly exposed API codes. `Mismatch` must map to the existing `409 IDEMPOTENCY_MISMATCH`; expiry/incomplete/unknown-result mappings and safe client reconciliation must be completed before route enablement. Existing product endpoints still return their explicit unavailable response.

## How events survive retries

`OutboxStore.append` requires the transaction connection. The full envelope preserves event ID/type/schema, aggregate ID/type/version, producer, correlation and causation IDs, data and database occurrence time. A duplicate event ID fails the transaction. Type-specific data validation and sensitive-field exclusion remain each producing module's responsibility; envelope validation alone is not content redaction.

Workers claim at most 100 rows per transaction using `FOR UPDATE SKIP LOCKED`. Each claim carries a fresh token and deadline. Acknowledge/failure updates require the matching unexpired lease, so a stale worker cannot alter a newer worker's record. Transient failure releases the lease with bounded exponential delay and jitter; five claims/failures or a permanent/unsupported envelope move it into durable quarantine. Expired fifth claims are also quarantined in bounded batches. Quarantine is retained work requiring operator review, not silent deletion.

`OutboxRelay` claims one item immediately before each send, processing at most 20 per call. A future queue adapter must keep its timeout below the lease duration; long jobs need a different heartbeat/renewal design. Send acceptance followed by a crash/timeout can still redeliver, as required by at-least-once semantics. No SQS/APNs/FCM/provider adapter has been configured. [PostgreSQL row locking and queue-oriented `SKIP LOCKED`](https://www.postgresql.org/docs/15/sql-select.html).

Malformed imported/older-writer envelopes are quarantined inside the claim transaction so they cannot repeatedly roll back healthy claims. A single-item relay poll that quarantines a malformed row may return zero before later healthy rows; zero acknowledged events **does not prove the queue is drained**. Subsequent polling continues progress.

`ConsumerInbox.consume` inserts `(consumer_name,event_id)` and applies database effects/follow-up events in one transaction. Duplicate deliveries do not repeat that consumer's effect. Consumers must independently recheck current ownership, deletion/expiry/recall and aggregate version; an event is never a standing authorization grant. The fixture projection test proves this extension point can reject old versions and avoid resurrecting a missing record; no real FeedMe consumer has yet satisfied those gates. External delivery needs its separate durable recipient/channel intent ledger and cannot claim exactly-once behavior.

## Migration, setup and rollback

V001 creates only `platform.idempotency`, `platform.outbox`, `platform.consumer_inbox` and migration history. These are the canonical persistence names with additional explicit lease/response metadata. No identity, catalog, cooking, post, billing or user-content tables are created. V001 is expand-only, has no backfill because the tables are new, and leaves the previous app compatible. Application rollback retains these tables and records; there is no destructive down migration. Future migrations must be consecutive and immutable, with independently reviewed backfills and N/N−1 compatibility.

The migration runner is **not called automatically from HTTP startup** and has no production connection defaults. The CLI still starts only the local degraded-health scaffold. Production database provisioning, roles, pooling, TLS, secrets, schema ownership and staged migration authorization remain unconfigured.

With the root project's JDK 17 and Android SDK setup, use installed PostgreSQL binaries (15+ supported by this initial SQL; actually tested here on Homebrew **15.19**):

```sh
FEEDME_POSTGRES_BIN=/path/to/postgresql/bin ./gradlew --no-daemon --console=plain :server:test :server:integrationTest :server:installDist :shared:core:jvmTest
```

`FEEDME_POSTGRES_BIN` selects binaries, **not an existing database URL**. If absent, the harness checks the documented Homebrew paths and `PATH`; missing binaries fail with instructions instead of silently skipping. Each test class starts its own private temporary cluster, loopback-only with SCRAM authentication, random credentials, fsync/synchronous commit enabled, then creates fresh test databases. Plaintext initialization password files are deleted; synthetic cluster data remains in private temporary directories. Owned servers are stopped after tests, with a JVM shutdown hook as fallback. No existing PostgreSQL instance or data is connected to, reconfigured, reset or stopped.

The first run found a Kotlin receiver-shadowing bug in the test harness's password binding; it was fixed before any integration test passed. Independent review then found and fixed rejection of canonical underscore event names, stale reserved-batch sends and poison-row starvation. Regression cases now cover each issue. These findings are preserved as execution history, not hidden by reporting only green checks.

pgJDBC **42.7.13** is pinned as a server-only dependency; see the [official JDBC release page](https://jdbc.postgresql.org/). No cloud resources, provider accounts, credentials, paid service, deploy or store submission were created. Full license/security inventory remains M0.08. Backup restoration, actual process/power-loss recovery, hosted queues, production auth and physical-device integration remain release gates.

## Next integration gate

Complete presence-aware client/server contract validation and mappings (M0.07), then add real identity and one authorized catalog → plan → cook → private-save transaction. M7.12 explicitly tracks scheduler/queue wiring; M7.13 tracks approved retention, erasure-aware tombstone purge and safe quarantine replay. The original Today/My Plate/circles/Make Mine identity and every retained feature stay in the delivery matrix; no feature is removed to fit this foundation.
