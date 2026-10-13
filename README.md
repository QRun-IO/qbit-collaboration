# QBit Collaboration

Record collaboration for QQQ: attachments, tags, custom fields, comments with mentions, presence, sharing, favorites, follow, timeline and tasks. **Implemented now:** feature settings and people tables, favorites and recents services (phase b), per-user security, daily recents retention, phase-c attachments (upload/list/download/rename/delete, previews and parent cleanup), and the Collaboration app home with real session-scoped counts. Phases d to i are planned; stored settings are not yet applied as runtime overrides and people are not yet populated automatically.

Design: [QRun-IO/qqq PR #1136](https://github.com/QRun-IO/qqq/pull/1136), `docs/COLLABORATION.md`. See [the phase table](docs/DESIGN.md).

## Requirements

- Java 21.
- QQQ 4.1.0-RC.5 or newer compatible 4.1 release. The build pins RC.5.
- A configured QQQ backend supporting query, get, insert, update, delete, count and aggregate actions. PostgreSQL 15+ is the intended persistent store (the generated personal-target indexes use `NULLS NOT DISTINCT`); tests use the memory backend.
- Attachments require `qbit-object-storage:0.1.0-RC.2` (a pinned dependency), installed with a PRIVATE bucket alias matching `attachmentsBucketName`. Configure its connection and credentials in the host; this qbit does not provision buckets.
- Later phases and runtime overrides need the core contracts from the QQQ collaboration PRs. Favorites and recents use only APIs released in RC.5. String enum ids match the spec until the core collaboration enums are released.

## Install

Add the dependency (the snapshot must first be published by CI, or installed locally):

```xml
<dependency>
   <groupId>com.kingsrook.qbits</groupId>
   <artifactId>qbit-collaboration</artifactId>
   <version>0.1.0-SNAPSHOT</version>
</dependency>
```

After registering your backend and optional parent app, register the producer:

```java
import com.kingsrook.qbits.collaboration.CollaborationQBitConfig;
import com.kingsrook.qbits.collaboration.CollaborationQBitProducer;

new CollaborationQBitProducer()
   .withQBitConfig(new CollaborationQBitConfig()
      .withBackendName("rdbms")
      .withParentAppName("admin"))
   .produce(qInstance);
```

Omit `withParentAppName` for a top-level app. Run normal QQQ instance validation after all producers.

## Tables

| Table | Purpose |
|---|---|
| `collaboration_feature_setting` (Feature Settings) | Scope type (INSTANCE, APP, TABLE), scope name, one of the 12 record features, enabled flag, settings JSON stored as TEXT, permission mode. Unique on scope type + scope name + feature; FIELD-level audit. |
| `collaboration_user` (People) | Unique session user ID, display name, email, avatar URL, last seen timestamp and active flag. Service-managed, read-only to users. |
| `collaboration_favorite` (Favorites) | `userId`, `targetType`, `targetName`, optional `recordId`, cached `label`, `sortOrder`, optional `folder`. Unique on user + type + name + record ID. Owner-locked, audit off. |
| `collaboration_recent` (Recently Viewed) | `userId`, `targetType`, `targetName`, optional `recordId`, cached `label`, `lastViewedAt`, `viewCount`. Same unique key and owner lock; audit off. |

| `collaboration_attachment` (Attachments) | Parent table and record ID, file metadata, SHA-256, bucket/key, optional description/thumbnail key, author and parent-deleted flag. FIELD audit; parent-based security. Preview/download URLs are virtual, never stored. |

All five include `id`, `createDate` and `modifyDate`, identity/details/dates sections, and table possible-value sources. SQL table names match metadata table names; SQL columns use snake_case. Use a stable nonempty scope name (convention: `instance` for INSTANCE; the metadata app/table name otherwise). JSON validation and override resolution arrive with the provider, not in this scaffold.

The home reports persisted enabled settings by scope type, people known and people seen within the inclusive last seven days, plus Favorites and Recently viewed row counts and distinct user counts, and attachment count and total bytes. Attachment aggregates are explicitly approximate until core can mirror parent locks. Recent activity is based on `lastSeenAt`, independently of the directory's `active` account flag. Empty states explain missing data. These counts do not represent resolved feature enablement on host tables.

## Permissions

| Permission | Behavior |
|---|---|
| `collaboration.view` | Browse Attachments, read Feature Settings, People, the app and home. Not required for personal favorites or recents. |
| `collaboration.admin` | Write feature settings, read the app/home, access all users' favorite/recent rows, and run recents retention. Can moderate others' attachments after the parent check. Does not permit direct People or attachment table writes. |
| `collaboration.fields.manage` | Reserved constant for the later custom-field designer. |
| `collaboration.tags.manage` | Reserved constant for later tag management. |

These use the reference qbit's custom `QPermissionRules` checker. Extra permissions are constants, not automatically registered catalog entries. Configure permission grants in your host. Home count actions explicitly enforce permissions; attachment totals have the parent-lock approximation described below. Hosts should enable `qInstance.setEnforcePermissionsInCoreActions(true)` for direct model API calls as well as middleware checks.

Personal tables use `PermissionLevel.NOT_PROTECTED` with a `READ_AND_WRITE` owner lock on `userId`. Every signed-in user can manage their own rows. `CollaborationSecurity` derives `collaboration_user` from `QSession.getUser().getIdReference()` and gives `collaboration_user_all_access` to admins. Its custom permission checker initializes keys before enforced table actions; services and the home also call `ensureSessionKeys`. Hosts may call `applyToSession(qInstance, session)` after authentication. Refreshing keys also removes revoked admin access. System sessions are untouched; create scheduler system sessions after the full instance is registered so QQQ includes the all-access key.

Favorites and recents are omitted from app nav sections. They remain normal, addressable QQQ tables, including for admin inspection. The header and palette will use the services through the follow-up API. These two personal tables intentionally skip audit logging. Personal service methods explicitly filter the current owner even when called by an admin; they never elevate the session. Use standard table actions for admin-wide management.

## Favorites and recents services

Call these static methods inside the caller's `QContext`:

```java
FavoritesService.star("RECORD", "order", "123");
FavoritesService.star("TABLE", "order", null);
List<FavoriteEntry> favorites = FavoritesService.list();
FavoritesService.reorder(List.of(12, 8), Map.of(12, "Work"));
FavoritesService.unstar("RECORD", "order", "123");

RecentsService.recordView("RECORD", "order", "123");
List<FavoriteEntry> recents = RecentsService.list(50);
```

`FavoritesService` and `FavoriteEntry` are in `com.kingsrook.qbits.collaboration.favorites`; `RecentsService` is in `com.kingsrook.qbits.collaboration.recents`. Target types are `TABLE`, `APP`, `RECORD`, `PROCESS`, `REPORT` and `SAVED_VIEW`. Only `RECORD` accepts a non-null record ID. For `SAVED_VIEW`, `targetName` is the saved view ID; the instance must contain a readable core `savedView` table and the view's target table must also be readable.

- Star and unstar are idempotent. New stars append to the existing sort order. Reorder moves the supplied IDs first and preserves the order of omitted favorites; folder entries may be null to clear a folder. All supplied IDs must belong to the current user.
- Targets are checked using core table/app/process/report permissions; record targets use `GetAction` and the session's record locks. Invisible and missing targets both fail with `Not found`.
- Lists refresh labels, using one batched record query per target table per batch. Favorites retain unavailable entries as `available=false`, `label="Unavailable"`, `href=null`; the cached label is never returned. Unstar remains possible after access is lost. Recents drop unavailable entries and refill from older rows up to the requested limit (1–50).
- `FavoriteEntry` contains `id`, `targetType`, `targetName`, `recordId`, `label`, `folder`, `sortOrder`, `available` and `href`. Recents use the same target DTO in newest-first order; their folder and sort order are null. `viewCount` and `lastViewedAt` remain available on the underlying recent table.
- Hrefs mirror Next's accepted flat routes: `/app/<table>/`, `/app/<table>/<id>/`, `/app/<app-or-process-or-report>/`, and `/app/<table>/savedView/<id>/`. Segments are URL-encoded. This avoids reconstructing nested app paths; trailing slashes support dotted process names.
- Record views upsert a unique personal target. If its persisted `lastViewedAt` is less than 60 seconds ago, nothing is written; otherwise its timestamp, cached label and count update. The hidden backend-only `collaborationTrimRecents` process retains the newest `recentsPerUser` targets independently for each user, breaking timestamp ties by ID.

The UI API endpoints in spec §10.2 will arrive in a **follow-up PR once QQQ's UI API extension point ([QRun-IO/qqq#1128](https://github.com/QRun-IO/qqq/pull/1128)) is released**. This PR adds no endpoint or unreleased core dependency. Runtime feature overrides and the record-feature summary SPI also await released core contracts; the home counts are implemented now.

## Attachments

Call the service inside the caller's `QContext`; configure object storage before using it:

```java
AttachmentsService attachments = new AttachmentsService();
AttachmentEntry added = attachments.upload("order", "123", "invoice.pdf", "application/pdf", stream, "Signed invoice");
List<AttachmentEntry> files = attachments.list("order", "123");
URL download = attachments.downloadUrl(added.id(), false);
attachments.rename(added.id(), "invoice-final.pdf", null); // null leaves a field unchanged
attachments.describe(added.id(), "");                    // empty clears the description
attachments.delete(List.of(added.id()));
```

Uploads check the parent and `attach`, count bytes and compute SHA-256 while streaming to a temporary file, then stream that file through object storage. This keeps memory bounded and rejects oversize files before a put. Temporary files are removed on success and failure; the caller owns the input stream. A failed row insert triggers object cleanup. Storage and SQL are not a distributed transaction: a cleanup failure is attached to the original error and requires operational reconciliation. Deletion removes the object first and retains the row on a storage failure for retry.

File names are reduced to a safe basename (maximum 255 characters). Object keys are `attachments/{table}/{recordId}/{uuid}/{fileName}`; table/id segments are URL-encoded to protect string primary keys containing slashes. Renames change the display name and subsequent download filename, retaining the original storage key. `AttachmentEntry` returns display metadata, creation time and `canDelete` (author or admin); it omits bucket/key and signed URLs. Lists are newest first. Changing or deleting an own row also rechecks `attach`; admins may moderate another author's row but must still see its parent. A batch authorizes all selected rows before deleting its first object.

**Permission model.** The panel service checks the parent with an enforced `GetAction` under the caller's session. Missing, unreadable and locked parents produce `QNotFoundException("Not found")`. Upload requires `<permissionBaseName>.attach` or table EDIT plus a visible parent. This is the explicitly temporary approximation of write-lock checks; distinct WRITE-only parent locks are not enforced until core's record-reference helper is released. Explicit `attach` grants work without EDIT. Collaboration tables cannot themselves be parents.

The admin Attachments table additionally requires `collaboration.view` (or `collaboration.admin`). The panel service bypasses that browse grant only after its own parent/feature/ownership checks. Direct table insert/update/delete is denied; use the service and record action so storage and rows remain coordinated. Storage operations use `ObjectStorage.asSystem("collaboration", runId).bucket(alias)` after these checks, preserving calling-qbit/initiating-user operation logging without requiring users to hold `object_storage.*` grants.

RC.5 has no PRE_QUERY customizer. `AttachmentSecurity` uses its custom permission checker as the pre-query hook, adding `tableName IN (readable parent tables)` to queries, counts and aggregates. Enable core permission enforcement for direct model API use. A POST_QUERY customizer checks each row's parent and removes invisible rows before signing any URLs. This applies to direct get/query as well as browsing. **Counts and aggregate bytes are approximate, and pages may be short** because parent record locks are checked after querying; the home labels the approximation. RC.5 cannot publish an `approximate` flag or refill pages through this hook. Replace this stand-in with core's record-reference security when released. Per-table feature enablement/settings and permission catalog integration also await those contracts.

**Preview and download.** The record view has `FILE_PREVIEW` and `FILE_DOWNLOAD` virtual fields, filled with five-minute presigned GETs only for visible rows (up to 200 rows per query). Downloads use RC.2's `PresignGet.downloadAs(fileName)`. RC.2 has no arbitrary response-header override for explicit inline disposition, so uploads store `Content-Disposition: inline`, and previews leave that header unchanged. URLs are never persisted. Images/PDF use the standard media preview; the host must allow its storage host in the frontend CSP (PDF frames need compatible `frame-src`). `thumbnailKey` is nullable and unused: object storage has no thumbnail generator, so thumbnails use FILE_PREVIEW rather than generated thumbnail files.

**Actions and cleanup.** `Delete attachments` is a standard streamed ETL record action with Review/Result counts, skipped unauthorized rows, permission rechecks at execution and per-row failure summaries. It is not in navigation. Soft deletion follows the bucket's versioning/trash; without either, deletion is permanent. There is deliberately no **Attach files** table action: uploads come through the API/panel. The current browse action requires `collaboration.view`; the panel may call the service without that grant.

After a parent deletion commits, trusted host code can call `new AttachmentCleanup().onParentsDeleted(table, ids)`. It deletes objects and rows in batches and is retryable. It cannot check an already-deleted parent and must never be exposed as a user endpoint. Wiring to core's `RecordReferenceCleanupInterface` waits for its release. UI API endpoints and the panel transport wait for [#1128](https://github.com/QRun-IO/qqq/pull/1128); direct/presigned upload endpoints, activity entries and realtime events remain future integrations. The table declares `fileName`/`description` search fields and uses normal QQQ writes, so host-configured record-change/ESB listeners receive events; this qbit does not install an ESB publication or Quick Search provider.

Tests use real QQQ memory actions and record locks plus an in-memory `AttachmentStorage` adapter. Object storage RC.2 exposes no fake bucket; its provider conformance suite uses MinIO/Testcontainers. This suite requires no Docker and does not claim MinIO/S3 conformance.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `backendName` | Required | Existing QQQ backend used by all five tables. |
| `schedulerName` | None | Existing scheduler for the hidden daily recents trim process (86400-second interval and initial delay). Without it, the process is available for explicit admin/system execution. |
| `attachmentsBucketName` | `collaboration` | PRIVATE object-storage bucket alias for new uploads; existing rows retain their bucket alias. |
| `defaultMaxBytes` | `104857600` (100 MiB) | Positive byte limit, capped by the bucket's `maxUploadBytes`. |
| `blockedExtensions` | Empty | Case-insensitive extension denylist, with or without leading dots. |
| `recentsPerUser` | `200` | Positive retention count per user. Enforced by the trim process. |
| `appName` | `collaboration` | Metadata name of the app. |
| `parentAppName` | None | Optional existing parent, such as `admin`. |
| `adminApp` | `true` | Set false for tables without the app and widget. |

## Adopting in another app

1. Register the host backend and optional Admin app, then invoke the producer above.
2. For PostgreSQL, generate `CollaborationSchema.postgresScript(config)` and apply it with the host's migration tool. It creates tables and unique indexes, never executes DDL, and does not migrate existing schemas. QQQ owns date/default values on writes.
3. Grant `collaboration.view` and `collaboration.admin` as appropriate; wire the host's audit persistence for FIELD audit events.
4. Validate the assembled instance. Navigate to Collaboration for Attachments, Feature Settings and People. Expect empty tables until settings or trusted directory integrations provide rows.
5. Wait for the released core contracts and later qbit phases before enabling collaboration features on host records. Favorites, recents and attachments services can be used now. This phase installs no endpoints or host-table overlays; recents scheduling is opt-in.

## Build

Always use the Maven 3.9.11 wrapper (Maven 3.10 cannot read the parent POM):

```sh
./mvnw verify
```

Offline verification: `./mvnw -o -q verify` (Windows: `./mvnw.cmd -o -q verify`). Verification runs Checkstyle, JUnit tests and JaCoCo gates: 70% instructions and 90% classes.

## License

[Apache License 2.0](LICENSE). Copyright 2026 Kingsrook, LLC; see [NOTICE](NOTICE).
