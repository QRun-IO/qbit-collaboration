# Changelog

All notable changes are documented here, following [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and semantic versioning.

## [Unreleased]

### Added

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
- UI API endpoints (§10.2) deferred to a follow-up PR after QQQ's UI API extension point ([QRun-IO/qqq#1128](https://github.com/QRun-IO/qqq/pull/1128)) is released; no unreleased core classes are required.
