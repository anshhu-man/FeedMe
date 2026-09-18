# Existing-account Terms — server-only checkpoint

19 September 2026 IST. Google Play first; Shipaton remains paused.

The current backend bundle adds `GET /v1/account/terms` and
`POST /v1/account/terms`. Both require the verified provider identity and an existing
active device. Reading does not accept anything. Explicit acceptance binds the
current notice version and descriptor fingerprint to the original idempotent
command. Account Terms, immutable evidence and the command receipt commit together;
exact retries preserve the original time. Eligibility, profile and device state
are unchanged. A configured notice does not override the actual acceptance policy.

The fingerprint hashes the UTF-8 JSON array `[termsVersion, termsUrl, privacyUrl]`,
not remote document contents. Real approved versioned documents must be published
immutably. No legal text or URLs are supplied as approved defaults.
Optional `termsNotice` configuration is unavailable when absent or null.

## Evidence and boundaries

The source workspace passed 115 focused JVM checks, 16 isolated PostgreSQL/HTTP
cases and 91 contract-generation checks. Coverage includes exact restricted-role
permissions, immutable evidence, concurrent/replayed commands, revoked authority
and unchanged private-account state. Both temporary databases stopped, and the
353 selected source inputs were unchanged during SQL verification. The first
contract run found a missing exact fingerprint-validation rule; after the fix,
the selected checks passed. A first SQL startup failed for missing locale before
test bodies; the same selection passed with explicit C locale. Raw local evidence
is not included in this curated source bundle.

All 31 previous migration files remain byte-identical. V032 and its narrow runtime
grants were verified locally, not installed remotely. Independent source and
boundary reviews reported no blocker. This is not a full-project regression or
current-clone native acceptance.

Still required: connect native current-Terms review and durable explicit acceptance
without replacing existing account/private history; approve and publish actual
Terms/Privacy; settle child-account eligibility, email, reviewed launch content,
budget and protected configured runtime; complete the real connected app journey
and remaining V1/Play gates. No APK or Play submission was created.

The [current 297-input context](BACKEND_DEPLOYMENT.md) is source-only. The older
Render preview remains unconfigured and Supabase is not connected to it.
