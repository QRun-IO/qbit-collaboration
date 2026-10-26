# Selectable collaboration fields: unreleased upgrade

Tracks [QQQ #1196](https://github.com/QRun-IO/qqq/issues/1196).

This source branch requires the new core virtual-field value-provider API. Its
default BOM pin is now the published
`4.1.0-virtual-field-values-1196-cc3981bb3-SNAPSHOT`. Core publisher job 9716
passed from commit `cc3981bb3c95e8f2dd7ef2a02e2233db7d0e2a79`; an independent
consumer with an initially empty Maven repository downloaded its BOM and core
and compiled the provider interface on October 26, 2026. Both artifacts resolve
to timestamp `20261026.130631-1` from Sonatype's snapshot repository.

Published artifact SHA-256 receipts:

| Artifact | SHA-256 |
| --- | --- |
| BOM POM | `d68cf837d7e07513c4e70977829f99e6236cb3e89f8af85fc75239d5b52564a5` |
| Core POM | `d89d491aaa9312051ec462e1cf165b948ee3e88c9aec2721ff25a00e956c431b` |
| Core JAR | `ae6dbeba8d0e4bc55f2714303ee3482dbf6a0cd34c03663c6009d8fe2c1da54d` |

This collaboration follow-up is still unpublished. The coordinated versions in
CANDIDATE-RELEASE.md describe the earlier candidate, not this source branch.

Earlier local verification used core artifacts installed as
`4.1.0-values1196-SNAPSHOT`, with
`./mvnw.cmd -Dqqq.snapshot.version=4.1.0-values1196-SNAPSHOT verify`.
That version exists only in the local Maven repository; it is not an adoption pin.

## Behavior

- `collaborationTags` returns sorted, distinct approved labels from global or
  matching-table vocabulary. Merged tags and deleted assignments are excluded.
  The picker and filters retain their tag IDs; exports contain one Tags column.
- EAV custom fields return typed existing values and ordinary possible-value
  display labels. Missing values are null internally and omitted from HTTP value
  maps. This change does not enable EAV writes.
- Providers process batches and use the originating transaction. Parent record
  authorization and field visibility remain enforced by core. Existing host
  post-query customizers remain installed.
- SQL DISTINCT on provider values is explicitly unsupported. Backend primary-key
  identity is required; joined heavy primary keys must be fetched explicitly.

## Release sequence

1. Publish and verify the coordinated core artifact containing the provider API (done).
2. Replace this branch's core BOM pin with that published version (done).
3. Run full collaboration verification against the published prerequisite
   (done October 26: 198 tests, no failures/errors/skips; packaging and coverage
   passed, including HTTP, PostgreSQL transaction and Redis tests).
4. Publish the collaboration snapshot and verify an independent consumer using
   published artifacts. Update CANDIDATE-RELEASE.md with the exact pair.
5. Update Kof22 Website-Backend adoption work and qualify it in the selected
   isolated staging environment before requesting production deployment.

Local verification is evidence for this implementation, not proof that the
published candidate or Kof22 deployment has these changes.
