# Collaboration design

The authoritative design is [QRun-IO/qqq PR #1136](https://github.com/QRun-IO/qqq/pull/1136), `docs/COLLABORATION.md` ([develop document](https://github.com/QRun-IO/qqq/blob/develop/docs/COLLABORATION.md)).

Collaboration supplies visible `collaboration_*` tables, feature services and 42 typed UI API operations. QQQ `4.1.0-SNAPSHOT` (target RC.6) owns feature resolution, permission registration, reference security, mirrored locks, virtual criteria, record summaries and realtime transport. Object storage remains `0.1.0-RC.2`.

| Phase | Core | Qbit | Next UI |
|---|---|---|---|
| a. Contracts | `RecordFeaturesMetaData` + resolver + frontend publication; `RecordReference`, `RecordReferenceTableMetaData` and the security rules of Ã‚Â§3.2 (table filter, mirrored locks, parent check, post-filter, cleanup hook); feature permissions in the catalog; `RecordFeatureSummaryProviderInterface` and `GET /table/{t}/{pk}/features`; `RuntimeMetaDataProviderInterface` and overlay (generation only, no fields yet); `RecordAccessCheckerInterface`, `UserSessionProviderInterface` | repo scaffold, qbit config, `collaboration_feature_setting`, `collaboration_user`, override provider, home skeleton | types for `RecordFeaturesV1` |
| b. Favorites, recents, palette | | favorites, recents, endpoints, summary provider | star in record and table headers, favorites menu, palette favorites group, server recents |
| c. Attachments | | attachments with object storage, previews and thumbnails, delete action | Files tab, upload, preview |
| d. Tags | `QSubQuery`; virtual field criteria expander in query, count, aggregate and search; `CONTAINS_ALL`; `#tag` search | tags, vocabulary modes, bulk actions, filter field | tag chips, Tags filter and column, `#tag` in palette and search |
| e. Comments, follow, timeline | | comments, revisions, mentions, follows, activity, timeline merge with audit, notifications | Comments and Activity tabs, composer, follow bell |
| f. User-defined fields | `JsonPathStorage` in RDBMS, memory and MongoDB; overlay field contributions | definitions, designer, publish and archive, index job, EAV fallback | designer preview widget; fields render through normal metadata |
| g. Realtime and presence | `RealtimeBus` (in-memory, ESB), SSE spec, subscriptions, `record.changed` producer, `RecordVersionCheck` with 409 | presence endpoints and replicated map, events for its features | stream client, presence avatars and field rings, conflict dialog, live panel refresh |
| h. Sharing | | link, shares with preview, access requests | share dialog, request-access page |
| i. Tasks, owner/assignee | `CurrentUser` expression, ownership metadata, "My records" view | tasks, reminders, assignment table and virtual fields, assign actions, My work | Tasks tab, My work page, assign actions |


## Phase-c implementation choices

- `ParentRecordAccess` delegates to core visibility and feature capabilities. Attach requires the core feature grant or implied EDIT with parent WRITE locks.
- Admin browsing requires collaboration.view, while panel service calls authorize the parent directly. Direct table writes are denied. Internal persistence uses explicit per-action permission bypasses after service checks, and per-input metadata clones without preview customizers. The caller's session is never elevated or replaced. Cloning also lets delete prefetch find rows after the parent is deleted, while preserving FIELD audits and record-change listeners.
- Core adds readable-table/feature restrictions before query/count/aggregate and enforces configured mirrored locks. The retained POST_QUERY fallback protects unmirrored parent locks before presigning. Page refill and exact unpinned counts remain an upstream limitation.
- Uploads spool to bounded temporary disk while hashing; no full-file heap buffer. The bucket cap also applies. Keys contain a UUID and safe basename; renames do not move bytes. Object/row writes are ordered with best-effort compensation, not a distributed transaction.
- The object-storage adapter uses the calling-qbit system facade only after authorization. Soft deletion follows bucket policy. Object retention failures leave retryable rows. In-memory adapter tests exercise QQQ actions and security; provider conformance stays with object storage's MinIO suite.
- Previews/downloads are virtual fields in the RECORD_VIEW alternative section (RC.5 disallows virtual fields in base sections). Five-minute URLs are never stored. RC.2 supports attachment filename overrides, but not explicit inline overrides; uploads carry an inline header. There is no thumbnail generator or generated thumbnail file.
- Delete attachments uses standard streamed ETL. Attach files is omitted for API/panel uploads. Cleanup implements RecordReferenceCleanupInterface. Typed endpoints, runtime settings and realtime publication are integrated; attachment services now write activity. Native search fields are present; ESB and Quick Search publication are host integrations.

See [README](../README.md#attachments) for the service contract, permissions and configuration.


## Phase-e implementation choices

- **Released core only.** RC.5 includes record-change listeners and transaction after-commit callbacks. The producer registers a listener only when `followChangeTables` is nonempty. Notification delivery uses the context transaction; temporary recipient contexts never inherit the caller's permission-bypass scope. The notifier remains an adapter for #1125; recipient access implements core access/session contracts.
- **Security at both boundaries.** Services check the real parent before disclosing any row. Direct table writes are denied even to admins. Core reference metadata adds parent-table and feature restrictions; `ParentRowCustomizer` checks record locks on fetched rows. Revision and mention rows mirror their comment's parent reference to use the same pre-query restriction. Configured mirrored locks and runtime resolution use core; unpinned post-filtering/page refill remain incomplete upstream; counts and unpinned page lengths have the same limits as attachments.
- **Conversations are persisted relationships.** Roots have null thread IDs; replies flatten to a root. `lastActivityAt` is maintained on roots to support indexed keyset paging. Revisions retain the old body. Mention uniqueness prevents re-notification on subsequent edits. Visible mentions auto-follow; unknown is never a grant. Notifications deduplicate mentions versus followers and recheck access after commit. The default no-op transport does not mark mentions delivered.
- **Durable follow batches.** The additional standard `collaboration_follow_digest` table stores one pending batch per parent/recipient. Due time is fixed at the first change plus five minutes. Record-change listeners copy event data and queue only after commit; batches contain field/actor summaries, never old/new values. Delivery rechecks current follow preferences and recipient access. One scheduled sender per installation is required; local synchronization and a unique key are not a cross-pod lease. Delivery/delete is at-least-once across a process crash; adapter idempotency is needed for stronger delivery guarantees.
- **Audit reads preserve the user's authority.** Timeline uses core's released audit table names/fields with ENFORCE on every query, then applies core table personalization to field details. Headers are queried separately from details so a joined-row limit cannot cut an event in half. Denied audits are omitted. Core stores audit actor names without stable session user IDs; the DTO leaves those IDs null. Both streams are bounded and merged by descending `(at, kind, id)`. Cursor IDs are compared as numeric strings because RC.5's memory equality distinguishes Integer and Long values.
- **Directory throttling is persistent.** Ten-minute last-seen checks survive service recreation and rollback without maintaining an in-memory cache. QUser supplies full name/ID only; email/avatar enrichment stays with trusted host integrations. Mention ranking queries participants first so the limit cannot hide an existing follower behind many other users.
- **User-facing metadata.** Comments and admin-only Activity are main nav tables. Revisions, mentions and Followers are child lists; digest/follow tables are admin-browsable with HIDDEN deny behavior. Follow actions use generic streamed ETL Review/Result against any host key. The home reads real session-scoped comments/open-thread/follower counts. Standard QQQ writes remain available to host ESB listeners.

The service API and configuration are documented in [README](../README.md#comments-follow-and-timeline). Endpoints use the core extension API; overlay, mirrored locks and realtime use the local core snapshot.

## Phase-d implementation choices

- **Core query boundary.** `TagCriteriaExpander` returns core subqueries over record tags, preserving AND/OR and complement semantics. Runtime metadata adds the Tags field and search entry where enabled. TEXT matches labels and TAG matches normalized names. Selectable values await a core virtual-field output hook.
- **Vocabulary versus assignments.** Tag names are unique per nullable scope;
  PostgreSQL DDL emits NULLS NOT DISTINCT for nullable unique-key components.
  Record Tags uses core reference metadata and the unpinned parent row fallback,
  including the existing approximation for aggregate counts and short pages.
  Vocabulary pickers need no collaboration.view grant: ordinary users see
  approved tags and their own suggestions in readable scopes, while manage sees
  all vocabulary for moderation. Direct writes are denied; services/actions
  preserve normalization, approval and counts.
- **Policy lookup.** Settings are read in a temporary system context, retaining
  the transaction and restoring the caller. TABLE overrides preferred APP (QQQ's
  app-affinity path), then INSTANCE, then `defaultTagMode`. Missing mode inherits;
  malformed JSON/invalid mode throws. General enabled/permission-mode runtime
  overrides remain outside this phase. Name collision resolution prefers global
  vocabulary; IDs provide an unambiguous selection.
- **Mutation boundaries.** Like existing services, writes participate in the
  caller's QQQ transaction and serialize within the installed config's monitor.
  Unique keys enforce assignment identity. Counts are recomputed from stored
  links, including merge deduplication, deletion and trusted parent cleanup.
  This is not a cross-pod lock: concurrent writers in separate instances need
  host transaction/serialization coordination for exact denormalized counts.
  Standard ETL runs with its process transaction. Activity is generated only for
  actual apply/remove changes and never calls follower notification services.
- **Review and Result.** Actions derive operation and table from installed process
  metadata. Management requires collaboration.tags.manage at both stages. Bulk
  actions resolve approved PVS selections and recheck every parent and feature
  permission at both stages. Review is read-only, counts already-satisfied rows,
  and Delete reports distinct affected parents. Result reports successful and
  failed records separately. The picker exposes multiSelectFields on EDIT_FORM
  for the separate Next client; RC.5 itself has no native multi-value form field.
- **Presentation.** Tags is in the admin app nav; Record Tags is a child widget.
  The home queries under the caller session and HTML-escapes labels. Native tag
  label search and host-record tag search use the core virtual-field expander.


## Phases h/i implementation choices

- Shares, tasks and assignments reuse parent checks, extension restrictions and FIELD audits.
  Access requests use requester/admin locks, no parent read, and metadata-only table labels.
  Trusted persistence removes only the request owner lock on a private metadata clone after
  explicit service authorization. Standard table actions retain that lock; audit keeps the caller.
- Previews retain true/false/unknown. Unknown never enables record-data delivery or following.
  Safe access notices use after-commit delivery without a parent check and omit record labels,
  sender messages and approver notes. Record-data notifications recheck access at delivery.
- OPEN request identity is `(tableName, recordId, requesterUserId)`. Local serialization and a
  PostgreSQL partial unique index protect it; insert collisions reread the existing row. No
  parent query distinguishes existing from missing records. Permission-holder resolution is an
  optional host SPI, with the qbit record owner as fallback. TABLE-scope SHARING settings supply
  the approver permission; native ownership integration awaits the released core phase-i API.
- Streamed actions choose table/operation from installed metadata, validate in Review and again
  at load, and report Result counts. Configured approvers use an authorized request extractor
  without widening ordinary table reads. Grant records an identity-system outcome only.
- Tasks default to the creator, compute reminder times using an injectable Clock, and keep
  independent reminder/overdue delivery receipts. Creation/completion also enter the existing
  follow digest. Run one scheduler per installation; delivery then receipt has the same crash
  retry/idempotency limitation as follow digests. Unknown access and no-op delivery leave no receipt.
- Assignment is a qbit fallback. Hosts with owner/assignee columns use core RecordOwnership and
  ordinary updates, not collaboration_record_assignment or its configured actions. Bulk actions
  preserve the other role. Virtual fields and CurrentUser integration belong to core phase i.
- My work/my-records counts recheck parents, removing invisible content and counts. RC.5 candidate
  rows are materialized for portable null-last sorting/counting. Home aggregates retain the
  existing documented parent-lock approximation until core mirrored locks are released.
- The producer registers a host DELETE listener with after-commit cleanup. Access requests
  survive deletion. Schema SQL includes partial request/reminder indexes and a readAt migration
  for the existing mention table. Task title search metadata is present; ESB/Quick Search
  publication remains a host integration.

See the README for service signatures, defaults, configuration and the exact access-request
prefill route. UI API endpoints and screens follow in their separate PR.


## RC.6 integration boundaries

`CoreCollaboration` installs configuration defaults, feature-setting overrides, runtime Tags metadata, summaries and cleanup providers. Core enrichment registers feature permissions. `ParentRecordAccess` is a canonical-key wrapper around core visibility and record capability checks, including WRITE locks for implied grants. Every extension reference table declares core metadata; revision/mention rows already carry denormalized references alongside their comment joins. Author restrictions apply where appropriate; tags and tasks retain their collaborative service rules.

Core still lacks unpinned row post-filter/refill support for unmirrored locks. Retained row/attachment filters protect that gap and redact tombstones; projected reads include the reference fields needed by these filters. Counts remain approximate. Mirrors default to none and generated DDL includes ALTER statements when configured. Cleanup uses private metadata without reference checks because the parent is gone. Share acknowledgement is a separate, recipient-verified exception that changes only receipt time.

Feature writes refresh the overlay after commit. Initial loading follows core's startup contract: refresh after enrichment and database setup. The Tags contribution resolves incoming policy before core publishes its new snapshot. No selectable virtual values are advertised, because the overlay has no output hook. The installed core jar also lacks the optional followers SPI.

`CollaborationWorkSchemas` and endpoint specs expose sharing, access requests, tasks, assignment and My work; the README and generated OpenAPI contain the route list. Task PATCH validates before writing and preserves omitted fields. Access requests never inspect parent existence. Contract version remains unreleased 1.0.0.

`CollaborationRealtime` merges stored references for partial updates, captures immutable identifier payloads and publishes after commit. Notification hints follow actual delivery. Rollback discards callbacks. Bus failure is best-effort and does not fail a committed write.


## Custom fields and presence

`CustomFieldsProvider` contributes ACTIVE definitions through core's runtime overlay. Tags and
custom fields share incoming feature-policy resolution, so a persisted setting takes effect in
the same refresh. FIELD-audited definitions enforce management permissions and lifecycle rules
on both direct writes and streamed actions. Numeric range behavior is added only when a bound
exists. Classification stays in core AiFieldMetaData. Select option ids are stable across label
changes, with parameterized PostgreSQL containment checks before removing active options.

Storage availability is cached per instance/table. Memory needs no schema; PostgreSQL uses a
zero-row probe through the backend connection provider. Failure installs a visible read-only
EAV filter instead of a broken JSON field. Typed subqueries retain parent permissions/locks;
EAV values are neither projected nor written by this PR. The child table declares core reference
security and participates in parent cleanup. MULTI_SELECT is a JSON-array scalar string until
core adds native array storage/filter contracts.

Index building is separate from overlay construction. Concurrent DDL runs through independent
autocommit connections and records its statement/state/error on the definition. A no-DDL option
records migration statements only. No column/table DDL runs during refresh. JDBC-boundary tests
cover probe caching, option containment, autocommit and failure reporting. Live PostgreSQL
acceptance remains necessary for host index expressions, especially temporal casts.

Presence is the only non-table state. Pods subscribe at initial overlay refresh and keep bounded
user/connection/target leases. `instance:presence` is denied by core's client channel authorizer.
State mutation and bus publication occur outside each other's monitor, preventing deadlock on
synchronous bus fan-out. Wire data uses transport-safe maps. Record focus stays on record
channels; table events omit pk and fieldName. Each HTTP request rechecks parent visibility and
PRESENCE; session identity is authoritative and the directory is never touched. Hosts close the
registry before the core bus.

Limits: EAV writes/projection, native multi-select operations, automatic cross-pod metadata
refresh, replacing existing index definitions, and live PostgreSQL acceptance are not provided.
The no-DDL option does not infer whether an external migration has run. Presence relies on a
trusted instance bus and best-effort delivery; renewed heartbeats restore state after outages.
