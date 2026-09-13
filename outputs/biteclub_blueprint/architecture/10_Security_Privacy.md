# Security, privacy and content rights design

Proposed controls for the FeedMe blueprint, 13 September 2026. This is an implementation and review specification, not a claim of certification or legal compliance. Launch geography, minimum age, privacy disclosures, content licensing and nutrition review require named owners before public release. The initial access proposal is an invite-only adult pilot; a checkbox alone is not treated as proof that all jurisdictional requirements are met.

## Authentication and sessions

Use managed Cognito identity through native supported SDK flows or the system browser authorization-code flow with PKCE. Public mobile clients contain no client secret. Validate access-token signature via pinned issuer JWKS, token_use=access, issuer, expiration and configured client_id; check requested resource scopes separately. ID tokens are for identity, not normal API authorization. JWT library success alone does not enforce object permissions. Ktor provides a JWT authentication mechanism; FeedMe must implement the issuer/audience/client checks and domain authorization policy explicitly. [Ktor JWT](https://ktor.io/docs/server-jwt.html)

AUTH_CALLBACK accepts only registered universal/app links and exact callback paths, state/nonce from an outstanding local auth transaction, and a single-use PKCE verifier. Do not log callback query strings or exchange credentials in a third-party webview. Store refresh tokens in iOS Keychain / Android keystore-backed protected storage through platform adapters. Access tokens stay in memory where possible. Enable supported refresh-token rotation and handle concurrent refresh through one shared mutex, preserving the prior token only during provider-supported retry grace. [Cognito token endpoint](https://docs.aws.amazon.com/cognito/latest/developerguide/token-endpoint.html)

The API also requires X-Device-Session, bound to token subject and a live server record. Revoke selected session disables that record immediately, clears its push token association, and revokes corresponding refresh capability where supported. Revoke-all uses fresh authentication and provider-wide signout. Local logout clears secure tokens and account caches, and the server disables the app session; a client that merely forgets its password does not cancel a subscription.

Recent reauthentication for account deletion, revoke-all and account-sensitive staff actions verifies a provider assertion from the same issuer/subject with auth_time ≤5 minutes, correct client and nonce binding where applicable. Ordinary token refresh is not fresh authentication. Never accept a client boolean `recentlyAuthenticated=true`.

Guest access uses an opaque random token hashed at rest, explicit scopes and separate rate limits. Guest-only objects remain owner-scoped. Authentication is required before social graph access, upload, message, invitation acceptance and purchase. Guest merge proves both the guest token and target user access; knowing guestSessionId is insufficient.

## Authorization matrix

| Object/action | Who may read | Who may change | Mandatory server checks |
|---|---|---|---|
| Private preferences, pantry, plans, cooking, memory, cookbook | Owning principal only | Owning principal | principal ownership, lifecycle, version; guest bounds |
| Reviewed free catalog | Eligible guest/member | staff author/reviewer/publisher roles only | published/recall state; review separation |
| Paid pack contents/tools | Entitled principal; permitted existing private saves remain readable | Staff catalog/commerce role | server entitlement and product mapping; no client unlock |
| Post/media | Author or current permitted audience, subject to block and placement time | Author; moderation action by scoped staff | published status, audience membership, bidirectional block, expiresAt/keepOnPlate, media ready, ACL revision |
| Recipe copy grant | Actor can request only from currently accessible saveable version | Creator controls future-grant policy; safety can recall | exact hash/policy version, content license, locked current permission |
| Remix trail | Each node independently visible | Child author adds own edge during publish | no cycle, bounded depth, no hidden ancestor metadata/media |
| Circle | Active members | Owner/admin scoped action; only owner transfers/dissolves | role, last-owner invariant, membership cap, fresh target status |
| Direct thread/message | Explicit active participants | Sender creates; safety removes by case | participant membership, blocks, text/content validation, referenced object permission separately |
| SOS/poll | Current selected circle audience | Author or one actor-owned reply/vote | server closing time, current membership, one vote per actor |
| Pact/potluck | Invited/accepted participant according to state | Host configuration, actor participation/contribution | schedule version, member visibility, claim ownership, shared constraints only |
| Household kitchen settings | Accepted members | Owner/shared-settings role | entitlement, seat cap, shared configuration only |
| Household member food preferences | Actor's own fields only; others receive neutral compatibility | That member only | member consents to server-side shared matching; owner cannot inspect or amend another member's exclusions |
| Report and moderation case | Reporter-safe receipt; authorized assigned staff sees evidence | Reporter submits; staff scoped adjudication | report evidence access, role, reason, audit, conflict-of-interest separation |
| Export/deletion job | Owner or authorized staff incident role | Explicit actor request/system workflow | recent auth when needed; no public object URLs |

For private object reads, respond 404 for nonexistent and unauthorized IDs to limit existence leaks. Return 403 when the user already legitimately knows the object and only the requested action is forbidden. 410 may explain expiry of an object the actor was authorized to know. Error text never includes private audience member names, stored exclusions, receipt contents or secret tokens. Client capabilities improve UX but do not replace checks at command execution.

## Media trust boundary

Create random per-upload storage keys; never trust supplied filenames. Presigned POST constrains the exact key, maximum content length and checksum where supported. Server complete verifies owner, expected object version, actual bytes/type/hash, upload expiry and draft state. Pipeline reads immutable version, scans, decodes in a resource-limited worker, strips EXIF/location, rejects malformed/oversized media and re-encodes safe derivatives. Quarantine bucket cannot serve through public CDN. S3 bucket public access is blocked; CloudFront uses origin access control to fetch only intended derivative prefixes. [S3 origin restriction](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-restricting-access-to-s3.html)

API signs derivative access only after current post/surface authorization. Proposed max TTL is 60s, capped by Today-only expiry. Signed URLs are bearer capabilities; a holder can reuse/share them while valid, and an already-started transfer can outlive expiry. Audience revocation prevents new URLs immediately, but previously issued delivery has a bounded residual window. Invalidation and origin deletion accelerate urgent removal without guaranteeing deletion of downloaded copies. [CloudFront URL validity](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-signed-urls.html)

Disable location collection and contact import in the initial scope. Use system photo picker rather than broad photo-library access; ask camera/microphone permissions only at capture. Clips begin with original sound or mute; licensed music libraries and face-recognition features are not assumed. Account-specific media caches clear on logout, do not persist signed URLs, and evict expired/non-retained posts on foreground validation.

## Recipe rights and memory privacy

Three rights classes are explicit:

| Rights class | Permitted use | Forbidden assumption |
|---|---|---|
| catalogRedistributable | Licensed catalog display, supported adaptation and attached card under agreed terms | A paid pack automatically gives unrestricted external republishing rights |
| creatorOriginal | Author confirms authority over supplied recipe text/media; platform obtains disclosed service license | Confirmation makes an unsafe/unreviewed recipe professionally reviewed |
| privateCopyOnly | Authorized recipient keeps immutable recipe content privately, without original photo/comment stream | Recipient may republish complete source card or grant onward copying |

Publish validates an attachment's `rightsBasis` against actual provenance; user-picked enum is not evidence. A saved third-party private recipe can be used in personal cooking, but sharing Your Take includes own photo and confirmed changes plus an authorized source link only unless redistribution rights are separately established. No source media is copied. A hidden/private/deleted ancestor is represented as unavailable inspiration without names, photos, counts or title leakage.

Future-save revocation stops new copies under a transaction lock. Existing authorized copies persist independently of ordinary post expiry/deletion, except safety recall or required legal/store deletion. This behavior is disclosed in SAVE_PERMISSION and DELETE_POST before publishing/deleting. Permission to keep a copy is not a waiver of data-deletion requirements. During account erasure remove all creator identity and shared UGC; default to recalling personal user-authored recipe copies where continued retention is not demonstrably permitted. Independently licensed catalog content and recipient-authored adaptations can remain with anonymized provenance. Launch review must settle the exact distinction and wording; it cannot be buried in a technical assumption. Apple explicitly expects account-creation apps to support account deletion, including associated shared user-generated content. [Apple account deletion guidance](https://developer.apple.com/support/offering-account-deletion-in-your-app)

Food exclusions and dietary patterns are private by default and may reveal sensitive information. Avoid sensitive inference labels, do not infer diagnoses, and do not place ingredient exclusions in analytics, pushes, social captions or model prompts unless required for the private requested action. Shared household constraints require each member's explicit selected fields; the app returns compatibility results without revealing who has a private undisclosed restriction. Explain why a suggestion fits through neutral reason codes, with editable provenance. Forgetting a memory stores only a minimal suppression fingerprint needed to prevent the same historical signal immediately recreating it.

## AI and reviewed-catalog controls

The model adapter can parse a freeform request into a validated constraint object. It cannot set access scopes, change hard exclusions, mark a recipe reviewed, issue SQL, choose arbitrary tools or publish content. Treat captions, imported recipe text and external links as untrusted data. Do not fetch arbitrary user URLs server-side; source links remain inert metadata unless a future isolated ingestion service enforces destination allowlists, redirects, size limits and private-IP rejection.

Only the deterministic catalog engine may create a supported recipe plan. It proves recipe/substitution review state, equipment, mandatory cooking steps and hard-exclusion feasibility, then ranks. Model timeout, invalid structure or ambiguity routes to manual controls. No photo-derived ingredient/allergen/nutrient assertion. Reviewers validate versioned content; an unreviewed community shortcut stays labelled a note and cannot replace a mandatory recipe step. Audit plan recipe IDs, review revision and reason codes, not full personal input.

## Abuse prevention and staff operations

Baseline proposed rate limits per actor: guest plan 20/day, registered plan 100/day, uploads 20/day, posts 10/day, new invitations 20/day, new direct threads 10/day, messages 60/min with burst 10, reports 10/day with emergency exception handled by support. Add device/IP anomaly detection without making IP the sole identity or blocking shared networks indiscriminately. Config values require monitored adjustment from pilot behavior.

Community terms must be accepted before uploads. Provide visible report/block paths on posts, messages, profiles and shortcuts, a support channel, and staffed handling. Apple and Google policies require meaningful moderation mechanisms for UGC; store review is an actual release gate, not something presumed satisfied by adding a report button. [Apple App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/), [Google Play UGC policy](https://support.google.com/googleplay/android-developer/answer/9876937?hl=en-GB)

Staff identity uses separate workforce OIDC, MFA and roles: catalogAuthor, qualifiedReviewer, catalogPublisher, moderator, seniorModerator, supportRead, onCall, securityAdmin. No default super-admin from an ordinary user claim. Authors cannot approve their own recipe; reviewer identity and qualification register are kept current. High-impact account restriction, evidence access and rollout increase have audit trails and second-person review according to impact. Immediate on-call kill switches may reduce exposure without waiting for approval; restoring/increasing exposure uses a reviewed proposal. No feature flag may disable object authorization, hard exclusions or recall enforcement.

## Encryption, secrets and exports

TLS in transit; managed encryption at rest for database, object storage, queues, backups and restricted evidence. Separate production/staging accounts, KMS keys and provider clients. IAM task roles are narrowly scoped; secrets injected from managed secret storage and rotated, never embedded in Kotlin bundles, logs or build artifacts. CI uses short-lived identity, dependency locks, secret scanning and signed build provenance. Private JSON is no-store and excludes shared CDN caching.

Exports include account-owned profile/preferences/pantry/plans/feedback/memories/private copies/posts/message contributions and consent records, with third-party data minimized. Request creates job; worker writes encrypted private archive; owner status endpoint signs a short download URL after authorization. No public emailed archive links. Deletion suspends the account immediately, revokes sessions, hides UGC, cancels pending notifications, purges private data and media by checkpointed jobs, applies required shared-content recall and keeps only justified minimal audit/transaction records. A public web deletion-request entry and in-app path both need to exist before release. [Google account deletion guidance](https://android-developers.googleblog.com/2024/03/designing-your-account-deletion-experience-google-play.html)

Restore procedures reapply the erasure ledger before a restored database serves traffic. Provider deletion/revocation and object storage purges are independent retried stages with verifiable receipts. A final status message reports actual completion or remaining justified retention; the app never describes merely disabling an account as deleting it.

## Required adversarial and privacy tests

Cross-account and guest/member ID substitution on every route; old JWT with revoked device session; mixed issuer/client token; callback replay; manipulated household member ID; private source remix to wider circle; media URL after expiry/block; upload replacing an already-verified key; image decompression bomb; save-versus-revoke transaction race; removed member with queued push; repeated invitations beyond capacity; raw-body webhook signature/replay failure; sandbox webhook in production; stale entitlement response overwriting refund; prompt injection asking to ignore exclusions; recalled recipe in cached plan; account deletion followed by DB restore. CI enforces denial and absence of sensitive response/log data, not just HTTP success codes.
