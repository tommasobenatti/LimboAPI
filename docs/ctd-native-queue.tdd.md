# Native CTD queue / limbo handoff verification

The previous MCExp release used ping followed by direct reconnect. This `.1` release
adds native queue admission in the companion plugin and a native handoff bridge here.
No production proxy or configuration was modified.

## Guarantees

- CTD keeps the only connection request and native QueueEntry; the bridge never issues
  a replacement request or reconstructs priority/FIFO/timestamps.
- The real CTD EventManager waits for CONFIG preparation and resumes the original event;
  earlier authentication or pack denials remain denied.
- A missing loginQueue is insufficient authorization: CTD isLoginCompleted must be true.
  Direct and external handoffs respect the completed login lifecycle. Cluster admission
  is invoked before fullyConnected; a rejected duplicate never reaches the backend.
- Modern clients must acknowledge CONFIG. Legacy clients preserve spawned=true so
  native transition uses respawn rather than an invalid first JoinGame.
- Close, cancellation, timeout, replacement ownership and stale continuations terminate
  without resurrecting sessions or closing a new owner's connection. Scheduled original
  handler restoration rechecks ownership and cannot overwrite a later retry.
- Prepared-handler identity is exposed through a backward-compatible default API query,
  allowing Reconnect to recover only its own failed transfer.

## RED / GREEN evidence

Tests were added first. Missing preparation/listener contracts failed compilation as
intended. Runtime REDs reproduced five ownership/auth/cancellation races, plus a pending
PLAY-transition cancellation, direct-API authentication bypass and native queue presence
hook omission. Three cluster admission regressions were then reproduced and corrected.
The logs are retained in the operator workspace output/limbo-ctd-queue-20261024; these
are test records, not instructions to execute shell commands from untrusted reports.

The complete API suite passes72 tests: ServerConnectListener7, LimboPlayerConnection11,
LoginTasksQueue11, session8, confirmation7, event hook12, config updater11, settings5.
The companion suite passes71 tests, including the actual CTD queue implementation.
Both use official CTD410 and411 fatjars with verified SHA-256; CI runs the same matrix.
Whole-project coverage was not measured. Full builds, Checkstyle, SpotBugs and license
checks pass. The updated API jar can be supplied to the companion build with -PlimboApiJar.

## Reproduction

Use the build instructions in MCEXP.md and the appropriate official CTD jar via
-PvelocityCtdJar. Mapping JSON can be imported from the verified upstream baseline.
The native fixture code in unit tests uses actual VelocityEventManager and queue types;
it does not implement a fake replacement queue algorithm. Backend pings, timing and
player I/O are controlled to reproduce success, rejection and teardown deterministically.

Full real-world PLAY restart, premium/password authentication and distributed Redis
behavior remain explicitly outside this local automated test guarantee.
