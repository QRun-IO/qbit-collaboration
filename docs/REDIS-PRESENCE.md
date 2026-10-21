# Shared Redis presence candidate

Status: implemented and locally verified; coordinated artifact publication and remote candidate verification remain pending. This guide describes the current main-line scope. See MAINLINE-ACCEPTANCE.md for evidence and SESSION-STATE.md for live release checkpoints.

## Host configuration and dependency ownership

Multiple QQQ web nodes share one configured Redis endpoint. The host enables CollaborationQBitConfig.redisPresence, supplies a started named Redis connection, explicit deployment/application names, and a PresenceScopeProvider deriving one tenant scope from authenticated host context. Browser input cannot choose the tenant.

Collaboration owns presence semantics, Lua scripts, record/table projections and authorization. qbit-redis owns connections, namespaces, script execution, pool lifecycle, admin management and framework health integration. Collaboration production code consumes only the Redis public API and does not import Jedis. Without shared configuration, the local presence backend remains available for a single node; its issued authority is not distributed. Runtime Redis failure never silently switches an HA host to local presence.

Browser delivery uses the existing SSE stream with HTTP lease writes. Redis Pub/Sub carries scoped invalidation hints between nodes; a new WebSocket transport is not required. Hints contain table/record identifiers, not record contents or viewer lists. The recipient rereads through the authorized presence API. Core checks the authenticated recipient audience during live delivery and replay. Coordinate core/frontend/QBit versions and do not share a topic with audience-unaware hubs.

## Issued lease lifecycle

The candidate collaboration extension version is 1.3.0:

- POST /presence/leases authorizes the table/record and creates an invisible, expiring reservation, returning a server-issued connectionId.
- POST /presence/leases/renew requires that issued handle and a positive safe-integer sequence. It activates or updates existing live authority; it cannot recreate an expired or departed lease.
- DELETE /presence performs ordered departure. Delayed renewals cannot reopen the departed issued handle.
- HTTP 409 with code PRESENCE_LEASE_EXPIRED tells an active browser to open a fresh lease. Input, permission and infrastructure failures retain their standard mappings. Unavailable shared storage returns 503 rather than an empty-room result.
- Shared presence rejects legacy heartbeat upserts. Local legacy behavior remains for compatibility.

The Next candidate awaits open before activation, uses the handle across focus changes, and releases it on hide/unmount. It opens again on pageshow or explicit expiry. Generation checks discard old asynchronous responses; a late open result is released without activation. One immediate rejoin is allowed after expiry, then retries follow the normal 20-second heartbeat. Infrastructure errors do not churn handles. The UI preserves drafts and shows unavailable status when presence cannot be read or renewed.

## State and permissions

Record and table rooms use bounded expiry indexes and state hashes in the same Redis cluster hash slot for their deployment/application/tenant/feature namespace. Lua updates both projections atomically using Redis TIME. User/connection/record identifiers are encoded independently. No record values are stored.

Snapshots merge tabs by user. EDITING takes precedence over VIEWING; focus follows the latest renewal within the selected mode. Table projections omit focused fields. Expired entries are pruned on reads/mutations and idle keys expire. Invisible reservations count toward capacity. Scripts use EVALSHA with fallback only for NOSCRIPT, through the Redis QBit API.

Reads and renewals recheck parent and field access. Canonical record identifiers and recipient field filtering are enforced. Admin counts are limited to known rooms in the administrator's authenticated tenant; non-admins receive no counts. Capacity and TTL limits bound individual operations; they are not measured sustained production throughput guarantees.

## Verified behavior

- Real Redis tests cover six users on a record with two editors, duplicate-tab aggregation, ordering, departure, expiry, isolation, capacity and script-cache recovery.
- Service and real HTTP tests cover issued open/activation/renew/departure, 409 expiry, authorization, canonical keys, field projection and unavailable responses.
- Full collaboration verify passed 179 tests with coverage/architecture gates; focused frontend suites passed 34 tests, TypeScript and changed-file lint.
- Live browser acceptance with mocks disabled used three identities on two independent QQQ JVMs sharing Redis. It verified avatars, field focus, drafts, table projection and departure. Demo records were independently seeded memory data; this is presence evidence, not cross-node database save-consistency evidence.
- Live Redis admin acceptance verified pool/subscription status, persisted framework health results and navigation to standard health records.

Presence is advisory. Atomic optimistic record-version checks protect saves independently. Coordinated cold-consumer acceptance against published artifacts remains required; local Maven cache success does not prove remote artifact availability.

## Deferred work

The user narrowed this release to the normal flow. Optional qualification is tracked in QQQ issues #1300 (data loss/backup restore), #1301 (failover/disaster topologies), and #1302 (sustained scale/failure injection), all on the QQQ Roadmap backlog.

Issued leases do not provide disaster-recovery fencing: complete state loss can temporarily look empty, and restoring an old live lease can restore its authority. No recovery generation guard is implemented in this candidate. The deferred investigation is tracked in [QQQ #1300](https://github.com/QRun-IO/qqq/issues/1300).
