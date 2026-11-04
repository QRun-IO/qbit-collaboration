# Collaboration attachment integration status

Updated: 2026-11-04. Implementation and local owner verification complete; this feature branch is not a published release.

## What changed

The attachment lifecycle, retained upload journal, recovery and cleanup APIs are preserved on canonical Collaboration. The default Core dependency is now the compatible `4.1.0-SNAPSHOT` from Core commit `551397c7c59b0f77833fc81e195f4c01cb1c282f` (see [setup](CORE-DEPENDENCY.md)). Recovery queries now handle tables without supplemental metadata instead of throwing a null-pointer exception.

## Verification

Using Java 21, the Maven wrapper, Docker and that locally installed Core snapshot:

```powershell
.\mvnw.cmd '-DmysqlJournalTests=true' '-DpostgresJournalTests=true' '-Dexec.skip=true' install
```

All **269 tests passed**, with zero failures, errors or skips. Checkstyle and the JaCoCo coverage gates passed. This includes 29 PostgreSQL attachment transaction/recovery cases, 11 MySQL journal cases, 12 PostgreSQL journal cases, HTTP API tests and dependency-boundary guardrails. Each provider fixture creates and removes its own disposable services; no existing database is used. The first run exposed the fixed null handling and one transient Windows socket allocation failure; the complete corrected run passed.

`-Dexec.skip=true` avoids the inherited POSIX console-summary command on Windows; it does not skip tests, Checkstyle or coverage. The artifact was installed into an isolated local Maven repository as `com.kingsrook.qbits:qbit-collaboration:0.1.0-SNAPSHOT`.

## Downstream consumer verification

Record Files `develop` at `162af9a7b8cd9c2673c26c88f00323cd90e85e48` compiled and passed its default `verify` lifecycle against this locally installed Collaboration snapshot and Core `4.1.0-SNAPSHOT` using `-Dqqq.version=4.1.0-SNAPSHOT`. Surefire reported 278 tests: 220 executed successfully and 58 optional MySQL/PostgreSQL cases skipped. Style/coverage gates passed. This proves consumer API compatibility and the exercised HTTP, retention and recovery paths; it does not claim the skipped Record Files provider qualification or its full release.

## Release boundary

Core and Collaboration must be published as a compatible dependency set before adoption from public Maven coordinates. Passing local fixtures does not qualify a deployment's storage, backup/restore or database failover. See [known issues and remaining release requirements](QBIT-KNOWN-ISSUES.md).
