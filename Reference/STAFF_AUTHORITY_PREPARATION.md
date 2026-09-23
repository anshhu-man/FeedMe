# Staff authority preparation

Status on 23 September 2026: **local preparation verified; owner decisions absent;
no identity selected and no hosted write performed**.

The credential-free source under [`operator-tools/`](operator-tools/) defines a
non-runnable owner-only preparation for the future production staff policy, staff
actor and moderator enrollment. It deliberately leaves the Supabase subject,
actor UUID, policy/client binding, roles and validity windows unset.

Verification:

- 8/8 focused validation methods pass;
- initial preparation creates an owner-only non-runnable file;
- repeated preparation preserves the exact existing file; and
- decision checking refuses while the separate owner-approved decisions file is
  absent.

The validator accepts only the fixed FeedMe Supabase project/issuer and production
environment, exact JSON fields, canonical UUIDs, explicit role booleans and
bounded time/runtime values. Its result excludes policy and identity values.

An email address is not a Supabase subject identifier. The permanent signed-in
subject and registry values must not be guessed or derived from support contact
details. They require explicit owner confirmation after the controlled database
rollout. The tool generates no SQL and performs no credential read, connection,
enrollment, runtime export, deployment, signing or Play action.
