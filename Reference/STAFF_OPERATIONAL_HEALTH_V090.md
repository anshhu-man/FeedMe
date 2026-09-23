# Staff operational health — V090

## Delivered

The credential-free `server-deploy/` snapshot now handles canonical
`GET /v1/admin/health` for the existing read-only Incident console.

- Current Supabase workforce, recent MFA and enabled moderator enrollment are
  rechecked in the same database transaction.
- Pending environment-owned outbox depth/age and active media-job age are read
  as aggregate values; no user payload or object identifier is returned.
- The response is canonical, private and no-store. V090 records an immutable
  `health-read` audit receipt, which the existing audit page labels
  `adminGetHealth`.
- Free V1 explicitly has no entitlement processor or incident registry. The
  snapshot therefore remains `degraded`; enabling either subsystem without a
  real measurement source fails closed.
- Media pause, event replay, incident mutation and release-flag mutation remain
  unavailable.

## Verification

- 12 staff PostgreSQL methods: pass.
- 6 migration methods, including V051 → V090: pass.
- 28 staff browser/runtime methods: pass.
- Credential-free context: 558 inputs / 8,638,490 bytes.
- Manifest SHA-256:
  `852639f17d0dbcdcd0e7b408442639373c1bcccec04cc2a580ddaf6275b2d2ed`.
- Exact offline JDK 17 `:verifyReleaseScope :server:installDist` build: pass.

## Release boundary

A protected read-only check confirms hosted Supabase is V001–V088 with V089 and
V090 pending. Neither migration was applied, Render was not deployed and no Play
state changed. A controlled backup/migration/grant/postflight and configured
staff-browser acceptance are still required. FeedMe remains release **NO-GO**.
