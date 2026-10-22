# AGENTS.md

This file provides guidance to Codex when working with code in this repository.

## Project Overview

**QBit Collaboration**: record collaboration for QQQ. The implemented feature set and published candidate deployment contract are documented in README.md and docs/CANDIDATE-RELEASE.md. Historical phase design: docs/DESIGN.md.

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
- Runtime overrides and record feature services use the coordinated core contracts; preserve authorization, transaction boundaries and recipient field filtering.

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
- Read pom.xml for the current build parent. The default-active qqq-snapshot profile pins the coordinated published core BOM; docs/CANDIDATE-RELEASE.md records compatible Redis, session-store and frontend versions.
- Tests: JUnit 5, AssertJ.

## Release

- CircleCI is centrally owned through .qqq-ci.yml and the canonical setup caller. Keep both declarations aligned; validate with the selected orb canonical validator as well as CircleCI schema validation.
- `develop` publishes SNAPSHOTs.
- A `release/*` branch publishes RCs (the orb bumps the version).
- Stable release tags and feature publish tags follow the manifest policy. Feature snapshots use the existing publish-tag automation; keep generated versions within the framework length limit. Do not treat snapshot publication as GA or production deployment.
