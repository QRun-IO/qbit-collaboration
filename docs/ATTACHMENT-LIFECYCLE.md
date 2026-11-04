# Attachment lifecycle extension

Collaboration owns standard attachment storage and record access. A host can configure `CollaborationQBitConfig.withAttachmentLifecycle(new QCodeReference(HostPolicy.class))` to enforce retention, registration and mandatory access-event persistence. `HostPolicy` extends `AttachmentLifecyclePolicy`. Collaboration has no dependency on Record Files, generation, scripting or a particular event store.

The default policy preserves legacy behavior for ordinary attachments. Invalid policy loading fails before storage mutation. This is a trusted Java extension, never an entry point for untrusted user scripts. Policy implementations must be safe for concurrent requests and may not depend on per-instance state surviving between operations.

## Contract

| Hook | Timing and meaning |
| --- | --- |
| `isRetained(attachment)` | Called before object storage during intake with complete metadata but no generated id. Its result is persisted in the server-controlled `retained` field. Also consulted for legacy rows during deletion and display hints. A persisted true value always wins. |
| `beforeStored(attachment)` | Called with canonical metadata and actual checksum immediately before the first object PUT, after matching retry replay returns. Retained files default to refusal; a mandatory provider commits storage intent independently. Retained uploads reject ambient transactions. |
| `afterStored(attachment)` | Called after object and attachment metadata insertion, before upload returns. Register the retained identity and ingestion status. Failure preserves the object and inserted row for reconciliation when the enclosing transaction permits it. |
| `beforeAccess(attachment, inline)` | Called after parent authorization, before signing either standard preview or download URLs. Throw to deny. Runtime policy failures are converted to checked failures so preview code cannot silently swallow them. |
| `beforeMetadataChange(attachment, changes)` | Called after parent/ownership checks, before applying filename/description changes. The second record contains id and changed fields only. This represents an intent, not a committed change. |

Callbacks receive record copies; scalar attachment metadata is the contract. They must not mutate the passed records to influence storage or authorization. They may throw `QException` to fail the operation. A failed registration must remain inaccessible through the policy until reconciliation succeeds; absence of a retained registry entry must never mean unrestricted access.

## Persistent retention

`collaboration_attachment.retained` is a required boolean with false default for existing ordinary attachments. Apply the generated PostgreSQL schema migration through the host's normal migration tooling before installing this version. The migration is `ALTER TABLE collaboration_attachment ADD COLUMN IF NOT EXISTS retained BOOLEAN NOT NULL DEFAULT FALSE`. It does not automatically classify pre-existing files; any historical backfill requires a deliberate host migration.

There is no public API to clear retention. Direct user table writes remain denied. Deletion first authorizes and checks retention for the complete selection; it checks retention again immediately before storage deletion. Parent cleanup tombstones retained attachment rows and excludes completed tombstones from subsequent pages. The original bytes remain. The ordinary parent-based UI remains inaccessible after parent deletion; a retained-history view must supply its own preserved authorization policy. This extension does not grant archive access.

Removing the configured provider cannot restore deletion of rows already marked retained. The no-op provider also refuses signed URLs and metadata edits for those retained rows. Replacing a provider is a trusted host operation: the replacement must still enforce audit and quarantine for every row marked retained, even if its table is no longer configured for new retention.

Attachment DTOs expose separate `canEdit` and `canDelete` hints. Both require current ownership/admin and attach capability; retention disables deletion. Existing constructor overloads preserve source compatibility. The server rechecks all permissions on mutation. The old `canDelete` flag must no longer control client rename/description actions.

## Verified content for integrations

`AttachmentsService.readContent(attachmentId, maximumBytes)` authorizes the actual parent/current actor, enforces both content-read policy phases, validates stored length and SHA-256, closes storage on all paths and returns immutable `AttachmentContent(attachmentId, fileName, contentType, sha256, bytes)`. No storage coordinates or signed URL escape. The caller chooses a limit up to 20 MiB; ordinary large-file download remains a separate streaming/presigned path. No bytes leave this method before integrity verification and the result audit hook succeed.

`beforeContentRead(attachment)` must authorize and durably record the attempt before storage opens. `afterContentRead(attachment, bytesRead)` must record verified server-read completion before returning content. Both default to refusal for retained files until the lifecycle provider implements them. These facts are distinct from URL issuance, user download, printer spooling and physical output.

## Retryable intake

An upload overload adds `intakeKey` and `manifestSha256` after the ordinary upload arguments. Both are server-coordinator SHA-256 identifiers. The owner additionally hashes actual authenticated actor and canonical parent into the storage scope; never trust a client key as authority. The intake fingerprint includes the caller's manifest, filename, declared type, description, actual length and actual content hash. Every retry reads and checks its supplied bytes. A matching attachment is returned and its idempotent registration hook is retried; changed inputs conflict.

This path rejects ambient transactions. It conditionally creates a deterministic storage key with fingerprint metadata and never overwrites or deletes an uncertain original. If storage succeeded but metadata insertion failed, the next attempt verifies the existing bytes/fingerprint and completes registration. Host migration must add nullable `intake_key`/`intake_fingerprint` columns and the unique nonnull intake-key index from `CollaborationSchema`. That index is the database race boundary; sequential memory fixtures do not prove its concurrency behavior. The calling coordinator still owns durable request reservation, lifecycle and reconciliation scheduling.

Retryable files are bounded to 20 MiB and use a conditional single-object PUT. The inspected object-storage multipart implementation does not carry conditional-create semantics through completion; it is deliberately not used here. Larger retryable originals need a separately qualified conditional multipart path before this bound changes. This is an advertised intake limit, not a claim of unlimited file sizes or storage immutability. Real storage conditional behavior, object version/lock and backup retention still require provider acceptance.

## Atomic display metadata auditing

`beforeMetadataChange` records an independent attempt. Before the update, `validateMetadataTransaction(attachment, transaction)` validates the actual transaction and audit backend; retained files default to denial. After the update, `afterMetadataChange(attachment, changes, transaction)` inserts the success event into that same transaction and also defaults to denial for retained files. The provider must not commit or close the supplied transaction. Reject no-op transactions before mutation; memory tests only establish callback behavior.

When no caller transaction exists, Collaboration opens, commits, rolls back and closes the owner transaction. If a caller supplied a transaction, that caller owns completion and must roll back on any propagated failure. Successful operation and its event become durable together on commit. Record Files must qualify this with a real supported JDBC backend; this generic owner SPI cannot prove provider durability.

## Durability boundaries and remaining integration

The callback is not an audit database. Before returning from `beforeAccess`, a mandatory audit provider must have durably committed its intent independently of a caller transaction that may later roll back. An event means URL issuance was requested, not completed download or physical printing. Storage signing can still fail after the intent is recorded. Signed URLs can be reused until expiration; each use is not observed by Collaboration. Storage access telemetry or a serving gateway is required for transfer events.

Retained originals are never deleted when metadata insertion fails; ordinary non-retained failed inserts retain legacy cleanup. A committed STORAGE_WRITE_ATTEMPT can identify an orphan even when no attachment row was inserted.

An object put and QQQ metadata insert are not an atomic transaction. If a surrounding transaction rolls back after object creation, reconciliation must recover an orphaned object. The retained-only journal integration below provides explicit registration recovery; it does not provide malware scanning, WORM storage protection, bucket lifecycle protection or backup verification. Those remain deployment requirements.

QQQ record-reference write validation issues an internal GetAction using global metadata. Attachment previews use the existing `QueryOrGetInputInterface.getShouldGenerateDisplayValues()` contract: visibility filtering always runs, while URL generation runs only when display values were requested. Core validation GETs default to false; the standard middleware's display GET/query executors explicitly request true. This avoids incidental signing during validation without removing validators or modifying QQQ core. Trusted callers requesting presentation URLs directly through GetAction/QueryAction must explicitly enable display values; `AttachmentsService.downloadUrl` remains an explicit authorized access operation.

## Aggregate visibility policy

The optional configuration-only `requiresPinnedAggregateParent()` hook defaults to false. Owners whose parent visibility relies on dynamic archive/project authorization opt in. AttachmentAggregateGuard preserves the normal collaboration permission checker and additionally requires Count/Aggregate inputs to contain one literal top-level AND equality for both `tableName` and `recordId`. It checks current native parent visibility before SQL; joins, set-operation filters, OR roots, missing and conflicting pins are rejected. It does not grant view access.

Opt-in table metadata hides generic TABLE_COUNT and QUERY_STATS capabilities in both instance and table metadata. The current frontend's use-filter-setup hook already skips totals when TABLE_COUNT is absent and represents them as unknown; record listings and the attachment panel remain available. Native CountAction still permits an authorized pinned count. Do not substitute zero for an unavailable total or issue an unpinned aggregate and rely on a post-query filter: aggregate SQL cannot use that filter. Ordinary policies preserve the existing count capability. Verification is pending the new focused guard suite.


## Retained upload journal integration (verification in progress)

Public attachment services always use JdbcUploadJournal for retained originals. Ordinary non-retained streaming behavior is unchanged. Apply UploadJournalTable.h2Script() as an owner migration before enabling this path: the immutable physical manifest includes private storage coordinates and lease tokens which deliberately are not QQQ metadata fields. Generating SQL from metadata alone is insufficient. Only shared file-H2 with actual WRITE_DELAY=0 is currently qualified; memory journals are explicitly injected test fixtures only.

Retained uploads are capped at20MiB and use conditional single-request creation. The journal commits the candidate, fences a lease, and records UNKNOWN before PUT. A conditional collision or uncertain response is accepted only after reading the bounded original and verifying actual length and SHA-256. Metadata insertion and journal REGISTERED checkpoint share one real owner transaction. The mandatory idempotent afterStored lifecycle callback runs after that commit; COMPLETE follows only after it succeeds. A failed callback leaves the same attachment id recoverable. Source archival refuses transfer while the owner checkpoint is incomplete.

Renewal occurs at synchronous stage boundaries. A storage call that outlives its lease cannot register under its expired fence; a newer recovery may inspect an existing matching original but never overwrite or delete it. No background task synthesizes or carries a browser session. Provider request deadlines and operational supervision remain host acceptance requirements.

The native Retained Upload Journal table exposes status to its original actor only, subject to current source visibility; after a completed archive transfer, visibility follows the exact attachment's current parent. Native totals are disabled and private-field predicate/order/unique-key/join probes are denied. The Recover retained upload action reauthorizes the original actor and current attach capability, verifies existing bytes and retries registration. It does not accept bytes, a locator, another actor or a coordinator scope. Coordinated intakeKey entries must retry through their original coordinator, preserving origin/version/provenance. The beforeRecovery(candidate) lifecycle hook is default-deny for retained originals and records a recovery read attempt separately from STORAGE_WRITE_ATTEMPT and user downloads.

Missing bytes or corrupt bytes remain unresolved, never timeout into cancellation or permission to delete possible evidence. A disabled/deleted original actor has no administrative impersonation escape; an explicitly audited trusted administrative reconciliation path remains to be designed. Existing attachment panels also need discoverable pending-upload recovery navigation; the standard table action alone is not a complete panel UX.


### Recovery navigation metadata

Retention owners can call public UploadRecoveryNavigation.configure(sourceTable) after producing source metadata. It adds ATTACHMENTS.recoveryView containing the native owner table, label and actor/status filter. The generic Files panel adds literal canonical tableName/recordId AND criteria and navigates to the standard QQQ table and its recovery action. CurrentUser expressions are accepted only for createdByUserId/EQUALS with one value. Filters aid discovery and never replace original-actor/current-parent authorization. Missing or malformed metadata hides this optional navigation on ordinary installations. An interrupted-upload message does not claim saved/completed bytes; recovery still verifies the owner journal and exact object.
