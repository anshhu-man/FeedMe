# Portable frozen ninth parser fixture

These nine unchanged source files support ten frozen Node parser regressions. The original local acceptance receipt is NOT bundled. Its SHA-256 is recorded only as provenance; this portable manifest is a new extraction record, not that receipt or its acceptance authority.

Run the standalone verifier from the publication root with `node tools/verify-historical-parser-fixture.mjs . --run-tests`. Without `--run-tests`, it checks fixture integrity only. Replaying these ten tests does not rerun historical native tests or verify the expanded current app.

The source workspace's historicalNinthClosure and fixed acceptance hashes remain unchanged and still require the original private receipt. Do not substitute this manifest, rewrite those hashes, or advertise all historical checkpoint scripts or a wildcard Node command as portable current-source acceptance.

The workspace subdirectory preserves original relative paths solely for immutable test imports and source reads. No Gradle, Android device, network, provider or original report is required by the ten parser tests.
