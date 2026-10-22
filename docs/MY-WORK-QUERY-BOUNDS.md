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
