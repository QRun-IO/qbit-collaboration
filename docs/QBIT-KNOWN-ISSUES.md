# QBit integration known issues

Updated: 2026-11-04.

- This branch requires the Core integration snapshot described in [CORE-DEPENDENCY.md](CORE-DEPENDENCY.md). Public Core RC.21 lacks rollback callbacks; public Collaboration RC.6 lacks the retained-upload SPI. A compatible published dependency set remains a release requirement.
- Local owner verification is complete: 269 tests and style/coverage gates pass, including both journal providers. Deployment-specific object storage, database failover, backup/restore and retention policies still require host qualification.
- Docker must be running for attachment transaction tests. Enable both `mysqlJournalTests` and `postgresJournalTests` for the additional journal-provider cases. On Windows use `-Dexec.skip=true` for the inherited POSIX console-summary command; all verification gates still run.
- Redis presence and unrelated private-cohort features were intentionally excluded. This attachment integration has no compile-time dependency on Record Files, Scripting or Document Generation.
- No release tag, public artifact publication or deployment is claimed by this branch. The matching Next UI and host configuration must be verified before rollout.
