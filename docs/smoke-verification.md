# Final isolated compatibility smoke — 24 October 2026

Run: `runtime-20261024T130926-nlogin`. Inputs match the final repository build jars byte for byte.

- Velocity-CTD 4.3.1 build 410, actual nLogin 2.0.18, actual ForcePack 1.3.75-SNAPSHOT and both rebuilt MCExp jars started together.
- Three synthetic offline protocol765 connections completed login and CONFIG. The first connection stayed on the same socket in CONFIG for a measured **36.00048 seconds** with ForcePack pending, then completed its pack statuses and CONFIG acknowledgement. Two further logins succeeded after the reload checks. nLogin changed the test profile to `Smoke765+`.
- Actual ForcePack received ACCEPTED, DOWNLOADED and SUCCESSFUL for the localhost fixture zip on each connection, then released its CONFIG continuation.
- Actual public LimboAPI.reload() ran twice through the disposable fixture command, and the LimboReconnect reload command ran twice. Both explicit completion markers appear twice in console output. The nLogin profile hook remained active.
- Administrator comments and an unknown empty list remained byte-identical across both reloads in both plugin configs. The nLogin pre-limbo list was preserved.
- No NoSuchMethodError, NoSuchFieldError, class-loading error, initializer error or Netty reference-count exception appeared. The proxy stopped with exit0; proxy/backend/HTTP fixture listeners are now closed.

The sandbox uses only loopback random ports, fresh jar defaults, a generated local resource-pack zip and a fake backend. nLogin routing comes from its bundled template, with auth-server `smoke-backend` and backend acknowledgement checking disabled because the fake backend does not run nLogin. No production config, account, database, credential, license or cache was copied.

Boundaries: the client is synthetic and stops after CONFIG acknowledgement; no world registry/JoinGame, resource rendering, password/premium authentication or real backend restart/reconnect cycle is claimed. The final console contains no ERROR messages. The fixture read timeout is 75 seconds because its fake backend sends no heartbeats; this avoids conflating the normal 25-second idle timeout with the independent reserved-identity lifecycle timer.

## Input SHA-256

- `limboapi.jar`: `58696d7e79561a924703c453f3173ac242a0dbde88063bce59d58ca65742dca7`
- `limboreconnect.jar`: `48eda7ac00e60a0661b52d8317e5343da4b437e908ffffec34582beeb4b38641`
- `forcepack.jar`: `654e4b5b8e9b94e65eb4d96ba3c89751b9d19ece5935d165242c6db2ba9021eb`
- `nlogin.jar`: `a685cdf1097a99559a95835e6bc69f1386d3605c930147e2a897ceb54b5ad1b1`

Evidence: [sanitized report](verification/smoke-result.json). Full private runtime logs remain in the operator workspace; third-party licensed jars and generated secrets are not distributed.

The listed MCExp hashes identify the original pre-commit test artifacts. Release binaries embed the published source revision and have their own SHA256SUMS; runtime code equivalence and a final paired smoke are verified before publication.
