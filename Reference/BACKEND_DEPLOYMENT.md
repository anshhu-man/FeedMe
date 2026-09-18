# Backend deployment source — 18 September 2026

This is a separate curated backend source bundle, not a refreshed full-app snapshot
or deployed service. The existing top-level `feedme/` and the manifest's source-copy
entries remain at their earlier checkpoint; its retained inventory includes this
separately identified addendum. Use [`server-deploy/`](../server-deploy/)
for this backend build, not that older tree.

## Exact source

[`context-manifest.json`](../server-deploy/context-manifest.json) binds 294 build
input files (5,605,796 bytes). Manifest SHA256:
`39d3255255af252b5b12196b23592d543a41abe69ff317a24ab523f8a648ea37`.
The bundle preserves `feedme/` and sibling `outputs/` so contract and release-scope
checks resolve their real inputs. It includes the current account runtime and
203-operation contract, V001–V031 migrations, managed Auth projections and explicit
runtime grants. No `.local`, credentials, caches, APKs, test fixtures/results or
unrelated workspace trees are included. The Gradle wrapper JAR is the sole
checked-in build-tool binary; no built server distribution is copied.

All 294 source and destination hashes were checked after copying. Relative to the
previous bundle, only the migration registry and provider authority changed;
V031 and the two private provider/runtime SQL resources were added. The other
289 build inputs, including V001–V030, remain byte-identical. No source inputs
were edited by this refresh and no bundle files were removed.

This exact refreshed bundle has not been image-built or started. Earlier Linux
image and Gradle dry-run results describe older sources, not this manifest. The
source workspace's focused SQL acceptance and actual managed-database checkpoint
are summarized in [Backend operations](BACKEND_OPERATIONS.md); neither proves a
live HTTPS API or connected Android journey. Public inventory verification is a
separate publication step, not implied by this local preparation.

## Render settings after publication

| Setting | Value |
| --- | --- |
| Runtime / Language | Docker |
| Root Directory | `server-deploy` |
| Dockerfile Path | `Dockerfile` |
| Docker Build Context | `.` |
| Docker Command override | Leave unset; use the packaged entrypoint |

These follow Render's [root-directory semantics](https://render.com/docs/monorepo-support)
and [Docker support](https://render.com/docs/docker). Do not select
`server-deploy/feedme` as root: Render excludes the sibling contract files. This
table does not select a service plan, name, region, public host or billing commitment.

## Still required

Managed-database history through V031, the six private Auth projections and the
dedicated runtime role were installed and checked in the operator workspace on
18 September. Do not rerun provisioning merely to deploy this source.

**Provider-policy blocker:** the live Supabase dashboard showed the AAL1
lower-assurance timeout enabled at 15 minutes. This exact bundle rejects any
non-null `lowAssuranceTimeoutSeconds`. The schema-only probe did not verify the
actual deployment/session policy and is not proof that this configuration can
launch. Faithful support is required before API launch; never disable that
security setting or supply `null` to conceal it. Any implementation change needs
a deliberate new bundle and verification; this 294-input manifest stays frozen.

Still required: truthful current provider/session-policy review, actual account
policy and reviewed content, stable protected runtime configuration, production
email sender, HTTPS service creation/acceptance, Android public configuration and
essential sign-in/isolation/cooking/Saved checks. Supabase Auth and the Ktor API
have different origins. Never include a DB password in the app/image or disable
confirmation and readiness checks to make an unconfigured service appear live.

Render sign-in is verified, but no FeedMe service was present. Publishing this
source is not a Render build or deployment. A successful image build alone does
not establish store readiness or public launch.
