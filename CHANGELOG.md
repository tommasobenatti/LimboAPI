# Changelog

## 1.1.27-MCExp-CTD410.1 — 2026-10-24

- Bridge authenticated native server requests out of limbo without creating a second connection.
- Wait for CONFIG acknowledgement and preserve spawned state for legacy clients.
- Expose a default prepared-handler ownership query for scheduler retry recovery.
- Honor CTD cluster admission, fully-connected queue hooks and final login lock lifecycle.
- Close or cancel stale handoffs safely; protect login events and other plugins' replacement handlers.
- Prevent a delayed original CONFIG restore from overwriting the next native handoff.
- Verify 72 API regression tests and the paired native queue tests on CTD410 and411.


## 1.1.27-MCExp-CTD410 — 2026-10-24

- Adapt asynchronous registration and the CTD identity-lock lifecycle, forwarding type and legacy tablists.
- Preserve nLogin's original inbound connection and pre-login handlers across reloads.
- Forward resource-pack statuses in CONFIG/PLAY to Velocity for ForcePack.
- Finish failed event futures, preserve other plugins' kick decisions and clean early disconnects.
- Bound and release confirmation packets; make session disposal and scheduled tasks safe.
- Upgrade config.yml conservatively on startup/reload with strict validation, private backups and atomic replacement.
- Preserve translations, comments, unknown keys, edited/empty collections and concurrent administrator edits.
- Pin CI to the verified CTD artifact and publish CI jars/test reports without automatic external uploads.

Validation: 51 unit/regression tests; full Gradle build, Checkstyle, SpotBugs and licenses pass.
Paired compatibility smoke: CTD 410, ForcePack 1.3.75-SNAPSHOT, nLogin 2.0.18,
three offline protocol-765 logins, a 36-second CONFIG hold and two reloads per plugin.
See docs/MCEXP.md for scope, build instructions and remaining staging checks.
