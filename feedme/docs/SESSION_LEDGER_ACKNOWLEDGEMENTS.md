# Independent session-ledger acknowledgements — M1.05d.5b.2b.3.1

13 September 2026. **DONE — bounded component**, verified at **2026-09-13T15:27:52.354Z**. This is the first task in [composite session setup](COMPOSITE_SESSION_SETUP.md), not the completed composite setup feature or a production release.

This receipt and the counts/hashes below are a historical checkpoint. Current source-bound totals and artifacts are indexed in [Build status](BUILD_STATUS.md#current-verification); the subsequent [work-origin planning component](WORK_ORIGIN_PLANNING.md) is now verified. This page preserves its original evidence rather than claiming it matches later build outputs.

## What changes in production

The independent control ledger remembers exact setup/retirement intent. The independent work ledger remembers opaque native timer/worker tickets. Neither a readable record nor a matching payload after a failed SQLite commit proves that the write was acknowledged durably. Previously, several callers promoted an `OUTCOME_UNKNOWN` result to success through readback, including replay paths after restart.

`SessionControlStore.acknowledge` now requires all of:

1. An active caller and the current owner/lifecycle fence before writing.
2. A new CAS against the exact observed revision. Even an identical payload changes the revision by exactly one and is encrypted/written again. Revision exhaustion fails closed.
3. A successfully returned commit receipt for precisely that next revision and payload. Every failure, including `OUTCOME_UNKNOWN`, stops this attempt without using readback to authorize effects.
4. An exact subsequent readback and another cancellation/owner check. A newer competing revision is a conflict, not the same acknowledgement.

This is not an automatic retry loop. Recovery chooses the exact original state and writes it again at the freshly observed revision. No replacement operation ID, credential plan, work origin or ticket is invented to make an uncertain operation pass.

| Caller | Acknowledgement and effect ordering |
| --- | --- |
| Live planned credential creation | Acknowledge exact pending plan before native create; failed completion does not return a credential snapshot or let runtime continue into data/work selection. |
| Explicit credential abort | Acknowledge exact confirmation before opening recovery. Restart/retry rewrites the same requested plan, even if native status would already be ABORTED. Abort/inspection errors retain the intent; acquired handles still close on cancellation. |
| Logout / empty setup discard | Fence the process first, acknowledge pending intent before exact cleanup. Replayed pending/checkpoint records get a new acknowledgement. A readable Complete must be rewritten successfully before reporting completion or removing the process latch. |
| Work creation / restore | Acknowledge the new origin before returning its binding; restore rewrites the same origin before admitting it. This does not yet provide the future lease-free work-origin plan. |
| Work install / cancel / retirement | Acknowledge reservation before installer and exact cancelling/retiring intent before cancellation, including replay. Failed or unreadable barriers do not permit a direct cancellation fallback. |
| Work callback | Revalidate lease, ticket, phase, retirement and domain policy, then acknowledge the exact work record before the synchronous local effect. A queued OS callback alone is never authority. |
| Runtime access publication | Re-acknowledge nonblocking control state before capturing the retirement CAS baseline and exposing access. Read-only recovery diagnostics remain read-only. |

Work compensation after an uncertain final install is allowed only through a separate successful acknowledged cancellation intent for that same ticket. Caller cancellation retains evidence and process fences; it is not permission to run a new effect inside a non-cancellable block. Required handle closing and immediate process fencing remain non-cancellable where appropriate.

The conservative implementation performs extra encrypted writes for work resume, reconciliation and callback admission. Before high-frequency production scheduling, measure latency and write amplification; do not replace these barriers with no-op/readback acknowledgements to optimize them. The registry requires one lifetime owner and the serialized application identity dispatcher; this is not a cross-store transaction or multi-process scheduler contract.

## Verification contract

Common tests cover exact next revision, identical-payload writes, every typed failure, repeated uncertainty, wrong receipts/readbacks, overflow, thrown exceptions, synchronous cancellation and changed caller fences. Real SQLite integration tests cover preserved identities, changed revisions across reopen, retirement latches, effects blocked after lost acknowledgements, and runtime publication.

Native acceptance adds nine actual-bundled-engine injected-VFS cases: journal sync, database sync and post-unlink directory-sync failure for each of credential-abort confirmation, work reservation and work-retirement barriers. The test-only URI-selected forwarding VFS must surface numeric SQLite errors 1034/1290 and prove zero downstream effects after the failed barrier. Visible pending states require a fresh acknowledged retry; rolled-back reservations must not invent a replacement ticket. Existing six private-data-abort VFS cases and other storage/session regressions remain in scope.

The [full source-bound receipt](verification/session-ledger-acknowledgements/verification.json) passed. Attempt: `verification/session-ledger-acknowledgements/attempts/2026-09-13T15-26-23.490Z/`.

| Check | Verified result |
| --- | --- |
| Shared Kotlin | 1,055 passed: 48 core, 119 contracts, 72 transport, 323 storage/integration, 103 sync, 142 kitchen and 248 session; zero failures/errors/skips |
| Server / real PostgreSQL | 46 + 45 passed against isolated local services, not a connected product backend |
| Node | 102 passed, including ten new native-evidence parser regressions |
| Android native | 131 passed on API35 arm64: 74 storage (57 regular, six private-data VFS, nine ledger VFS, two process-separated persistence stages), plus 57 session (47 regular, ten integration) |
| Android libraries | Five fresh AARs, five zero-issue lint reports, both isolated test APKs rebuilt |
| Frozen evidence | 216 source inputs, 13 artifacts, 112 retained evidence files |
| Test-helper packaging | Four ABI-specific ELF helpers only in the storage test APK; absent from five AARs, session test APK and historical demo |

The three directory-failure cases additionally fail a second recovery acknowledgement before any effect, then succeed with the exact plan/ticket and a new revision. Rolled-back empty reservations retain the exact original scope, origin, revision and payload. Control tests use the real Android credential factory in the isolated test APK UID; work effect ports count admission and do not schedule OS work. Separate existing session integration covers native cancellation. Delivered-notification cancellation passed; existing-hardlink construction was denied by the platform (`EACCES`, 13), so those branches remain unexercised.

| Receipt / artifact | SHA-256 |
| --- | --- |
| Source manifest | `f12bd5c153cf48eef7d2bfd5ff6590f240bb7279b5bb16f93156073762c8d03d` |
| Verification receipt | `8668944070ae72a4065623e5b0949d21e387ccd8bda59e26f91894e5dd68c48d` |
| Session AAR | `8d6c81dc480c74b66e2bc95e5b84d6e0b8e36bcf6b461de2c2c042e7c15d10c1` |
| Storage AAR | `49bc8ec8d70334f66d3d37bb2bb0d0c5b88daf5ccb52359d8854c44ed6c48b47` |
| Storage test APK (8,607,348 bytes) | `f66379825006ac0cc341d0e37147db8213d3041810e4d58a5f04513b23d211a7` |
| Session test APK (8,557,943 bytes) | `af6337df86a239ea35bfd7dd53f126064b5cebb7851df92da17a353cdac2bbcb` |

Reproduce with JDK17, Android SDK36, installed NDK28.2.13676358, `FEEDME_POSTGRES_BIN` and a booted emulator selected by `FEEDME_TEST_DEVICE`:

```sh
node scripts/verify-session-ledger-acknowledgements.mjs
```

The owned emulator exited after verification. Owned native fixtures were removed; session WorkManager's expected database files remain. Test notification permission was granted before and after, without permission changes. Port8789 has no test-server listener, and all 90 retained synthetic PostgreSQL cluster directories have no postmaster PID file. No demo data were reset. Earlier receipts remain historical; older verifier scripts have frozen expectations for their earlier source snapshots and are not the current-tree reproduction command.

## Remaining gates

Six composite tasks remain: lease-free work-origin planning; one credential/data/work pending codec; serialized create; durable exact private activation binding; startup observation/confirmed abort; integrated native failure matrix. In particular, `PrivateSessionRuntime.persistBinding` still has its separate private-data readback reconciliation and early internal lease/data activation ordering. This component does not claim those are fixed or that the full runtime is ready for provider/UI integration.

No kernel/OS `fsync` failure, physical power loss, hard-kill, public hot-journal recovery, first-factory repair, API26 credential recovery or iOS compilation is proved by VFS error injection. Native verifiers/confirmation are synthetic/programmatic. Auth/provider decisions, reviewed content, social safety, billing, physical devices and releases remain open. The app demo and public GitHub snapshot are unchanged.
