# Runtime staff-readiness reporting

Status on 23 September 2026: **source verified; private reviewed Render
configuration absent; no deployment attempted**.

The packaged account-runtime validator and protected exporter now report five
separate redacted booleans:

- staff session configured;
- catalog drafts enabled;
- catalog review enabled;
- catalog publication enabled; and
- moderation enabled.

This prevents a valid general account configuration from being interpreted as
proof that staff moderation or catalog operations are active. Policy version and
client identifiers are never returned. The exporter still reports
`productReady: false`, performs no network request and deploys nothing.

Verification:

- 12/12 focused Kotlin validator methods pass;
- 16/16 protected exporter methods pass against the actual packaged Kotlin
  validator;
- the exact 559-input / 8,653,116-byte context passes its 17-task offline JDK 17
  release-scope/server-distribution build; and
- context-manifest SHA-256 is
  `4ec5bccad0d50d687997d2f2a00030b5b5af38bdb88611a52724bbb6a637113a`.

The owner-only reviewed runtime configuration file is not present. Export is
therefore correctly refused. A real staff policy registry binding, staff actor,
moderator enrollment, fresh provider review and exact runtime configuration must
be established before these booleans may truthfully become active.
