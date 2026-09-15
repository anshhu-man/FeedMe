# Next public reference snapshot

The user authorized the public `anshhu-man/FeedMe` repository and source, idea, docs and UI publication. This note plans the next scoped snapshot; it does not report a new export or push. Deployment, paid CI, signing and store submission are separate decisions.

## Preserve the project; exclude repeated local diagnostics

The existing exporter ignores `.gitignore` and recursively copies verification attempts. A read-only measurement on 15 September found about 6.54 GiB in verification, most of it repeated sources, APKs and screenshots. Do not export that tree unchanged.

Proposed exact source-relative exclusions:

- `feedme/docs/verification/ui-ux`
- `feedme/docs/verification/social-draft-publication`
- `feedme/docs/verification/cookbook-processor`
- `feedme/docs/verification/recalled-copy-drafts`
- `feedme/docs/verification/progress-cookbook-timers`
- `feedme/docs/verification/cookbook-timer-media`

Together these account for about 6.44 GiB, leaving about 101 MiB of older verification files before further review. Keep implementation source, engineering docs, blueprint, all 98 screen designs and Reference indexes. Never remove original local evidence as part of publication.

## Required exceptions and checks

Some verification files are real build/test inputs. Preserve and explicitly verify `canonical-validation/format-corpus.json`, `contract-artifacts/generated-receipt.json`, `schema-validator-spike/cases.json` and `release-scope-report.json`.

The historical ninth verifier additionally requires `cookbook-processor/attempts/2026-09-14T08-50-11.189Z/report.json` and the nine retained source files selected by `historicalNinthClosure` in `scripts/verify-recalled-copy-drafts.mjs`. Preserve only that exact dependency closure if included, not the entire attempt. Its report contains local home paths: existing sanitization changes the pinned original digest. Do not weaken privacy filtering or rewrite original acceptance hashes to make a sanitized historical replay appear to pass. A separate portable fixture/explicit historical-replay boundary must be resolved and tested before claiming clone-wide verifier reproducibility.

The exporter currently replaces `Reference/artifacts/FeedMe-android-demo-debug.apk` from a mutable build path while the reference verifier pins the old demo. Preserve and verify the existing historical artifact. Publish a newly accepted preview under a separate name with its actual limitations and provenance; do not relabel a failed build as accepted.

Before export, reject unexpected existing destination files under excluded prefixes; ignoring a directory does not remove stale copies. Validate complete intended manifest coverage, privacy checks, executable wrapper permissions, Reference links and required fixtures. Engineering docs that link excluded local attempt evidence need an explicit local-only evidence notice or curated public summaries, not a false reproducibility claim.

## Execution order

1. Finish and review the current Android native gate.
2. Make a small, independently reviewed exporter/verifier correction implementing the exact policy and historical-fixture boundary.
3. Export into a separate reviewed publication tree, preserving original workspace and historical artifacts.
4. Verify source/asset completeness, privacy, build prerequisites and public limitations; inspect the Git diff.
5. Push the authorized public snapshot only after these checks. No cloud or store release follows from a Git push.
