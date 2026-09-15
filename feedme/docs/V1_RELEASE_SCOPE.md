# FeedMe — version 1 scope

14 September 2026. User decision: “All the features that should be added later / Donot add in the first version.” Apply the existing blueprint's P2/P3 phases, not a newly invented subset. The [machine-readable manifest](V1_RELEASE_SCOPE.json) keeps all 54 feature identities: **44 P1 features in the V1 build plan; 10 later features excluded from V1**. This is a sequencing decision, not implementation, store-readiness or a Shipaton deadline guarantee.

## Excluded from version 1

| Feature | Later phase |
| --- | --- |
| F32 — Fridge SOS | P2 |
| F33 — Tonight | P2 |
| F34 — Dinner Pact | P3 |
| F35 — Bring a Bit | P3 |
| F36 — Shortcut Swap | P3 |
| F37 — Dinner Vote | P3 |
| F38 — Short Clips | P3 |
| F44 — Household Preferences | P3 |
| F45 — Expanded Personal Library | P3 |
| F46 — Reviewed Situation Packs | P3 |

Do not implement or enable these as V1 features. Preserve their designs, contracts, tests and backlog for later releases. Their presence in the complete 98-screen reference prototype or canonical API specification is not permission to expose them in the production V1 app.

## What stays

F01–F31, F39–F43 and F47–F54 remain in the first-version build plan. FeedMe's identity is unchanged: effortless cooking, Make Mine, guided cooking, private saves, Today, My Plate and invited kitchen circles. Privacy, report/block/delete, account recovery, content review and operational controls remain required for the features that need them.

The basic private cookbook (F19) remains; advanced collections/library functionality (F45) does not. Photo sharing remains; short video capture/edit/transcoding (F38) does not. General notifications remain; SOS, pact, contribution, poll, household and pack-specific jobs do not. Ordinary individual preferences remain; household preferences do not.

## Integration rules

- Select implementation tasks from the 44 included features and their necessary shared foundations. Later-only milestone tasks are DEFERRED, not DONE. Mixed tasks must deliver only their explicitly retained portion in V1.
- Build/package checks validate this manifest against the canonical feature phases. Unknown IDs, overlaps, missing features and re-enabling a later feature fail the scope check. These structural checks do not prove runtime routes or screens are correctly gated.
- When production UI/API/workers are connected, omit later-only entry points, reject later-only operations at the server, and do not register later-only scheduled jobs, notifications or paid offers. Hidden buttons alone are insufficient. Native/deep-link/direct-API tests must prove refusal before V1 release.
- Shared screens and endpoints must remain available to their included feature owners. For example, cookbook, media composition, people pickers or billing surfaces cannot be removed wholesale merely because a deferred feature also references them. Gate feature-specific actions and payload variants.
- Preserve the full canonical 54-feature/98-screen/900-action/201-operation design. Do not rewrite it as if future features were completed, deleted or part of the first release.
- F50 billing/monetization integration remains a competition gate, but F44/F45/F46 paid products are deferred. The earlier proposed paid recipe pack is therefore **not** an approved V1 offering. U07 must resolve a compliant V1 monetization approach without silently restoring a deferred feature, inventing paid value, or enabling purchases/ads without approval.

No production runtime exposure is certified by this manifest. Native recovery work is supporting infrastructure for included account/private-data features; its [existing-only work recovery component](WORK_RECOVERY_OWNERS.md) is verified, while complete startup composition remains unfinished. The original full-product goal remains active after V1.

## Verification

The 18 release-scope tests and 15 delivery-plan tests pass; the complete current Node suite passes161 tests. Both structural reports pass. Gradle `verifyReleaseScope` executed successfully, and an Android `assembleDebug --dry-run` confirmed the scope check is a packaging dependency. This is task-graph evidence, not a newly built app or proof of runtime exclusions.

Current canonical associations span81 screens/157 operations for included features, with11 screens/27 operations also referenced by deferred features. Those shared associations are **not a V1 allowlist**: later-specific collection/pack/admin actions must still be withheld even where a P1 support feature references them. There are17 deferred-only screens and44 deferred-only operations. The full registry remains98 screens/201 operations.

Run `node --test scripts/verify-release-scope.test.mjs` and `node scripts/verify-release-scope.mjs --write-report`. The root Gradle `verifyReleaseScope` task also checks the selection before normal assemble/bundle/check/build tasks. [Report](verification/release-scope-report.json). [Release plan](RELEASE_PLAN.md), [feature matrix](FEATURE_DELIVERY_MATRIX.md), [user decisions](USER_ACTIONS.md).
