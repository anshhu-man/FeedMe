# Backend operations — public handoff

This document describes the separately curated
[server bundle](BACKEND_DEPLOYMENT.md), not the older top-level app snapshot.
No credential, private runtime configuration, local operator helper or raw local
test/evidence directory is included here.

## Current source/runtime boundary — 23 September 2026 IST

The public source handoff now contains the exact current V001–V088 server closure
and compiles offline with JDK 17. Hosted Supabase is independently current through
V088, and the restricted `feedme_api` role has passed its exact current-core ACL
check. Public Supabase settings also report Google OAuth enabled, email enabled,
anonymous sign-in disabled, signup open and mail auto-confirm disabled.

The latest source-only increment aligns the protected Render-environment exporter
with the actual runtime parser for optional Supabase media and account-export
settings. Its fixed-schema redacted result now distinguishes AI, media, export and
deletion configuration instead of allowing an incomplete configuration to appear
feature-complete. Optional files remain owner-only, exact and absent by default;
this source contains none of their secret values and activates nothing.

The same refresh corrects notification Inbox admission from the original V076
erasure-helper body to the actual current V088 body. Without this correction, a
correctly migrated V088 database could be refused during configured runtime
startup. The public source compiles with this fix; no hosted setting, database
row, runtime secret or notification state changed.

These facts do not make the API live. The Render service is not verified on this
source handoff and its public health endpoint returned no response bytes in the
latest bounded observation. The current account runtime configuration,
session-policy review, OAuth mobile return, content, legal/deletion operation and
connected app acceptance are not in this public source bundle. Do not deploy the
V088 handoff with the historical V032 runtime environment or infer readiness from
successful compilation.

## Current closed deployment attempt — 19 September UTC

The operator has now approved storing the existing restricted `feedme_api`
credential in the existing free Render preview and deploying connection-check
mode only. The saved configuration and credential match the approved local import;
they are not published here. Public account/product access remains unauthorized.

The first approved deployment (`dep-damtvluk1f9s73eni430`, source `4d4f559`)
built successfully but failed closed during startup. Cloud connectivity is not
yet verified. Constant startup-stage diagnostics are being added without logging
secrets or weakening checks. No database migrations/grants, provider settings,
paid plan, public app launch or Play publication are part of this increment.

## Previous dependency-hold source — historical

The latest source-only bundle has **299 inputs, 5,666,558 bytes**, with context
manifest SHA-256 `c0bc25a984c0588012cc2bd0f00b7558733ca9fd125eec6e1f48dc7f12303e61`.
It adds an explicit dependency-hold configuration and bounded operational probe:
real database metadata and public signing keys must pass before listener startup.
It does not instantiate account/product stores, use placeholder product policies,
or grant account, consent or eligibility authority. Public-key validation accepts
the provider's optional boolean WebCrypto `ext` field without accepting private
key material or arbitrary unknown fields.

The hold intercepts public ingress before product routing, authentication, body
consumption and store dispatch. All requests remain 503 `SERVICE_NOT_READY` with
`X-FeedMe-Access: held`; only exact `GET /v1/health` probes dependencies and adds
`X-FeedMe-Dependencies: available` or `unavailable`. The database diagnostic is
rollback-only metadata inspection, not current-user authorization. Even a positive
diagnostic is not signup, cooking/Saved, connected Android or launch acceptance.

The packaged runtime passed an actual restricted-role connection to Supabase from
the source computer. Health reported dependencies available but stayed 503; a
malformed bootstrap request and unknown path were refused, and the process stopped.
Only the public CA filesystem path was adapted locally. 65 selected server checks
passed. The measured 45-second diagnostic budget is not a product performance goal.
Supabase's public JWKS uses a 10-minute edge cache; the held configuration accounts
for that age while retaining certificate, key-type and signature validation.
No Render connection, image build, deployment, automatic policy review renewal or
database change is claimed by this source refresh. The latest recorded hosting
checkpoint remains the historical unconfigured public-CA preview below until
separate verification is recorded. Private runtime inputs and local helpers are
not in this publication. Two new and six changed backend inputs leave 291
unchanged; all 8,319 historical full-app copies and generated lists are preserved.

The local-only decision at this historical checkpoint has been explicitly
superseded by the narrow closed-deployment approval above.

## Preceding historical Terms-receipt recovery — 19 September IST

The preceding 297-input bundle added [exact historical receipt recovery](ACCOUNT_TERMS.md)
for already committed Terms acceptance. Current account/device/provider authority,
the original command fingerprint and immutable notice descriptor must match.
Recovery bypasses neither current authentication nor missing evidence, and does
not change consent, eligibility, profile/device state or the response cache.

V032, its narrow SELECT/thirteen-column INSERT grants and the runtime probe were
already installed/verified at the preceding 20:35 managed-database checkpoint.
There are no new database changes in this increment. The bundle is source-only:
297 inputs, 5,650,097 bytes, context-manifest SHA256
`bf158f70f2005dc8aa0e3ef791aa0aea96ebdfea570cc013eec90d45c2c22a48`.
Thirty selected server checks (10 unit, 15 Terms SQL, 5 Terms HTTP) and 92 Node
checks passed. Eighty selected shared checks and a debug APK build passed only
locally; mobile changes are not part of this public server bundle or device/live
acceptance. The Render image/source below is unchanged, with Auto-Deploy off.
Legal, policy, email, reviewed content, configured runtime, safe offline sign-out
and connected-app acceptance remain open.

## Public-CA cloud acceptance — 18 September 2026, 18:45 UTC

The existing `feedme-api-preview` manually deployed public source
`b0fadf68f03ac0f335e12b4aedd9e222bb79f1ff` as
`dep-damoasid0e5s73d4dcg0`, from 18:39:46 to 18:43:43 UTC (237 seconds).
Gradle passed in 2 minutes 17 seconds across 17 tasks. At 18:42:30 UTC, build
step `#24` passed the actual UID/GID 10001 checks: the packaged public CA was
readable, and its file and parent directories were non-writable (`DONE 0.1s`).
The unconfigured listener started at 18:43:19 UTC.

At 18:45:48 UTC, HTTPS `/v1/health` returned **503 `SERVICE_NOT_READY`**, trace
`91954fba-d24d-4f4e-9756-146426d42dc2`. No database password, runtime configuration
or keys were uploaded, and no database or hosting-plan changes occurred.
Supabase remains unconnected: this is accepted trust-root/image packaging, not
account-core readiness. The historical 295-input source and CA identity/path are described in
[Backend deployment](BACKEND_DEPLOYMENT.md). The first preview remains recorded below.

## First unconfigured hosting preview — 18 September 2026, 18:24 UTC (historical)

Render successfully deployed public commit
`e4b9c6656dfcadfc50ade84ad0bc4f70456ae571` as `feedme-api-preview` in Singapore
on a $0 Free instance (0.1 CPU, 512 MB; no card). Deployment
`dep-damo0okri2ms73b6057g` took 4 minutes 33 seconds. The Linux build succeeded in
2 minutes 19 seconds across 17 tasks; the server logged its unconfigured listener
at 18:22:19 UTC and Render marked it live at 18:22:43 UTC. See the exact service,
source and Docker settings in [Backend deployment](BACKEND_DEPLOYMENT.md).

This uses Public Git Repository deployment without OAuth/new repository grants;
Auto-Deploy is off. Only container mode, port `10000`, minimum app version `0.1.0`
and request limit `16` were supplied. No `FEEDME_ACCOUNT_*` configuration, runtime
password, cursor keys or other secrets were uploaded. The preview never connected
to Supabase. At 18:24:02 UTC, HTTPS GET
[`/v1/health`](https://feedme-api-preview.onrender.com/v1/health) returned HTTP/2
503, `application/problem+json`, code `SERVICE_NOT_READY`.

The default [TCP health check](https://render.com/docs/health-checks) establishes
listener availability only. The 503 honestly preserves unavailable account/core
readiness and product operations. This checkpoint supersedes earlier no-service/
image-not-built wording, but does not prove production signup, account isolation,
cooking/Saved or Android/Play readiness. [Free services](https://render.com/docs/free)
sleep after 15 minutes without inbound traffic; production hosting budget remains
pending. No paid deployment commitment is implied.

## Recorded database checkpoint

The latest recorded managed-database checkpoint includes V032, the dedicated
`feedme_api` login, its narrow Terms evidence grants and a successful runtime
probe. The following earlier counts describe the V031 checkpoint, not a new
execution or the current schema inventory. The historical 17:33 UTC checkpoint
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
timeout. The preceding 294-input MFA update, retained in the current 297-input bundle, supports its explicit non-null
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
one stopped isolated cluster. Those tests did not exercise a live user journey;
the later unconfigured container preview is a separate hosting checkpoint above.

**Remaining launch boundary:** the seven required helpers are now installed and
verified, but metadata compatibility is not complete live deployment/session-policy
review or configured account API readiness. Preserve the observed security setting and configure its
real value; never disable it or set this field to `null` to conceal the policy.
The older public-CA source built and started as an unconfigured preview; the current
historical-receipt recovery source has not been image-built or deployed. No
live signup or connected Android journey has been accepted. Complete protected
runtime configuration, actual eligibility/content policy, production email,
configured production HTTPS deployment, approved immutable legal documents,
safe offline sign-out preserving unresolved requests, and the
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
`verify-full` and the reviewed public CA at `/opt/feedme/trust/prod-ca-2021.crt`.
Its readability and non-writability under UID 10001 passed in the cloud build;
actual runtime configuration and a Render-to-Supabase connection remain unverified.

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
configured production image/runtime checks, HTTPS acceptance and the connected
Android sign-in/isolation/cook/Saved journey. A successful account-core health
check remains a bounded component result, not whole-V1 or Play release approval.
