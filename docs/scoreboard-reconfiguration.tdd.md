# Scoreboard restoration after a limbo reconnect — 27 October 2026

Status: fixed on top of LimboAPI259704d. The verified candidate was uploaded to
the Velocity proxy at the operator's request; on27 October2026 the operator
confirmed that the original sidebar bug was fixed in game. LimboReconnect and
ENU source, configuration values and player preferences were unchanged.
No reload, restart or player command was executed by the deployment workflow.

## Reproduced cause

The user reported an absent sidebar after a backend restart, despite TAB reporting
visible=Enabled and active layout=kingdoms. Switching /sb off and on restored it.
These observations alone did not establish which plugin was responsible.

The actual CTD410/411 state machine fires PlayerEnterConfigurationEvent before
sending StartUpdate, waits at most5s, and fires PlayerEnteredConfigurationEvent
when the client acknowledges CONFIG. LimboAPI's custom transitions bypassed both:
LimboImpl sent its prepared StartUpdate directly on entry; the session handler sent
StartUpdate directly on exit. CTD's native backend finish event still ran.

The installed VelocityScoreboardAPI2.1.1 freezes its stored scoreboard on Enter.
Its resend method does nothing unless frozen. TAB's VSA adapter delegates resend
to VSA and does not recreate an unchanged active scoreboard. The missing entry
notification therefore leaves the logical proxy state intact while preventing
objective/display packets from being restored after the client resets its state.

## Fix

A shared session helper waits for the native Enter event before either custom
PLAY-to-CONFIG start. It coalesces duplicate starts, runs the continuation on the
connection event loop, and rechecks exact handler ownership and socket state.
The ACK handler dispatches native Entered before spawning or resuming the handoff.
Closed/replaced sessions cannot resume, spawn or close their successor; physical
disconnect cancels a pending entry future. Premature ACKs cannot bypass the wait.

Initial CONFIG after login and no-rejoin PLAY paths emit no invented entry event.
No Finish/Finished event is fabricated for a virtual limbo: those require the real
backend and stay under CTD control. Native queue requests, resource-pack events,
configuration updater behavior, TAB objective registrations and /sb choices are
unchanged. There is no TAB or VSA runtime dependency in the plugin.

## Executed RED/GREEN

An optional integration suite uses the locally owned actual VSA2.1.1 jar:
SHA256 `1235ec12f52796ea14520c4d7781c32532ddf9e8ee2ef0e39333b162eda560ce`. The jar is not committed or distributed.
The fixture invokes its real ServerSwitchManager, VelocityScoreboard,
DownstreamScoreboard, freeze and resend code; player transport/manager boundaries
are controlled. Actual Objective REGISTER and SIDEBAR DisplayObjective packets
are asserted, not only a logical visible flag.

Before the fix:3 tests ran,2 assertion failures. An already registered kingdom
sidebar emitted no restoration packets; the expected native Enter was missing.
The voluntarily hidden/no-objective case passed. After the fix:3/3 pass, with the
same objective object restored and an intentionally hidden sidebar still absent.

Twelve ordinary regressions cover async ordering, coalescing, successive entry and
exit, owner replacement, close/cancellation, listener failures, premature ACK,
Entered-listener takeover, real LimboImpl entry, initial CONFIG and no-rejoin.
Both CTD410 and411 pass **85 ordinary tests +3 actual VSA tests**, zero failed or
skipped. Full build, compilation, Checkstyle, SpotBugs and license checks pass.
LimboReconnect's unchanged71-test build also passes on CTD411 with the paired API.
Whole-project coverage percentage was not measured.

Run from the repository using JDK25 and verified CTD/mapping inputs:

```sh
./gradlew build -PvelocityCtdJar=/absolute/path/to/velocity-ctd.jar \
  -PmappingsJar=/absolute/path/to/verified-mappings.jar \
  -PvelocityScoreboardJar=/absolute/path/to/VelocityScoreboardAPI.jar
```

Without the last property, the optional source set is absent; the ordinary suite
still verifies event lifecycle and introduces no new runtime dependency. Optional
integration sources are under plugin/src/scoreboardTest/java.

## Validation boundary

The automated test verifies restoration packets from the actual installed VSA
implementation while preserving the registered/hidden state. Separately, the
operator confirmed the original sidebar issue was fixed after testing the deployed
candidate in game. This is user-reported acceptance; no independent client capture
or per-backend test matrix was recorded. Distributed Redis remains unverified.
The local CONFIG smoke with native
queue enabled, actual ForcePack1.3.75-SNAPSHOT and VSA2.1.1 checks initial login,
pack hold/statuses, two reloads and teardown separately. Premium/password nLogin
authentication remains outside this new test because the local Premium input
previously reported an unlinked license. No license bypass was attempted.

CTD source contract (pinned official source, matching examined jar handlers):
- [Enter event](https://github.com/GemstoneGG/Velocity-CTD/blob/787b9aa8da8654f3d0b585e899a32d0ae0581d31/api/src/main/java/com/velocitypowered/api/event/player/configuration/PlayerEnterConfigurationEvent.java)
- [Client PLAY handler](https://github.com/GemstoneGG/Velocity-CTD/blob/787b9aa8da8654f3d0b585e899a32d0ae0581d31/proxy/src/main/java/com/velocitypowered/proxy/connection/client/ClientPlaySessionHandler.java)
- [Client CONFIG handler](https://github.com/GemstoneGG/Velocity-CTD/blob/787b9aa8da8654f3d0b585e899a32d0ae0581d31/proxy/src/main/java/com/velocitypowered/proxy/connection/client/ClientConfigSessionHandler.java)

Private raw logs/configs from the live system are neither printed nor copied into
this document. Workspace-only evidence is in output/limbo-scoreboard-20261027.
