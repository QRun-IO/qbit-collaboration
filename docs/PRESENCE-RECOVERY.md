# Presence recovery candidate — QQQ #1300

This branch is an uncommitted implementation candidate. It is not part of the
published snapshots and has not passed the existing store/service suites.

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
- Six focused tests now pass: full loss, loss of volatile projections while the
  guard survives, restored guard/lease bytes followed by a host generation
  change, bounded generation validation, and rejection of inconsistent lease
  lifetimes across nodes, plus guard loss across previously unvisited records. These use independent clients in
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
- Still required: remaining cold/readiness and partial-loss edge cases,
  browser-matrix and deployed-host qualification, enforced host maintenance/reset, and measured
  per-operation cost before adoption. The implementation uses no INFO call or
  lifecycle polling cache; its additional guard reads/writes still need measurement.
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

## Chromium recovery acceptance

The same harness accepts `--frontend-repo /path/to/qqq-frontend-next`. It copies
that checkout's existing `out` export into the owned test output directory and
serves it through the real middleware on both JVMs. Playwright is loaded from
the frontend checkout; no API mocks are installed. Both browser users first
see the other viewer, then Redis FLUSHDB triggers the unavailable message and
removes stale people. Both viewer badges return without reloading either page.

This check passed on October 26 with the local export; traces and screenshots
are under `target/recovery-processes-229f305e83`. The final screenshot was also
visually inspected. This is Chromium only and does not establish provenance
of a newly published frontend artifact or deployed Kof22 acceptance. Fixture
frontend serving is opt-in via `collab.dev.frontend`; its default remains false.
