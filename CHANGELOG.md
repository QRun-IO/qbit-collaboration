# Changelog

All notable changes are documented here, following [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and semantic versioning.

## [Unreleased]

### Added

- Phase-h links, share previews, recipient-safe notifications and SHARED followers; access requests with requester/admin locks, duplicate OPEN handling, optional permission-holder directory and streamed Mark granted/Decline outcomes.
- Phase-i tasks with assignee validation, injectable-clock reminder/overdue receipts, complete/reopen/cancel, due-date cursor paging and streamed task actions.
- Qbit owner/assignee services, configured Assign to/Take ownership bulk actions, My work summaries and mention read receipts.
- Four audited tables, task-title search metadata, work/share home cards, committed parent-delete cleanup, PostgreSQL tables/indexes and a mention readAt migration.
- Acceptance tests for security, notifications, request existence neutrality, actual streamed actions, reminders, summaries, cleanup and schema. Existing JaCoCo gates retained; UI API endpoints remain separate.

- Phase-d tag vocabulary and record assignments on QQQ 4.1.0-RC.5; normalized names, FREE/APPROVED_ONLY policy resolved from system-read scoped feature settings, explicit global/manage permissions, parent-checked idempotent assignment, usage counts and TAGGED/UNTAGGED activity without follower notifications.
- Tags navigation, secured Record Tags child list, FIELD audit, label search, approved/scoped table PVS, streamed Approve/Merge/Delete and configured host Add/Remove actions, parent cleanup and real Tags home metrics. Bulk multi-select intent is exposed in picker metadata for the separate Next UI implementation.
- Standalone `TagFilterTranslator` for IN/CONTAINS_ALL/NOT_IN/IS_BLANK/IS_NOT_BLANK with a 10,000 matching-ID cap. Filter/quick-search integration lands after QRun-IO/qqq#1140 is released.
- Tag service, security, vocabulary, settings, schema, cap and streamed Review/Result tests; existing JaCoCo gates retained.

- Phase-e comments/mentions, one-level threads, edit revisions, author/admin soft deletion, resolve/reopen, ranked mention candidates and keyset pagination on QQQ 4.1.0-RC.5.
- Comments, Comment History, Mentions, Followers, Activity and durable Follow Digests tables, child-list widgets, service-only writes, shared parent-table query restrictions, schema indexes and real home counts.
- Lazy people-directory upserts with ten-minute throttling from comments/follow/favorites/recents/attachments entry points; bounded active-name/email search.
- Configurable notifier and recipient-session stand-ins, default no-op and recording transports, conservative unknown-access handling and RC.5 after-commit delivery with fresh access checks.
- Follow preferences, generic streamed ETL Follow/Unfollow actions, configured RC.5 change listeners and a hidden minute scheduler for five-minute digests.
- Timeline merging enforced core audit reads with collaboration activity, field-visibility filtering and tuple cursors. Attachments now record FILE_ADDED/FILE_REMOVED activity.
- Memory-backed tests for recipient access, transactions, moderation, pagination, real ETL Review/Result, digest batching, directory ranking, schema and audit timeline security. Existing JaCoCo gates retained; endpoint publication deferred to #1128.

- Phase-c attachments against QQQ 4.1.0-RC.5 and object storage 0.1.0-RC.2: parent-checked upload/list/download/rename/describe/delete service, bounded streaming, SHA-256 and configurable byte/extension limits.
- Visible `collaboration_attachment` table, FIELD auditing, file-name/description search fields, virtual presigned FILE_PREVIEW/FILE_DOWNLOAD adornments, PostgreSQL BIGINT size and parent index, and an approximate count/bytes home card.
- Temporary `ParentRecordAccess`, readable-table query restriction through RC.5's permission hook and parent post-filtering. Panel calls do not require the admin browse grant; direct attachment table writes are service-managed.
- Streamed ETL **Delete attachments** Review/Result record action and retryable `AttachmentCleanup.onParentsDeleted` callback. No Attach files table action; endpoint integration awaits #1128 and cleanup registration awaits core.
- Memory-backed attachment/security/process tests. Thumbnails use FILE_PREVIEW; `thumbnailKey` remains nullable/unused because object storage has no generator.

- Repository scaffold; feature settings and people tables.
- Phase-b favorites and recents on QQQ 4.1.0-RC.5: visible `collaboration_favorite` and `collaboration_recent` tables, target-type possible values, unique personal targets and audit disabled for high-volume data.
- `CollaborationSecurity` owner locks based on the session user's ID reference, with admin all-access keys and automatic initialization before enforced table actions and in services/home. No collaboration permission is required to manage one's own rows.
- `FavoritesService` and `FavoriteEntry`: idempotent star/unstar, reorder/folders, refreshed labels, batched record visibility checks, unavailable-target redaction and Next UI paths.
- `RecentsService`: persisted one-minute throttling, visible newest-first lists (up to 50), and hidden daily `collaborationTrimRecents` scheduling with configurable per-user retention (default 200).
- Home row/distinct-user summaries, PostgreSQL 15+ unique indexes that treat null record IDs as equal, and memory-backend security/service/retention tests.
- UI API endpoints (Â§10.2) deferred to a follow-up PR after QQQ's UI API extension point ([QRun-IO/qqq#1128](https://github.com/QRun-IO/qqq/pull/1128)) is released; no unreleased core classes are required.
