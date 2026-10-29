# Independent-node presence workload

This fixture targets QQQ #1302/#1291. It runs 2–8 independent JVM HTTP servers
against an owned loopback Redis container. Every node serves the same configured
mock identities across 2–8 tenant scopes. The scope comes from the authenticated
fixture identity, never a supplied tenant parameter. Records are independently
seeded in memory; this does not qualify shared database or production IAM behavior.

Build with Java 21:

```powershell
./mvnw.cmd test-compile dependency:build-classpath '-Dmdep.includeScope=test' '-Dmdep.outputFile=target/capacity-classpath.txt'
python docs/performance/presence_nodes.py --java /path/to/java --nodes 3 --tenants 2 --users 6 --tabs 4 --rounds 100
```

Four tabs per user means two duplicate tabs on each of two records. Every round
renews each issued lease on a rotating node, with sequential per-tab sequences.
All nodes must then return exactly the expected tenant's distinct people and
exactly two editors on both records. Any mismatch or request failure fails the
run. Requests use bounded worker concurrency and ten-second timeouts. Each node
has eight pooled Redis connections, a 500 ms pool wait and a 384 MiB heap ceiling.

Results and per-node logs are retained under `target/capacity-nodes-<id>`.
The result records parameters, node PIDs, elapsed time, sample counts and
nearest-rank HTTP p50/p95/p99/max. Renewal throughput includes snapshot checks
and is not maximum server throughput. All owned JVMs and the Redis container are
removed in `finally`; the receipt reports cleanup separately from test success.

Each fixture JVM emits resource samples every 100 ms using Java management APIs
and the Redis QBit's existing pool metrics. The receipt summarizes sampled heap
used/committed bytes, active/idle/waiting pool counts and the configured maximum.
CPU core equivalents are process CPU time divided by observed wall time; they
can exceed one for a multithreaded JVM. Observation begins after server startup
and includes lease setup and workload execution. Unsupported CPU counters are
reported as null. Linux fixtures also read process `VmRSS` from `/proc/self/status`
every 100 ms and record `residentBytes_sampled_max`; unsupported platforms report
null. Linux-container runs require resident-memory samples. Heap is not total
resident memory, and process RSS is not the container's complete cgroup footprint.
Sampled peaks may miss
short bursts and must not be represented as exact maxima. Raw samples remain in
node logs; missing node samples or a sampled pool-bound violation fail the run.

The final Redis INFO capture records `used_memory`, lifetime `used_memory_peak`,
and current `used_memory_rss`, plus process user/system CPU seconds since Redis
startup. Redis RSS is an end-of-run observation, not a sampled peak. These fresh,
owned Redis instances include fixture setup and diagnostics in their lifetime;
do not interpret those CPU values as workload-only measurements or add independent
process peaks and call the sum a measured simultaneous maximum. Missing Redis
memory/CPU diagnostics fail acceptance while cleanup still runs.

The [100-user resource run](results/linux-100-users-resources.json) used three
Linux JVMs, two tenants with 50 people each, four leases per person, 16 request
workers and 200 rounds. All 80,000 renewals and 2,400 exact-membership snapshots
passed in 102.568 workload seconds. Renewal p50/p95/p99/max were
11.326/21.765/25.881/1,007.404 ms; snapshot p99 was 19.424 ms. The one-second
renewal maximum is retained, not discarded. Cleanup passed.

Sampled JVM RSS peaks were 662,786,048 / 662,188,032 / 703,479,808 bytes
(approximately 632–671 MiB), while used-heap peaks were about 171–177 MiB.
CPU observations were 1.73–1.83 core equivalents per JVM; sampled pool-active
peaks were 7/7/6 of eight with no sampled waiters. Redis peak allocated memory
was 2,579,896 bytes; final RSS was 8,777,728 bytes. Redis process CPU since
startup was 23.738 system plus 35.526 user seconds. These measurements include
fixture overhead, omit browser load, and do not establish deployment limits.

Initial smoke run: three JVMs, two tenants, six users each, four tabs per user,
three rounds: 144 renewals and 36 snapshots passed with tenant isolation,
duplicate-tab deduplication and two editors retained. Cleanup passed. This short
run validates the harness, not sustained capacity or operational thresholds.

A second run with 12 users per tenant and 100 rounds passed 9,600 renewals and
1,200 snapshots in 23.110 seconds. Renewal p50/p95/p99 were 7.246/16.454/21.853 ms;
snapshot p50/p95/p99 were 12.908/15.776/18.239 ms. Every round checked exact
tenant membership, deduplication and two editors on both records at every node.
Cleanup passed. These are measurements from one local Windows/Docker run;
they do not establish deployment limits or browser propagation latency.

## Sustained-run limitation under investigation

The 1,000-round workload has intermittently failed with HTTP 503 on lease
renewal. Four runs failed; one completed 96,000 renewals and 12,000 snapshots.
The passing run does not resolve the failures. Temporary local diagnostics on
the third failure identified `JedisConnectionException` caused by
`SocketTimeoutException` while reading the `EVALSHA` response in
`RedisPresenceStore.mutate`. The configured socket timeout remained two seconds.
The temporary production-file diagnostics were removed after identifying this
path. No timeout increase, hidden request retry or production fix is included.

Resource samples did not show pool waiters, but cannot rule out short bursts.
Redis slow-command timings and latency events are now captured before fixture
cleanup, alongside Redis CPU, memory and command counters, to distinguish server
execution cost from transport or scheduling delays. Slow-command summaries omit
command arguments. These diagnostics belong only to the disposable fixture.
Until the cause is established and qualified, this workload establishes no
supported sustained throughput or production alert thresholds.

The timing-instrumented Windows-JVM run failed at round 480, with no commands
at or above the configured one-millisecond Redis slow-log threshold and no
latency-monitor events. Redis reported about 93 microseconds per `EVALSHA` on
average. This weakens a slow-Lua explanation but does not identify the delayed
component or exclude time spent outside command execution.

## Linux comparison

`--node-image eclipse-temurin:21-jre` optionally runs each JVM in its own Linux
container on an owned Docker network shared with Redis. The HTTP driver remains
on the host. Only loopback HTTP ports are published. Compiled fixture classes
and copied dependency JARs are mounted read-only; Redis remains disposable.
The existing loopback defaults remain unchanged when this option is absent.
Container mode records Docker client PIDs separately from container identities.
It removes the Windows-JVM-to-published-Redis-port path, but also changes the JVM
platform and scheduling environment; it is a diagnostic comparison, not proof
of a specific operating-system or Docker defect. Cleanup includes every owned
node container and the owned network.

`--churn-every 20` departs all tabs belonging to the last user in each tenant
every 20 rounds. Every departed lease must reject a later renewal with HTTP 409
and `PRESENCE_LEASE_EXPIRED` on another node. All surviving nodes must show that
user absent on both records, then show the original exact membership after
fresh, distinct leases are acquired and activated. Only this explicit rejoin
path acquires new leases; the node-crash path preserves existing ones.

`--pause-redis` adds a controlled failure phase after the ordinary rounds. It
freezes only the owned Redis container, sends up to 64 concurrent renewal
requests, and requires HTTP 503 within an eight-second fixture bound. Snapshots
must also fail explicitly rather than return an empty room. Redis is unpaused
in `finally`; once reachable, higher-sequence renewals must recover the same
leases and exact membership. The test requires observed pool waiters and rejects
sampled active counts above the configured maximum. It does not retry failed
requests invisibly or raise production timeouts.

The [Linux Redis-stall receipt](results/linux-redis-stall.json) passed with three
nodes, two tenants, 24 users and 96 tab leases. All 96 stalled renewals and all
three node snapshots returned the expected unavailable response. Existing
leases recovered after Redis resumed, and cleanup passed. This proves bounded
failure and recovery for an injected stall; it does not explain the intermittent
Windows timeout or establish a saturation throughput threshold.

```powershell
python docs/performance/presence_nodes.py --java java --node-image eclipse-temurin:21-jre --nodes 3 --tenants 2 --users 12 --tabs 4 --rounds 1000
```

The first Linux comparison completed 1,000 rounds in 250.811 seconds: 96,000
renewals and 12,000 snapshots, with exact membership/editor assertions and
cleanup passing. Renewal p95/p99 were 15.331/17.760 ms. The sampled active pool
peak was five of eight, with no sampled waiters. This one run is separate from
the unresolved Windows failures and does not establish a production SLA.

`--crash-round 50` abruptly kills the first node before that round, then sends
all existing leases through surviving nodes. It does not acquire replacement
leases or hide failed requests with retries. Exact tenant membership, duplicate
tabs and editor counts must remain correct on every surviving node. This tests
HTTP lease continuity after process loss, not browser/SSE reconnect, ingress
failover, database durability or a Redis outage.

The Linux crash run passed 100 rounds (9,600 renewals and 996 snapshots) in
25.148 seconds. The first JVM exited with code 137 at round 50; the remaining
two retained all issued leases and expected membership. Cleanup passed.

A [combined churn and node-loss run](results/linux-node-crash-churn.json) passed
100 rounds with the first node killed at round 50 and churn every 20 rounds.
Five cycles rejected all 40 departed handles and checked absence/restored
membership across both tenants and records on every surviving node. Cleanup
passed. This is bounded HTTP lifecycle qualification; browser reconnect and
missed-event behavior remain separate requirements.

Recorded receipts retain both successful and failing evidence:

- [Windows timeout with Redis diagnostics](results/windows-timeout.json)
- [Linux 1,000-round run](results/linux-1000-rounds.json)
- [Linux node crash](results/linux-node-crash.json)

Only the Java executable path is normalized in these receipts. The Linux image
used for these runs was
`eclipse-temurin@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99`.

## Browser propagation baseline

`browser_subscription_affinity.mjs FRONTEND_REPO BASE_URL RESULT_PATH` verifies
the current owning-node subscription contract against the two-node fixture. It
uses the connection ID from the real browser's successful subscription request,
then preserves the same session cookies while changing only the test routing
cookie. The [published-artifact receipt](results/browser-subscription-affinity.json)
records owner 200, other node 404, owner 200, with the requested channel active
on both successful responses and the unsaved draft retained. This proves why
subscription requests need owning-node routing even when presence state is shared.
It does not test the deployed Traefik cookie, HTTPS, buffering or failover.
The packaged runner accepts this script through `--affinity-script`.

`browser_presence_latency.mjs` measures Chromium field-focus changes until a
different editor sees the corresponding remote marker. Supply a caller-owned
two-node loopback fixture serving the published frontend with `alice`/`admin`
mock identities and the acceptance router's `presence_node=first/second`
affinity cookies:

```powershell
node docs/performance/browser_presence_latency.mjs /path/to/frontend http://localhost:PORT result.json 25
```

Use `presence_router.mjs FIRST_NODE_PORT SECOND_NODE_PORT ROUTER_PORT` for this
test. It binds only to loopback and adds a test-only POST endpoint that severs
first-node SSE responses. Never deploy this fault-injection router. The packaged
acceptance fixture accepts it through `--router-script` alongside `--latency-script`.

The test verifies the target marker is absent before every change, the old
marker disappears afterward, both real SSE streams opened successfully,
issued-lease writes reached distinct nodes, and unsaved drafts survive. API
responses are not mocked. The fixture owns backend/router/Redis cleanup; this
script owns and closes its browser.

After timing the normal transitions, the script takes the observer offline and
explicitly severs its existing SSE response through the fixture router. It requires
an observed stream failure, a failed offline fetch, three acknowledged sender focus
changes, and absence of the new marker while disconnected. After network restoration
it requires a new successful SSE response and the current marker with the previous
marker removed, without reload or loss of either unsaved draft. Recovery may use
the shipped reconnect and polling behavior; this does not prove event replay.

The [disconnect/recovery receipt](results/browser-reconnect.json) passed against
the public RC37 JAR: one stream severed, three missed changes, recovery in 1,858 ms,
both drafts preserved. The accompanying six-user and Redis admin checks passed;
fixture cleanup passed. This is one Chromium disconnect case without concurrent
load, not a reconnect storm, lease-expiry test, or production recovery objective.

Append `load` to the browser command (or use `--latency-load` on the packaged
fixture) to add 16 concurrent HTTP lease workers: four mock identities with four
leases each on the same record, split between both nodes. Each worker renews
sequentially with a 100 ms pause after each response. The browser observations
are spread over at least 60 seconds; every observation interval must include
completed background renewals. The workload continues during disconnect/reconnect.
Any request or lease-cleanup failure fails the test. Responses are disposed after
reading, and workers stop before the contexts close. The receipt reports completed
renewals, observed node counts, elapsed time and any errors. These are HTTP load
workers, not 16 additional browser tabs or distinct people.

The [bounded load/reconnect receipt](results/browser-load-reconnect.json) passed
9,046 renewals over 69.728 seconds, with no worker or lease-release errors.
Across 25 observed focus transitions, p50/p95/p99 were 32.384/85.391/785.495 ms;
p99 is the maximum of this small sample, not a reliable tail-latency estimate.
The observer recovered in 1,855.887 ms after missing three updates while load
continued. Both drafts survived. Six-user and Redis admin acceptance also passed
against the public RC37 JAR; fixture cleanup passed. An initial run failed only
because the new load helper used the wrong lease-release endpoint; that receipt
is retained in the local packaged fixture history, not counted as a passing run.

The [25-transition receipt](results/browser-focus-25.json) passed against the
downloaded public RC37 JAR and published backend snapshots. Focus-to-observed-DOM
p50/p95/p99 were 31.648/33.078/36.092 ms; p99 is the maximum in this small sample.
There were no page errors or failed lease responses. Six-user presence and Redis
admin browser checks also passed in the same fixture run; fixture cleanup passed.

This baseline includes browser automation, UI debounce, network, invalidation
and rendering. It is not a server-only transport measurement, an SSE-only causal
proof, an under-load result, or a production latency objective. The separate load
receipt above covers one bounded workload and disconnect scenario; larger loads,
reconnect storms and deployment-specific behavior remain unqualified.

Remaining qualification includes longer soak and larger workloads, container
resource limits, reconnect storms and the actual deployment's capacity and alert
thresholds. The receipts above cover specific bounded HTTP, browser, saturation,
churn, missed-update and process-crash cases, not every combination. The native
Windows intermittent Redis read timeout remains unresolved. Loopback HTTP latency must not be reported
as browser propagation or a production SLA. No deployed capacity threshold is
established by this fixture.
