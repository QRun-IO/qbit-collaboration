# Changelog

All notable changes are documented here, following [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and semantic versioning.

## [Unreleased]

### Added

- Repository scaffold; feature settings and people tables.
- Phase-b favorites and recents on QQQ 4.1.0-RC.5: visible `collaboration_favorite` and `collaboration_recent` tables, target-type possible values, unique personal targets and audit disabled for high-volume data.
- `CollaborationSecurity` owner locks based on the session user's ID reference, with admin all-access keys and automatic initialization before enforced table actions and in services/home. No collaboration permission is required to manage one's own rows.
- `FavoritesService` and `FavoriteEntry`: idempotent star/unstar, reorder/folders, refreshed labels, batched record visibility checks, unavailable-target redaction and Next UI paths.
- `RecentsService`: persisted one-minute throttling, visible newest-first lists (up to 50), and hidden daily `collaborationTrimRecents` scheduling with configurable per-user retention (default 200).
- Home row/distinct-user summaries, PostgreSQL 15+ unique indexes that treat null record IDs as equal, and memory-backend security/service/retention tests.
- UI API endpoints (§10.2) deferred to a follow-up PR after QQQ's UI API extension point ([QRun-IO/qqq#1128](https://github.com/QRun-IO/qqq/pull/1128)) is released; no unreleased core classes are required.
