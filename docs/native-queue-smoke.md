# Native queue startup smoke — 24 October 2026

Actual CTD411 and ForcePack1.3.75-SNAPSHOT ran with both queue-release plugins,
native local queue enabled and Redis disabled. Both plugins loaded successfully.
Three synthetic offline protocol765 clients completed login, resource-pack statuses
and CONFIG; the first stayed on the same socket for **36.00507s**. Both reload
commands completed twice, preserving config hashes, comments and an unknown empty
list. No ERROR, binary linkage, initializer or reference-count exception occurred.
Exit0 and all three loopback listeners closed.

This post-fix pre-commit test validates mapping initialization under Gson2.15 as
well as login/pack/reload lifecycle. The startup verifier was also run against the
broken candidate: despite CTD reaching Done it exited1 for missing plugins and
initializer errors. With the corrected registries the full fixture exits0.

[Sanitized result](verification/native-queue-smoke-result.json). Final release jar
hashes and embedded source revisions are listed in release PROVENANCE.json and
SHA256SUMS, with an additional release-time fixture check.

The fake backend only supplies login/CONFIG packets; this is not a complete in-game
PLAY→backend restart→limbo→return or distributed Redis test. nLogin was omitted from
this run because the local Premium input reports an unlinked license. The earlier
CTD410 nLogin2.0.18 success is a separate historical test, and no premium/password
authentication or license bypass is claimed. No production files were read or changed.
