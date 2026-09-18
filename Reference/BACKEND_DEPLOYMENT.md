# Backend deployment source — 18 September 2026

This is a separate curated backend source bundle, now used by an unconfigured
hosting preview, not a refreshed full-app snapshot or production-ready API.
The existing top-level `feedme/` and the manifest's source-copy
entries remain at their earlier checkpoint; its retained inventory includes this
separately identified addendum. Use [`server-deploy/`](../server-deploy/)
for this backend build, not that older tree.

## Exact source

[`context-manifest.json`](../server-deploy/context-manifest.json) binds 294 build
input files (5,608,900 bytes). Manifest SHA256:
`0d4edf4685460252d77e3e8de0793ef0832ff3ac84bc8ee920e62bf9069ea006`.
The bundle preserves `feedme/` and sibling `outputs/` so contract and release-scope
checks resolve their real inputs. It includes the current account runtime and
203-operation contract, V001–V031 migrations, managed Auth projections and explicit
runtime grants. No `.local`, credentials, caches, APKs, test fixtures/results or
unrelated workspace trees are included. The Gradle wrapper JAR is the sole
checked-in build-tool binary; no built server distribution is copied.

All 294 source and destination hashes were checked after copying. Relative to the
previous 294-input bundle, only the provider authority and the two private
provider/runtime SQL resources changed for lower-assurance timeout support. The
other 291 build inputs, including V001–V031, remain byte-identical. No source inputs
were edited by this refresh and no bundle files were added or removed.

The 18:24 UTC preview checkpoint below supersedes the earlier statement that this
bundle had not been image-built or started. It proves source-to-Linux-build,
host startup and HTTPS reachability, not configured account/core functionality.
The separate SQL and managed-database checks are summarized in
[Backend operations](BACKEND_OPERATIONS.md); that database was not connected to
this preview. No connected Android journey or production readiness is implied.

## Render preview — 18 September 2026, 18:24 UTC

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

Managed-database history through V031 and the dedicated runtime role were already
installed. At 18:03:49 UTC on 18 September, the guarded transactional upgrade from
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

Still required: truthful current provider/session-policy review, actual account
policy and reviewed content, stable protected runtime configuration, production
email sender, configured production HTTPS acceptance, Android public configuration and
essential sign-in/isolation/cooking/Saved checks. Supabase Auth and the Ktor API
have different origins. Never include a DB password in the app/image or disable
confirmation and readiness checks to make an unconfigured service appear live.

The preview is a real source/build/hosting/TLS milestone. It is not production
account, sign-in, cooking/Saved, Android or Play acceptance. A successful image
build and listening Free service do not establish store readiness or public launch.
