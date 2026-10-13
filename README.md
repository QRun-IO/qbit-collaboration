# QBit Collaboration

Record collaboration for QQQ: attachments, tags, custom fields, comments with mentions, presence, sharing, favorites, follow, timeline and tasks. **Implemented now:** the phase-a repository scaffold, feature settings and people tables, and the Collaboration app home with real session-scoped counts. Feature services in phases b to i are planned; stored settings are not yet applied as runtime overrides and people are not yet populated automatically.

Design: [QRun-IO/qqq PR #1136](https://github.com/QRun-IO/qqq/pull/1136), `docs/COLLABORATION.md`. See [the phase table](docs/DESIGN.md).

## Requirements

- Java 21.
- QQQ 4.1.0-RC.5 or newer compatible 4.1 release. The build pins RC.5.
- A configured QQQ backend supporting the table operations. PostgreSQL is the intended persistent store; tests use the memory backend.
- Later phases and runtime overrides need the core contracts from the QQQ collaboration PRs. This scaffold uses string enum ids matching the spec until those contracts are released.

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

Both include `id`, `createDate` and `modifyDate`, identity/details/dates sections, and table possible-value sources. SQL table names match metadata table names; SQL columns use snake_case. Use a stable nonempty scope name (convention: `instance` for INSTANCE; the metadata app/table name otherwise). JSON validation and override resolution arrive with the provider, not in this scaffold.

The home reports persisted enabled settings by scope type, people known and people seen within the inclusive last seven days. Recent activity is based on `lastSeenAt`, independently of the directory's `active` account flag. Empty states explain missing data. These counts do not represent resolved feature enablement on host tables.

## Permissions

| Permission | Behavior |
|---|---|
| `collaboration.view` | Read both tables, the app and home. |
| `collaboration.admin` | Write feature settings; also grants read access. Does not permit direct People writes. |
| `collaboration.fields.manage` | Reserved constant for the later custom-field designer. |
| `collaboration.tags.manage` | Reserved constant for later tag management. |

These use the reference qbit's custom `QPermissionRules` checker. Extra permissions are constants, not automatically registered catalog entries. Configure permission grants in your host. Home count actions explicitly enforce permissions and honor record security locks. Hosts should enable `qInstance.setEnforcePermissionsInCoreActions(true)` for direct model API calls as well as middleware checks.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `backendName` | Required | Existing QQQ backend used by both tables. |
| `schedulerName` | None | Reserved for scheduled work in later phases; unused now. |
| `appName` | `collaboration` | Metadata name of the app. |
| `parentAppName` | None | Optional existing parent, such as `admin`. |
| `adminApp` | `true` | Set false for tables without the app and widget. |

## Adopting in another app

1. Register the host backend and optional Admin app, then invoke the producer above.
2. For PostgreSQL, generate `CollaborationSchema.postgresScript(config)` and apply it with the host's migration tool. It creates tables and unique indexes, never executes DDL, and does not migrate existing schemas. QQQ owns date/default values on writes.
3. Grant `collaboration.view` and `collaboration.admin` as appropriate; wire the host's audit persistence for FIELD audit events.
4. Validate the assembled instance. Navigate to Collaboration for Feature Settings and People. Expect empty tables until settings or trusted directory integrations provide rows.
5. Wait for the released core contracts and later qbit phases before enabling collaboration features on host records. This phase installs no endpoints, scheduler jobs or host-table overlays.

## Build

Always use the Maven 3.9.11 wrapper (Maven 3.10 cannot read the parent POM):

```sh
./mvnw verify
```

Offline verification: `./mvnw -o -q verify` (Windows: `./mvnw.cmd -o -q verify`). Verification runs Checkstyle, JUnit tests and JaCoCo gates: 70% instructions and 90% classes.

## License

[Apache License 2.0](LICENSE). Copyright 2026 Kingsrook, LLC; see [NOTICE](NOTICE).
