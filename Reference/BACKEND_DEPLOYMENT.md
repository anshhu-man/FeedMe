# Backend deployment source

## Current V091 source handoff — 23 September 2026 IST

The credential-free [`server-deploy/`](../server-deploy/) handoff is refreshed
from the current production source closure: **734 inputs / 11,134,003 bytes**,
all **V001–V091** application migrations, the locked 207-operation server
contract, public build metadata, reviewed Dockerfile and pinned public Supabase
CA. Its context-manifest SHA-256 is
`dbea124f80c6e883393af9ef47c2f242d63ca4f67bdc07d73b706f70f447f750`.

V091 adds the restrictive staff-flag serving path: an environment-scoped flag
inventory, immutable action history, durable command receipt/outbox agreement,
forced-RLS protection and only the complete enabled-to-disabled transition with
rollout zero. The existing canonical list/PATCH routes and original confirmed
staff action use current workforce, MFA and moderator admission. Enablement,
partial rollout, repeat kills and stale revisions remain closed.

The current packaged runtime validator also reports each staff capability
explicitly—session, catalog drafts, catalog review, catalog publication and
moderation—without exposing policy identifiers. Missing private reviewed runtime
configuration therefore remains visible as a closed deployment gate rather than
being mistaken for an active staff service.

The handoff now includes the explicit optional staff-moderation serving grant
package. It supplies only the reviewed provider/MFA observations, workforce and
moderator reads, report workflow capabilities, exact source-content reads and
three aggregate media-health columns used by the existing handlers. It creates
no role, credential, staff actor, moderator enrollment, policy or product
activation. Four real PostgreSQL role-boundary methods and all 12 existing staff
workflow methods pass. Hosted Supabase has not received this package.

V090 adds a restricted, read-only `adminGetHealth` backend for the existing
staff Incident console. Current Supabase workforce/MFA/moderator admission,
environment-scoped outbox and media aggregates, canonical response validation,
immutable `health-read` auditing and a final authority check share one database
transaction. Free V1 has no entitlement processor or incident registry, so the
snapshot remains `degraded`; enabling either future subsystem without a real
metric source fails closed. No pause, replay, flag mutation or on-call authority
is added. Twelve staff PostgreSQL, six migration and 28 staff-browser methods pass.

V089 adds only exact-owner deletion of account-export data that never crossed a
durable upload-dispatch or verification marker. Dispatched, verified, foreign,
duplicated or malformed export history remains a deletion hold. Direct DELETE
and TRUNCATE stay denied, and the ordinary API receives no erasure privilege.
The source passed 6 core-erasure and 15 runtime-role real PostgreSQL methods, 11
receipt-ownership methods and 15 current-core package/ACL checks.

The production-source delta from the preceding V088 handoff closes a protected
configuration-export mismatch. The packaged validator now accepts the same
explicit optional Supabase media and account-export environment families as the
actual runtime parser, and its redacted report separately exposes AI, media,
account-export and account-deletion configuration status. No optional capability
is defaulted or enabled; no credential value is reported or published.

This refresh also fixes a current-schema admission mismatch in the optional
notification Inbox. Its compatibility check now pins the shared account-erasure
helper to the current V088 definition rather than the superseded V076 body. This
is a startup/readiness correction only; it does not weaken erasure guards, enable
notifications or complete account deletion.

The exact copied tree passed byte-for-byte comparison with the independently
verified temporary export apart from ignored local build caches. All 734 files
and the manifest passed the publication policy and exact SHA/size scan; the 33
publication-policy methods also passed. An offline JDK 17 build then passed
`:verifyReleaseScope :server:installDist`: 44 included and 10 deferred features,
all 98 canonical screens retained, dependency inventory accepted, 208 schemas /
160 paths / 207 operations contract-locked, and all 18 requested Gradle tasks
executed. The free-V1 runtime-surface gate scanned 18 dependency sources and 569
product sources. The selected Android/shared production source in this handoff is
evidence for that gate only; it is not compiled or packaged into the server.

No credential, cursor key, AI token, private Android configuration, signing
material, build output or local evidence is included. This is a buildable source
handoff, not a container image or live deployment. Hosted Supabase remains
current through V088, but the existing Render preview still runs the historical
closed V032 dependency-hold revision. Current reviewed runtime configuration,
provider/session-policy approval, recipe content and connected end-to-end
acceptance remain required before replacing that service or calling the API live.
Do not deploy this V091 source until the controlled hosted V089–V091 database
rollout, optional staff grants, runtime configuration and postflight are complete.

## Current Render connection attempt — 19 September 2026 UTC

The operator explicitly approved uploading the existing restricted runtime
credential to `feedme-api-preview` and deploying only the closed connection check.
That approval supersedes the earlier local-only decision; it does not authorize
public product access, new database migrations/grants or paid infrastructure.
The three saved Render values were compared in memory with the protected local
import and matched exactly. No credential is included in this repository.

Deployment `dep-damtvluk1f9s73eni430` at commit `4d4f559` built successfully
(2m9s, 17 tasks), but exited during startup and was not accepted as live.
The following source change adds constant-name startup failure stages only:
no exception messages, configuration values or database contents are logged.
All existing checks and the closed public-request boundary remain unchanged.
The database remains on V032; unfinished V033/V034 feature work is not included.

## Previous dependency-hold source increment — historical

The curated backend bundle now contains **299 build inputs (5,666,558 bytes)**.
Its [context manifest](../server-deploy/context-manifest.json) SHA-256 is
`c0bc25a984c0588012cc2bd0f00b7558733ca9fd125eec6e1f48dc7f12303e61`.
Two production files were added and six changed; the other 291 inputs are
byte-identical to the preceding Terms-recovery bundle. No inputs were removed.

The new explicit `dependency-hold` mode checks real configured database metadata
and public signing-key dependencies before binding a listener, without constructing
product stores or accepting product requests. Every public request remains
503 `SERVICE_NOT_READY`; exact `GET /v1/health` can additionally report coarse
dependency availability. An available dependency is not product readiness or
permission to sign in, mutate accounts, plan, cook or save. There is no fallback
to an accepting or unconfigured listener after a failed dependency check.
Public-key validation also accepts the provider's optional boolean WebCrypto
`ext` field; private key material and unknown key fields remain refused.

The source workspace passed 65 focused server checks and a real packaged-runtime
connection to Supabase with the restricted API role, exact migration/provider
metadata and public JWKS. The local runtime kept health and all product/unknown
requests at 503 and was stopped afterward. Startup took 33.568 seconds; the
dependency-health check took 33.430 seconds. This is operational connectivity,
not product latency or user-journey acceptance. No Render image build/deployment
or connection is claimed for this increment. Protected configuration and hosting
acceptance are separate. No private preparation helper,
configuration, credential, `.local` file, APK or raw test evidence is exported.
The 8,319 historical full-app source copies and generated reference lists remain
unchanged; this is not a mobile release or a refreshed full-app snapshot.

The earlier local-only decision applied at this checkpoint. It is superseded only
by the narrow closed-deployment approval recorded above.

## Preceding Terms-recovery checkpoint (historical)

This is a separate curated backend source bundle. The latest recovery of historical
Terms receipts is not deployed; the older public-CA bundle still backs the unconfigured hosting
preview. Neither is a refreshed full-app snapshot or production-ready API.
The existing top-level `feedme/` and the manifest's source-copy
entries remain at their earlier checkpoint; its retained inventory includes this
separately identified addendum. Use [`server-deploy/`](../server-deploy/)
for this backend build, not that older tree.

### Previous exact source

The preceding context bound 297 build input files (5,650,097 bytes). Its historical manifest SHA256 was:
`bf158f70f2005dc8aa0e3ef791aa0aea96ebdfea570cc013eec90d45c2c22a48`.
The bundle preserves `feedme/` and sibling `outputs/` so contract and release-scope
checks resolve their real inputs. It includes the current account runtime and
205-operation contract, V001–V032 migrations, managed Auth projections and explicit
runtime grants. No `.local`, credentials, caches, APKs, test fixtures/results or
unrelated workspace trees are included. The Gradle wrapper JAR is the sole
checked-in build-tool binary; no built server distribution is copied.

**19 September IST — preceding historical-receipt recovery increment:** exact
previously committed Terms acceptance can now be recovered from its permanent
audit after notice rollover or response-cache expiry. The original account,
device, provider session, command key and body must still match; current authority
is rechecked. Recovery does not accept the new notice, rewrite consent, refresh a
cache or grant private access. See [Terms scope and limits](ACCOUNT_TERMS.md).

The source workspace passed 30 selected server checks (10 unit, 15 Terms SQL and
5 Terms HTTP) and 92 Node checks. Separately, 80 selected shared checks and a debug
APK build passed locally; those mobile sources/APK are not published in this
server-only update and are not device or live-flow acceptance. V032, its narrow
runtime grants and the runtime probe were already installed/verified at the prior
20:35 managed-database checkpoint; this increment made no database changes.
No new image was built or deployed. Render remains on the older public-CA source
below with Auto-Deploy off.

**Historical: 19 September IST / 18 September 19:25 UTC — initial Terms-only source increment:**
two new inputs and fourteen changed inputs added authenticated current-Terms read
and explicit acceptance, immutable evidence and restricted runtime grants. The
other 281 inputs, including every V001–V031 migration, remained unchanged. The
source workspace passed 115 focused JVM, 16 isolated PostgreSQL/HTTP and 91 Node
checks. Both temporary databases stopped. See [scope and limits](ACCOUNT_TERMS.md).
At that checkpoint V032 was not yet installed on Supabase; its later installation
is recorded above. That source context was not image-built or deployed.

For the preceding MFA refresh, all 294 source and destination hashes were checked after copying. Relative to the
previous 294-input bundle, only the provider authority and the two private
provider/runtime SQL resources changed for lower-assurance timeout support. The
other 291 build inputs, including V001–V031, remain byte-identical. No source inputs
were edited by this refresh and no bundle files were added or removed.

**Historical accepted public-CA increment:** the preceding 295-input context adds exactly one
public certificate and changes only the Dockerfile relative to that 294-input
MFA bundle; 293 prior build inputs are unchanged. The reviewed public Supabase CA
has DER SHA-256 `807025ad50d4ed219d2c9c7d299c004f824eb00cf7f65afef607d07b72e6cafa`.
It is copied root-owned/read-only to `/opt/feedme/trust/prod-ca-2021.crt`.
The Dockerfile's UID/GID 10001 readability and file/parent non-writability checks
passed in the real cloud build recorded below. A public CA is not a password or
grant of database access.

The 18:24 UTC preview checkpoint below superseded the earlier statement that its
then-current bundle had not been image-built or started. It proves source-to-Linux-build,
host startup and HTTPS reachability, not configured account/core functionality.
The separate SQL and managed-database checks are summarized in
[Backend operations](BACKEND_OPERATIONS.md); that database was not connected to
this preview. No connected Android journey or production readiness is implied.

## Public-CA cloud acceptance — 18 September 2026, 18:45 UTC

The same `feedme-api-preview` service manually deployed public commit
`b0fadf68f03ac0f335e12b4aedd9e222bb79f1ff` as
`dep-damoasid0e5s73d4dcg0`. Deployment started at 18:39:46 UTC and was live at
18:43:43 UTC: **237 seconds (3 minutes 57 seconds)**. Gradle reported a successful
2-minute-17-second build across 17 tasks. Build step `#24` passed the actual
UID/GID 10001 CA-readability and file/parent non-writability checks at 18:42:30 UTC
(`DONE 0.1s`). The server logged its unconfigured listener at 18:43:19 UTC.

At 18:45:48 UTC, HTTPS `/v1/health` returned **503 `SERVICE_NOT_READY`**, trace
`91954fba-d24d-4f4e-9756-146426d42dc2`. This accepts the public trust-root packaging
and non-root image checks, not database connectivity. No database password,
account-runtime configuration or keys were uploaded; no hosting plan or database
settings changed. Supabase remains unconnected. The earlier preview below is
retained as historical evidence; service settings remain unchanged.

## First Render preview — 18 September 2026, 18:24 UTC (historical)

Service `feedme-api-preview` (`srv-damo0nsri2ms73b601b0`) was created at
18:18:10 UTC from public Git source commit
`e4b9c6656dfcadfc50ade84ad0bc4f70456ae571`. Deployment
`dep-damo0okri2ms73b6057g` succeeded in 4 minutes 33 seconds; its Linux build reported
`BUILD SUCCESSFUL` in 2 minutes 19 seconds across 17 tasks. The packaged server
logged its explicitly unconfigured container listener at 18:22:19 UTC, and Render
reported it live at 18:22:43 UTC. Public Git Repository deployment required no
OAuth connection or new repository grant. Auto-Deploy is off; deployments are manual.

| Setting | Value |
| --- | --- |
| Runtime / Language | Docker |
| Root Directory | `server-deploy` |
| Dockerfile Path | `Dockerfile` |
| Docker Build Context | `.` |
| Docker Command override | Leave unset; use the packaged entrypoint |
| Region / instance | Singapore / Free, 0.1 CPU, 512 MB |
| Platform health check | Default TCP only, not product readiness |

These follow Render's [root-directory semantics](https://render.com/docs/monorepo-support)
and [Docker support](https://render.com/docs/docker). Do not select
`server-deploy/feedme` as root: Render excludes the sibling contract files.
This preview selected the $0 Free instance without a card; a production budget
and hosting choice remain pending. Free web services sleep after 15 minutes
without inbound traffic; see [Render Free services](https://render.com/docs/free).

Only four non-secret environment settings were supplied:

| Variable | Value |
| --- | --- |
| `FEEDME_SERVER_MODE` | `container` |
| `PORT` | `10000` |
| `FEEDME_MINIMUM_APP_VERSION` | `0.1.0` |
| `FEEDME_SERVER_MAX_IN_FLIGHT_REQUESTS` | `16` |

No `FEEDME_ACCOUNT_*` settings, runtime password, cursor keys or other secrets were
uploaded. Supabase was never connected to this preview. At 18:24:02 UTC, an HTTPS
GET to [the preview health endpoint](https://feedme-api-preview.onrender.com/v1/health)
returned HTTP/2 **503**, `application/problem+json`, code `SERVICE_NOT_READY`.
That is the correct unconfigured response: the [TCP health check](https://render.com/docs/health-checks)
proves a listening process, not account/core readiness. Do not change that 503 to
manufacture readiness; product operations remain unavailable.

## Still required

Managed-database history through V032 and its narrow runtime grants are installed;
the current source-only receipt-recovery change adds no migration. Historically,
at 18:03:49 UTC on 18 September, the guarded transactional upgrade from
six to seven Auth projections completed, including the factor-status projection.
Exact helper-source/ownership/ACL checks, actual runtime-role login/effective-ACL
verification and the metadata-compatibility probe passed. No Auth data or provider
MFA settings were changed. Do not rerun fresh provisioning merely to deploy this
source; these bounded checks do not establish live API or deployment-policy acceptance.

The live Supabase dashboard showed the AAL1 lower-assurance timeout enabled at
15 minutes. This deliberate bundle update supports the explicit non-null
`lowAssuranceTimeoutSeconds: 900`: any verified factor makes an AAL1 session expire
at its creation time plus that timeout, with equality conservatively refused.
All factor statuses are locked and overflow is refused. The source workspace
passed 48 focused unit tests, integration-test compilation and distribution
packaging, plus thirteen focused managed-Auth SQL cases in one stopped isolated
cluster. These are not live deployment-policy or user-flow acceptance. Preserve
the provider security setting and declare its real value; never substitute `null`
to conceal it. The earlier schema-only probe did not establish that live policy.

Still required: approved immutable Terms/Privacy, truthful current provider/session-policy
review, actual account/child-eligibility policy and reviewed content, stable protected runtime configuration, production
email sender, configured production HTTPS acceptance, Android public configuration and
essential sign-in/isolation/cooking/Saved checks, including safe offline sign-out
while preserving unresolved originals. Supabase Auth and the Ktor API
have different origins. Never include a DB password in the app/image or disable
confirmation and readiness checks to make an unconfigured service appear live.

The preview is a real source/build/hosting/TLS milestone. It is not production
account, sign-in, cooking/Saved, Android or Play acceptance. A successful image
build and listening Free service do not establish store readiness or public launch.
