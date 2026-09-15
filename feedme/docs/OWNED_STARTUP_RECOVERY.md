# All-owning startup setup recovery

14 September 2026. M1.05d.5b.2b.3.6d.3 and6d.4 are **DONE bounded**, with the fresh source-bound acceptance below. This adds retained Android startup composition and close-before-Complete ownership. It does not complete production application wiring, later integrated process-interruption or product-release gates; parent6d/task6 remain IN_PROGRESS for those integration boundaries.

This builds on the [credential/data retained owners](STARTUP_RECOVERY_OWNERS.md), [existing-only work owner](WORK_RECOVERY_OWNERS.md), and the retained CONTROL owner in [parallel feature foundations](PARALLEL_FEATURE_FOUNDATIONS.md). The older [composite abort coordinator](COMPOSITE_SETUP_ABORT.md) keeps its borrowed-resource behavior by default.

## One application composition, reserved before I/O

Later integration checkpoint: [seven witnessed process-interruption/recovery pairs](ANDROID_PROCESS_INTERRUPTION.md) now pass in the [third parallel batch](PARALLEL_UI_HTTP_PROCESS.md). The same-process-only native description and future-interruption references below belong to this earlier component's immutable acceptance. The newer evidence extends those specific scenarios, without completing production app/confirmation wiring, the entire task7 matrix or physical-power-loss/iOS acceptance.

Create `SessionApplicationComposition` with the application's one `SessionBoundary`, serialized application dispatcher, and approved immutable `configurationBinding`. Call its synchronous `reserve()` **before constructing or opening any production resource**. Then retain the owner returned by `AndroidSessionSetupRecovery.createOwner(context, reservation)` before awaiting `open()`.

Root creation, reservation and recovery-owner construction do not access Context storage, files or Keystore. Construction claims the reservation immediately, so caller `release()` cannot discard ownership during opening, cancellation or failed close. A second recovery owner cannot borrow or release that claim. A root cannot close or be replaced while its reservation or runtime registration remains held.

The registry excludes actual `PrivateSessionRuntime` lifetimes, not merely active leases. Legacy `PrivateSessionRuntime.open(...)` is permitted only without a registered application root; its registration prevents a root or another runtime from being admitted until logical runtime close acknowledges. An unreserved runtime cannot bypass even an otherwise idle registered root.

This is a trusted composition contract, not authentication or a sandbox against arbitrary code in the same process. The application must use one root/boundary for these fixed native namespaces and must route all resource acquisition through that reservation. Creating another boundary or directly invoking an older public storage factory is not a supported bypass. The native factories are not retrofitted with a universal application-root parameter.

All lifecycle and boundary operations belong on the configured serialized application dispatcher. `invalidate()` permanently makes the root close-only, fences its generation, and synchronously clears the current lease and its private-state invalidation observers. It releases no native resource. After successful cleanup, close that root and explicitly create another root before reserving again, even with identical configuration bytes. Neither a reused token nor a replacement root revives old authority.

## Restricted retained opening

`SessionSetupRecoveryOwner` exposes only `open`, advisory `inspect`, `prepareAbort`, `confirmAbort`, `retryAbort`, `phase` and `close`. It exposes no raw control ledger, token, ordinary private store, work registry or lease.

Opening checks the exact reservation/lifecycle and the real process-retirement latch before creating the CONTROL owner. It then:

1. Retains and opens the existing-only independent CONTROL owner; reads the original `PendingSetup` and checks its configuration binding.
2. Decodes the original composite plan, retaining credential, data and work owners before any subordinate opening can suspend.
3. Opens credentials, data and work in that order, bracketing each with exact control, lifecycle and latch checks.
4. Authenticates all original plans through their existing native owners and brackets the complete resource evidence before publishing READY.

There is no initialization, schema migration, ordinary resume, key garbage collection, new signing, identity allocation, credential-token read, provider call or scheduling. The existing component factories remain authoritative about supported schemas, file inventory, native keys and opaque plan authentication. Work proof verification necessarily follows guarded existing-only SQLite reads; it is not claimed to occur before SQLite opens.

Missing or malformed control, another control state, different configuration or failed native authentication cannot become an abort proposal. A persisted `Complete` is not a substitute for the original pending plan or for consent. Selected credential metadata does not prove that its selected key/blob is present, decryptable or usable; supported exact metadata-only retirement remains possible without reading tokens.

An admitted failed or cancelled open becomes close-only, retaining every owner acquired so far. A synchronous later factory exception does not discard earlier owners. READY is not published while a successful I/O result is still waiting on a cancellable caller-return handoff. The final publication checks the protected composition claim, without reading dispatcher-owned boundary state from a foreign caller dispatcher. A cancelled queued second open cannot quarantine the first admitted owner.

## Confirmation and the retained closing checkpoint

Inspection is advisory. `prepareAbort()` returns a redacted, runtime/generation-bound proposal after stable exact evidence checks; it neither confirms nor erases anything. `confirmAbort(proposal)` rechecks that evidence and acknowledges the exact original `PendingSetup` with `abortRequested=true` before native cleanup. `retryAbort()` requires already-confirmed original intent; it cannot silently confirm an unrequested plan.

The ordered path is:

`fresh exact control acknowledgement → work abort → data abort → credential abort → retained ALL-ABORTED checkpoint → work close → data close → credential close → fresh exact Complete acknowledgement`

Every component abort has its own required fresh acknowledgement and exact post-observation checks, including an already-aborted replay. Data cleanup accepts only the original empty state or sole reserved schema-2 binding whose bytes match the canonical original session binding derived by the trusted coordinator. No generic scope erase, newer-incarnation adoption or foreign inventory cleanup is introduced.

After all three abort acknowledgements, the owned variant synchronously retains the exact final evidence and requested control record **before any close**. It stops the borrowed coordinator's default Complete path at that point. The checkpoint is memory-only; no new durable “closing” state is invented.

Closing proceeds work, data, then credentials. Each successful close is recorded inside a non-cancellable section before caller cancellation is checked. A failed or unknown close stops finalization, retains the exact owner/checkpoint and cannot be credited by a later no-op. A retry closes only unfinished stages and never reinspects or re-aborts an already closed handle. Independent CONTROL ownership and the application reservation remain held throughout.

Only after all subordinate closes acknowledge does the owner perform a fresh changed CONTROL CAS and exact readback of the original operation's `Complete`, with current lifecycle/latch/control checks. A matching payload after a failed or unknown write is not automatically accepted as acknowledgement.

If an attempted Complete became visible but its receipt was lost, only the same retained owner has the exact predicted record plus acknowledged close checkpoint. Its retry may freshly re-acknowledge that exact Complete, without using closed subordinate handles. Another control revision/body, lifecycle change or root invalidation blocks that continuation. A new owner presented with Complete has no such proof and cannot reconstruct consent.

## Close, cancellation and restart responsibilities

`close()` abandons recovery; it never writes Complete, even when all resources were already aborted. It is non-cancellable and retains failed stages. CONTROL is not closed while any subordinate release is unresolved. The application reservation is released only after CONTROL itself acknowledges close. The caller must not independently close the private subordinate owners hidden inside this composition.

Successful Complete therefore does not mean the owner may be discarded: final owner close still has to acknowledge CONTROL release. Conversely, an interrupted close leaves the original durable confirmed PendingSetup available for a fresh existing-only owner and an explicit retry once ownership has genuinely been released.

The new Android tests stop finalization after one or two subordinate closes, then truthfully abandon/close the remaining owners and reopen a new public owner against the original authenticated plans. This is real same-process native close/reopen continuation. It is **not** an additional kill-process stage, OS crash or physical-power-loss test. The existing five controlled process stages remain separate evidence.

`PrivateSessionRuntime.openReserved(...)` remains a **borrowed-store** API. Its caller must retain the reservation until both the runtime and every supplied native store have acknowledged close. Runtime close only releases the logical runtime registration; it does not release the reservation or close those stores. Failed runtime construction closes its local work-registry ownership and awaits exact registration release under the registry mutex in a non-cancellable section, avoiding an inaccessible registration left by transient try-lock contention. It never releases a replacement registration or adds native I/O to that bookkeeping cleanup. Older value-returning storage factories still do not supply retained failed-acquisition owners; this fix does not turn them into the new retained startup API.

## Verification handoff

Focused verification passed **593 session JVM tests**, Android test-APK compilation and zero-issue lint. This package adds **36 common/JVM methods**: 26 owned-recovery, eight application-composition and two deterministic JVM mutex-contention/exact-instance-release tests. Protocol fakes prove ordering and ownership, not native cryptography or physical durability.

`AndroidSessionSetupRecoveryOwnerTest` adds **12 native methods** using the four real public native factories and exact isolated fixture namespaces. Coverage includes all six setup stages, original-plan close/reopen after one/two closes, close failures, lost Complete acknowledgements, missing selected credential key/blob, failed data acquisition, real runtime registration/invalidation, and the process-retirement gate before CONTROL acquisition. Synthetic verifier responses are explicitly test-only; no provider authentication is claimed. Fault wrappers inject application-level failures/acknowledgement loss, not new physical SQLite sync faults.

The first native diagnostic run passed the previous 147 session tests and 11 of the 12 new methods. One new test incorrectly expected `restorationAllowed()` on PendingSetup to install a process-retirement latch; PendingSetup is deliberately separate from legacy InFlight retirement. Only the test setup was corrected: it now uses a real runtime-captured retirement binding and an actual coordinator retirement request, injecting failure of the first control CAS. The zero-control-factory and no-effect assertions are unchanged. The failed transcript is retained at `verification/native-credentials/android-attempts/2026-09-13T22-06-27.128Z/owned-startup-recovery.log`.

The [final full source-bound attempt](verification/parallel-meal-startup/verification.json) passed at2026-09-13T22:19:20.584Z: all593 session JVM methods and159 native session methods, including all12 corrected/new owned-startup tests, passed. The combined1,890 Kotlin/161 Node/323 native run retains the previous39 VFS labels and five process stages. [Independent audit, cleanup and integration limits](PARALLEL_MEAL_STARTUP.md). No new VFS labels, process stages or cleanup namespaces are added by this package.

## Remaining gates

Partial native acquisition and known pre-close faults retain retryable owners. A real platform/driver close error after its handle became invalid remains an ambiguous terminal process-repair gate: there is no raw numeric-FD retry, no replacement-owner bypass and no claimed successful close. Credential poison/unknown-inventory rules remain intact; an original plan cannot authorize a general orphan sweep or missing-key regeneration.

Public SQLite recovery still refuses rollback journals, WAL/SHM, missing stores and unsupported schemas. Faithful public hot-journal recovery, integrated process-separated composite interruption acceptance, hard kill, physical power loss and physical-device coverage remain separate work. API26 credential/recovery support and iOS/Xcode verification remain open.

This component does not deliver real provider/bootstrap configuration, confirmation/startup UI, a general orphan or refresh repair policy, production application composition wiring, remote revocation or release acceptance. A 64-hex configuration binding is a trusted input here, not an implemented provider/configuration verifier. The [V1 scope decision](V1_RELEASE_SCOPE.md) is unchanged: it neither re-enables deferred features nor proves the included features release-ready. This local implementation does not publish, deploy or submit the app.
