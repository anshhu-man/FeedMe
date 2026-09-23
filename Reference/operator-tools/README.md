# FeedMe V090 operator-tool source snapshots

These are exact credential-free source snapshots for the guarded V089/V090 and
optional staff-serving grant workflow, plus the protected account-runtime
preparation/export boundary. They are reference artifacts, not part of the
`server-deploy/` container context and not an instruction to run a hosted write
or deployment.

The tools expect their original path to be `deploy/` inside a matching FeedMe
source root. They also require the ignored, owner-only local Supabase password and
reviewed public CA files, the current JDK 17 server distribution, and the fixed
local PostgreSQL client path recorded in source. No credential is included here.

The default rollout mode is a credential-free plan. Read-only preflight is
separate. The only write mode requires the exact literal
`--apply-v089-v090-and-staff-grants` argument and fresh explicit operator approval.
Do not modify, relocate and execute these files without re-running their byte-pin
and focused tests against the matching server source.

`prepare-staff-authority.mjs` is additionally local-only and non-runnable. It
does not generate an actor, select a Supabase subject, produce SQL or install
authority. Its decision-check mode accepts only a separate owner-only local file
and returns a redacted report.
