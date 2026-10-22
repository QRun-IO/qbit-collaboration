# Attachment cleanup recovery

Deletion writes a standard `collaboration_attachment_cleanup` row and removes attachment metadata in the same backend transaction. Only after commit does it delete the stored object, then acknowledge the cleanup row. PostgreSQL rejection of metadata deletion leaves both the original row and bytes intact. A provider failure or lost callback leaves the cleanup locator available after restart. The service returns a failure when immediate cleanup fails; the logical attachment deletion may already have committed.

The cleanup table is visible in the Collaboration app to administrators. Direct table mutations are denied, including to administrators, so callers cannot forge deletion targets. Recovery runs through `collaborationRetryAttachmentCleanup`, guarded for system sessions or `collaboration.admin`. With the host's configured scheduler, it retries a bounded batch of 100 every minute. Kof22 already supplies a scheduler name. Without a scheduler, operators must invoke the recovery process; it is not safe to assume background retries exist.

Apply the generated PostgreSQL schema before enabling this version; it creates the additional cleanup table and unique attachment identity index. Do not remove that table during application rollback while cleanup remains pending. Existing releases do not know how to drain it.

Verification covers real PostgreSQL metadata rejection, outer upload/delete rollback, commit, provider failure followed by a new recovery service, loss of post-commit callbacks, failed queue acknowledgement and repeat replay. Storage faults use a deterministic byte store. This is not yet evidence for every object-storage provider or a killed-JVM crash test.

Issue #1289 remains open. Outstanding acceptance includes upload compensation failure/crash recovery, actual provider replay behavior, concurrent cleanup, bounded retry behavior under sustained failures, and host migration/rollback. Do not interpret the deletion fix alone as completion of the full issue.
