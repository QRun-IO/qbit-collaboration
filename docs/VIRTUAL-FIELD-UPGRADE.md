# Selectable collaboration fields: unreleased upgrade

Tracks [QQQ #1196](https://github.com/QRun-IO/qqq/issues/1196).

This source branch requires the new core virtual-field value-provider API. The
published versions in CANDIDATE-RELEASE.md do not include that API. The checked-in
core pin still identifies the earlier published candidate and must be updated
before this branch can be built or released with its default configuration.

Local verification uses core artifacts installed as
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

1. Publish and verify the coordinated core artifact containing the provider API.
2. Replace this branch's core BOM pin with that published version.
3. Run full collaboration verification against the published prerequisite,
   including coverage, HTTP, PostgreSQL transaction and Redis tests.
4. Publish the collaboration snapshot and verify an independent consumer using
   published artifacts. Update CANDIDATE-RELEASE.md with the exact pair.
5. Update Kof22 Website-Backend adoption work and qualify it in the selected
   isolated staging environment before requesting production deployment.

Local verification is evidence for this implementation, not proof that the
published candidate or Kof22 deployment has these changes.
