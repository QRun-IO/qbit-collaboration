# Recovery cost diagnostic — October 26, 2026

One Java 21 process, one Redis connection, disposable loopback Redis 7.4-alpine,
1,000 issued leases, ten viewers per record, no TLS or HTTP. The probe keeps
all leases alive through the real 45-second recovery window and verifies that
the final table snapshot contains all 1,000 viewers. Each phase samples 200
renewals with increasing sequence numbers. This is not a capacity or SLA claim.

| Renewal latency (ms) | During recovery | Ready |
|---|---:|---:|
| p50 | 3.6779 | 0.5134 |
| p95 | 4.1956 | 0.7226 |
| p99 | 4.5447 | 0.9122 |
| Maximum | 6.8271 | 1.4886 |

Population took 4,640.799 ms. The run passed all correctness checks. This first
implementation scanned touched projections on every request during recovery.

After changing table cleanup to occur atomically only when creating its recovery
generation (individual record cleanup remains lazy), the same diagnostic passed:

| Renewal latency (ms) | Optimized recovery | Ready |
|---|---:|---:|
| p50 | 0.6375 | 0.5029 |
| p95 | 0.8150 | 0.5762 |
| p99 | 0.9406 | 0.7500 |
| Maximum | 4.2280 | 1.2251 |

Optimized population took 1,634.486 ms. All 1,000 leases survived. The focused
recovery/store suite also passed 13 tests, including guard loss with previously
unvisited record rooms. Full optimized verification passed 184 tests with no failures/errors/skips and coverage gates passed. These are
individual local runs and need further qualification before claiming supported
production capacity. They do not isolate every source
of latency, compare against an earlier release, or measure sustained concurrent
load, saturation, TLS, HTTP or browser propagation.

At 10,000 entries the optimized diagnostic also passed, retaining all leases.
Population took 10,687.2105 ms; the same 200-sample phases measured:

| Renewal latency (ms) | Optimized recovery, 10,000 entries | Ready |
|---|---:|---:|
| p50 | 0.4978 | 0.5341 |
| p95 | 0.5914 | 0.7108 |
| p99 | 0.7255 | 1.2964 |
| Maximum | 0.7544 | 1.8799 |

Do not infer that larger tables are faster from these separate local runs.
JIT warmup, scheduling and concurrent machine activity were not controlled.
The useful result is that the optimization avoids a repeated whole-table scan
during recovery and both populated-table runs preserved lease correctness.

The explicit `RedisPresenceRecoveryCostProbe` test-source main class is not
automatically run by the unit suite and imposes no machine-dependent CI gate.
After test compilation, run it on the test classpath with argument `1000`.
It fails if the real recovery window ends before the cold measurement, rather
than silently reporting ready-state latency as recovery latency.
