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

Remaining qualification includes larger sustained workloads, CPU/memory and pool
utilization sampling, browser/SSE propagation percentiles, saturation, churn,
missed messages and process crashes. Loopback HTTP latency must not be reported
as browser propagation or a production SLA. No deployed capacity threshold is
established by this fixture.
