# Core dependency: after-rollback callbacks

Collaboration's retained-upload and cleanup paths compensate external object storage only after a transaction's
rollback is confirmed. They need two methods on `com.kingsrook.qqq.backend.core.actions.QBackendTransaction` that the
released core `4.1.0-RC.21` does not declare. This module does not compile against RC.21 until a core release that has them
is available. This branch now defaults to `4.1.0-SNAPSHOT`; the verified source is Core commit `551397c7c59b0f77833fc81e195f4c01cb1c282f`. Build/install that Core branch first for local development. No compatibility shim is provided (a shim could not confirm rollback and would weaken the guarantee).

## Required API (reviewed implementation: `b6fb2382`, `third-party-src/qqq-core`)

`qqq-backend-core`, class `QBackendTransaction`:

```java
public boolean supportsRollbackCallbacks()                  // default: false
public void addAfterRollbackCallback(Runnable callback)      // throws UnsupportedOperationException unless supportsRollbackCallbacks(); Objects.requireNonNull(callback)
protected void runAfterRollbackCallbacks()                   // run once, in order, then clear; exceptions (incl. LinkageError) logged and swallowed
```

Pending rollback callbacks are preserved when a required operation marks the transaction rollback-only, and run only after confirmed JDBC rollback. A failed physical commit clears them because its outcome is uncertain. Successful commit discards them.

`qqq-backend-module-rdbms`, class `RDBMSTransaction`:

```java
@Override public boolean supportsRollbackCallbacks()                 // true
@Override public void addAfterRollbackCallback(Runnable callback)    // super.add...; then rolledBackSinceCommit = false
// rollback()/close(): after connection.rollback() succeeds -> rolledBackSinceCommit = true; runAfterRollbackCallbacks()
// close() rolls back (and so runs callbacks) unless a rollback already happened since the last commit / callback registration
```

## Callers in this module
- `attachments/AttachmentsService.storeUpload`: refuses an ambient transaction where `!supportsRollbackCallbacks()`; registers storage compensation.
- `attachments/AttachmentCleanupQueue.beginUpload`: registers the same cleanup on both `addAfterRollbackCallback` and `addAfterCommitCallback`.

## Core tests to carry with the change
- Base class: `supportsRollbackCallbacks()` is false; `addAfterRollbackCallback` throws `UnsupportedOperationException`.
- RDBMS: callback runs after `rollback()`; does not run after `commit()`; runs when a transaction with a registered callback is closed without commit;
  a callback registered after an earlier rollback still runs on close; a throwing callback does not stop later callbacks.
- Integration (already in this module, needs the core): `attachments/AttachmentTransactionsPostgresTest` (needs Docker) incl. `interruptedUploadSurvivesLostRollbackCallback`.
