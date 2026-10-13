# AGENTS.md

This file provides guidance to Codex when working with code in this repository.

## Project Overview

**QBit Collaboration**: record collaboration for QQQ. Phase a implements feature settings, people tables and the app home. Design: [QRun-IO/qqq#1136](https://github.com/QRun-IO/qqq/pull/1136) (`docs/COLLABORATION.md`). Phases b to i are planned.

## Build Commands

```bash
./mvnw compile          # Compile (always use the wrapper: Maven 3.10 cannot read the parent POM)
./mvnw test             # Unit tests
./mvnw verify           # Unit tests + JaCoCo gate (70% instructions, 90% classes)
```

## Architecture

**Entry point:** `CollaborationQBitProducer implements QBitMetaDataProducer<CollaborationQBitConfig>` in `com.kingsrook.qbits.collaboration`. The producer registers table metadata, possible values and the optional app home.

## Invariants

- The home reads real data with the current session.
- People are a service-managed directory; direct user writes are denied.
- Feature settings are audited at FIELD level; writes require `collaboration.admin`.
- Runtime override application and record feature services await the core contracts.

## Naming (owner rule)

- Tables are lowercase snake_case prefixed with the full qbit name: `collaboration_<name>`. The QQQ table name and the backend (SQL) name are identical; possible-value sources take the table name.
- Permissions use the full qbit name: `collaboration.<action>` (for example `collaboration.view`, `collaboration.admin`).
- Qbit data lives in standard, visible QQQ tables, secured by permissions and record security locks, never hidden.
- Processes run as table or record actions, never in the nav.

## Code Style

- 3-space indentation, opening braces on the next line, `return (x);`.
- Javadoc flower-box comments (`/*****` with `**` lines) on every class and method.
- Fluent setters (`withX()`).
- Wrapper types (`Integer`, `Boolean`) in config and entities.

## Dependencies

- Java 21.
- Parent `com.kingsrook:qbit-build-parent:2.0.0` (qqq 4.0.0). The default-active `qqq-snapshot` profile imports `qqq-bom-pom:4.1.0-RC.5` ahead of it, as in qbit-quick-search (ADR-0007). Remove that activation when the parent moves to 4.1.
- Tests: JUnit 5, AssertJ.

## Release

- CircleCI qqq-orb 0.6.8.
- `develop` publishes SNAPSHOTs.
- A `release/*` branch publishes RCs (the orb bumps the version).
- A `v*` tag publishes the release.
