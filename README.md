# QBit Collaboration

Record collaboration for QQQ: attachments, tags, custom fields, comments with mentions, presence, sharing, favorites, follow, timeline and tasks. **Implemented now:** feature settings and people tables, favorites and recents services (phase b), per-user security, daily recents retention, phase-c attachments (upload/list/download/rename/delete, previews and parent cleanup), and the Collaboration app home with real session-scoped counts. Phase e adds comments/mentions, follow actions and digests, the audit/activity timeline, and automatic people-directory updates. Phase d adds tags, vocabulary policies, management and bulk actions, and core-integrated tag filtering/search. Phases h/i add sharing/access requests, tasks/reminders, assignments and My work services. Phases f/g add the custom-field designer and ephemeral replicated presence. Runtime overrides and TAGS mode settings resolve through core.

Design: [QRun-IO/qqq PR #1136](https://github.com/QRun-IO/qqq/pull/1136), `docs/COLLABORATION.md`. See [the phase table](docs/DESIGN.md).

## Dependent fields

Comment parents list the selected thread's root and replies. Feature-setting scope names follow `scopeType` (apps, eligible tables, or `instance`). Comment field anchors follow `tableName` and offer non-hidden fields visible to the session. These use RC.5 filter variables and custom providers receiving `otherValues`; automatic dependency refresh awaits core `dependsOn`. See [dependent-field details](docs/CONTINGENT-POSSIBLE-VALUES.md).

## Requirements

- Java 21.
- QQQ `4.1.0-SNAPSHOT` installed from develop, including UI API extension point #1128. The build will move to `4.1.0-RC.6` once released; middleware Javalin is a compile dependency.
- A configured QQQ backend supporting query, get, insert, update, delete, count and aggregate actions. PostgreSQL 15+ is the intended persistent store (the generated personal-target indexes use `NULLS NOT DISTINCT`); tests use the memory backend.
- Attachments require `qbit-object-storage:0.1.0-RC.2` (a pinned dependency), installed with a PRIVATE bucket alias matching `attachmentsBucketName`. Configure its connection and credentials in the host; this qbit does not provision buckets.
- Core feature resolution, permissions, reference security, summaries, query expansion and realtime use QQQ `4.1.0-SNAPSHOT`, targeting RC.6.

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
| `collaboration_share` (Shares) | Recipient, sender, message, access outcome and sent/opened times. Parent security and FIELD audit. |
| `collaboration_access_request` (Access Requests) | Requester/admin visibility, optional share reference, OPEN/GRANTED/DECLINED/WITHDRAWN status and outcome fields. FIELD audit; no parent-data lookup. |
| `collaboration_task` (Tasks) | Title, markdown notes, assignee, due/reminder/delivery times and completion fields. Parent security, FIELD audit and title search metadata. |
| `collaboration_record_assignment` (Record Owners) | Unique parent reference with owner/assignee. Admin-only nav with HIDDEN denial, parent security and FIELD audit. |
| `collaboration_follow_digest` (Follow Digests) | Service-managed pending five-minute batches, unique parent + recipient, due timestamp and changed-field summaries. Admin browse only, outside navigation. |

All tables include `id`, `createDate` and `modifyDate`, identity/details/dates sections, and table possible-value sources. SQL table names match metadata table names; SQL columns use snake_case. Use a stable nonempty scope name (convention: `instance` for INSTANCE; the metadata app/table name otherwise). JSON validation and override resolution arrive with the provider, not in this scaffold.

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

Core reference metadata now restricts parent tables/features and enforces parent visibility and record feature permissions. Direct writes remain service-only. The qbit retains its unpinned row-visibility fallback because core does not yet post-filter unmirrored parent locks or refill pages. Counts and aggregates remain approximate.

**Preview and download.** The record view has `FILE_PREVIEW` and `FILE_DOWNLOAD` virtual fields, filled with five-minute presigned GETs only for visible rows (up to 200 rows per query). Downloads use RC.2's `PresignGet.downloadAs(fileName)`. RC.2 has no arbitrary response-header override for explicit inline disposition, so uploads store `Content-Disposition: inline`, and previews leave that header unchanged. URLs are never persisted. Images/PDF use the standard media preview; the host must allow its storage host in the frontend CSP (PDF frames need compatible `frame-src`). `thumbnailKey` is nullable and unused: object storage has no thumbnail generator, so thumbnails use FILE_PREVIEW rather than generated thumbnail files.

**Actions and cleanup.** `Delete attachments` is a standard streamed ETL record action with Review/Result counts, skipped unauthorized rows, permission rechecks at execution and per-row failure summaries. It is not in navigation. Soft deletion follows the bucket's versioning/trash; without either, deletion is permanent. There is deliberately no **Attach files** table action: uploads come through the API/panel. The current browse action requires `collaboration.view`; the panel may call the service without that grant.

After a parent deletion commits, trusted host code can call `new AttachmentCleanup().onParentsDeleted(table, ids)`. It deletes objects and rows in batches and is retryable. It cannot check an already-deleted parent and must never be exposed as a user endpoint. Wiring to core's `RecordReferenceCleanupInterface` waits for its release. UI API endpoints use the merged #1128 extension point from the locally installed snapshot; direct/presigned upload endpoints and realtime events remain future integrations. Upload/delete now write FILE_ADDED/FILE_REMOVED activity. The table declares `fileName`/`description` search fields and uses normal QQQ writes, so host-configured record-change/ESB listeners receive events; this qbit does not install an ESB publication or Quick Search provider.

Tests use real QQQ memory actions and record locks plus an in-memory `AttachmentStorage` adapter. Object storage RC.2 exposes no fake bucket; its provider conformance suite uses MinIO/Testcontainers. This suite requires no Docker and does not claim MinIO/S3 conformance.

## Comments, follow and timeline

Run services in the caller's `QContext`. Register host tables before producing the qbit when using the follow configuration lists:

```java
config.withNotifier(new QCodeReference(MyCollaborationNotifier.class))
   .withUserSessionProvider(new QCodeReference(MyUserSessions.class))
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
- Mentions use `@[Name](user:<userId>)`, deduplicate IDs and reject more than 50. `CollaborationRecordAccessChecker.canRead` returns `Optional<Boolean>` using a supplied recipient session and an enforced GetAction in a temporary context. Missing providers/sessions and mismatched identities are unknown. False/unknown recipients get neither record data nor automatic follows. Edits notify only previously unmentioned users. Mention delivery and ordinary follower delivery are deduplicated; authors never receive their own comment notifications.
- `CommentEntry` includes author display name, capabilities, deleted/resolved state, mentions with visibility, and ordered replies. `CommentPage` pages 1–50 root threads by latest activity then ID, with replies oldest first. Pass `nextCursor` unchanged. Cursors are opaque, versioned Base64 tuple encodings, not authorization tokens.
- Manual/assigned follow creation defaults change notifications on; other reasons default them off. Comment notifications default on. Existing preferences survive automatic follows and repeated calls; non-null arguments to `follow` change preferences. Follow needs only parent READ. The internal `ensureFollower` helper also verifies other-user access. Muted subscriptions are skipped until `mutedUntil` expires.
- `FollowProcesses.processesFor(tableName)` returns the same Follow/Unfollow processes for manual host registration. Review performs no writes; Result rechecks permissions and reports successes/failures. They work with the host's actual primary-key field, not just `id`, and are never navigation entries.
- RC.5 has `RecordChangeListenerInterface` and `QBackendTransaction.addAfterCommitCallback`. The configured UPDATE listener queues changed-field summaries after commit, excluding the actor's own subscription. The trusted `FollowChangeBatcher.recordChange` helper may also be called by host code after its write commits. `collaborationSendFollowDigests` runs every minute and sends batches due five minutes after their first change, rechecking preferences, mute and recipient access. Pending data survives process restarts. Run one digest scheduler per installation; local synchronization is not a distributed lock. A transport crash after delivery but before row deletion can cause a retry; an adapter needing exactly-once delivery must provide idempotency.
- Notifications carry recipient ID, title, markdown body, MENTION/SHARE/FOLLOW/TASK category, source table/record and `/app/<table>/<id>` href. Without a transport, `NoOpNotifier` sends nothing and activity remains available. With a `QContext` transaction, delivery waits for commit and is discarded on rollback; otherwise it follows the write immediately. Access is checked again at delivery. Mention `notifiedAt` is recorded only after a real transport returns successfully. `RecordingNotifier` provides an in-memory test sink.
- Timeline merges collaboration activity and RC.5 `audit`/`auditDetail`/`auditTable`/`auditUser` through enforced QueryActions. Missing or denied audit access omits that stream. Audit details exclude fields absent from the caller's personalized table. Core audits have actor names but no session user IDs, so their `actorUserId` is null. Paging uses `(at, kind, id)`; `types` accepts AUDIT, ACTIVITY or individual activity type names. Each source query is bounded before merging.
- `PeopleService.touch()` upserts the signed-in user's full name and last-seen timestamp at most once per ten minutes, using the persisted timestamp. Comments/follow and existing favorites/recents/attachment entry points call it. `search(q, limit)` matches active users' names/emails case-insensitively. QUser in RC.5 has no email/avatar fields; trusted directory integrations can enrich those columns. Mention candidates rank followers/commenters before applying the 50-result limit.

The main comments/activity tables, revision/mention child joins and Followers child list are ordinary QQQ metadata. Direct API writes are refused; services authorize the actual parent. Shared `ParentTableRestriction` protects attachment and conversation queries/counts/aggregates; parent post-filtering protects row reads. Aggregate counts remain approximate and browse pages can be short for unmirrored parent locks. Raw persistence, activity writing and change-hook helpers are trusted internal boundaries and must not become public endpoints.

UI API endpoints are described below. Runtime feature enablement, mirrored WRITE locks and core record-access/session contracts remain replacement points; the services do not claim to resolve feature-setting overrides. QAlerts.notify integration waits for #1125. Host-configured ESB listeners receive ordinary QQQ writes; realtime/UI and Quick Search provider registration remain host/later-phase integrations.

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
| `userSessionProvider` | None | `QCodeReference` implementing `UserSessionProviderInterface`; missing user sessions mean unknown access. |
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
5. Wait for the released core contracts and later qbit phases before enabling collaboration features on host records. Favorites, recents, attachments, comments, follow and timeline services can be used now. The versioned UI API is enabled by default; host-table overlays remain separate and scheduled work is opt-in.

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
recounts vocabulary usage. Core invokes `CollaborationCleanup` after committed parent deletion. The home shows session-visible vocabulary
size, pending suggestions, and the five most-used approved tags.

`collaborationTags` is a virtual STRING field labeled Tags with possible-value source `collaboration_tag`. `TagCriteriaExpander` supports IN, NOT_IN, EQUALS, NOT_EQUALS, IS_BLANK, IS_NOT_BLANK and CONTAINS_ALL using core `QSubQuery` membership filters. Ordinary quick search matches labels; `#name` matches normalized names. Numeric criteria are tag IDs; string criteria are names. The old ID materializer is removed. Core owns SQL execution or bounded cross-backend materialization; its bound applies to inner rows, not final parent matches.

The runtime provider adds this field and its search entry where TAGS is enabled. It is queryable but not selectable: the overlay has no virtual-field post-query hook and existing host customizers must be preserved.

## UI API

The producer registers extension `collaboration` version `1.0.0` at
`/qqq/v1/qbit/collaboration`. It uses the signed-in user's session and existing
services. Disable registration with `new CollaborationQBitConfig().withUiApi(false)`.
Presence, shares, tasks, my-work, direct upload and thumbnail routes are not part
of this extension version.

All successes return HTTP 200. All bodies have named `Collaboration*V1` schemas
in [the extension contract](src/main/resources/openapi/qqq-v1/collaboration.yaml).
The server publishes these alongside core at `/qqq/v1/openapi.yaml` and `.json`.

| Method | Path (relative to `/qqq/v1/qbit/collaboration`) | Purpose | Request schema | Response schema |
|---|---|---|---|---|
| GET | `/favorites` | List personal favorites | — | `CollaborationFavoriteListV1` |
| PUT | `/favorites` | Star a target | `CollaborationTargetRequestV1` | `CollaborationMutationResultV1` |
| DELETE | `/favorites` | Unstar a target | `CollaborationTargetRequestV1` | `CollaborationMutationResultV1` |
| POST | `/recents` | Record a recent view | `CollaborationTargetRequestV1` | `CollaborationMutationResultV1` |
| PATCH | `/favorites/order` | Reorder personal favorites | `CollaborationFavoriteOrderRequestV1` | `CollaborationMutationResultV1` |
| GET | `/recents` | List recent targets | — | `CollaborationRecentListV1` |
| GET | `/records/{table}/{pk}/attachments` | List record attachments | — | `CollaborationAttachmentListV1` |
| GET | `/attachments/{id}/download` | Get a temporary download URL; clients follow url | — | `CollaborationDownloadV1` |
| GET | `/attachments/{id}/preview` | Get a temporary preview URL; clients follow url | — | `CollaborationDownloadV1` |
| PATCH | `/attachments/{id}` | Rename or describe an attachment | `CollaborationAttachmentPatchRequestV1` | `CollaborationAttachmentV1` |
| DELETE | `/attachments/{id}` | Delete an attachment | — | `CollaborationMutationResultV1` |
| GET | `/tags` | Search table tag vocabulary | — | `CollaborationTagListV1` |
| POST | `/tags` | Create or suggest a tag | `CollaborationTagCreateRequestV1` | `CollaborationTagV1` |
| GET | `/records/{table}/{pk}/tags` | List applied record tags | — | `CollaborationTagListV1` |
| PUT | `/records/{table}/{pk}/tags` | Add and remove record tags | `CollaborationTagApplyRequestV1` | `CollaborationTagListV1` |
| GET | `/records/{table}/{pk}/comments` | Page record comment threads | — | `CollaborationCommentPageV1` |
| POST | `/records/{table}/{pk}/comments` | Post a comment or reply | `CollaborationCommentPostRequestV1` | `CollaborationCommentV1` |
| PATCH | `/comments/{id}` | Edit the caller's comment | `CollaborationCommentEditRequestV1` | `CollaborationCommentV1` |
| DELETE | `/comments/{id}` | Delete a comment thread | — | `CollaborationMutationResultV1` |
| POST | `/comments/{id}/resolve` | Resolve a comment thread | — | `CollaborationMutationResultV1` |
| POST | `/comments/{id}/reopen` | Reopen a comment thread | — | `CollaborationMutationResultV1` |
| GET | `/comments/{id}/history` | Read comment edit history | — | `CollaborationCommentHistoryV1` |
| GET | `/mentions/candidates` | Find people to mention on a record | — | `CollaborationMentionCandidateListV1` |
| GET | `/records/{table}/{pk}/follow` | Get the caller's follow state | — | `CollaborationFollowStateV1` |
| PUT | `/records/{table}/{pk}/follow` | Follow a record and configure notifications | `CollaborationFollowRequestV1` | `CollaborationFollowStateV1` |
| DELETE | `/records/{table}/{pk}/follow` | Unfollow a record | — | `CollaborationFollowStateV1` |
| GET | `/records/{table}/{pk}/followers` | List record followers | — | `CollaborationFollowerListV1` |
| GET | `/records/{table}/{pk}/timeline` | Page the merged record timeline | — | `CollaborationTimelinePageV1` |
| GET | `/people` | Search the active people directory | — | `CollaborationPersonListV1` |
| GET | `/people/{userId}` | Get an active directory person | — | `CollaborationPersonV1` |
| POST | `/records/{table}/{pk}/attachments` | Upload one file (multipart) | `CollaborationAttachmentUploadRequestV1` | `CollaborationAttachmentV1` |

List requests accept `limit` (default 20, range 1-100). Responses include
`nextCursor`: pass it as `cursor` to continue. Comments page root threads by last
activity; replies are oldest first. Timeline uses its `(at, kind, id)` keyset and
also accepts `before` as an alias for `cursor` (supply only one), plus comma-separated
`types` (`AUDIT`, `ACTIVITY`, or activity names such as `COMMENTED`). Complete-list
services use opaque offset cursors and recheck visibility on every request;
concurrent list changes may shift offsets. Recents and mention candidates retain
their service's 50-item window. People and vocabulary search accept `q` and `limit`
only and return null `nextCursor`; narrow the search to find more matches. Tag apply
returns the full resulting tag set.

Upload is real `multipart/form-data`, with exactly one binary part named `file`
and an optional text `description`. The direct `AbstractEndpointSpec` reads Javalin's
uploaded parts and streams the file to `AttachmentsService`, which enforces parent
access, filename rules and bounded storage. Hosts retain the default
`AttachmentStorage` adapter backed by object storage RC.2; an alternative adapter
can be configured with `withAttachmentStorage(new QCodeReference(Adapter.class))`.

Download and preview both return `CollaborationDownloadV1 {url}` with status 200:
**clients follow url**. The presigned URL lasts five minutes. Download requests an
attachment filename; preview requests inline content. QQQ's current OpenAPI
`Response` model cannot declare the `Location` header, so this contract uses the
JSON URL alternative consistently.

A missing or invisible parent returns identical `404 {"error":"Not found"}`.
Missing write permissions return 403; invalid input returns 400 with an `error`
message through QQQ's exception mapper. Provider/backend failures remain server
errors. Services continue to own author, record-lock and permission checks.
Mention candidates expose `canRead` as the string enum `"true"`, `"false"`,
`"unknown"`; unknown does not imply the recipient can read the record.

QQQ's schema model currently lacks object-property `required` and numeric
minimum/maximum keywords. Required fields and limit bounds are described in the
schemas and enforced at runtime; the request body itself is marked required.

Regenerate the fragment after intentionally changing the contract:

```powershell
.\mvnw.cmd -o -q '-Dtest=CollaborationApiContractTest' '-Dcollaboration.openapi.update=true' test
```

Normal `mvnw.cmd -o -q verify` checks the fragment without modifying it, runs real
HTTP integration tests on a free port with the memory backend, and retains the
70% instruction / 90% class coverage gates. This is the first release of the
extension, so there is no prior release baseline yet.
## Sharing and work services (phases h/i)

Services run in the caller's `QContext`. UI API endpoints and screens follow in a separate PR.

```java
SharingService sharing = new SharingService();
sharing.link("order", "123"); // Link(url, label)
sharing.preview("order", "123", List.of("sam")); // canRead: true, false or null (unknown)
sharing.share("order", "123", List.of("sam"), "Please review");
sharing.markOpened(shareId); // recipient only

AccessRequestService access = new AccessRequestService();
access.request("order", "123", "Please grant access", shareId); // optional shareId
access.withdraw(requestId);

TasksService tasks = new TasksService();
QRecord task = tasks.create("order", "123", "Review order", "Markdown notes", "sam", dueAt, null);
tasks.complete(task.getValueInteger("id"));
tasks.reopen(task.getValueInteger("id"));
tasks.cancel(task.getValueInteger("id"));
tasks.list("order", "123", false);
tasks.mine("OPEN", 20, null); // Page(tasks, nextCursor)

AssignmentService assignments = new AssignmentService();
assignments.assign("order", "123", "owner", "sam");
assignments.get("order", "123");
assignments.myRecords(null, 20); // owner OR assignee; per-table count and first 20
new MyWorkService().summary();
new MyWorkService().markMentionsRead(null); // all visible unread mentions; or supply IDs
```

Set `frontendBaseUrl` for absolute record links; omission produces
`/app/{encodedTable}/{encodedId}`. Sharing and notifications reuse `TargetResolver.recordHref`.
This is trusted host configuration. Sharing requires parent READ and `<table>.share` or EDIT,
1-25 recipients (deduplicated), and a message of at most 2,000 characters. Readers get the
record label/message/link and SHARED following. False/unknown recipients get only sender name,
table label and an access-request link. The sender's message is omitted because it may contain
record data. The prefill route for the follow-up UI is:

`/app/collaboration_access_request/create?tableName={encodedTable}&recordId={encodedId}&shareId={id}`

The UI consumes these parameters and calls `request`; a supplied share ID must match its
recipient and parent reference. Sharing never grants access. Parent-data notifications recheck
recipient access at delivery, after commit. Access notices contain no parent data and can reach
nonreaders.

Any signed-in user may request access, without a parent read or existence check. Existing and
nonexistent targets return the same request shape and OPEN status; generated IDs/timestamps
and submitted targets naturally differ. Duplicate OPEN requests return the existing row.
A PostgreSQL partial unique index protects that natural key across instances. Ordinary table
reads are requester/admin only; all direct writes are denied. Approver actions use an explicitly
authorized extractor without widening ordinary table visibility.

Set TABLE-scope SHARING `settings` to `{"accessApproverPermission":"order.approve"}` (default:
`collaboration.admin`). Configure `.withPermissionHolderDirectory(new QCodeReference(MyDirectory.class))`;
`sharing.PermissionHolderDirectory.userIdsWithPermission(permission)` returns stable user IDs.
Recipients are the union of admin holders, configured approver holders and the qbit record owner.
Without a directory, only the owner is notified and a warning is logged once per JVM. Mark granted
and Decline accept an optional note and tell the requester the outcome. Review explicitly says
"Record the outcome; grant access in your identity system first." No permission is changed.
Notifications omit free-form messages and notes.

Task create requires `task`. Changes additionally require creator, assignee or admin authority,
while retaining parent visibility and task permission checks. Titles contain 1-200 characters;
notes are markdown TEXT. An omitted create assignee defaults to self. Other assignees need a
positive read check; false/unknown is refused with "Sam can't open this record." The full-field
`update(id, title, notes, assignee, due, remind)` replaces editable fields: null clears an assignee
or due time; null remind computes the default from due. The reminder defaults to due minus one
day, or due itself when less than a day away. Assignments add ASSIGNED following and notify the
new assignee unless self. Creation/completion writes activity and queues notify-on-change follower
digests. Reopen clears completion fields but retains delivery history; changing due/reminder time
or assignee resets the relevant delivery receipt.

The hidden `collaborationSendTaskReminders` process runs every 60 seconds with `schedulerName`.
It requires an admin/system session and no open transaction. `TaskReminders(Clock)` and
`TasksService(Clock)` allow deterministic time. Separate timestamps track successful reminder
and overdue delivery; revoked/unknown access and the no-op notifier do not mark delivery.
Run one scheduler per installation. A crash between delivery and receipt can cause a retry;
stronger guarantees require transport idempotency, as with follow digests.

Configure `assignActionTables` for streamed Assign to and Take ownership actions. Assign needs
`<table>.assign` or EDIT and verifies both roles can read (self passes after the parent check).
`assign` replaces both roles, with null clearing a role; bulk actions preserve the other role.
For host tables with their own owner/assignee columns, use core `RecordOwnership` metadata and
normal host-table updates instead. Do not use the qbit assignment table or configure these bulk
actions for such tables. Core phase-i virtual fields, CurrentUser and filter integration remain
separate work; RC.5 does not provide that contract.

My work returns the exact visible open-task count and first ten, assigned-record counts per
table, and delivered unread mentions. Personal lists/counts recheck each parent. This portable
RC.5 implementation materializes matching candidates for null-last sorting/counting; large
installations should replace it with core mirrored-lock queries when available. Task cursors
encode `(dueAt, id)` and limits are 1-100. My records limits returned rows per table, counting all
visible matches.

The home adds open access requests, open/overdue tasks and shares in the last seven days.
Task/share aggregate counts retain the existing approximation for parent record locks until
core mirrors them. `WorkCleanup` is registered for committed host deletes: it removes tasks,
assignments and shares, retaining access requests. Attachment cleanup also invokes the
idempotent hook. Schema generation includes the four tables, supporting indexes and
`ALTER TABLE collaboration_mention ADD COLUMN IF NOT EXISTS read_at TIMESTAMPTZ` for upgrades.
Hosts apply generated SQL through their migration tooling; ESB/search publication uses the same
host integrations as other collaboration tables.


## Core contracts and completed UI API

The qbit targets QQQ RC.6 using the locally installed `4.1.0-SNAPSHOT`. Defaults enable FAVORITES, RECENTS and TIMELINE for eligible host tables; all other features start off. Configure before producing metadata:

```java
config.withDefaultFeatures(RecordFeature.FAVORITES, RecordFeature.RECENTS, RecordFeature.TIMELINE)
   .withTableFeatures("order", RecordFeature.TAGS, RecordFeature.COMMENTS, RecordFeature.FOLLOW)
   .withMirroredSecurityKeyTypes(List.of("clientId"))
   .withUserSessionProvider(new QCodeReference(MyUserSessions.class));
```

`withTableFeatures` supplies the complete enabled set for that table. Host declarations and hard limits use `RecordFeaturesMetaData`. After declaring host tables and producing/enriching the instance, call `RuntimeMetaDataOverlay.refresh(instance)` after database initialization with that instance in `QContext`, as required by core. This loads stored policy and initial runtime fields. Feature-setting insert/update/delete refresh again after commit. Rollback discards callbacks; invalid contributions retain the previous accepted snapshot.

Feature permissions appear automatically in the policy editor through core enrichment. Record-level implied EDIT grants also require the parent's WRITE locks. `mirroredSecurityKeyTypes` defaults to none; configured keys add `lockKey_<type>` fields, null-ALLOW locks and PostgreSQL columns/ALTER statements. Apply generated DDL before enabling the configuration. Core propagates parent lock changes. Revisions and mentions already carry denormalized references in this schema and receive core protection too.

`CollaborationCleanup` removes attachments, tags, work and conversation data after committed parent deletion, retaining access requests. `CollaborationRecordAccessChecker` implements `RecordAccessCheckerInterface` through core `UserSessionProviderInterface.sessionForUser`; missing/mismatched recipient sessions mean unknown access. `CollaborationSummaryProvider` fills core `GET /qqq/v1/table/{table}/{pk}/features` with favorite/follow state, open comment/task counts, attachment/tag/follower counts and owner/assignee values.

New routes, all under `/qqq/v1/qbit/collaboration`:

| Method | Path |
| --- | --- |
| GET | `/records/{table}/{pk}/link` |
| POST | `/records/{table}/{pk}/shares/preview` |
| POST | `/records/{table}/{pk}/shares` |
| POST | `/access-requests` |
| GET | `/access-requests/mine` |
| GET, POST | `/records/{table}/{pk}/tasks` |
| PATCH | `/tasks/{id}` |
| GET | `/tasks/mine` |
| PUT | `/records/{table}/{pk}/assignment` |
| GET | `/my-work` |

Typed `CollaborationWorkSchemas` describe requests and results. Task PATCH preserves omitted fields, accepts OPEN/DONE/CANCELLED transitions, and permits explicit null for optional fields; null `remindAt` selects the service's due-date default. Assignment PUT replaces owner and assignee; null clears. Invalid input is 400, denied visible-record operations are 403, and missing/invisible parents are 404. Access requests deliberately never inspect parent existence. The extension stays at unreleased **1.0.0**; contract tooling accepts these additions without a bump.

`CollaborationRealtime` publishes `comment.added/updated/deleted`, `attachment.changed`, `tags.changed` and `task.changed` on `record:{table}:{pk}` after commit. Successful notification delivery publishes `notification.created` on `user:{userId}`. Payloads contain identifiers only. The optional followers provider is skipped because `RecordFollowersProviderInterface` is absent from the installed jar. Selectable Tags values remain unavailable because the overlay has no output hook.


## Custom fields (phase f)

Enable `RecordFeature.CUSTOM_FIELDS` on each host table. **Custom Fields** is the standard,
FIELD-audited `collaboration_field_definition` table. Reads require `collaboration.view` (or
admin); writes/actions require `collaboration.fields.manage` (or admin). Definitions have a
unique `(tableName, name)` key. The CUSTOM table picker lists enabled host tables; this core
snapshot has no generic table-name provider. EAV values are a secured child list, not a nav item.

Create a DRAFT named `[a-z][a-z0-9_]{0,40}`, then run **Publish field**. ACTIVE definitions appear
as ordinary `cf_<name>` fields through the overlay. **Archive field** counts records with values
in Review and hides the field, retaining its values. This version publishes DRAFT definitions
only. **Rebuild index** reruns the index job. Types can change only in DRAFT; table/name identity
is immutable. Lifecycle/index fields are server-managed; published definitions cannot be deleted.

Types: STRING, TEXT, INTEGER, DECIMAL, BOOLEAN, DATE, DATE_TIME, SELECT, MULTI_SELECT, USER and
RECORD_LINK. Select options use stable ids, for example:

```json
[{"id":"normal","label":"Normal"},{"id":"urgent","label":"Urgent"}]
```

Active options may be added or relabeled. PostgreSQL containment counts refuse removal of an id
still in use; other backends skip this check with a visible warning. USER uses `collaboration_user`
PVS; RECORD_LINK requires the linked table's PVS. MULTI_SELECT is a JSON-array **STRING** with a
PVS: native array membership operators and multi-select editing await core support. Numeric
bounds use `ValueRangeBehavior`; required, maxLength, defaultValue and plain-text help use normal
metadata. Optional PUBLIC/CONFIDENTIAL/PII/PHI classification is carried by `AiFieldMetaData`.
Sections follow `sectionName`, defaulting to Custom fields. `sortOrder` controls contribution
order; searchable definitions join the normal search-field list.

Definition writes refresh metadata after commit; rollbacks do not. Perform the initial
`RuntimeMetaDataOverlay.refresh(instance)` after metadata and database initialization. Other pods
must refresh their overlays when definitions change; presence replication does not replicate
metadata.

Memory uses core `JsonPathStorage`. PostgreSQL uses it after a cached
`SELECT qqq_custom_fields FROM <backend table> LIMIT 0` probe succeeds. Missing columns and
unsupported backends get read-only EAV virtual filters over `collaboration_field_value`, with
typed `QSubQuery` criteria. These fields are not query-selectable; EAV value writes and projection
are outside this version. `statusMessage` records the fallback reason. After adding a missing
column, call `CustomFieldStorage.invalidate(instance, tableName)` and refresh the overlay.

### PostgreSQL setup and indexes

Apply the one-time migration with your normal migration tool:

```sql
ALTER TABLE orders ADD COLUMN IF NOT EXISTS qqq_custom_fields JSONB NOT NULL DEFAULT '{}';
CREATE INDEX IF NOT EXISTS orders_custom_fields_gin ON orders
   USING gin (qqq_custom_fields jsonb_path_ops);
```

`CollaborationSchema.customFieldsDdl("orders")` generates equivalent SQL with quoted identifiers
and a deterministic index name. `postgresScript(config)` includes the two new collaboration
tables. Neither helper executes SQL. Do not declare the backing column as a regular QQQ field.

The hidden `collaborationBuildFieldIndexes` job runs every 60 seconds when `schedulerName` is
set, and through Publish/Rebuild/Archive. Definitions show BUILDING/READY/FAILED, SQL and errors.
Range fields get typed expression indexes; searchable text uses `gin_trgm_ops` when `pg_trgm`
is installed, otherwise a normal expression index. Concurrent DDL uses a fresh backend
connection with autocommit, outside the record transaction. PostgreSQL can reject temporal text
casts as non-immutable; that failure is recorded. A host migration can supply its chosen
immutable expression. Rebuild uses `IF NOT EXISTS`, preserving existing index definitions.
Archive drops the index concurrently while retaining values.

`withManagedIndexes(false)` records `indexDdl` only, with indexState NONE, for host migrations.
There is no automatic migration receipt. The home shows definition/index status totals and the
number of definitions with diagnostic messages.

## Presence (phase g)

Enable `RecordFeature.PRESENCE` on the table. The typed endpoints below
`/qqq/v1/qbit/collaboration` are:

| Operation | Input |
| --- | --- |
| `POST /presence` | `{connectionId, table, pk?, mode: "VIEWING"|"EDITING", fieldName?}` |
| `DELETE /presence` | `{connectionId, table, pk?}` |
| `GET /presence?table=...&pk=...` | Omit pk for a table snapshot |

Session identity supplies userId and displayName. Every call rechecks table READ and record
GetAction visibility. Disabled features and denied/missing parents return the same 404. A
refused renewal removes the caller's previous lease. Connection ids are namespaced by user;
they need not be active SSE connections, allowing polling clients to participate.

Renew on open/focus change and every 20 seconds. Leases expire after 45 seconds, with a one-second
sweep and expiry checks on reads. The cap is 50,000 leases per pod: new targets are refused with
a warning while existing leases can renew. Tabs merge per user; EDITING wins and the latest
editor supplies focus. Nothing is persisted, including directory rows.

Every heartbeat/leave replicates over core's bus on `instance:presence`, a channel refused by
core client subscriptions. `presence.changed` is emitted to record and table channels. Table
snapshots aggregate users without record ids or focused field names; those stay on record
channels. Configure core's ESB bus for multiple pods. Delivery is best effort; clients renew and
refetch after reconnecting.

The initial overlay refresh starts the registry on every pod, including those without local
viewers. Endpoints also initialize it lazily. At shutdown call `PresenceRegistry.close(instance)`
before `RealtimeBus.close(instance)`. The admin-only **Who's here** home card shows user counts
per table, and core record-feature summaries include the presence snapshot.
