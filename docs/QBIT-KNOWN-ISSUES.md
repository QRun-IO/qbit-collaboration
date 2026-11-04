# QBit integration known issues

Updated: 2026-11-04. Unresolved at closeout.

- Core must provide supportsRollbackCallbacks(), addAfterRollbackCallback(Runnable), runAfterRollbackCallbacks() and matching RDBMSTransaction behavior. See CORE-DEPENDENCY.md. No compatibility shim is present or approved.
- Current public Core RC.21 does not compile this branch. Public Collaboration RC.6 does not provide these APIs to Record Files.
- Full attachment, upload-journal, MySQL/PostgreSQL transaction, interruption/recovery and Record Files integration verification remains outstanding.
- Preserve newer canonical changes and existing pipeline files. Redis presence and unrelated private-cohort features were intentionally not folded into this attachment change.
- The snapshot version is a source-development convention, not a complete published dependency cohort. Do not claim a production retention guarantee from this source checkpoint alone.
