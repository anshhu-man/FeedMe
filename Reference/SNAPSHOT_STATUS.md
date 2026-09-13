# Published development checkpoint

14 September 2026 (India time). This repository is the user-requested public snapshot of FeedMe work to date, not a release announcement. It refreshes the initial 13 September publication with the subsequent implementation, documentation and retained test evidence.

## Included

Shared Kotlin and native Android/iOS hosts, the local server foundation, tests and scripts; the complete 54-feature/98-screen blueprint; the full UI prototype and screen exports; architecture, data/API/event contracts, security plans, milestone/task tracking, retained test evidence, the historical Android demo APK, original brand story and sanitized historical ZIP copies.

## Verification boundaries

The latest source-workspace checkpoint is [retained credential/private-data recovery owners, slice 6d.1](../feedme/docs/STARTUP_RECOVERY_OWNERS.md). Full source-bound verification passed at **2026-09-13T19:31:47.969Z** (14 September in India): **1,592 Kotlin/server/PostgreSQL tests, 142 Node checks and 280 isolated Android tests**, plus five freshly built Android libraries with zero-issue lint. The [retained receipt](../feedme/docs/verification/startup-recovery-owners/verification.json) binds 280 source inputs, 13 artifacts and 148 evidence files. These are component-level results from the source workspace, not a new native test run of the sanitized publication tree.

The formerly unfinished native SQLite VFS hookup is now compiled and included in the passing component checks: 39 injected VFS labels and five controlled process stages. Its C helper is confined to the storage test APK and absent from the five production libraries, session test APK and historical demo. Some internal Kotlin fault hooks remain in library bytecode; this is not a claim that every test-related symbol is absent. Engine fault injection and controlled reopen are not physical power-loss or hard-kill verification.

Earlier failed attempts remain included, including the initial 6d.1 run that failed six credential-test path-counter assertions. Only the canonical-path test helper changed before the focused and full passing reruns. No failed evidence was removed or relabelled as success. Historical receipts describe their original checkpoints; statements that the public repository was unchanged are true of those earlier verification runs, not a claim that this refresh did not occur.

Existing-only work recovery (6d.2), all-owning startup composition (6d.3), owned-close-before-Complete coordination (6d.4) and integrated interruption acceptance remain open. This publication does not mark the full milestone or the ship-ready goal complete.

The included demo APK is an earlier explicitly local-only demo, not a fresh build of all current sources. iOS is uncompiled. Provider login, real app persistence/sync integration, social service, reviewed recipes/media, purchases, release signing, physical-device checks and store publication remain open. See [build status](../feedme/docs/BUILD_STATUS.md) and [planned recovery](../feedme/docs/PLANNED_STATE_RECOVERY.md).

## Public-export privacy and evidence

Personal home-directory prefixes in textual files are replaced with `/Users/LOCAL_USER` (and equivalent encoded forms). Unrelated installed-app instrumentation inventory lines are omitted from public diagnostic log copies; FeedMe inventory and test-result lines are retained. The original workspace and original verification evidence are not modified by export. [The snapshot manifest](SNAPSHOT_MANIFEST.json) records every copied source file's original SHA-256, published SHA-256 and both transformation counts; [history metadata](History/HISTORY_MANIFEST.json) covers sanitized archive copies. The refresh does not rewrite earlier Git commits, which can retain the previously published diagnostic inventory.

Because of those transformations, some public historical receipts and their nested source/artifact hashes refer to original local bytes, not to the redacted file beside them. They are retained provenance records, **not** a byte-identical acceptance receipt for this public tree. Build outputs and historical temporary clusters referenced by them are not bundled, except for the separately identified demo APK. Run the appropriate current tests to generate fresh evidence for a clone; historical fixed-count verifiers should not be treated as current-source acceptance commands. The current full component runner is `feedme/scripts/verify-startup-recovery-owners.mjs`; see [setup](SETUP.md) for prerequisites.

Excluded: dependency/build caches, temporary machine state, local SDK properties, signing material, credentials, unrelated Career projects and TasteEcho's earlier concept. No CI, cloud deployment, GitHub Pages site, store upload or Devpost submission is configured by this snapshot.

The active implementation workspace is preserved separately from this publication checkout. Later uploads must deliberately refresh and re-audit the snapshot; this export does not silently synchronize future edits.
