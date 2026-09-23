# Staff authority preparation

Status on 23 September 2026: **local preparation and insert-only installer
verified; owner decisions absent; no identity selected and no hosted write
performed**.

The credential-free source under [`operator-tools/`](operator-tools/) defines a
non-runnable owner-only preparation for the future production staff policy, staff
actor and moderator enrollment. It deliberately leaves the Supabase subject,
actor UUID, policy/client binding, roles and validity windows unset.

Verification:

- 14/14 focused preparation/installer methods pass;
- one opt-in real PostgreSQL method passes first installation, exact replay and
  conflict refusal in a disposable loopback fixture;
- initial preparation creates an owner-only non-runnable file;
- repeated preparation preserves the exact existing file; and
- decision checking refuses while the separate owner-approved decisions file is
  absent.

The validator accepts only the fixed FeedMe Supabase project/issuer and production
environment, exact JSON fields, canonical UUIDs, explicit role booleans and
bounded time/runtime values. Its result excludes policy and identity values.

`feedme-staff-authority.mjs` pins the matching V040, V077 and V090 migrations,
the decision validator and the database launcher. Its default mode is a
credential-free plan; read-only preflight is separate. Its only write mode
requires `--apply-owner-approved-staff-authority`, accepts only an exact absent
state and inserts the policy, actor and moderator enrollment once. Exact replay
is a no-op; partial or different authority fails closed. It contains no update,
delete, truncate, DDL, grant, revoke or automatic retry path.

An email address is not a Supabase subject identifier. The permanent signed-in
subject and registry values must not be guessed or derived from support contact
details. They require explicit owner confirmation after the controlled database
rollout. No production credential read, external network request, hosted SQL
write, enrollment, runtime export, deployment, signing or Play action occurred.
The only database write was inside the disposable loopback PostgreSQL fixture.
