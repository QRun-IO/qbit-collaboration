# Collaboration design

The authoritative design is [QRun-IO/qqq PR #1136](https://github.com/QRun-IO/qqq/pull/1136), `docs/COLLABORATION.md` ([develop document](https://github.com/QRun-IO/qqq/blob/develop/docs/COLLABORATION.md)).

Collaboration adds metadata-driven record features to QQQ: attachments, tags, custom fields, comments and mentions, presence, sharing, favorites, follow, timeline, recents, tasks and assignment. Core owns security, query and runtime metadata contracts; this qbit owns visible `collaboration_*` tables and feature services. Implemented: phase-a configuration, feature settings and people tables, plus phase-b favorites/recents tables, services, owner locks, retention and home counts against QQQ 4.1.0-RC.5. The phase-b UI API endpoints await the released extension point from QRun-IO/qqq#1128. The override and record-feature summary providers await released core contracts; phases c to i remain planned. The table below is the full roadmap from the specification, not a list of shipped functionality.

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

