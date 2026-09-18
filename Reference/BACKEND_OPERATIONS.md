# Backend operations — public handoff

18 September 2026. This document describes the separately curated
[server bundle](BACKEND_DEPLOYMENT.md), not the older top-level app snapshot.
No credential, private runtime configuration, local operator helper or raw local
test/evidence directory is included here.

## Recorded database checkpoint

The operator workspace records completed managed-database installation and checks
through V031, six exact private Auth projection functions, and a dedicated
`feedme_api` login. At 17:33 UTC, actual role authentication/effective-ACL checks
covered 79 tables, 798 columns, ten schemas, 74 functions and all eighteen exact
immutable-key guards. The actual runtime-role schema probe and helper-source
verification passed. Five focused restricted-role PostgreSQL cases passed locally
with synthetic identity/content and a stopped isolated cluster.

Those are database/permission checks, not evidence of Render deployment, production
signup, a live user transaction or Android connectivity. No production user rows,
eligibility, content or email settings were created by this checkpoint. The raw
operator receipts remain outside this curated build context.

## Auth and runtime privilege boundaries

The [authority](../server-deploy/feedme/server/src/main/kotlin/com/feedme/server/identity/SupabasePostgresAuthority.kt)
checks current provider user/session/authentication-method facts in the same
database transaction as FeedMe's account/device checks. The six fixed
[private projections](../server-deploy/feedme/server/src/main/resources/db/provider/supabase-authority.sql)
are owned by the trusted installer, not by the API role. Their fixed search path,
RLS visibility checks and private execution permissions are part of the boundary.
No direct Auth table access or administrator credential belongs in the API process.

Provider fact-table and exact row locks preserve current identity consistency.
The reviewed Auth migration vector is observed, not globally frozen through commit;
an unreviewed vector is refused on the next check. Neither this mechanism nor the
JWT signature freezes provider binaries/settings. Current deployment/session-policy
review is explicit and expires after at most 24 hours; do not blindly extend it.

**Current deployment blocker:** the live Supabase dashboard showed an enabled
15-minute AAL1 lower-assurance timeout. The bundled authority rejects any non-null
`lowAssuranceTimeoutSeconds`; it does not yet support that observed policy. The
schema-only probe used an ephemeral inspection declaration and did not validate
the live deployment/session policy. Implement and verify faithful timeout support
before API launch. Do not disable the provider security setting or set this field
to `null` to hide the mismatch. The current 294-input bundle remains unchanged;
supporting code must be exported and reviewed as a subsequent deliberate update.

The [runtime grant resource](../server-deploy/feedme/server/src/main/resources/db/provider/feedme-runtime-grants.sql)
permits the account/profile/preferences, ingredient, pantry, planning, cooking and
Saved path. Deliberate `BYPASSRLS` makes this a trusted server role, **not per-user
database isolation**: application authorization is still mandatory. The role has
no catalog-publication, migration, direct Auth or guest/social/media-worker grants.
Never expose its credential through the client Data API or an untrusted SQL surface.

Eighteen UPDATE-column grants exist solely to support PostgreSQL row-locking reads.
Twelve existing immutable-row guards and six
[V031 guards](../server-deploy/feedme/server/src/main/resources/db/migration/V031__lock_only_key_guards.sql)
reject key assignments, including no-op assignments. Do not replace these with
table-wide UPDATE, change their attachments, or permit trigger bypass.
Bootstrap INSERT still includes eligibility fields: denial of eligibility UPDATE
does not protect against a compromised backend inserting an eligible row. Initial
eligibility must come from the actual reviewed account policy, never guessed defaults.

## Deployment configuration and rollout

Use the exact [AccountCoreRuntimeConfig parser](../server-deploy/feedme/server/src/main/kotlin/com/feedme/server/config/AccountCoreRuntimeConfig.kt)
as the schema. Supply `FEEDME_ACCOUNT_RUNTIME_CONFIG`,
`FEEDME_ACCOUNT_DB_PASSWORD` and `FEEDME_ACCOUNT_CURSOR_KEYS` only through the
host's protected runtime facility. The API password is separate from the migration
password; cursor keyrings must remain stable across restarts. Remote JDBC requires
`verify-full` and an explicitly mounted reviewed CA readable by UID 10001.

For the container listener, explicitly configure `0.0.0.0` and its validated port.
If Render supplies `PORT`, its canonical decimal must exactly match the configured
listener port. Do not combine account settings with conflicting legacy server/DB
variables. The image does not migrate, seed content or provision roles on startup.

The packaged `PlatformMigrationMainKt --check` is a separate operator action using
separate protected migration configuration. `--apply` is deliberate; it is never
automatically chained after a pending check. A failed/interrupted apply may have an
unknown outcome: inspect the same target/history before deciding on a retry. Keep
applied migration bytes immutable, establish the backup/rollout plan, and do not
use a history check as proof of physical schema or product readiness.

Before serving users, complete truthful provider/session-policy and legal/age/consent
configuration, reviewed ingredients/recipes/Saved-copy rights, email delivery,
the current image build/runtime checks, HTTPS deployment and the connected
Android sign-in/isolation/cook/Saved journey. A successful account-core health
check remains a bounded component result, not whole-V1 or Play release approval.
