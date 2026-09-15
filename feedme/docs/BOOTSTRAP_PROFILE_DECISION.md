# Decision needed: first-use account bootstrap and profile setup

Status: engineering proposal only; no contract, server, provider or database changes. This decision is separate from the pending infrastructure/provider proposal. It does not block the explicitly labelled local synthetic preview work.

## The current contradiction

[`BootstrapRequest`](../../outputs/biteclub_blueprint/architecture/04_API_Contract.json:21597) accepts only installationId, platform, appVersion and optional termsVersion/eligibilityDeclaration, with additionalProperties=false. It cannot carry a chosen display name or handle.

[`Bootstrap`](../../outputs/biteclub_blueprint/architecture/04_API_Contract.json:21633) nevertheless requires a non-null [`Profile`](../../outputs/biteclub_blueprint/architecture/04_API_Contract.json:21242). Profile requires displayName (1–50 characters) and handle (`^[a-z0-9_]{3,24}$`), plus ID/version/timestamps/onboardingStep/eligibility. The [data model](../../outputs/biteclub_blueprint/architecture/03_Data_Model.md:15) requires a unique live normalized handle and separates app identity from provider email.

A newly verified subject with no profile therefore cannot receive a truthful conforming 200. Omitting/nulling profile, empty strings, or requiredGates=[profile] does not repair that required object. Deriving a handle from email/provider metadata or allocating a placeholder would introduce unapproved profile semantics.

The intended [new-user sequence](../../outputs/biteclub_blueprint/architecture/02_Architecture.md:98) bootstraps before PROFILE_SETUP. Existing PATCH `/v1/me` cannot create a workaround before bootstrap: it requires the registered device session and an existing If-Match version; GET `/v1/me` likewise requires that device. See [the actual routes](../../outputs/biteclub_blueprint/architecture/04_API_Contract.json:362).

## Two valid contract options

### A. Explicit pending profile — recommended

Keep the existing bootstrap → PROFILE_SETUP order. Refine the canonical Profile schema so displayName and handle may be absent together only while onboardingStep=profile. When present they retain all current constraints; both are required before advancing to preferences/equipment/ready. Keep the genuine profile ID, version, timestamps and policy-derived eligibility in every representation. Do not use null/default strings or interpret pending as eligible/publicly discoverable.

Bootstrap can then atomically create the account, a versioned unfinished profile and registered device, returning requiredGates including profile. GET `/v1/me` returns that same pending representation and its real ETag. Existing ProfilePatch fields collect the user's chosen values; the server checks uniqueness and allowed onboarding transition, atomically updates the profile and receipt, then returns the complete representation. A client-provided onboardingStep alone cannot complete the gate. Other eligibility/terms gates remain independently enforced.

This changes Profile response validation and the stored pending-profile constraints, but adds no endpoint or provider-derived profile field. Update canonical schema/generated consumers and tests together; do not merely relax a Kotlin decoder. Required coverage: fresh pending bootstrap, exact replay, pending GET/ETag, unique-handle conflict, stale update, rejected advancement without both values, and no public/social admission while gates remain unsatisfied.

### B. Collect the initial profile before bootstrap

Keep Profile responses complete. Add a bounded initialProfile input to BootstrapRequest containing the existing displayName/handle fields. Require it when provisioning a new account; validate and reserve the handle in the same bootstrap transaction. Existing-account bootstrap must use the stored profile, not overwrite it with this input. Missing initialProfile for a new subject is a canonical error, not success.

This moves initial PROFILE_SETUP ahead of successful bootstrap and changes request contracts/client navigation. It is valid but less aligned with the documented sequence. Do not satisfy it by silently copying Google/provider fields.

## Provider-neutral persistence boundary after the decision

Use an explicit expand-only identity/profile migration and transaction-scoped store: unique exact issuer+subject mapping; app account/principal distinct from provider identity; versioned profile; per-account active installation uniqueness; registered device ownership/revocation. Keep the verifier mandatory and externally supplied, with no accepting default. No credentials, provider refresh logic or cloud setup belongs in this slice.

Bootstrap must commit its exact key/result and domain changes atomically. [`DurableCommands`](../server/src/main/kotlin/com/feedme/server/db/DurableCommands.kt:103) currently owns its transaction and takes an already resolved UUID principal. Fresh mapping resolution therefore needs a narrow same-connection command primitive or dedicated bootstrap transaction using [`PgTransactions`](../server/src/main/kotlin/com/feedme/server/db/PgTransactions.kt:14), not nested independently committed transactions or a fabricated UUID derived from the provider subject. Preserve same-key reconciliation after unknown commit, replay authorization, and changed-request rejection.

One separate projection rule must be explicit before listSessions: [DeviceSession.deviceLabel](../../outputs/biteclub_blueprint/architecture/04_API_Contract.json:21683) is required but absent from BootstrapRequest and the proposed device table. A truthful platform-only display-label rule could resolve this without claiming a device model; do not silently invent a personalized device name.

No scaffolding is authorized by this note. Until the profile decision and required integration are implemented and tested, preserve current [unconfigured 503 behavior](../server/src/main/kotlin/com/feedme/server/http/FeedMeApplication.kt:108) and the unchanged Cognito-named bearer contract. Local preview development continues independently; this is not live-auth or release acceptance.
