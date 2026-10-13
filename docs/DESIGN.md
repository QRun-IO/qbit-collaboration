# Collaboration design

The authoritative design is [QRun-IO/qqq PR #1136](https://github.com/QRun-IO/qqq/pull/1136), `docs/COLLABORATION.md` ([develop document](https://github.com/QRun-IO/qqq/blob/develop/docs/COLLABORATION.md)).

Collaboration adds metadata-driven record features to QQQ: attachments, tags, custom fields, comments and mentions, presence, sharing, favorites, follow, timeline, recents, tasks and assignment. Core owns security, query and runtime metadata contracts; this qbit owns visible `collaboration_*` tables and feature services. Implemented: phase-a configuration, feature settings and people tables, plus phase-b favorites/recents tables, services, owner locks, retention and home counts against QQQ 4.1.0-RC.5. The phase-b UI API endpoints await the released extension point from QRun-IO/qqq#1128. The override and record-feature summary providers await released core contracts; phase-c attachments are implemented on object storage 0.1.0-RC.2; phase e comments/follow/timeline is implemented against released RC.5; phase d and phases f to i remain planned. The table below is the full roadmap from the specification, not a list of shipped functionality.

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

- `ParentRecordAccess` is the named replacement point for the unreleased core helper. GetAction enforces parent READ and locks. Attach is an explicit feature grant or EDIT plus visible parent; separate WRITE locks remain an approximation.
- Admin browsing requires collaboration.view, while panel service calls authorize the parent directly. Direct table writes are denied. Internal persistence uses explicit per-action permission bypasses after service checks, and per-input metadata clones without preview customizers. The caller's session is never elevated or replaced. Cloning also lets delete prefetch find rows after the parent is deleted, while preserving FIELD audits and record-change listeners.
- RC.5 offers no PRE_QUERY customizer. The attachment permission checker adds a readable-table IN filter before query/count/aggregate; POST_QUERY filters parent locks before presigning. Approximate counts/bytes and short pages are documented. Full mirrored locks, page refill and API approximate flags await core.
- Uploads spool to bounded temporary disk while hashing; no full-file heap buffer. The bucket cap also applies. Keys contain a UUID and safe basename; renames do not move bytes. Object/row writes are ordered with best-effort compensation, not a distributed transaction.
- The object-storage adapter uses the calling-qbit system facade only after authorization. Soft deletion follows bucket policy. Object retention failures leave retryable rows. In-memory adapter tests exercise QQQ actions and security; provider conformance stays with object storage's MinIO suite.
- Previews/downloads are virtual fields in the RECORD_VIEW alternative section (RC.5 disallows virtual fields in base sections). Five-minute URLs are never stored. RC.2 supports attachment filename overrides, but not explicit inline overrides; uploads carry an inline header. There is no thumbnail generator or generated thumbnail file.
- Delete attachments uses standard streamed ETL. Attach files is omitted for API/panel uploads. Cleanup is a trusted public after-commit callback pending RecordReferenceCleanupInterface. Endpoints await #1128; runtime feature settings and realtime publication are future integrations; attachment services now write activity. Native search fields are present; ESB and Quick Search publication are host integrations.

See [README](../README.md#attachments) for the service contract, permissions and configuration.


## Phase-e implementation choices

- **Released core only.** RC.5 includes record-change listeners and transaction after-commit callbacks. The producer registers a listener only when `followChangeTables` is nonempty. Notification delivery uses the context transaction; temporary recipient contexts never inherit the caller's permission-bypass scope. The notifier/access interfaces are explicit stand-ins for #1125 and the unreleased recipient/session contracts.
- **Security at both boundaries.** Services check the real parent before disclosing any row. Direct table writes are denied even to admins. `ParentTableRestriction` adds a READ-table condition outside caller OR clauses; `ParentRowCustomizer` checks record locks on fetched rows. Revision and mention rows mirror their comment's parent reference to use the same pre-query restriction. Core mirrored locks/page refill and runtime feature resolution remain deferred; counts and unpinned page lengths have the same limits as attachments.
- **Conversations are persisted relationships.** Roots have null thread IDs; replies flatten to a root. `lastActivityAt` is maintained on roots to support indexed keyset paging. Revisions retain the old body. Mention uniqueness prevents re-notification on subsequent edits. Visible mentions auto-follow; unknown is never a grant. Notifications deduplicate mentions versus followers and recheck access after commit. The default no-op transport does not mark mentions delivered.
- **Durable follow batches.** The additional standard `collaboration_follow_digest` table stores one pending batch per parent/recipient. Due time is fixed at the first change plus five minutes. Record-change listeners copy event data and queue only after commit; batches contain field/actor summaries, never old/new values. Delivery rechecks current follow preferences and recipient access. One scheduled sender per installation is required; local synchronization and a unique key are not a cross-pod lease. Delivery/delete is at-least-once across a process crash; adapter idempotency is needed for stronger delivery guarantees.
- **Audit reads preserve the user's authority.** Timeline uses core's released audit table names/fields with ENFORCE on every query, then applies core table personalization to field details. Headers are queried separately from details so a joined-row limit cannot cut an event in half. Denied audits are omitted. Core stores audit actor names without stable session user IDs; the DTO leaves those IDs null. Both streams are bounded and merged by descending `(at, kind, id)`. Cursor IDs are compared as numeric strings because RC.5's memory equality distinguishes Integer and Long values.
- **Directory throttling is persistent.** Ten-minute last-seen checks survive service recreation and rollback without maintaining an in-memory cache. QUser supplies full name/ID only; email/avatar enrichment stays with trusted host integrations. Mention ranking queries participants first so the limit cannot hide an existing follower behind many other users.
- **User-facing metadata.** Comments and admin-only Activity are main nav tables. Revisions, mentions and Followers are child lists; digest/follow tables are admin-browsable with HIDDEN deny behavior. Follow actions use generic streamed ETL Review/Result against any host key. The home reads real session-scoped comments/open-thread/follower counts. Standard QQQ writes remain available to host ESB listeners.

The service API and configuration are documented in [README](../README.md#comments-follow-and-timeline). Endpoints remain deferred to QRun-IO/qqq#1128; no unreleased core dependency, runtime overlay, mirrored-lock implementation or realtime/UI delivery is introduced.

## Phase-d implementation choices

- **RC.5 boundary.** `tags/TagFilterTranslator` uses operator names, QQQ queries and
  string parent IDs. It returns matching rows for all operators, so NOT_IN and
  IS_BLANK enumerate the visible host-table complement. The 10,000 limit applies
  to final matching IDs, not assignment count. It pages parent reads under the
  caller's permissions and locks. Filter/quick-search integration lands after
  QRun-IO/qqq#1140 is released; no unreleased core types or virtual fields are used.
- **Vocabulary versus assignments.** Tag names are unique per nullable scope;
  PostgreSQL DDL emits NULLS NOT DISTINCT for nullable unique-key components.
  Record Tags inherits `ParentTableRestriction` and the parent row customizer,
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
  label search metadata is present; host-record tag search awaits #1140.
