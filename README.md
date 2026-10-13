# QBit Collaboration

Record collaboration for QQQ: attachments, tags, custom fields, comments with mentions, presence, sharing, favorites, follow, timeline and tasks. **Implemented now:** feature settings and people tables, favorites and recents services (phase b), per-user security, daily recents retention, and the Collaboration app home with real session-scoped counts. Phases c to i are planned; stored settings are not yet applied as runtime overrides and people are not yet populated automatically.

Design: [QRun-IO/qqq PR #1136](https://github.com/QRun-IO/qqq/pull/1136), `docs/COLLABORATION.md`. See [the phase table](docs/DESIGN.md).

## Requirements

- Java 21.
- QQQ 4.1.0-RC.5 or newer compatible 4.1 release. The build pins RC.5.
- A configured QQQ backend supporting query, get, insert, update, delete, count and aggregate actions. PostgreSQL 15+ is the intended persistent store (the generated personal-target indexes use `NULLS NOT DISTINCT`); tests use the memory backend.
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

All four include `id`, `createDate` and `modifyDate`, identity/details/dates sections, and table possible-value sources. SQL table names match metadata table names; SQL columns use snake_case. Use a stable nonempty scope name (convention: `instance` for INSTANCE; the metadata app/table name otherwise). JSON validation and override resolution arrive with the provider, not in this scaffold.

The home reports persisted enabled settings by scope type, people known and people seen within the inclusive last seven days, plus Favorites and Recently viewed row counts and distinct user counts. Recent activity is based on `lastSeenAt`, independently of the directory's `active` account flag. Empty states explain missing data. These counts do not represent resolved feature enablement on host tables.

## Permissions

| Permission | Behavior |
|---|---|
| `collaboration.view` | Read Feature Settings, People, the app and home. Not required for personal favorites or recents. |
| `collaboration.admin` | Write feature settings, read the app/home, access all users' favorite/recent rows, and run recents retention. Does not permit direct People writes. |
| `collaboration.fields.manage` | Reserved constant for the later custom-field designer. |
| `collaboration.tags.manage` | Reserved constant for later tag management. |

These use the reference qbit's custom `QPermissionRules` checker. Extra permissions are constants, not automatically registered catalog entries. Configure permission grants in your host. Home count actions explicitly enforce permissions and honor record security locks. Hosts should enable `qInstance.setEnforcePermissionsInCoreActions(true)` for direct model API calls as well as middleware checks.

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

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `backendName` | Required | Existing QQQ backend used by all four tables. |
| `schedulerName` | None | Existing scheduler for the hidden daily recents trim process (86400-second interval and initial delay). Without it, the process is available for explicit admin/system execution. |
| `recentsPerUser` | `200` | Positive retention count per user. Enforced by the trim process. |
| `appName` | `collaboration` | Metadata name of the app. |
| `parentAppName` | None | Optional existing parent, such as `admin`. |
| `adminApp` | `true` | Set false for tables without the app and widget. |

## Adopting in another app

1. Register the host backend and optional Admin app, then invoke the producer above.
2. For PostgreSQL, generate `CollaborationSchema.postgresScript(config)` and apply it with the host's migration tool. It creates tables and unique indexes, never executes DDL, and does not migrate existing schemas. QQQ owns date/default values on writes.
3. Grant `collaboration.view` and `collaboration.admin` as appropriate; wire the host's audit persistence for FIELD audit events.
4. Validate the assembled instance. Navigate to Collaboration for Feature Settings and People. Expect empty tables until settings or trusted directory integrations provide rows.
5. Wait for the released core contracts and later qbit phases before enabling collaboration features on host records. Favorites and recents services can be used now. This phase installs no endpoints or host-table overlays; recents scheduling is opt-in.

## Build

Always use the Maven 3.9.11 wrapper (Maven 3.10 cannot read the parent POM):

```sh
./mvnw verify
```

Offline verification: `./mvnw -o -q verify` (Windows: `./mvnw.cmd -o -q verify`). Verification runs Checkstyle, JUnit tests and JaCoCo gates: 70% instructions and 90% classes.

## License

[Apache License 2.0](LICENSE). Copyright 2026 Kingsrook, LLC; see [NOTICE](NOTICE).
