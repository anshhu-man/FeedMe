# Development snapshot — 15 September 2026

**18 September backend-only addendum:** [current server deployment source](BACKEND_DEPLOYMENT.md)
is separately packaged under `server-deploy/` with its own current295-file manifest and
203-operation contract. The top-level full-app/source-copy checkpoint described
below remains historical and unchanged; the public inventory additionally records
the new bundle and these reference updates. No refreshed APK, native acceptance,
production API readiness or store publication is implied. Current direction is Google Play
first with Shipaton paused; Supabase/Render technical direction is now approved.

The latest additive packaging change includes one pinned public database CA and
non-root readability/non-writability checks. Its Linux build is still pending;
the confirmed preview below used the preceding294-input context. No password or
account-runtime configuration is included. [Exact current source](BACKEND_DEPLOYMENT.md).

The live Supabase dashboard showed an enabled 15-minute AAL1 lower-assurance
timeout. A second deliberate 294-input backend update now supports explicit
`lowAssuranceTimeoutSeconds: 900` and adds a locked factor-status projection.
The guarded live upgrade to seven helpers completed at 18:03:49 UTC, with exact
helper/ACL, runtime-role login and metadata-compatibility checks. No Auth data or
provider MFA settings changed. These checks and focused source tests do not
establish complete live deployment-policy or API/native acceptance. Preserve
security rather than disable the setting or substitute
`null`. [Operational status](BACKEND_OPERATIONS.md).
The historical full-app checkpoint below remains unchanged.

**18 September, 18:24 UTC hosting checkpoint:** public source commit
`e4b9c6656dfcadfc50ade84ad0bc4f70456ae571` built and deployed successfully as
`feedme-api-preview` on Render's Singapore Free instance ($0, 0.1 CPU, 512 MB,
no card). The deployment took 4 minutes 33 seconds and was live at 18:22:43 UTC.
At 18:24:02 UTC, its HTTPS `/v1/health` returned the expected HTTP/2 503
`SERVICE_NOT_READY`. Only four non-secret listener settings were supplied;
no account configuration, runtime password or keys were uploaded, and Supabase
was not connected. Default TCP health proves the listener, not product readiness.
This supersedes earlier no-service/current-image-not-built statements, not the
historical app evidence below. The preview sleeps after 15 idle minutes;
production budget and account/core/native acceptance remain pending.
[Deployment details](BACKEND_DEPLOYMENT.md), [operational boundary](BACKEND_OPERATIONS.md).

This is the user-requested public reference snapshot, not a release announcement. The original FeedMe idea is intact: effortless healthy cooking, Make Mine, Today, My Plate and Kitchen Circles. All 54 features/98 screens remain documented; 44 features are in V1 and the existing ten are deferred.

## Included

Current shared Kotlin, Android/iOS hosts, local Ktor/server components, source tests and engineering docs; complete blueprint, 900-action ledger, 201-operation API contract, UI prototype and 98 screen exports; milestone/user-action tracking; the new retained Android preview; the unchanged historical demo, original brand story and sanitized historical ZIPs. Repeated local verification directories are excluded, not deleted from the source workspace.

## Latest bounded preview result

The source-workspace build finished at **2026-09-15T04:03:59.906Z** and the full emulator run at **2026-09-15T04:23:26.609Z** (09:53 IST). Root and independent audits verify **360 fresh JVM methods** (260 app + 100 progress), **63 native methods across ten groups**, **76 individually reviewed PNGs**, three APK artifacts and two zero-issue lint reports. The build executed 489 Gradle tasks; all 756 build/native source inputs remained unchanged.

| Source-workspace evidence | SHA-256 |
| --- | --- |
| Build receipt | `567440805bac6c49b86c0519c6c5c537dfe0f045a4a0ee98ed4c8eba5b1f70e4` |
| Native receipt | `84b65e17cad9b111ccbadbe0874517e80db37ef2e5dfd8e8e5f3cf623f68a78d` |
| Source-inventory digest | `652984d5164e5d6c3896012e92bd396ca0f5fd52e66b3ef3bcfe95316f9df47c` |
| Independent native audit | `cdd972e2826ffa763b9a5c65e29ab6e4fd2c6cc13121e7f96f48983875a500d7` |
| Downloadable preview APK | `21755e11413984e877211776f40eaf81c46f048dd095f2c0c21a7322b70eb86b` |

These hashes identify original **local** evidence; the bulky underlying attempts are not bundled in this public tree. This table is a curated summary, not a replacement acceptance receipt or a claim that native tests were rerun against sanitized public sources. [APK and testing instructions](ARTIFACTS.md), [local-evidence boundary](LOCAL_EVIDENCE.md).

The preview uses real retained controllers/native encrypted storage for meal requests, pantry/preferences, cooking, foreground timers, cookbook copies and private text drafts with separately reviewed Save/Publish. Its identity, content and service adapters are synthetic. Publication is text-only and self-audience on the device—not a live post to other users. Missing social records do not force an automatic reset of retained cooking. Earlier failed attempts remain failed in the original workspace; no failed evidence was reclassified or removed.

A separate earlier focused JVM run passed 1,571 contracts/transport/mealflow/server methods. Its JVM/production inputs were unchanged by the two subsequent Android-test corrections; the complete 756-file inventory differs in those two instrumentation files. The 1,571 methods were **not rerun** in this 360-method build. These are separate, non-overlapping module results, not one fresh all-project invocation.

All 76 captures were individually reviewed across four reviewers. No blocking overlap or clipped dialog action was found at normal-font 1080×1920. Tall headers, technical wording, isolated-host status-icon contrast and small copy issues remain polish. This does not establish large-font/keyboard/TalkBack, physical-device, API26, full OS-process-death, iOS or release acceptance.

## Still required

Live signup/login and account bootstrap, approved providers and deployment, licensed/reviewed launch content, photo/audience/circle/feed integration, remaining V1 functions, moderation/privacy operations, approved monetization and RevenueCat, physical-device/iOS tests, signing and store submission remain open. Shared Kotlin is not proof of iOS parity.

[Supabase + Ktor on Render](../feedme/docs/BACKEND_PROPOSAL.md) is a proposal awaiting technical-direction approval, not an installed or paid service. No provider account, spending, region, cloud deployment or store release is approved by publishing source. [Milestones](../feedme/docs/RELEASE_PLAN.md), [current build status](../feedme/docs/BUILD_STATUS.md), [user actions](../feedme/docs/USER_ACTIONS.md).

Earlier checkpoints, including the 14 September 6d.1 result of 1,592 Kotlin/server/database, 142 Node and 280 Android tests, are historical component evidence. Their fixed inventories and “remaining work” statements describe those dates, not the current implementation or a current clone-wide command. The historical demo remains byte-identical and is not the retained preview.

## Privacy and reproducibility

Textual home paths are replaced with `/Users/LOCAL_USER` and equivalent encoded forms. Unrelated installed-app instrumentation lines are omitted from public diagnostic copies. Original workspace/evidence bytes stay unchanged. [The snapshot manifest](SNAPSHOT_MANIFEST.json) records copied source/published hashes and transformation counts, generated references and preserved files; [history metadata](History/HISTORY_MANIFEST.json) covers the two sanitized archives. Earlier Git commits are not rewritten.

Remaining older public receipts can refer to original local bytes that differ from redacted copies. They are provenance, not byte-identical acceptance receipts for this tree. Six repeated diagnostic subtrees are excluded; four build/test fixtures remain. The [portable ninth-parser fixture](Fixtures/ninth-parser/README.md) contains exactly nine unchanged sources and a new provenance record, **not** the original ninth receipt. Its standalone command replays ten frozen parser tests without that receipt; it neither weakens the main historical verifier nor certifies native/current-source acceptance.

Use [setup](SETUP.md) for ordinary build and public-reference checks. Do not treat wildcard historical script tests or old full-checkpoint runners as portable current verification. Build caches, local SDK configuration, credentials, signing material, temporary database clusters, unrelated Career projects and TasteEcho's previous concept are excluded.

No paid CI, GitHub Pages, cloud deployment, store upload or Devpost submission is enabled by this snapshot. The active implementation workspace is separate; later edits require another deliberate export and review.
