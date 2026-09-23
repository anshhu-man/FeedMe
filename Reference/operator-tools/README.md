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

`feedme-staff-authority.mjs` is the separate fixed-project installation boundary
for those future owner-approved decisions. It pins the required migrations,
validator and launcher; reports only `absent`, `current` or `conflict`; and has no
update/delete/grant/retry route. Its sole write argument is
`--apply-owner-approved-staff-authority`. Exact replay changes nothing and any
partial or changed authority refuses. The companion focused snapshots cover the
credential-free plan, byte pins, rollback-only preflight and insert-only SQL; the
opt-in PostgreSQL snapshot exercises installation, replay and conflict refusal in
a disposable local fixture.
