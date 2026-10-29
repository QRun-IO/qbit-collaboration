# My Work summary query bounds

The summary previously materialized every matching task, assignment and mention.
A regression fixture measured 601 parent lookups for 601 tasks on two parents.

The summary now scans each category in primary-key pages of at most 200 rows.
It retains at most ten display tasks and sorts them using the existing due-date
order (earliest first, null last, then ID). Counts still include every visible
matching row, rather than silently truncating at a display limit. Assignment and
mention filters remain scoped to the signed-in user.

Each page caches parent visibility only for that page, using the existing caller
permission and record-lock checks. Missing or revoked parents contribute neither
rows nor counts; unexpected authorization/backend failures propagate. No decision
is shared across requests. The starting maximum ID bounds each category scan so
concurrent inserts cannot extend it indefinitely. Like the earlier summary, this
is not a transactionally consistent snapshot across the three categories.

This bounds row retention, not total work independently of data size. For N
matching rows, each category needs one high-watermark query plus approximately
ceil(N/200) page queries, and one parent lookup per distinct parent per page.
Many unique parents still require linear authorization work. Per-record heavy
field size is also outside this row-count bound. Database and browser latency,
N-node presence, saturation and production capacity remain part of #1291/#1302.

Regression evidence: the 601-task case passes a budget of at most eight parent
queries, with no task query above 200 rows; correct top-ten ordering and revoked
access on a subsequent request are checked. Another case crosses assignment and
mention page boundaries and excludes hidden parents, other users and undelivered
mentions. These tests use the framework's real memory backend instrumentation.

`MyWorkPostgresTest` repeats the 601-row case using PostgreSQL 17 and the generated
production schema. Independent `pg_stat_statements` observations verify exactly
five task queries and between one and eight parent queries. It also verifies the
same top ten, visible count and next-request permission revocation against SQL.
The fixture passes; these query counts are not sustained throughput or
deployment-capacity measurements.

## Reproducible local workload

Run `mvn -Dtest=MyWorkPostgresTest -Dqqq.mywork.load=true test` with Java21 and
Docker. The fixture owns a disposable PostgreSQL17 container, uses the generated
schema and real authorization checks, and writes `target/mywork-load.csv`.
It never connects to an application database. The optional workload runs after
the normal query-budget regression so ordinary CI keeps the smaller test.

Each scenario has 2,000 tasks: one uses ten shared parents, the other 2,000 distinct
parents. Every tenth parent is inaccessible. Two warmups precede ten measured
sequential summaries. Every sample verifies 1,800 visible tasks and the exact top
ten. SQL calls and server execution time are collected independently using
pg_stat_statements after warmup. Latency includes service execution and database
round trips, excluding fixture setup and assertions.

CSV percentiles use nearest rank. With only ten samples, p95 and p99 both equal
the maximum observed sample; they are descriptive, not stable tail estimates.
SQL figures are totals for all ten samples. This is a single-JVM local workload,
not HTTP/browser latency, sustained concurrency, pool saturation, multi-node
presence or a deployment-capacity claim. Do not derive production alert thresholds
from these numbers. No wall-clock performance assertion is added to CI.

The fixture explicitly uses the framework C3P0 connection provider, matching the
host's provider, with initial/minimum1, maximum3 and a two-second checkout timeout.
It verifies the pool stays within that cap, has no checkout timeouts and returns
all borrowed connections after each scenario. The original fixture used the
unpooled default; the expanded workload failed with a Windows socket-allocation
error after repeatedly opening connections. This was a fixture mismatch, not
proof of a Kof22 outage. The same workload completed with pooling.

Observed local run (Java21, PostgreSQL17 in Docker on Windows, ten sequential
samples after two warmups): shared-parent p50=91.192ms, max=112.105ms; distinct-parent
p50=960.565ms, max=1100.260ms. SQL calls per summary were113 and2013 respectively.
This confirms linear distinct-parent authorization work remains despite bounded
row retention. The existing exact-count semantics still require examining every
matching task. No runtime optimization or production-capacity claim follows from
this fixture measurement alone.

## Compatibility with the later attachment changes

On October 26, an isolated source export combined attachment commit
`dc65a826ce362416d55d91136fa39225f4a7cf94` with the four My Work files from
`2ac067bea4af199486f90e2dc5212baf8910b8fb`. Both branches share base
`15cb8cbf72aa9a5e033280cf5a38384d1295fc7d`; their changed files do not overlap.
This was a source-overlay verification, not a branch merge or publication.

Java 21 `./mvnw.cmd verify -Dqqq.mywork.load=true` passed all 209 tests with zero
failures, errors or skips, including the 29 attachment transaction tests.
Packaging and JaCoCo gates passed. The optional PostgreSQL workload retained the
same visible-count, top-ten, query-budget and pool assertions. Shared-parent
p50/max were 76.301/96.044 ms; distinct-parent p50/max were 939.896/1035.402 ms.
SQL counts remained 113 and 2013 per summary. These local measurements preserve
the limitations above; they do not qualify an integrated published artifact or
Kof22 deployment. The eventual integrated branch still needs its own CI gates.
