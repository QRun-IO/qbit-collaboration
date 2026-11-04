# Retained upload journal

The Collaboration-owned journal is wired into retained `AttachmentsService` upload and recovery. It persists candidate metadata before object creation, owns the attachment/checkpoint transaction, and exposes native read-only recovery navigation. It has no Record Files dependency and stores no second copy of file bytes.

The integrated owner snapshot passed all **214 tests**, including 10 real MySQL provider cases and 11 H2 journal regressions, and was installed under `0.1.0-record-files-SNAPSHOT`. The subsequent doublewrite/session-read-only delta compiles and awaits its provider rerun. This document does not qualify Aurora, other SQL vendors, external object storage, disks, backups, or a host deployment.

## Ownership and recovery

1. `prepare` independently commits the immutable server-built candidate before the first PUT. Matching retries reuse it; changed manifests conflict.
2. `reserve` locks the row and grants a bounded lease with a monotonically increasing fence. `renew` extends only a still-live exact reservation at synchronous stage boundaries.
3. `recordStorage(UNKNOWN)` precedes conditional object creation. `VERIFIED` means the storage owner proved the exact length and SHA-256.
4. `register` passes its real transaction to the attachment registrar. The attachment insert and REGISTERED checkpoint commit together; callback failure rolls both back.
5. The retained lifecycle registration runs after commit and is retried idempotently. The journal becomes COMPLETE only after it succeeds. Completed coordinated retries still invoke required lifecycle registration without adding a second attachment or activity event.
6. Recovery reauthorizes the original actor and current source. It verifies the stored original without overwriting it and resumes registration. Missing or unreadable bytes remain uncertain; timeout never proves absence.

The owner rejects ambient/no-op transactions. SQL values are bound; mutable operations lock rows, check the token/fence/expiry, and require exactly one guarded update. Reads reconstruct manifest hashes. Private locator and lease columns are absent from QQQ metadata and forbidden in native filters/projections; native direct writes and unscoped totals are denied. Current actor/source authorization remains in the owner service and native journal security, not in the low-level JDBC port.

Disabled or deleted original actors still need an explicitly designed administrative reconciliation flow. No fabricated session or broad archive permission substitutes for that flow. Broad post-filtered listings can contain short pages; source-pinned native recovery navigation is the supported user entry point.

## Database boundaries

Both owner tables must use the same QQQ backend. Hosts apply DDL through their migrations; runtime code never creates tables or changes server-global settings.

| Backend | Runtime requirements | Schema |
| --- | --- | --- |
| File H2 | Native `h2`, `jdbc:h2:file:`, actual configured connection, writable real transaction, live `WRITE_DELAY=0` | `UploadJournalTable.h2Script()` |
| MySQL 8.4 | Native `mysql`, actual MySQL 8.4 product/version, exact configured URL target and catalog, writable real transaction, both owner tables InnoDB, fixed owner table mappings, journal `utf8mb4_0900_bin` columns/table, single-column unique locator/intake indexes, synchronous commit settings | `UploadJournalTable.mysqlScript()` |
| Aurora MySQL, MariaDB, PostgreSQL, other versions/providers | Refused; no inferred compatibility or durability claim | None |

The MySQL adapter resolves explicit or native-generated URLs through QQQ `ConnectionManager`. It accepts a single ordinary `jdbc:mysql://host:port/catalog` target with a simple catalog identifier. Proxy/load-balancing URLs, alternate catalogs or table remapping fail closed. Error messages do not echo URLs or credentials. Driver options remain host-owned; TLS and credentials are not changed by the journal.

Each MySQL operation checks `innodb_flush_log_at_trx_commit=1`, and `sync_binlog=1` when binary logging is enabled; a read-only server or session is refused. It also requires `innodb_doublewrite` to be `ON` or `DETECT_AND_RECOVER`; [detect-only mode does not retain recoverable page copies](https://dev.mysql.com/doc/refman/8.4/en/innodb-doublewrite-buffer.html). These settings follow the [MySQL 8.4 InnoDB durability documentation](https://dev.mysql.com/doc/refman/8.4/en/innodb-parameters.html#sysvar_innodb_flush_log_at_trx_commit) and [binary-log durability documentation](https://dev.mysql.com/doc/refman/8.4/en/replication-options-binary-log.html#sysvar_sync_binlog). Hardware that misreports flush completion can still lose data; replication/failover, backups, restore and disk guarantees remain deployment qualifications. Runtime settings are checked at transaction entry; an administrator changing server settings concurrently is outside the application trust boundary.

The MySQL journal uses millisecond timestamps with explicit UTC JDBC calendars, InnoDB, enforced CHECK constraints and binary NO PAD string comparisons. Its live session must report UTC (+00:00/UTC, or SYSTEM with a UTC system zone); the journal does not silently change a pooled session timezone. Hosts should configure the native JDBC driver timezone consistently for generic QQQ date fields as well. The host must configure each journal connection with `innodb_lock_wait_timeout` between 1 and 5 seconds, through its backend JDBC sessionVariables/pool setup (or a dedicated database default). Qualification reads and preserves that session setting; it never changes it or other pooled-session/global settings. Each statement also has a ten-second query timeout. Duplicate error 1062 is distinguished from unrelated integrity failures; deadlock/lock-timeout failures remain typed BUSY responses, never fabricated success.

## Reproducible tests

Use the repository Maven wrapper and the same revision/QQQ dependency overrides as the integrated build. The default suite remains Docker-independent; explicitly enable the provider fixture:

```powershell
.\mvnw.cmd '-Drevision=0.1.0-record-files-SNAPSHOT' '-DmysqlJournalTests=true' '-Dtest=MySqlUploadJournalTest,UploadJournalTest' test
```

`MySqlUploadJournalTest` creates only its own Testcontainers MySQL 8.4.13 container, random synthetic credentials and private anonymous data volume, and destroys that container on completion. It does not use existing host services. The image is pinned to `mysql@sha256:a0225d1ea6631c8bc2385dc4c2faba17bbf1834ecf369e1659a9a382bd8d5dd7`. Credentials are not printed by the fixture.

The eleven provider cases cover acknowledged prepare surviving a real database process restart, attachment/checkpoint rollback and completion replay, simultaneous prepare/lease fencing, wrong backend/catalog/mapping/ambient/Aurora refusal, actual non-durable server settings and MyISAM refusal, binary identifier comparisons and native write denial, actual lock-timeout/BUSY behavior, column/index drift and immutable conflict, and lease instants across differing JVM default zones, preservation/refusal of host session lock-timeout settings, and refusal of detect-only doublewrite/read-only sessions without changing their settings. The attachment schema in this suite is deliberately a minimal transaction fixture; this is not an end-to-end MySQL object-storage or tenant/archive qualification. Record Files owns its separate provider integration tests.

First provider run compiled but failed its restart fixture because Docker reassigned the dynamic port and Testcontainers retained a cached mapping; two subsequent setup errors followed that stale endpoint. The fixture now refreshes the inspected binding after restart and uses bounded JDBC readiness checks. Docker wire logs are suppressed before container creation. Expanded snapshot verification: **20 tests passed (9 MySQL, 11 H2), zero failures/errors/skips**, `target/mysql-journal-focused-verification.log`, completed 2026-10-31 00:01:44. The tenth provider case and full 214-test owner quality gates subsequently passed; the corrected development-GAV artifact SHA-256 is `72D6E36E77B92BE3ECFE9BE7042D292C76B308AA05A9E43BD490B229BD1A75E2`. The eleventh provider regression and doublewrite/read-only delta await fresh verification.
