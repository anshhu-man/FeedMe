# Existing-account Terms — historical receipt recovery

19 September 2026 IST. Google Play first; Shipaton remains paused.

The current backend bundle adds `GET /v1/account/terms` and
`POST /v1/account/terms`. Both require the verified provider identity and an existing
active device. Reading does not accept anything. Explicit acceptance binds the
current notice version and descriptor fingerprint to the original idempotent
command. Account Terms, immutable evidence and the command receipt commit together;
exact retries preserve the original time. Eligibility, profile and device state
are unchanged. A configured notice does not override the actual acceptance policy.

The current increment recovers an already committed acceptance directly from its
immutable audit, even after the notice changes or the seven-day response cache
expires or is compacted. It requires current authentication and the exact same
account, device, provider issuer/subject/session, command key and body fingerprint.
The retained descriptor and canonical receipt are validated before delivery, with
current authority checked again after the read. No audit match means no historical
success; a mismatch never falls through to create replacement consent.

This returns the original acceptance version/hash/time, not acceptance of the new
notice. It writes no account state, audit, response cache or eligibility. The client
must check the current notice again and obtain a separate affirmative acceptance
when needed. Missing/uncommitted evidence and safe offline sign-out remain distinct
recovery problems; no unresolved original may be silently discarded.

The fingerprint hashes the UTF-8 JSON array `[termsVersion, termsUrl, privacyUrl]`,
not remote document contents. Real approved versioned documents must be published
immutably. No legal text or URLs are supplied as approved defaults.
Current-notice discovery and fresh acceptance are unavailable when optional
`termsNotice` configuration is absent or null. Exact historical receipt recovery
does not depend on that current descriptor.

## Current evidence and boundaries

The source workspace passed 30 selected server checks: 10 unit checks, all 15 Terms
SQL cases and five Terms HTTP cases. These cover expired/compacted cache recovery,
notice rollover, newer consent preservation, exact runtime privileges, mismatched
or revoked authority, absent audit and the final provider recheck. Ninety-two Node
checks passed. This is bounded regression evidence, not a full-project or live-user
acceptance claim.

Separately, 80 selected shared checks and an Android debug APK build passed locally.
The 110 combined Kotlin/SQL/HTTP checks are not a full regression run.
That implementation includes returning-account and restricted-onboarding Terms
routing, but the mobile changes/APK are not published in this server-only bundle
and these results are not on-device or live connected-flow acceptance.

All previous migrations remain unchanged. V032, its narrow runtime grants and
the runtime probe were installed/verified at the prior 20:35 managed-database
checkpoint. No database, Render image or deployment changed in this increment.

## Historical initial Terms checkpoint

The source workspace passed 115 focused JVM checks, 16 isolated PostgreSQL/HTTP
cases and 91 contract-generation checks. Coverage includes exact restricted-role
permissions, immutable evidence, concurrent/replayed commands, revoked authority
and unchanged private-account state. Both temporary databases stopped, and the
353 selected source inputs were unchanged during SQL verification. The first
contract run found a missing exact fingerprint-validation rule; after the fix,
the selected checks passed. A first SQL startup failed for missing locale before
test bodies; the same selection passed with explicit C locale. Raw local evidence
is not included in this curated source bundle.

At that initial checkpoint, all 31 previous migration files remained byte-identical;
V032 and its narrow runtime grants were verified locally but not yet installed
remotely. Their later installation is recorded above. Independent source and
boundary reviews reported no blocker. This is not a full-project regression or
current-clone native acceptance.

Still required: verify the actual connected native review/acceptance journey while
preserving existing account/private history; approve and publish actual
Terms/Privacy; settle child-account eligibility, email, reviewed launch content,
budget and protected configured runtime; complete the real connected app journey,
safe offline sign-out preserving unresolved originals, and remaining V1/Play gates.
No APK is included in this update and no Play submission was made.

The [current 297-input context](BACKEND_DEPLOYMENT.md) is source-only: 5,650,097 bytes,
manifest SHA256 `bf158f70f2005dc8aa0e3ef791aa0aea96ebdfea570cc013eec90d45c2c22a48`.
The older Render preview remains unconfigured and Supabase is not connected to it.
