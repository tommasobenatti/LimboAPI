# MCExp build and release guide

## Supported target

- Java 25 or later; build with JDK 25.
- Velocity-CTD 4.3.1-SNAPSHOT builds410 and411 verified. The baseline CTD tree at
  `cf46d4000d252124c280f6361166df57f3d0f7f3` matched build 410 when verified on 24 October 2026.
- Use the paired MCExp LimboAPI 1.1.27-MCExp-CTD410.1 and LimboReconnect 1.1.7-MCExp-CTD410.
- Actual integration inputs: ForcePack 1.3.75-SNAPSHOT and nLogin 2.0.18.

Changes address the reported removed-method login error and concrete event/session
lifecycle defects. They do not claim compatibility with every Velocity fork or future CTD build.

## Install, reload and rollback

1. Stop the proxy and keep copies of the previous plugin jars.
2. Replace both plugin jars; leave a single version of each in `plugins/`.
3. Keep both configuration folders. Do not regenerate or delete configuration files.
4. Start the proxy and check login, authentication, pack acceptance/refusal and backend restart in staging.

Existing `config.yml` receives missing schema keys automatically. Messages are embedded
in that file; neither plugin introduces a standalone `messages.yml`. Customized values,
translations, comments, unknown keys and edited/empty lists/maps remain preserved.
No bundled message defaults changed in this patch, so no old translation is replaced.
Invalid files and edits detected during an update are refused. Private backups are
stored under each plugin's `backups/` folder before atomic replacement.

Keep `save-uncompressed-packets: true` with `compatibility-mode: true` in LimboAPI.
Changing compatibility mode requires a full restart. New default pre-login lists
include `nlogin`; existing custom lists are preserved, and replay still uses the original
inbound connection for deferred profile handlers.

Reload LimboAPI via the proxy reload lifecycle (`/velocity reload`); its public
`reload()` method is also available to integrations. There is no `/limboapi reload`
command. LimboReconnect uses `/limboreconnect reload`. These reloads update settings;
the first installation of new binaries requires a stopped proxy.

For rollback, stop the proxy and restore the previous paired jars. If a config rollback
is needed, compare the private backup with later administrator changes before restoring it.

## Build

Download the official [CTD 410 fatjar](https://github.com/GemstoneGG/Velocity-CTD/releases/tag/build-410)
and verify SHA-256:

`7ec86cab7c4187e84ff0312ef2459bd70f96c5484552142b79b82d7c9d25a05d`

The optional mapping source is the upstream LimboAPI dev-build for `bfef579`, SHA-256
`5d541984b90ca7a88eb49ca5f8cc3a803f5ecdfa6d7ed30b605b72ff94156e40`.
Its [dev-build URL](https://github.com/Elytrium/LimboAPI/releases/tag/dev-build) can move;
never accept another digest silently. Supply an archived matching jar or omit the
option and generate Minecraft mappings from source. Only unchanged mapping JSON is
imported; all plugin classes compile from this repository.

From this repository with JDK 25 selected through `JAVA_HOME`:

```sh
./gradlew build -PvelocityCtdJar=/absolute/path/to/velocity-ctd-fatjar-4.3.1-SNAPSHOT-410.jar \
  -PmappingsJar=/absolute/path/to/limboapi-upstream-bfef579.jar
```

Result: `plugin/build/libs/limboapi-1.1.27-MCExp-CTD410.jar`.
Supply `velocityCtdJar` explicitly: building against the old upstream dependency is
not the verified CTD path. No Velocity or test classes are bundled. SnakeYAML 2.5 is shaded privately.

## Validation and limitations

72 focused tests pass, with zero skipped/failed tests. Compilation, Checkstyle,
SpotBugs and license tasks pass. The paired `.1` build totals 143 tests. Whole-project coverage was
not measured and the audit was scoped to login, resource packs, reconnect and resource lifecycle.

The isolated smoke used real CTD/ForcePack/nLogin jars, offline protocol 765,
a local pack and a fake backend. One same-socket CONFIG hold measured 36.00048s;
three logins and two reloads of each plugin passed with unchanged config hashes,
active nLogin profile mutation and successful ForcePack statuses. The sandbox's
read-timeout was 75s because the fake backend does not send heartbeat/world data.
The CTD identity-lock lifecycle was unmodified. No production settings, database,
credentials, license or cache were copied. No production server was changed.

Rendering, premium/password authentication and a complete PLAY→backend restart→limbo→return
cycle remain staging checks. Details: [smoke result](smoke-verification.md) and
[regression evidence](velocity-ctd-compatibility.tdd.md).

## Release procedure

CI builds push, pull-request and published-release commits on JDK 25 with the verified
CTD inputs. It has read-only repository permissions and uploads jars/test evidence.
It does not delete or rewrite dev tags, replace release assets or publish to upstream Modrinth projects.

Create an annotated version tag on a verified commit. Rebuild the tagged commit,
check its embedded Git revision, run the paired local smoke, compute SHA256SUMS and
publish the corresponding jar, checksum and documentation with `gh release create --verify-tag`.
GitHub's source archives identify the tagged source; the release asset checksum identifies
the uploaded binary. Release publication triggers another read-only CI check.

## Reproduce the isolated smoke

The Python standard-library fixture is [tools/ctd_smoke.py](../tools/ctd_smoke.py).
It requires a local JDK and locally owned third-party jars; none is committed or redistributed.
Its private `tools/runtime-*` folders are ignored by Git. It binds only loopback random ports.

```sh
python3 tools/ctd_smoke.py \
  --ctd /absolute/path/to/velocity-ctd-fatjar-4.3.1-SNAPSHOT-410.jar \
  --limboapi /absolute/path/to/limboapi-1.1.27-MCExp-CTD410.jar \
  --reconnect /absolute/path/to/LimboReconnect-1.1.7-MCExp-CTD410.jar \
  --forcepack /absolute/path/to/forcepack.jar \
  --nlogin /absolute/path/to/nlogin.jar --with-nlogin --hold-config-seconds 36
```

Read the generated report and console: process startup alone does not establish success.
Expected: three complete login/CONFIG attempts, first same-socket hold at least36s,
ForcePack SUCCESSFUL, both reload counters2, unchanged config hashes, nLogin profile
hook active, no binary/ERROR messages and graceful proxy exit0. The fixture uses only
fresh test defaults, an empty local pack and a synthetic backend; it never loads production data.


## Native queue integration (.1)

Pair this API with LimboReconnect1.1.7-MCExp-CTD410.1. Its new setting
`integrate-ctd-queue: true` is added conservatively to existing config.yml files.
CTD must also have its native queue enabled in velocity.toml; no server files are
changed by this source update.

On a matching restart kick, Reconnect sends the player to limbo and enrolls once
through CTD's public QueueManager. CTD owns queue ordering, priority, warmup, capacity,
paused states and Redis behavior. Existing entries and timestamps are preserved.
Native `/leavequeue` and configured server command aliases are forwarded through
Velocity's permission-aware CommandManager; login/auth/admin commands are not exposed.
Viewing the server list does not cancel pending admission. Leaving a queue does not
cause automatic re-enrollment.

When CTD selects the player, the API delays the original ServerPreConnectEvent until
limbo has completed its CONFIG transition (legacy clients get a native PLAY handler
with their spawned state preserved). It resumes that same connection request; no
second request is created. A bounded preparation and ownership guard handle closed,
cancelled or replaced sessions. Reconnect recovers a failed native handoff only while
it still owns the specifically prepared handler and no other connection/fallback won.
It does not recreate queue entries removed by CTD, the user, bans or retry exhaustion.

Queue disabled, excluded/inactive destination, bypass permission or integration off
uses standalone reconnect. PAUSED does not silently direct-connect. Reloads refresh
native manager snapshots; removing a target terminates cleanly instead of pinging a
stale backend. Queue-on-shutdown controls CTD's fallback policy independently of this
explicit plugin reconnect feature. Native banned-reason policy remains authoritative.

See [queue test evidence](ctd-native-queue.tdd.md). Local tests cover the native queue,
entry and event-manager code from official CTD410/411; a real multi-proxy Redis setup
and an in-game PLAY backend restart remain staging checks. The earlier successful
nLogin2.0.18 smoke and the later license-unlinked refusal are historical validation
boundaries, not a claim that current premium authentication was retested.
