# Collaboration design

The authoritative design is [QRun-IO/qqq PR #1136](https://github.com/QRun-IO/qqq/pull/1136), `docs/COLLABORATION.md` ([develop document](https://github.com/QRun-IO/qqq/blob/develop/docs/COLLABORATION.md)).

Collaboration adds metadata-driven record features to QQQ: attachments, tags, custom fields, comments and mentions, presence, sharing, favorites, follow, timeline, recents, tasks and assignment. Core owns security, query and runtime metadata contracts; this qbit owns visible `collaboration_*` tables and feature services. Implemented: phase-a configuration, feature settings and people tables, plus phase-b favorites/recents tables, services, owner locks, retention and home counts against QQQ 4.1.0-RC.5. The phase-b UI API endpoints await the released extension point from QRun-IO/qqq#1128. The override and record-feature summary providers await released core contracts; phase-c attachments are implemented on object storage 0.1.0-RC.2; phases d to i remain planned. The table below is the full roadmap from the specification, not a list of shipped functionality.

| Phase | Core | Qbit | Next UI |
|---|---|---|---|
| a. Contracts | `RecordFeaturesMetaData` + resolver + frontend publication; `RecordReference`, `RecordReferenceTableMetaData` and the security rules of Â§3.2 (table filter, mirrored locks, parent check, post-filter, cleanup hook); feature permissions in the catalog; `RecordFeatureSummaryProviderInterface` and `GET /table/{t}/{pk}/features`; `RuntimeMetaDataProviderInterface` and overlay (generation only, no fields yet); `RecordAccessCheckerInterface`, `UserSessionProviderInterface` | repo scaffold, qbit config, `collaboration_feature_setting`, `collaboration_user`, override provider, home skeleton | types for `RecordFeaturesV1` |
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
- Delete attachments uses standard streamed ETL. Attach files is omitted for API/panel uploads. Cleanup is a trusted public after-commit callback pending RecordReferenceCleanupInterface. Endpoints await #1128; runtime feature settings, activity and realtime publication are future integrations. Native search fields are present; ESB and Quick Search publication are host integrations.

See [README](../README.md#attachments) for the service contract, permissions and configuration.
