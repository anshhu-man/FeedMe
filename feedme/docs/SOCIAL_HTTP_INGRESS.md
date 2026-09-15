# FeedMe — circle and invitation HTTP ingress

14 September 2026. **VERIFIED BOUNDED COMPONENT; not a public social release.** Implements a configured HTTP boundary for F23/F40 over the existing transactional `CirclesStore`.

## Production composition

The application must explicitly supply `SocialHttpConfiguration`: environment, real store, trusted ACCOUNT verifier and database dispatcher. There is no accepting verifier, provider selection or automatic configuration. Default Main remains closed for product operations. Trusted token verification and transaction-time current identity/device/membership/role/block checks are separate requirements; a parsed Authorization header is not identity proof.

Fourteen canonical operations are mapped: list/create/get/update/delete circles; list/update/remove circle members; leave/transfer ownership; create/preview/accept/revoke invitations. Ten mutations preserve the original idempotency key and six versioned operations require If-Match. Wire framing, duplicate controls, UUIDs, body/query fields, response schema, status and ETags are validated. DELETE success is a genuinely empty204 response. Cancellation or uncertain commit never invents rollback or a replacement key.

Public invitation preview uses only its bounded query capability and returns minimal canonical information. Well-formed optional auth/device metadata is ignored without invoking a verifier; malformed or ambiguous controls are rejected. Preview never accepts an invitation or returns private account/circle identifiers. Revoked, expired and unknown capabilities remain non-enumerating under the store policy.

## Verification and remaining work

The lane adds22 input/unit and26 real PostgreSQL/Ktor/CIO integration methods. First integration execution exposed four incorrect response-member-order expectations (PostgreSQL JSONB reorders object fields) and an ambiguous folded Authorization classification. Complete JSON value/status/ETag equality and exact request/effect checks remain required; ambiguous folded Authorization now rejects before verifier invocation. Both focused execution and the frozen combined run at2026-09-14T00:45:13.550Z pass all110 server unit and145 PostgreSQL integration methods. [Central acceptance](PARALLEL_KITCHEN_SOCIAL_HOST.md).

These tests use synthetic verification, block/profile policy and invitation capability configuration with actual PostgreSQL transactions and HTTP. They do not establish production identity adapters, deploy infrastructure or deliver native circle screens, posts, media, outbox consumers, notification delivery, moderation operations, iOS or public-release acceptance.
