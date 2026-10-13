# QBit Collaboration

Record collaboration for QQQ: attachments, tags, custom fields, comments with mentions, presence, sharing, favorites, follow, timeline and tasks. **Implemented now:** feature settings and people tables, favorites and recents services (phase b), per-user security, daily recents retention, phase-c attachments (upload/list/download/rename/delete, previews and parent cleanup), and the Collaboration app home with real session-scoped counts. Phase e adds comments/mentions, follow actions and digests, the audit/activity timeline, and automatic people-directory updates. Phase d adds tags, vocabulary policies, management and bulk actions, and standalone filter translation. Phases f to i remain planned; general runtime overrides are still deferred. TAGS mode settings are read directly by the service.

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

| `collaboration_comment` (Comments) | Markdown body, author, root/reply reference, optional field anchor, latest thread activity, edit/resolve/delete timestamps. FIELD audit and body search. |
| `collaboration_comment_revision` (Comment History) | Prior body, edit timestamp and editor; comment child list. |
| `collaboration_mention` (Mentions) | Unique comment + mentioned user, recipient visibility and actual delivery timestamp; comment child list. |
| `collaboration_follow` (Followers) | Unique parent + user, reason, change/comment notification preferences and mute timestamp. Admin browse only; Followers child list on Comments. |
| `collaboration_activity` (Activity) | Event time, actor, type, summary and reference. Read-only admin main table. |
| `collaboration_follow_digest` (Follow Digests) | Service-managed pending five-minute batches, unique parent + recipient, due timestamp and changed-field summaries. Admin browse only, outside navigation. |

All eleven include `id`, `createDate` and `modifyDate`, identity/details/dates sections, and table possible-value sources. SQL table names match metadata table names; SQL columns use snake_case. Use a stable nonempty scope name (convention: `instance` for INSTANCE; the metadata app/table name otherwise). JSON validation and override resolution arrive with the provider, not in this scaffold.

The home reports persisted enabled settings by scope type, people known and people seen within the inclusive last seven days, plus Favorites and Recently viewed row counts and distinct user counts, attachment count and total bytes, comments in the last seven days, open root threads, and follower subscriptions (admins). Attachment aggregates are explicitly approximate until core can mirror parent locks. Recent activity is based on `lastSeenAt`, independently of the directory's `active` account flag. Empty states explain missing data. These counts do not represent resolved feature enablement on host tables.

## Permissions

| Permission | Behavior |
|---|---|
| `collaboration.view` | Browse Comments, their revisions/mentions and Attachments; read Feature Settings, People, the app and home. Not required for personal favorites or recents. |
| `collaboration.admin` | Write feature settings, read the app/home, access all users' favorite/recent rows, and run recents retention. Can moderate others' attachments and delete others' comments after the parent check. Browses Followers, Activity and Follow Digests. Does not permit editing another author's comment or bypassing service-only table writes. |
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
- Lists refresh labels, using one batched record query per target table per batch. Favorites retain unavailable entries as `available=false`, `label="Unavailable"`, `href=null`; the cached label is never returned. Unstar remains possible after access is lost. Recents drop unavailable entries and refill from older rows up to the requested limit (1â€“50).
- `FavoriteEntry` contains `id`, `targetType`, `targetName`, `recordId`, `label`, `folder`, `sortOrder`, `available` and `href`. Recents use the same target DTO in newest-first order; their folder and sort order are null. `viewCount` and `lastViewedAt` remain available on the underlying recent table.
- Hrefs mirror Next's accepted flat routes: `/app/<table>/`, `/app/<table>/<id>/`, `/app/<app-or-process-or-report>/`, and `/app/<table>/savedView/<id>/`. Segments are URL-encoded. This avoids reconstructing nested app paths; trailing slashes support dotted process names.
- Record views upsert a unique personal target. If its persisted `lastViewedAt` is less than 60 seconds ago, nothing is written; otherwise its timestamp, cached label and count update. The hidden backend-only `collaborationTrimRecents` process retains the newest `recentsPerUser` targets independently for each user, breaking timestamp ties by ID.

The UI API endpoints in spec Â§10.2 will arrive in a **follow-up PR once QQQ's UI API extension point ([QRun-IO/qqq#1128](https://github.com/QRun-IO/qqq/pull/1128)) is released**. This PR adds no endpoint or unreleased core dependency. Runtime feature overrides and the record-feature summary SPI also await released core contracts; the home counts are implemented now.

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

After a parent deletion commits, trusted host code can call `new AttachmentCleanup().onParentsDeleted(table, ids)`. It deletes objects and rows in batches and is retryable. It cannot check an already-deleted parent and must never be exposed as a user endpoint. Wiring to core's `RecordReferenceCleanupInterface` waits for its release. UI API endpoints and the panel transport wait for [#1128](https://github.com/QRun-IO/qqq/pull/1128); direct/presigned upload endpoints and realtime events remain future integrations. Upload/delete now write FILE_ADDED/FILE_REMOVED activity. The table declares `fileName`/`description` search fields and uses normal QQQ writes, so host-configured record-change/ESB listeners receive events; this qbit does not install an ESB publication or Quick Search provider.

Tests use real QQQ memory actions and record locks plus an in-memory `AttachmentStorage` adapter. Object storage RC.2 exposes no fake bucket; its provider conformance suite uses MinIO/Testcontainers. This suite requires no Docker and does not claim MinIO/S3 conformance.

## Comments, follow and timeline

Run services in the caller's `QContext`. Register host tables before producing the qbit when using the follow configuration lists:

```java
config.withNotifier(new QCodeReference(MyCollaborationNotifier.class))
   .withUserSessionSupplier(new QCodeReference(MyUserSessions.class))
   .withFollowActionTables(List.of("order"))
   .withFollowChangeTables(List.of("order"))
   .withSchedulerName("scheduler");

CommentsService comments = new CommentsService();
CommentEntry root = comments.post("order", "123", "Please review @[Sam](user:sam)", null, null);
CommentEntry reply = comments.post("order", "123", "Reply", root.id(), null);
CommentPage page = comments.list("order", "123", null, 20);
comments.edit(reply.id(), "Updated reply");
comments.history(reply.id());
comments.resolve(root.id());
comments.reopen(root.id());
comments.delete(reply.id());
comments.mentionCandidates("order", "123", "Sam");

FollowService follow = new FollowService();
follow.follow("order", "123", null, null);
follow.state("order", "123");
follow.followers("order", "123");
follow.unfollow("order", "123");
TimelinePage timeline = new TimelineService().timeline("order", "123", null, 20, Set.of());
```

Classes live in the `comments`, `follow`, `timeline`, `people` and `notify` packages under `com.kingsrook.qbits.collaboration`.

- Comments require visible parents and the `comment` feature check (explicit `<table>.comment` or table EDIT). Markdown is nonblank and at most 20,000 characters; clients must sanitize rendered HTML. Replies to replies attach to the root. Roots have null `threadId`; replies store the root ID in both thread/parent fields. Deleted DTO bodies are null. Authors alone edit; authors with comment permission or admins delete. Resolve/reopen applies only to roots. History follows parent READ.
- Mentions use `@[Name](user:<userId>)`, deduplicate IDs and reject more than 50. `OtherUserAccess.canRead` returns `Optional<Boolean>` using a supplied recipient session and an enforced GetAction in a temporary context. Missing providers/sessions and mismatched identities are unknown. False/unknown recipients get neither record data nor automatic follows. Edits notify only previously unmentioned users. Mention delivery and ordinary follower delivery are deduplicated; authors never receive their own comment notifications.
- `CommentEntry` includes author display name, capabilities, deleted/resolved state, mentions with visibility, and ordered replies. `CommentPage` pages 1–50 root threads by latest activity then ID, with replies oldest first. Pass `nextCursor` unchanged. Cursors are opaque, versioned Base64 tuple encodings, not authorization tokens.
- Manual/assigned follow creation defaults change notifications on; other reasons default them off. Comment notifications default on. Existing preferences survive automatic follows and repeated calls; non-null arguments to `follow` change preferences. Follow needs only parent READ. The internal `ensureFollower` helper also verifies other-user access. Muted subscriptions are skipped until `mutedUntil` expires.
- `FollowProcesses.processesFor(tableName)` returns the same Follow/Unfollow processes for manual host registration. Review performs no writes; Result rechecks permissions and reports successes/failures. They work with the host's actual primary-key field, not just `id`, and are never navigation entries.
- RC.5 has `RecordChangeListenerInterface` and `QBackendTransaction.addAfterCommitCallback`. The configured UPDATE listener queues changed-field summaries after commit, excluding the actor's own subscription. The trusted `FollowChangeBatcher.recordChange` helper may also be called by host code after its write commits. `collaborationSendFollowDigests` runs every minute and sends batches due five minutes after their first change, rechecking preferences, mute and recipient access. Pending data survives process restarts. Run one digest scheduler per installation; local synchronization is not a distributed lock. A transport crash after delivery but before row deletion can cause a retry; an adapter needing exactly-once delivery must provide idempotency.
- Notifications carry recipient ID, title, markdown body, MENTION/SHARE/FOLLOW/TASK category, source table/record and `/app/<table>/<id>` href. Without a transport, `NoOpNotifier` sends nothing and activity remains available. With a `QContext` transaction, delivery waits for commit and is discarded on rollback; otherwise it follows the write immediately. Access is checked again at delivery. Mention `notifiedAt` is recorded only after a real transport returns successfully. `RecordingNotifier` provides an in-memory test sink.
- Timeline merges collaboration activity and RC.5 `audit`/`auditDetail`/`auditTable`/`auditUser` through enforced QueryActions. Missing or denied audit access omits that stream. Audit details exclude fields absent from the caller's personalized table. Core audits have actor names but no session user IDs, so their `actorUserId` is null. Paging uses `(at, kind, id)`; `types` accepts AUDIT, ACTIVITY or individual activity type names. Each source query is bounded before merging.
- `PeopleService.touch()` upserts the signed-in user's full name and last-seen timestamp at most once per ten minutes, using the persisted timestamp. Comments/follow and existing favorites/recents/attachment entry points call it. `search(q, limit)` matches active users' names/emails case-insensitively. QUser in RC.5 has no email/avatar fields; trusted directory integrations can enrich those columns. Mention candidates rank followers/commenters before applying the 50-result limit.

The main comments/activity tables, revision/mention child joins and Followers child list are ordinary QQQ metadata. Direct API writes are refused; services authorize the actual parent. Shared `ParentTableRestriction` protects attachment and conversation queries/counts/aggregates; parent post-filtering protects row reads. Aggregate counts remain approximate and browse pages can be short for unmirrored parent locks. Raw persistence, activity writing and change-hook helpers are trusted internal boundaries and must not become public endpoints.

UI API endpoints remain deferred to [QRun-IO/qqq#1128](https://github.com/QRun-IO/qqq/pull/1128). Runtime feature enablement, mirrored WRITE locks and core record-access/session contracts remain replacement points; the services do not claim to resolve feature-setting overrides. QAlerts.notify integration waits for #1125. Host-configured ESB listeners receive ordinary QQQ writes; realtime/UI and Quick Search provider registration remain host/later-phase integrations.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `backendName` | Required | Existing QQQ backend used by all eleven tables. |
| `schedulerName` | None | Existing scheduler for daily recents trimming and minute-by-minute follow digest delivery. Without it, both hidden processes remain available for explicit admin/system execution. |
| `attachmentsBucketName` | `collaboration` | PRIVATE object-storage bucket alias for new uploads; existing rows retain their bucket alias. |
| `defaultMaxBytes` | `104857600` (100 MiB) | Positive byte limit, capped by the bucket's `maxUploadBytes`. |
| `blockedExtensions` | Empty | Case-insensitive extension denylist, with or without leading dots. |
| `recentsPerUser` | `200` | Positive retention count per user. Enforced by the trim process. |
| `notifier` | `QCodeReference(NoOpNotifier.class)` | Implements `CollaborationNotifier`; replace with a QAlerts.notify adapter when QRun-IO/qqq#1125 is released. |
| `userSessionSupplier` | None | `QCodeReference` implementing `UserSessionSupplier`; missing user sessions mean unknown access. |
| `followActionTables` | Empty | Existing host tables to receive standard Follow and Unfollow Review/Result processes. |
| `followChangeTables` | Empty | Existing host tables whose UPDATE events should produce five-minute follow digests. |
| `appName` | `collaboration` | Metadata name of the app. |
| `parentAppName` | None | Optional existing parent, such as `admin`. |
| `adminApp` | `true` | Set false to omit the app/home. Record child-list widgets remain available. |

## Adopting in another app

1. Register the host backend and optional Admin app, then invoke the producer above.
2. For PostgreSQL, generate `CollaborationSchema.postgresScript(config)` and apply it with the host's migration tool. It creates tables and unique indexes, never executes DDL, and does not migrate existing schemas. QQQ owns date/default values on writes.
3. Grant `collaboration.view` and `collaboration.admin` as appropriate; wire the host's audit persistence for FIELD audit events.
4. Validate the assembled instance. Navigate to Collaboration for Comments, Attachments, Activity (admins), Feature Settings and People. People populate as signed-in users call collaboration services.
5. Wait for the released core contracts and later qbit phases before enabling collaboration features on host records. Favorites, recents, attachments, comments, follow and timeline services can be used now. This phase installs no endpoints or host-table overlays; scheduled work is opt-in.

## Build

Always use the Maven 3.9.11 wrapper (Maven 3.10 cannot read the parent POM):

```sh
./mvnw verify
```

Offline verification: `./mvnw -o -q verify` (Windows: `./mvnw.cmd -o -q verify`). Verification runs Checkstyle, JUnit tests and JaCoCo gates: 70% instructions and 90% classes.

## License

[Apache License 2.0](LICENSE). Copyright 2026 Kingsrook, LLC; see [NOTICE](NOTICE).

## Tags

Phase d builds against **QQQ 4.1.0-RC.5**. Register host actions with
`config.withTagActionTables(List.of("order"))`; `withDefaultTagMode("FREE")` is the
default. Set `collaboration_feature_setting.feature = TAGS` and JSON
`{"mode":"APPROVED_ONLY"}` to require approval. The service reads settings as the
system, in TABLE → preferred APP → INSTANCE order, falling back to config. The
first scope containing `mode` wins; `enabled` remains part of future general
feature resolution. Invalid policy JSON or mode fails closed.

`TagsService` provides:

- `vocabulary(table, query, limit)`: global and table vocabulary, approved tags plus
  the caller's pending suggestions, excluding merged aliases; usage descending,
  then label. Default limit 20, maximum 100.
- `create(tableOrNull, label, color)`: trimmed, lowercase, collapsed name of 1–60
  characters; the label retains its case. Duplicate names reuse the existing tag.
  Per-table creation requires `<table>.tag` or table EDIT and READ. FREE creates
  approved tags; APPROVED_ONLY creates suggestions unless the caller holds
  `collaboration.tags.manage`. Global creation always requires that manage grant.
- `tagsFor(table, recordId)` and `apply(table, recordId, add, remove)`: parent READ
  and record locks are checked first. Writes also require the tag feature grant or
  EDIT. Numeric values are tag IDs; strings are labels. Unknown add labels are
  created in FREE; in APPROVED_ONLY they fail with
  “Pick an approved tag (or suggest one)”. Pending tags cannot be applied by anyone.
  Repeated changes are idempotent; actual changes write TAGGED/UNTAGGED activity,
  recount usage, and do not notify followers.

Tags is a main navigation table with FIELD auditing and native label search.
Record Tags is a secured child list. Both use service-managed writes. Standard
streamed **Approve tags**, **Merge tags**, and **Delete tags** actions require
`collaboration.tags.manage`; Delete Review counts distinct affected parents.
Merge deduplicates references and retains the source as an alias. Targets must be
approved and in the source scope or global; a global source cannot merge into a
single table's vocabulary.

Host **Add tags** and **Remove tags** actions use the table-type
`collaboration_tag` PVS, filtered to approved, unmerged global or same-table tags.
Each record is rechecked during Review and execution. Input `tags` is a list of
integer IDs (comma-separated IDs are also accepted); `targetTagId` is a scalar.
The picker step exposes `EDIT_FORM` with `multiSelectFields = ["tags"]`. RC.5 has
no native multi-valued field type: rendering this component-value contract as a
multi-select belongs to the separate Next UI phase. The qbit tests exercise the
submitted arrays and real streamed Review/Result lifecycle.

`AttachmentCleanup.onParentsDeleted` now also removes tag assignments and
recounts vocabulary usage. Hosts keep using this trusted callback until the core
cleanup registration contract ships. The home shows session-visible vocabulary
size, pending suggestions, and the five most-used approved tags.

`TagFilterTranslator.recordIds(table, operatorName, tags)` supports `IN` (any),
`CONTAINS_ALL`, `NOT_IN` (none), `IS_BLANK`, and `IS_NOT_BLANK`. Every operator
returns matching visible parent IDs as strings, including complements. Exactly
10,000 results are allowed; larger results throw. Unknown tags match nothing;
empty IN matches nothing and empty CONTAINS_ALL/NOT_IN match all visible parents.
Numeric inputs are IDs, strings are normalized names; if a global and local tag
share a name, the global tag wins (use IDs to disambiguate).

**Filter/quick-search integration lands after QRun-IO/qqq#1140 is released.** This
phase does not register a virtual filter field, core expander, `QSubQuery`, tag
column filler, or `#tag`/host-record quick-search provider. The standalone
translator deliberately imports none of those unreleased types.
