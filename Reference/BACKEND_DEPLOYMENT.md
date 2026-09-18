# Backend deployment source — 18 September 2026

This is a separate curated backend source bundle, not a refreshed full-app snapshot
or deployed service. The existing top-level `feedme/` and the manifest's source-copy
entries remain at their earlier checkpoint; its retained inventory includes this
separately identified addendum. Use [`server-deploy/`](../server-deploy/)
for this backend build, not that older tree.

## Exact source

[`context-manifest.json`](../server-deploy/context-manifest.json) binds291 build
input files (5,579,693 bytes). Manifest SHA256:
`a22ea882af14ff71f16ef9cef573e33fad1069b001bc7f0ca45c0fc701734df2`.
The bundle preserves `feedme/` and sibling `outputs/` so contract and release-scope
checks resolve their real inputs. It includes the current account runtime and
203-operation contract. No `.local`, credentials, caches, APKs, test results or
unrelated workspace trees are included.

Source hashes and the repository's publication-policy scan passed after copying.
One private hardcoded SDK home path was replaced in source by the JVM user's home
directory; normal SDK resolution on this Mac is unchanged. This is not full
Android-native portability and changes its recipe hash; earlier native evidence
remains historical. An offline JDK17 Gradle dry-run resolved the backend task
graph. Tasks were skipped, not compiled or tested. The earlier Linux image build
predates this one-line edit; this exact bundle needs host build/runtime acceptance.

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

Accepted protected DB access, migrations and least-privilege runtime role; actual
account policy/content and stable runtime secrets; production email sender; HTTPS
service creation and acceptance; Android public configuration and essential
sign-in/isolation/cooking/Saved checks. Supabase Auth and the Ktor API have different
origins. Never include a DB password in the app/image or disable confirmation and
readiness checks to make an unconfigured service appear live.

Render sign-in is verified, but no FeedMe service was present. Publishing this
source is not a Render build or deployment. A successful image build alone does
not establish store readiness or public launch.
