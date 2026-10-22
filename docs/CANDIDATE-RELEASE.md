# Coordinated collaboration snapshot

The main-line candidate is published. Use these versions together:

| Component | Version |
| --- | --- |
| QQQ BOM and modules | `4.1.0-collab-33464d37e-SNAPSHOT` |
| qbit-redis | `0.1.0-redis-7fe5492-SNAPSHOT` |
| qbit-collaboration | `0.1.0-collab-8f348da-SNAPSHOT` |
| qbit-session-store | `0.1.0-session-b5276ab-SNAPSHOT` |
| Next frontend source | `8d372142f8af0c41a75a353edc6d150c33ccf613` |

Maven snapshot repository: https://central.sonatype.com/repository/maven-snapshots/ . Import the candidate QQQ BOM in the host to align transitive QQQ modules. The standalone consumer was verified from an empty cache. This is a feature snapshot, not a stable/GA release or a production deployment.

Frontend build and full browser acceptance: https://github.com/QRun-IO/qqq-frontend-next/actions/runs/39683690943 . Its acceptance-build artifact contains the tested export. No dashboard container image publication is implied. Install the export through the normal host frontend packaging path; upgrade frontend and backend together because the shared-presence issued-lease protocol changed.

## Host requirements

- Configure a named Redis connection through qbit-redis and enable CollaborationQBitConfig.redisPresence. Use explicit deployment/application namespaces and a PresenceScopeProvider derived from authoritative authenticated tenant context.
- Start Redis infrastructure before dependent services and stop dependent services before closing its pools. Session-store may select SHARED_REDIS to reuse the named connection; its session semantics remain owned by session-store.
- Configure TLS/authentication and bounded timeouts/pools for the actual environment. The live acceptance fixture's plaintext loopback Redis and mock authentication are test-only.
- Deliver SSE without proxy buffering. The stream and its subscription updates must reach the owning web node; configure affinity for that connection. Shared Redis does not make node-local SSE connection IDs portable.
- Configure record versions on supported backend paths for stale-save protection. Presence is advisory and never replaces database compare-and-write. Unversioned records do not gain conflict protection merely by enabling presence.
- Use explicit QInstance registration when startup code runs outside QContext. Follow core docs/misc/SessionStoreIsolation.md and session-store docs/SESSION-REGISTRATION.md. JDBC shutdown and health operations must target the owning application; see core docs/lifecycle/application-shutdown.md.
- Apply generated schema/migrations through the host's migration tooling. Do not infer that publishing this library migrated an existing database or an external deployed host.

## Verification

All four standard backend publishers and the three QBit source CI runs passed. Tests against published prerequisites: Redis23, collaboration177, session-store96; zero failures/errors/skips and configured coverage gates passed. Core verification4209 and frontend acceptance39683690943 passed. Frontend local3664 tests and TypeScript passed.

Independent JAR/POM downloads, exact timestamped versions, SHA-256 hashes and an empty-cache standalone consumer passed. Final browser checks used published backend JARs, the exact CI frontend export, two independent JVMs and one Redis endpoint. Cross-node avatars, self exclusion, concurrent editor field focus, preserved drafts, table presence and departure passed. Redis admin pool/subscription metrics, persisted framework UP health history and health-record navigation passed.

The two-node fixture uses independent memory records and mock users; it proves published-artifact presence/admin integration. Database conflict and authentication behavior have separate core/frontend acceptance evidence. Publication does not prove unknown deployed hosts have migrated.

Full evidence and issue reconciliation: https://github.com/QRun-IO/qqq/issues/1293#issuecomment-6329618640 .

## Supported milestone and optional work

The owner requested the main-line flow on N web nodes sharing one configured Redis endpoint. Browser delivery remains SSE plus HTTP lease writes; Redis Pub/Sub supplies invalidation hints. A new WebSocket protocol is not required.

Data-loss/restore qualification (#1300), additional failover topologies (#1301), sustained scale/failure injection (#1302), Windows tooling qualifications (#1304/#1305/#1309), CI mirror resilience (#1307), search layout polish (#1310), long-version preflight (#1312) and test-only AssertJ maintenance (#1314) remain tracked separately in the QQQ project. No measured production capacity or disaster-recovery guarantee is claimed by this snapshot.
