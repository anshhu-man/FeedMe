# Staff moderation serving grants

## Current local package

The V090 source handoff contains an optional installer for the database rights
used by the existing staff admission, report moderation and operational-health
handlers. It is intentionally separate from the account-core grants.

The package provides:

- exact provider-session and TOTP observations needed for staff admission;
- read-only workforce and moderator-enrollment access plus the non-mutating
  moderator lock helper;
- report/case/evidence reads, bounded case transitions and immutable moderation
  audit/action/removal inserts;
- read-only current post/message material for exact removal decisions; and
- only `environment`, `state` and `created_at` from media-processing jobs for
  aggregate health.

It creates no role, password, actor, enrollment, policy, incident, feature flag
or route. The installer rejects unsafe role flags, ownership, memberships,
schema creation, authority-changing staff/Auth privileges and missing pinned
migrations before adding grants.

## Verification

- 4/4 real PostgreSQL role-boundary methods pass.
- 12/12 existing real PostgreSQL staff workflow methods pass.
- The exact 559-input / 8,653,116-byte source context passes its 17-task offline
  JDK 17 release-scope and server-distribution build.
- Context manifest SHA-256:
  `4ec5bccad0d50d687997d2f2a00030b5b5af38bdb88611a52724bbb6a637113a`.

## Deployment boundary

Hosted Supabase remains V088. V089, V090 and these optional grants are not
installed. No staff actor or moderator is enrolled, Render is not refreshed and
no Android, signing or Play state changed. A controlled migration/grant/
configuration/postflight and live staff-browser acceptance are still required.
FeedMe remains release **NO-GO**.
