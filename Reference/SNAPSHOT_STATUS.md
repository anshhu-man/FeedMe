# Published development checkpoint

13 September 2026. This repository is the user-requested public snapshot of FeedMe work to date, not a release announcement.

## Included

Shared Kotlin and native Android/iOS hosts, the local server foundation, tests and scripts; the complete 54-feature/98-screen blueprint; the full UI prototype and screen exports; architecture, data/API/event contracts, security plans, milestone/task tracking, retained test evidence, the historical Android demo APK, original brand story and sanitized historical ZIP copies.

## Verification boundaries

The preceding complete planned-state-activation source snapshot passed 1,069 Kotlin/server/PostgreSQL tests, 92 Node tests and 102 Android checks. These are historical results, not fresh verification of this export.

The subsequent planned-data recovery implementation passed 315 targeted storage JVM tests and 57 regular Android storage tests. Its two native sync-injector attachment attempts failed and remain recorded. After those runs, a replacement test-only C VFS was authored; it is uncompiled and its Kotlin registration/URI hookup and verifier labels are still unfinished. The six sync cases are not expected to pass at this checkpoint. The full new recovery verifier has not passed. No failing evidence was removed or relabelled as success.

The included demo APK is an earlier explicitly local-only demo, not a fresh build of all current sources. iOS is uncompiled. Provider login, real app persistence/sync integration, social service, reviewed recipes/media, purchases, release signing, physical-device checks and store publication remain open. See [build status](../feedme/docs/BUILD_STATUS.md) and [planned recovery](../feedme/docs/PLANNED_STATE_RECOVERY.md).

## Public-export privacy and evidence

Personal home-directory prefixes in textual files are replaced with `/Users/LOCAL_USER` (and equivalent encoded forms). The original workspace and original verification evidence are not modified by export. [The snapshot manifest](SNAPSHOT_MANIFEST.json) records every copied source file's original SHA-256, published SHA-256 and transformation count; [history metadata](History/HISTORY_MANIFEST.json) covers sanitized archive copies.

Because of those transformations, some public historical receipts and their nested source/artifact hashes refer to original local bytes, not to the redacted file beside them. They are retained provenance records, **not** a byte-identical acceptance receipt for this public tree. Build outputs and historical temporary clusters referenced by them are not bundled, except for the separately identified demo APK. Run the appropriate current tests to generate fresh evidence for a clone; unfinished suites remain unfinished.

Excluded: dependency/build caches, temporary machine state, local SDK properties, signing material, credentials, unrelated Career projects and TasteEcho's earlier concept. No CI, cloud deployment, GitHub Pages site, store upload or Devpost submission is configured by this snapshot.

The active implementation workspace is preserved separately from this publication checkout. Later uploads must deliberately refresh and re-audit the snapshot; this export does not silently synchronize future edits.
