# FeedMe backend and sign-in — proposal for approval

Prepared 15 September 2026 IST at the user's request. **Technical direction is awaiting approval.** No provider accounts, paid plans, deployments, permanent IDs or secrets have been created by this proposal. Google Play production access is user-reported ready; Apple Developer access and full Xcode are not ready.

## Recommended stack

| Responsibility | Proposed implementation | What stays in FeedMe |
| --- | --- | --- |
| Accounts | Supabase Auth; Google and verified email/password for initial Android integration | Account/device bootstrap, canonical ownership, session invalidation and secure credential storage |
| Application API | Existing Kotlin/Ktor service, packaged as a Render Docker web service | Domain validation, permissions, exact-original retries, transactions, moderation and publication decisions |
| Database | Supabase managed PostgreSQL, accessed by the backend through restricted JDBC credentials | Existing migrations, relational model and transaction/idempotency semantics |
| Photos | Private Supabase Storage buckets | Quarantine, rights/moderation checks, protected delivery, expiry and block/recall enforcement |

This is an integration proposal, not a replacement of the product or a rewrite into database-driven client screens. The current Android progress app has synthetic identity/catalog/service adapters. Live server startup is deliberately loopback-only, and release builds remain disabled until release gates pass.

Google and email/password preserve the planned sign-in, verification and reset journeys. Apple sign-in remains part of V1, pending Apple setup; it is not silently deferred out of scope. OTP-only would change the existing password/reset design. [Google support](https://supabase.com/docs/guides/auth/social-login/auth-google), [password authentication](https://supabase.com/docs/guides/auth/passwords).

## How the connected flow must work

1. The user explicitly chooses Google or email signup/login. Google credentials are handled through the provider flow; email verification and reset have separate, clearly acknowledged states. Cancelling does not create a FeedMe session.
2. The provider adapter returns a bounded credential result. The backend verifies trusted issuer/audience/signature/expiry and maps the verified subject through FeedMe's account/device bootstrap. An email address, decoded token, or visible SDK session alone cannot grant product authority.
3. FeedMe's existing session owner installs credentials through the secure native storage boundary. Account switching, sign-out, stale callbacks and process restoration must retain the established invalidation rules. Do not create a second independent token-persistence or refresh owner.
4. The app calls Ktor product endpoints. The backend authorizes each operation and performs existing PostgreSQL transactions. Mobile clients receive no database password or privileged service key and cannot bypass domain checks through direct product-table calls.
5. Photo uploads enter private quarantine. Only validated, authorized derivatives can be delivered. Public sharing, circles, Today expiry and block/recall checks remain server decisions—not consequences of a successful upload or a generated URL.
6. After the user separately approves a staging environment, verify real signup, verification/reset, cancellation, refresh, sign-out, process recreation, stale-session rejection, account isolation and protected media before considering production deployment.

## Compatibility and security gates

The [source-level integration readiness map](BACKEND_INTEGRATION_READINESS.md) identifies reusable code and concrete missing work. In particular, the runtime needs an explicit owned refresh operation, the service needs verified identity/bootstrap routes, and the canonical bootstrap/profile representation must support a genuinely new user. These are not already connected by selecting Supabase; ordinary engineering decisions can proceed within the approved direction without asking the user to approve every implementation detail.

- Supabase's Kotlin Multiplatform library is **community-maintained, not an official SDK**. First perform a bounded compatibility and session-ownership spike against the repository's actual Kotlin/Ktor versions. Do not assume automatic SDK persistence/refresh is safe alongside FeedMe's retained session owner. [Kotlin reference](https://supabase.com/docs/reference/kotlin/introduction).
- Some canonical auth bindings currently name Cognito. A stack approval requires explicit provider-binding updates and tests; Supabase is not already connected merely because these abstractions exist.
- Keep product tables outside public Data API access and use least-privilege SQL roles. Validate TLS and direct/session-pooled JDBC behavior before changing pooling mode. [API controls](https://supabase.com/docs/guides/api/securing-your-api), [database connections](https://supabase.com/docs/guides/database/connecting-to-postgres).
- Signed Storage URLs remain usable until their expiry and do not become immediately revoked when Auth keys change. Do not promise instantaneous block/recall enforcement using long-lived signed URLs. FeedMe needs protected delivery with current authorization checks. [Storage delivery](https://supabase.com/docs/guides/storage/serving/downloads).
- Production email requires an approved sending domain and SMTP service. The default Supabase mail service is for non-production use, restricted to project team addresses and currently two messages/hour. Verification must not be disabled to work around that restriction. [SMTP requirements](https://supabase.com/docs/guides/auth/auth-smtp).

## Environments and budget

Use isolated staging and production projects, credentials, callback allowlists and storage. Region is an owner decision, not inferred from the user's timezone or Shipaton's US listing requirement. Singapore is a possible colocated region, subject to target-market/data-location approval. [Supabase regions](https://supabase.com/docs/guides/platform/regions), [Render regions](https://render.com/docs/regions).

An indicative **single-environment infrastructure baseline is US$50/month**: Supabase Pro starts at US$25 for one included project, plus a Render 2GB web service at US$25. The Render `1c-2g` plan replaces the legacy Standard name with unchanged pricing. Actual CPU/memory sizing still needs measurement; these prices are not proof that this deployment meets load or reliability targets. [Supabase pricing](https://supabase.com/pricing), [Render's 2GB price](https://render.com/docs/render-vs-heroku-comparison#pricing), [compute-plan mapping](https://render.com/docs/compute-plans).

The US$50 baseline assumes Render's free Hobby workspace plus paid web-service compute. Render's Pro workspace adds US$25/month before compute; choosing it would make this example US$75/month before other extras. Workspace tier and compute are separate charges. [Render pricing](https://render.com/pricing).

Free Supabase projects can pause after inactivity and are suitable for exploration, not an uptime commitment. SMTP, a second environment, media workers, moderation, paid workspace plans, domains, overages and taxes are additional; this document is not a full-production quote or spending authorization. Keep cost alerts and provider controls, but do not describe any provider spend cap as an all-inclusive bill guarantee. [Supabase pricing](https://supabase.com/pricing), [Render pricing](https://render.com/pricing), [cost-control limitations](https://supabase.com/docs/guides/platform/cost-control).

## Alternative

Firebase Auth can serve the same Ktor/PostgreSQL architecture. Official Android/Apple SDKs and backend token verification are advantages; native bridges would need an explicit KMP design. This adds another provider and is not a reason to replace the existing transactional backend with Firestore. [Firebase SDKs](https://firebase.google.com/docs/libraries), [token verification](https://firebase.google.com/docs/auth/admin/verify-id-tokens).

## Approval boundaries

The question raised now asks only whether to adopt **Supabase + Ktor on Render, Google + verified email/password** as the technical direction. Separate approval is still required for region, environment count/budget, permanent app/domain identity, SMTP, collaborator access, paid resources and staging deployment. Store upload/release and Devpost submission remain artifact-specific decisions. Never send passwords, signing keys or privileged tokens in chat.

The tomorrow target remains the strongest verified Android preview, not a promise that selecting providers completes all 44 V1 features. See [the sprint plan](TOMORROW_SPRINT.md) and [user-action register](USER_ACTIONS.md).
