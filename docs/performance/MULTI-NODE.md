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
reported as null. Heap is not total resident memory. Sampled peaks may miss
short bursts and must not be represented as exact maxima. Raw samples remain in
node logs; missing node samples or a sampled pool-bound violation fail the run.

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

Recorded receipts retain both successful and failing evidence:

- [Windows timeout with Redis diagnostics](results/windows-timeout.json)
- [Linux 1,000-round run](results/linux-1000-rounds.json)
- [Linux node crash](results/linux-node-crash.json)

Only the Java executable path is normalized in these receipts. The Linux image
used for these runs was
`eclipse-temurin@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99`.

Remaining qualification includes larger sustained workloads, total resident
memory and Redis-server resource measurements, browser/SSE propagation percentiles, saturation, churn,
missed messages and process crashes. Loopback HTTP latency must not be reported
as browser propagation or a production SLA. No deployed capacity threshold is
established by this fixture.
