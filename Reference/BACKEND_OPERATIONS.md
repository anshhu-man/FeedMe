# Backend operations — public handoff

18 September 2026. This document describes the separately curated
[server bundle](BACKEND_DEPLOYMENT.md), not the older top-level app snapshot.
No credential, private runtime configuration, local operator helper or raw local
test/evidence directory is included here.

## Recorded database checkpoint

The operator workspace records completed managed-database installation and checks
through V031 and a dedicated `feedme_api` login. The historical 17:33 UTC checkpoint
covered six private Auth projection functions. At 18:03:49 UTC, a guarded
transactional upgrade completed to seven, after a dry-run rollback confirmed the
old six remained intact. Exact helper bodies, signatures, attributes, trusted
ownership and private execution ACLs were verified after commit.

Actual runtime-role login/effective-ACL checks then covered 79 tables, 798 columns,
ten schemas, 75 functions, seven helpers and all eighteen exact immutable-key
guards. No direct Auth access, role membership, owned objects or schema-CREATE
privilege was admitted; the actual runtime-role metadata-compatibility probe
passed. Five focused restricted-role PostgreSQL cases had passed locally with
synthetic identity/content and a stopped isolated cluster.

Those are database/permission checks, not evidence of Render deployment, production
signup, a live user transaction or Android connectivity. No production user rows,
eligibility, content or email settings were created by these checkpoints, and the
upgrade changed no Auth data or provider MFA settings. The raw
operator receipts remain outside this curated build context.

## Auth and runtime privilege boundaries

The [authority](../server-deploy/feedme/server/src/main/kotlin/com/feedme/server/identity/SupabasePostgresAuthority.kt)
checks current provider user/factor/session/authentication-method facts in the same
database transaction as FeedMe's account/device checks. The seven fixed
[private projections](../server-deploy/feedme/server/src/main/resources/db/provider/supabase-authority.sql)
are owned by the trusted installer, not by the API role. Their fixed search path,
RLS visibility checks and private execution permissions are part of the boundary.
No direct Auth table access or administrator credential belongs in the API process.

Provider fact-table and exact row locks preserve current identity consistency.
The reviewed Auth migration vector is observed, not globally frozen through commit;
an unreviewed vector is refused on the next check. Neither this mechanism nor the
JWT signature freezes provider binaries/settings. Current deployment/session-policy
review is explicit and expires after at most 24 hours; do not blindly extend it.

The live Supabase dashboard showed an enabled 15-minute AAL1 lower-assurance
timeout. This new 294-input bundle supports its explicit non-null
`lowAssuranceTimeoutSeconds: 900`. Any verified factor sets the user's highest
possible assurance to AAL2, regardless of factor type; an AAL1 session then expires
at `created_at + 900 seconds`. Equality is conservatively refused, and the same
deadline caps fresh-password evidence and its final revalidation.

The authority locks user, all factor statuses, session and authentication-method
rows in that order. Validated immediate foreign keys prevent insertion/reparenting
past the locked user; factor row locks prevent concurrent verification or deletion.
More than 100 factors, unknown/null statuses, or incompatible schema are refused.
The source workspace passed 48 focused unit tests, integration-test compilation
and distribution packaging; thirteen focused managed-Auth SQL cases passed in
one stopped isolated cluster. No container or live user journey was exercised.

**Remaining launch boundary:** the seven required helpers are now installed and
verified, but metadata compatibility is not complete live deployment/session-policy
review or a running API. Preserve the observed security setting and configure its
real value; never disable it or set this field to `null` to conceal the policy.
The current image has not been built, and no live signup or connected Android
journey has been accepted. Complete protected runtime configuration, actual
eligibility/content policy, production email, Render HTTPS deployment and the
essential native/end-to-end gates before claiming launch readiness.

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
