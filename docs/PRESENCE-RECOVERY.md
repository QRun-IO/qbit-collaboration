# Presence recovery candidate — QQQ #1300

This is an unreleased implementation candidate in collaboration draft PR47.
The existing store/service suites and CI pass; host adoption and enforced
maintenance remain incomplete. It is not part of the published snapshots.

## Contract under qualification

Each deployment/application/tenant/table has a persistent recovery guard in the
same Redis hash slot as its bounded room projections. Each Lua operation reads
that guard atomically with its room work. The guard contains a generation, a
readiness deadline and the latest issued expiry. A missing guard, or complete
loss of table projections before that expiry, starts a fresh generation and a
recovery interval of one lease lifetime. Existing live browsers can reserve and
renew replacement leases during that interval. Snapshots report recovery rather
than a successful empty room until the interval finishes.

Lease entries carry the guard generation. Recovery removes invalid-generation
projections from touched rooms so they cannot consume all capacity and prevent
re-registration. Existing authorization remains in PresenceService; the guard
does not grant record, tenant or field access. The shared adapter maps recovery
to a sanitized service-unavailable response. Record-version conflict checks are
independent of this advisory state.

An untouched new namespace is conservatively unknown too. Its first snapshot
therefore starts a recovery interval. Normal idle expiry retains the guard so
later empty snapshots can be known-empty. The guard has no TTL; tenant-retirement
operations and long-term guard storage must be included in operational capacity
qualification.

## Controlled restore

The host configuration has a bounded `recoveryGeneration`, default `initial`,
included in both the storage namespace and realtime audience. It belongs in
deployment configuration outside the Redis backup. All nodes must use the same
value. A restored guard can otherwise restore old authority; Redis process IDs
cannot detect arbitrary same-process data restoration.

The required maintenance procedure is: stop admission and drain all old web
nodes; restore Redis; choose a never-reused generation outside Redis; start only
nodes with that generation; verify recovery and authenticated re-registration;
then reopen traffic. Do not roll back the generation when rolling back an app.
The host tooling that enforces and verifies this sequence is **not implemented**
yet. The branch does not claim transparent arbitrary restore or split-brain
recovery. Native Sentinel/Cluster routing remains the separate Redis issue #1301.

The storage prefix is v2 so old executables cannot treat generation-bound entries
as old-format authority. This requires a coordinated deployment, not a mixed
version rolling deployment. Existing published v1 snapshots are unchanged.

## Evidence and remaining work

- Before the patch, a two-client disposable Redis test failed because total loss
  returned an empty snapshot.
- Seven focused tests now pass: full loss, loss of volatile projections while the
  guard survives, restored guard/lease bytes followed by a host generation
  change, bounded generation validation, and rejection of inconsistent lease
  lifetimes across nodes, guard loss across previously unvisited records, and
  shared cold readiness followed by normal idle expiry. The latter verifies
  that peer reads do not extend the deadline, the guard remains persistent,
  expired projections remain known-empty and expired leases cannot renew.
  These use independent clients in
  one JVM, not a deployed HA cluster.
- Full wrapper verification passed on October 26: 184 tests, no failures,
  errors or skips; packaging and coverage gates passed. This includes the
  original seven store tests and two-node six-viewer/two-editor scenario.
  The admin dashboard reads every table, so the fixture initializes every
  dashboard table for both tenants through the real backend and waits for
  recovery without writing Redis guard state.
- The service fixture socket read timeout is one second; its previous 200 ms
  limit caused a Redis EVALSHA read timeout. Production timeouts are unchanged.
- The full run includes a real HTTP recovery test: real Redis
  FLUSHDB produces 503 during recovery, the former lease returns 409 with
  PRESENCE_LEASE_EXPIRED, hidden records and unauthorized viewers remain
  inaccessible, and a new lease rejoins through the production recovery window.
  This uses a loopback HTTP server with fixture authentication, not a browser
  or a deployed host. The focused test and the combined full suite both pass.
- The store suite checks loss of either state or expiry data in both record
  and table projections. Server-issued lease renewal must fail unavailable
  without changing the surviving projection. It also covers complete loss
  of either projection pair and stale departure ordering.
- Still required: deployed-host qualification and enforced host maintenance/reset. Local
  per-operation cost measurements at 1,000 and 10,000 leases are recorded in
  `acceptance/RECOVERY-COST.md`; sustained production capacity is unqualified.
  The implementation uses no INFO call or lifecycle polling cache.
- This is an unreleased draft candidate. Publication and production deployment
  have not occurred; the remaining acceptance work above is still required.

## Independent-process acceptance

`docs/acceptance/presence_recovery_processes.py` passed on October 26 using
two independent `SharedPresenceDevServer` JVMs and one disposable Redis container.
Each user renews through the opposite node. After FLUSHDB, both nodes return 503
for presence and 409 for the former leases; fresh leases restore both viewers on
both nodes. The harness terminates only its owned processes and container.
This is fixture authentication with independently seeded memory records, not a
deployed HA application or a browser acceptance result.

Reproduce from the repository root after `./mvnw test-compile`:

```text
./mvnw dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target/recovery-process-classpath.txt
python docs/acceptance/presence_recovery_processes.py --java /path/to/java --repo /path/to/repository
```

Requires Python 3, Java 21 and Docker. JVM output and argument files stay under
`target/recovery-processes-*` for diagnosis. The harness uses the production
45-second recovery window, with bounded startup and recovery deadlines.

## Browser recovery acceptance

The same harness accepts `--frontend-repo /path/to/qqq-frontend-next`. It copies
that checkout's existing `out` export into the owned test output directory and
serves it through the real middleware on both JVMs. Playwright is loaded from
the frontend checkout; no API mocks are installed. Both browser users first
see the other viewer, then Redis FLUSHDB triggers the unavailable message and
removes stale people. Both viewer badges return without reloading either page.

The final fixed-candidate run passed all five profiles on October 26 against
commit `4b0d6a32d6bd8ecba023857d3661d9f3f5980975`: Chromium, Firefox, WebKit,
Pixel 7 emulation and iPad emulation. Source/compiled Lua hashes were checked
before the run and after each profile. Traces and screenshots remain under
each `target/recovery-processes-*` directory. The earlier Chromium screenshot
in `target/recovery-processes-229f305e83` was also visually inspected.

Select profiles using `--browser-profile chromium|firefox|webkit|mobile|tablet`.
These tests use the local export and do not establish provenance of a newly
published frontend artifact, physical-device behavior or deployed Kof22
acceptance. Fixture frontend serving is opt-in via `collab.dev.frontend`;
its default remains false.

## Controlled restore acceptance

`acceptance/presence_restore_processes.py` exercises a disposable Redis RDB
restore with two independent JVMs. It saves the populated dataset, verifies
both old JVMs have terminated and their listeners have closed, clears the
dataset, reloads the saved image and starts both nodes with a shared fresh
generation supplied outside Redis. It checks recovery responses, rejects old
leases on both nodes, and requires two fresh viewers to rejoin across nodes.
The final run passed on October 26; logs are retained in
`target/restore-processes-aec5288289`. Both leases are explicitly renewed before
saving, and rejection must complete within 40 seconds of that renewal, before
their normal 45-second expiry. Old leases are rejected again after fresh
viewers have rejoined.

The fixture enables Redis DEBUG RELOAD only on its owned loopback container.
Run after test compilation and classpath preparation:

```text
python docs/acceptance/presence_restore_processes.py --java /path/to/java --repo /path/to/repository
```

This is a protocol acceptance fixture, not host maintenance automation. Actual
host admission closure, replacement prevention and generation configuration
still require implementation and staging validation.

To qualify a packaged backend, add `--backend-jar /path/to/qbit-collaboration.jar`.
This excludes production `target/classes` from the JVM classpath, rejects a
duplicate collaboration dependency JAR, and records the artifact SHA-256 in
`target/restore-processes-*/result.json`. The receipt includes the elapsed time
for rejection of old leases (required to remain below their normal expiry),
the restore/rejoin checks and fixture cleanup. Cleanup failure fails the run.
The test repository still supplies compiled fixture classes and its dependency
classpath; this is not a standalone production launcher.

## Packaged browser qualification

The process harness accepts `--backend-jar` and `--frontend-jar` for artifact
verification. With a backend JAR, production `target/classes` is omitted from the
runtime classpath; test classes provide only the disposable server fixture. The
frontend JAR is served directly instead of copying a local `out` export.
`--frontend-repo` remains required for Playwright tooling. Dependency classpaths
must not contain a second collaboration or frontend artifact.

Each run writes `result.json` with the selected artifact SHA-256 hashes, browser
profile, result and cleanup outcome. A successful test with failed fixture cleanup
exits unsuccessfully. This records local artifact qualification, not publication
or deployment provenance. Supply `--browser-profile` to qualify each required
browser independently.
