# V090 staff controlled rollout

Status on 23 September 2026: **locally verified; hosted write not run**.

The fixed-project operator workflow coordinates only the existing V089 and V090
migrations and the reviewed optional staff-moderation serving grants. It pins the
exact input bytes, admits only exact V001–V088 or exact V001–V090 history, verifies
after each write and never retries automatically.

Current evidence:

- 7/7 focused coordinator methods pass;
- all 16 combined migration/grant/coordinator methods pass;
- the current JDK 17 server distribution build passes;
- a protected read-only hosted preflight reports exact V088 with only V089 and
  V090 pending; and
- no migration, grant, service deployment, staff enrollment, activation,
  signing or Play action occurred.

The reviewed source snapshots are under [`operator-tools/`](operator-tools/).
They contain no password, token, signing key or runtime configuration. They are
kept outside the exact `server-deploy/` build context so its 559-input manifest
remains unchanged.

Fresh explicit approval is required before the exact hosted V089/V090 plus staff
grant write. Even after a successful database rollout, Render configuration,
service deployment and live acceptance remain separate release gates.
